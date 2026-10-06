package com.mediaviewer.stream

import com.mediaviewer.platform.IOException
import com.mediaviewer.platform.currentTimeMillis
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.Foundation.NSInputStream
import platform.Foundation.NSOutputStream
import platform.Foundation.NSStream
import platform.Foundation.NSStreamSocketSecurityLevelKey
import platform.Foundation.NSStreamSocketSecurityLevelNegotiatedSSL
import platform.Foundation.NSStreamStatusClosed
import platform.Foundation.NSStreamStatusError
import platform.Foundation.NSStreamStatusOpen
import platform.Foundation.NSThread
import platform.Foundation.getStreamsToHostWithName
import platform.posix.usleep

/**
 * The publisher's socket and threads on iOS. The connection is a pair of
 * Foundation streams — the one networking API here that does plain TCP and
 * TLS (rtmps://) alike and can simply be read and written from a
 * background thread, which is how the publisher is written.
 */
@OptIn(ExperimentalForeignApi::class)
object IosRtmpThreads : RtmpThreads {
    override fun open(host: String, port: Int, secure: Boolean, timeoutMs: Int): RtmpTransport {
        var input: NSInputStream? = null
        var output: NSOutputStream? = null
        memScoped {
            val inVar = alloc<ObjCObjectVar<NSInputStream?>>()
            val outVar = alloc<ObjCObjectVar<NSOutputStream?>>()
            NSStream.getStreamsToHostWithName(host, port.toLong(), inVar.ptr, outVar.ptr)
            input = inVar.value
            output = outVar.value
        }
        val i = input ?: throw IOException("Couldn't reach $host")
        val o = output ?: throw IOException("Couldn't reach $host")
        if (secure) {
            i.setProperty(NSStreamSocketSecurityLevelNegotiatedSSL, forKey = NSStreamSocketSecurityLevelKey)
            o.setProperty(NSStreamSocketSecurityLevelNegotiatedSSL, forKey = NSStreamSocketSecurityLevelKey)
        }
        i.open()
        o.open()
        // Wait for the connection (and, for rtmps, the TLS handshake).
        val deadline = currentTimeMillis() + timeoutMs
        while (true) {
            val si = i.streamStatus; val so = o.streamStatus
            if (si == NSStreamStatusError || so == NSStreamStatusError || si == NSStreamStatusClosed || so == NSStreamStatusClosed) {
                val why = (i.streamError ?: o.streamError)?.localizedDescription
                i.close(); o.close()
                throw IOException("Couldn't connect to $host" + if (why != null) ": $why" else "")
            }
            if (si == NSStreamStatusOpen && so == NSStreamStatusOpen) break
            if (currentTimeMillis() > deadline) {
                i.close(); o.close()
                throw IOException("Timed out connecting to $host")
            }
            usleep(10_000u)
        }
        return Transport(i, o)
    }

    override fun start(name: String, block: () -> Unit) {
        val thread = NSThread { runCatching { block() } }
        thread.setName(name)
        thread.start()
    }

    override fun sleep(milliseconds: Int) { usleep((milliseconds * 1000).toUInt()) }

    private class Transport(private val input: NSInputStream, private val output: NSOutputStream) : RtmpTransport {
        private var readTimeoutMs = 0
        @kotlin.concurrent.Volatile private var closed = false

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            if (length <= 0) return
            bytes.usePinned { pinned ->
                var done = 0
                var stalled = 0
                while (done < length) {
                    if (closed) throw IOException("Connection closed")
                    val n = output.write(pinned.addressOf(offset + done).reinterpret(), (length - done).toULong()).toInt()
                    if (n < 0) throw IOException(output.streamError?.localizedDescription ?: "Connection lost")
                    if (n == 0) {
                        // Nothing taken: the stream is full or gone. Wait a
                        // little; give up after ten seconds of that.
                        if (output.streamStatus != NSStreamStatusOpen || ++stalled > 2000) throw IOException("Connection lost")
                        usleep(5_000u)
                    } else {
                        stalled = 0
                        done += n
                    }
                }
            }
        }

        /** (Foundation streams send as they're written; nothing is held back here.) */
        override fun flush() {}

        override fun read(into: ByteArray, offset: Int, length: Int): Int {
            if (length <= 0) return 0
            if (readTimeoutMs > 0) {
                val deadline = currentTimeMillis() + readTimeoutMs
                while (!input.hasBytesAvailable) {
                    if (closed) return -1
                    val status = input.streamStatus
                    if (status == NSStreamStatusError) throw IOException(input.streamError?.localizedDescription ?: "Connection lost")
                    if (status == NSStreamStatusClosed) return -1
                    if (currentTimeMillis() > deadline) throw RtmpTimeout()
                    usleep(5_000u)
                }
            }
            val n = into.usePinned { pinned -> input.read(pinned.addressOf(offset).reinterpret(), length.toULong()).toInt() }
            if (n < 0) {
                if (closed) return -1
                throw IOException(input.streamError?.localizedDescription ?: "Connection lost")
            }
            return if (n == 0) -1 else n
        }

        override fun setReadTimeout(milliseconds: Int) { readTimeoutMs = milliseconds }

        override fun close() {
            closed = true
            runCatching { input.close() }
            runCatching { output.close() }
        }
    }
}
