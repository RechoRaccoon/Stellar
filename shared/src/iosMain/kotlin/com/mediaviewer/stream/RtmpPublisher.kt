// iOS counterpart of Android's stream/RtmpPublisher.kt: the same protocol
// code — handshake, connect, createStream, publish, FLV tags, the
// drop-when-congested rules — with the java.io / java.net parts replaced by
// two small interfaces ([RtmpTransport] for the socket, [RtmpThreads] for
// the two background loops), since iOS has neither. Still plain Kotlin:
// tools/stream-test pushes a real stream through it into ffmpeg.
package com.mediaviewer.stream

import com.mediaviewer.platform.IOException
import com.mediaviewer.platform.currentTimeMillis
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlin.concurrent.Volatile

/** The connection to the server: blocking reads and writes. */
interface RtmpTransport {
    /** Sends all of [bytes] from [offset] for [length]. Throws [IOException]. */
    fun write(bytes: ByteArray, offset: Int, length: Int)
    fun flush()
    /** Waits for at least one byte and returns how many were read into
     *  [into]; -1 when the server has closed. Throws [RtmpTimeout] if
     *  nothing arrives within the read timeout, [IOException] otherwise. */
    fun read(into: ByteArray, offset: Int, length: Int): Int
    /** 0 = wait for ever. */
    fun setReadTimeout(milliseconds: Int)
    fun close()
}

class RtmpTimeout : IOException("Timed out")

/** What the publisher needs from the platform besides a socket. */
interface RtmpThreads {
    /** Opens a connection (TLS when [secure]). Throws [IOException]. */
    fun open(host: String, port: Int, secure: Boolean, timeoutMs: Int): RtmpTransport
    /** Runs [block] on a new background thread. */
    fun start(name: String, block: () -> Unit)
    fun sleep(milliseconds: Int)
}

/**
 * A small, dependency-free RTMP / RTMPS publisher — just enough of the
 * protocol to push one live H.264 + AAC stream to YouTube, Twitch, Kick,
 * Facebook (rtmps), Owncast, nginx-rtmp, OBS-style ingest servers, etc.
 *
 * Threading: [connect] blocks (call it off the main thread). After that,
 * [sendVideo]/[sendAudio]/config calls only enqueue — one writer thread
 * owns the socket, one reader thread answers pings/acks. When the network
 * can't keep up, queued non-key video frames are dropped (never audio,
 * never keyframes) and [Listener.onCongestion] fires so the encoder can
 * lower its bitrate — the stream stutters briefly instead of drifting
 * minutes behind real time.
 */
class RtmpPublisher(private val listener: Listener, private val threads: RtmpThreads) {

    interface Listener {
        /** The socket died or the server hung up after publishing started. */
        fun onDisconnected(reason: String)
        /** Queued-but-unsent media is backing up: [queuedMs] of video waiting. */
        fun onCongestion(queuedMs: Long) {}
        /** The backlog has drained again. */
        fun onCongestionCleared() {}
        /** Queued frames were dropped; the next frame should be a keyframe. */
        fun onKeyframeNeeded() {}
    }

    class Endpoint(
        val secure: Boolean,
        val host: String,
        val port: Int,
        val app: String,
        val tcUrl: String,
        val streamName: String
    )

    private class Packet(
        val type: Int,
        val csid: Int,
        val timestampMs: Long,
        val payload: ByteArray,
        val isVideo: Boolean,
        val isKeyframe: Boolean,
        val mediaTimeMs: Long
    )

    private var transport: RtmpTransport? = null
    private var streamId = 1
    private var outChunkSize = 128
    @Volatile private var running = false
    private val queueLock = reentrantLock()
    private val queue = ArrayDeque<Packet>()
    private val writeLock = reentrantLock()
    @Volatile private var writerDone = true
    @Volatile private var lastVideoQueuedMs = -1L
    @Volatile private var lastVideoSentMs = -1L
    @Volatile private var dropUntilKeyframe = false
    @Volatile private var congested = false
    @Volatile private var disconnectReported = false

    /** Bytes actually written to the socket (for the bitrate readout). */
    @Volatile var bytesSent = 0L
        private set
    /** Video frames thrown away because the network couldn't keep up. */
    @Volatile var droppedFrames = 0L
        private set

    val isConnected: Boolean get() = running

    // ───────────────────────────────────────────────────────────── connect

    /**
     * Opens the connection and gets the server into "publishing" state.
     * Throws [IOException] with a human-readable message on failure.
     */
    fun connect(serverUrl: String, streamKey: String, timeoutMs: Int = 10_000) {
        val ep = parseEndpoint(serverUrl, streamKey)
        val t = threads.open(ep.host, ep.port, ep.secure, timeoutMs)
        t.setReadTimeout(timeoutMs)
        transport = t
        try {
            handshake(t)
            // Big chunks: far less header overhead than the default 128.
            writeMessage(TYPE_SET_CHUNK_SIZE, 2, 0, 0, int32(OUT_CHUNK_SIZE))
            outChunkSize = OUT_CHUNK_SIZE
            sendCommand(0, 3, "connect", 1.0, linkedMapOf(
                "app" to ep.app,
                "type" to "nonprivate",
                "flashVer" to "FMLE/3.0 (compatible; FMSc/1.0)",
                "swfUrl" to ep.tcUrl,
                "tcUrl" to ep.tcUrl
            ))
            t.flush()
            awaitResult(1.0, "connect")
            sendCommand(0, 3, "releaseStream", 2.0, null, ep.streamName)
            sendCommand(0, 3, "FCPublish", 3.0, null, ep.streamName)
            sendCommand(0, 3, "createStream", 4.0, null)
            t.flush()
            val created = awaitResult(4.0, "createStream")
            streamId = (created.getOrNull(3) as? Double)?.toInt() ?: 1
            sendCommand(streamId, 4, "publish", 5.0, null, ep.streamName, "live")
            t.flush()
            awaitPublishStart()
        } catch (e: Throwable) {
            closeQuietly()
            throw if (e is IOException) e else IOException(e.message ?: e.toString())
        }
        t.setReadTimeout(0)
        running = true
        writerDone = false
        threads.start("rtmp-writer") { writeLoop() }
        threads.start("rtmp-reader") { readLoop() }
    }

    /** `@setDataFrame onMetaData` — optional but YouTube/Twitch like it. */
    fun sendMetadata(width: Int, height: Int, fps: Int, videoKbps: Int, audioKbps: Int, sampleRate: Int, stereo: Boolean) {
        val body = ByteSink()
        Amf0.writeString(body, "@setDataFrame")
        Amf0.writeString(body, "onMetaData")
        Amf0.writeEcmaArray(body, linkedMapOf(
            "duration" to 0.0,
            "width" to width.toDouble(),
            "height" to height.toDouble(),
            "videodatarate" to videoKbps.toDouble(),
            "framerate" to fps.toDouble(),
            "videocodecid" to 7.0,
            "audiodatarate" to audioKbps.toDouble(),
            "audiosamplerate" to sampleRate.toDouble(),
            "audiosamplesize" to 16.0,
            "stereo" to stereo,
            "audiocodecid" to 10.0,
            "encoder" to "Stellar"
        ))
        enqueue(Packet(TYPE_DATA_AMF0, 4, 0, body.toByteArray(), false, false, 0))
    }

    // ───────────────────────────────────────────────────────────── media

    /** AVC sequence header from the encoder's SPS + PPS (no start codes). */
    fun sendVideoConfig(sps: ByteArray, pps: ByteArray) {
        if (sps.size < 4) return
        val b = ByteSink()
        b.write(0x17); b.write(0x00); b.write(0); b.write(0); b.write(0)
        b.write(1); b.write(sps[1].toInt()); b.write(sps[2].toInt()); b.write(sps[3].toInt())
        b.write(0xFF) // 4-byte NAL lengths
        b.write(0xE1) // 1 SPS
        b.write((sps.size shr 8) and 0xFF); b.write(sps.size and 0xFF); b.write(sps)
        b.write(1)    // 1 PPS
        b.write((pps.size shr 8) and 0xFF); b.write(pps.size and 0xFF); b.write(pps)
        enqueue(Packet(TYPE_VIDEO, 6, 0, b.toByteArray(), true, true, 0))
    }

    /** One encoded frame: [nals] are raw NAL units without start codes. */
    fun sendVideo(nals: List<ByteArray>, timestampMs: Long, keyframe: Boolean) {
        var size = 0
        for (n in nals) size += 4 + n.size
        val avcc = ByteArray(size)
        var o = 0
        for (n in nals) {
            avcc[o] = (n.size ushr 24).toByte(); avcc[o + 1] = (n.size ushr 16).toByte()
            avcc[o + 2] = (n.size ushr 8).toByte(); avcc[o + 3] = n.size.toByte()
            n.copyInto(avcc, o + 4)
            o += 4 + n.size
        }
        sendVideoAvcc(avcc, timestampMs, keyframe)
    }

    /** One encoded frame as the encoder hands it over on iOS: every NAL
     *  unit already preceded by its 4-byte length ("AVCC"), which is
     *  exactly what RTMP carries. */
    fun sendVideoAvcc(avcc: ByteArray, timestampMs: Long, keyframe: Boolean) {
        if (!running) return
        if (dropUntilKeyframe && !keyframe) { droppedFrames++; return }
        if (keyframe) dropUntilKeyframe = false
        val p = ByteArray(avcc.size + 5)
        p[0] = (if (keyframe) 0x17 else 0x27).toByte()
        p[1] = 1 // AVC NALU
        // p[2..4] composition time = 0 (no B-frames)
        avcc.copyInto(p, 5)
        lastVideoQueuedMs = timestampMs
        enqueue(Packet(TYPE_VIDEO, 6, timestampMs, p, true, keyframe, timestampMs))
        checkCongestion()
    }

    /** AAC sequence header: the encoder's 2-byte AudioSpecificConfig. */
    fun sendAudioConfig(audioSpecificConfig: ByteArray) {
        enqueue(Packet(TYPE_AUDIO, 5, 0, byteArrayOf(0xAF.toByte(), 0x00) + audioSpecificConfig, false, false, 0))
    }

    /** One raw AAC frame (no ADTS header). */
    fun sendAudio(frame: ByteArray, timestampMs: Long) {
        if (!running) return
        val p = ByteArray(frame.size + 2)
        p[0] = 0xAF.toByte(); p[1] = 1
        frame.copyInto(p, 2)
        enqueue(Packet(TYPE_AUDIO, 5, timestampMs, p, false, false, timestampMs))
    }

    /** Sends FCUnpublish/deleteStream and closes. Safe to call any time. */
    fun close() {
        val wasRunning = running
        running = false
        disconnectReported = true
        // Give the writer a moment to put down what it's holding.
        var waited = 0
        while (!writerDone && waited < 1500) { threads.sleep(20); waited += 20 }
        if (wasRunning) {
            runCatching {
                sendCommand(streamId, 3, "FCUnpublish", 6.0, null, "")
                sendCommand(0, 3, "deleteStream", 7.0, null, streamId.toDouble())
                transport?.flush()
            }
        }
        closeQuietly()
        queueLock.withLock { queue.clear() }
    }

    // ───────────────────────────────────────────────────────────── internals

    private fun enqueue(p: Packet) {
        if (running) queueLock.withLock { queue.addLast(p) }
    }

    /** Backlog measured in media time: newest queued video vs. last sent. */
    private fun checkCongestion() {
        val queued = lastVideoQueuedMs
        val sent = lastVideoSentMs
        if (queued < 0 || sent < 0) return
        val backlog = queued - sent
        if (backlog > DROP_THRESHOLD_MS) {
            // Throw away queued P-frames; the next keyframe resyncs cleanly.
            var dropped = 0
            queueLock.withLock {
                val it = queue.iterator()
                while (it.hasNext()) {
                    val p = it.next()
                    if (p.isVideo && !p.isKeyframe) { it.remove(); dropped++ }
                }
            }
            droppedFrames += dropped.toLong()
            dropUntilKeyframe = true
            lastVideoSentMs = queued
            listener.onKeyframeNeeded()
        }
        if (backlog > CONGESTION_MS && !congested) {
            congested = true
            listener.onCongestion(backlog)
        } else if (backlog < CONGESTION_CLEAR_MS && congested) {
            congested = false
            listener.onCongestionCleared()
        }
    }

    private fun writeLoop() {
        try {
            while (running) {
                val p = queueLock.withLock { queue.removeFirstOrNull() }
                if (p == null) {
                    // Coalesce: only flush when nothing else is waiting.
                    transport?.flush()
                    threads.sleep(4)
                    continue
                }
                writeMessage(p.type, p.csid, streamId, p.timestampMs, p.payload)
                if (p.isVideo) lastVideoSentMs = p.mediaTimeMs
            }
        } catch (e: Throwable) {
            reportDisconnect("Connection lost: ${e.message ?: "write failed"}")
        } finally {
            writerDone = true
        }
    }

    private fun readLoop() {
        try {
            val chunks = pendingReader ?: ChunkReader(transport ?: return)
            while (running) {
                val msg = chunks.next() ?: break
                handleIncoming(msg, chunks)
            }
            if (running) reportDisconnect("The server closed the connection")
        } catch (e: Throwable) {
            if (running) reportDisconnect("Connection lost: ${e.message ?: "read failed"}")
        }
    }

    private fun reportDisconnect(reason: String) {
        if (disconnectReported) return
        disconnectReported = true
        running = false
        closeQuietly()
        listener.onDisconnected(reason)
    }

    private fun handleIncoming(msg: Message, chunks: ChunkReader) {
        when (msg.type) {
            TYPE_SET_CHUNK_SIZE -> if (msg.body.size >= 4) chunks.chunkSize = readInt32(msg.body, 0) and 0x7FFFFFFF
            TYPE_WINDOW_ACK -> if (msg.body.size >= 4) chunks.windowAckSize = readInt32(msg.body, 0).toLong()
            TYPE_USER_CONTROL -> {
                // Ping request (6) → ping response (7) with the same timestamp.
                if (msg.body.size >= 6 && readInt16(msg.body, 0) == 6) {
                    val resp = byteArrayOf(0, 7) + msg.body.copyOfRange(2, 6)
                    runCatching { writeMessage(TYPE_USER_CONTROL, 2, 0, 0, resp); transport?.flush() }
                }
            }
            TYPE_COMMAND_AMF0 -> {
                val values = runCatching { Amf0.readAll(msg.body) }.getOrDefault(emptyList())
                val name = values.firstOrNull() as? String
                if (name == "onStatus") {
                    val info = values.getOrNull(3) as? Map<*, *>
                    val level = info?.get("level") as? String
                    val code = info?.get("code") as? String ?: ""
                    if (level == "error" || code == "NetStream.Publish.BadName" || code.contains("Unpublish")) {
                        reportDisconnect(info?.get("description") as? String ?: code)
                    }
                }
            }
        }
        if (chunks.needsAck()) {
            val ack = int32(chunks.bytesRead.toInt())
            runCatching { writeMessage(TYPE_ACK, 2, 0, 0, ack); transport?.flush() }
        }
    }

    private fun handshake(t: RtmpTransport) {
        val c1 = ByteArray(HANDSHAKE_SIZE)
        val random = kotlin.random.Random(currentTimeMillis())
        for (i in 8 until HANDSHAKE_SIZE) c1[i] = random.nextInt(256).toByte() // (first 8: time + zero)
        t.write(byteArrayOf(3), 0, 1); t.write(c1, 0, c1.size); t.flush()
        val s0 = ByteArray(1); readFully(t, s0)
        if ((s0[0].toInt() and 0xFF) != 3) throw IOException("Not an RTMP server (handshake version ${s0[0].toInt() and 0xFF})")
        val s1 = ByteArray(HANDSHAKE_SIZE); readFully(t, s1)
        t.write(s1, 0, s1.size); t.flush() // C2 = echo of S1
        val s2 = ByteArray(HANDSHAKE_SIZE); readFully(t, s2)
    }

    /** Reads messages until the `_result`/`_error` for [txn]. */
    private fun awaitResult(txn: Double, what: String): List<Any?> {
        val chunks = pendingReader ?: ChunkReader(transport!!).also { pendingReader = it }
        val deadline = currentTimeMillis() + 10_000
        while (currentTimeMillis() < deadline) {
            val msg = chunks.next() ?: throw IOException("Server closed the connection during $what")
            if (msg.type == TYPE_COMMAND_AMF0 || msg.type == TYPE_COMMAND_AMF3) {
                val body = if (msg.type == TYPE_COMMAND_AMF3 && msg.body.isNotEmpty()) msg.body.copyOfRange(1, msg.body.size) else msg.body
                val values = Amf0.readAll(body)
                val name = values.firstOrNull() as? String
                val id = values.getOrNull(1) as? Double
                if (id == txn && name == "_result") return values
                if (id == txn && name == "_error") {
                    val info = values.getOrNull(3) as? Map<*, *>
                    throw IOException("Server refused $what: ${info?.get("description") ?: info?.get("code") ?: "error"}")
                }
            } else {
                handleIncoming(msg, chunks)
            }
        }
        throw IOException("Timed out waiting for the server ($what)")
    }

    private fun awaitPublishStart() {
        val chunks = pendingReader ?: ChunkReader(transport!!).also { pendingReader = it }
        val deadline = currentTimeMillis() + 10_000
        while (currentTimeMillis() < deadline) {
            val msg = try {
                chunks.next()
            } catch (e: RtmpTimeout) {
                return // some servers never send onStatus; carry on
            } ?: throw IOException("Server closed the connection — check the stream key")
            if (msg.type == TYPE_COMMAND_AMF0) {
                val values = Amf0.readAll(msg.body)
                if (values.firstOrNull() == "onStatus") {
                    val info = values.getOrNull(3) as? Map<*, *>
                    val code = info?.get("code") as? String ?: ""
                    val level = info?.get("level") as? String ?: ""
                    if (code == "NetStream.Publish.Start") return
                    if (level == "error" || code.contains("BadName") || code.contains("Failed")) {
                        throw IOException("Server refused the stream: ${info?.get("description") ?: code}")
                    }
                } else if (values.firstOrNull() == "_error") {
                    throw IOException("Server refused the stream — check the stream key")
                }
            } else {
                handleIncoming(msg, chunks)
            }
        }
    }

    private var pendingReader: ChunkReader? = null

    private fun sendCommand(msgStreamId: Int, csid: Int, name: String, txn: Double, obj: Map<String, Any?>?, vararg args: Any?) {
        val b = ByteSink()
        Amf0.writeString(b, name)
        Amf0.writeNumber(b, txn)
        if (obj != null) Amf0.writeObject(b, obj) else Amf0.writeNull(b)
        for (a in args) Amf0.write(b, a)
        writeMessage(TYPE_COMMAND_AMF0, csid, msgStreamId, 0, b.toByteArray())
    }

    /** Every message goes out with a full (type 0) header; continuation
     *  chunks use type 3. Simple and accepted by every server. */
    private fun writeMessage(type: Int, csid: Int, msgStreamId: Int, timestampMs: Long, payload: ByteArray) = writeLock.withLock {
        val o = transport ?: throw IOException("Not connected")
        val ts = timestampMs and 0xFFFFFFFFL
        val extended = ts >= 0xFFFFFF
        val h = ByteArray(12 + if (extended) 4 else 0)
        h[0] = (csid and 0x3F).toByte()
        val t3 = if (extended) 0xFFFFFF else ts.toInt()
        h[1] = (t3 ushr 16).toByte(); h[2] = (t3 ushr 8).toByte(); h[3] = t3.toByte()
        h[4] = (payload.size ushr 16).toByte(); h[5] = (payload.size ushr 8).toByte(); h[6] = payload.size.toByte()
        h[7] = type.toByte()
        h[8] = msgStreamId.toByte(); h[9] = (msgStreamId ushr 8).toByte()
        h[10] = (msgStreamId ushr 16).toByte(); h[11] = (msgStreamId ushr 24).toByte()
        if (extended) {
            h[12] = (ts ushr 24).toByte(); h[13] = (ts ushr 16).toByte(); h[14] = (ts ushr 8).toByte(); h[15] = ts.toByte()
        }
        // One buffer per message: one write to the socket instead of many.
        val chunks = if (payload.isEmpty()) 0 else (payload.size - 1) / outChunkSize
        val out = ByteArray(h.size + payload.size + chunks * (1 + if (extended) 4 else 0))
        h.copyInto(out, 0)
        var at = h.size
        var off = 0
        while (off < payload.size) {
            if (off > 0) {
                out[at++] = (0xC0 or (csid and 0x3F)).toByte()
                if (extended) { h.copyInto(out, at, 12, 16); at += 4 }
            }
            val n = minOf(outChunkSize, payload.size - off)
            payload.copyInto(out, at, off, off + n)
            off += n
            at += n
        }
        o.write(out, 0, at)
        bytesSent += at.toLong()
    }

    private fun closeQuietly() {
        runCatching { transport?.close() }
        transport = null
    }

    // ───────────────────────────────────────────────────────────── reading

    private class Message(val type: Int, val streamId: Int, val body: ByteArray)

    private class ChunkReader(private val input: RtmpTransport) {
        private class State(var ts: Long = 0, var length: Int = 0, var type: Int = 0, var streamId: Int = 0,
                            var buf: ByteArray? = null, var filled: Int = 0, var extended: Boolean = false)
        private val states = HashMap<Int, State>()
        var chunkSize = 128
        var windowAckSize = 2_500_000L
        var bytesRead = 0L
        private var lastAck = 0L

        // A small read-ahead buffer, so header bytes aren't one socket read each.
        private val buffer = ByteArray(16 * 1024)
        private var have = 0
        private var pos = 0

        fun needsAck(): Boolean {
            if (windowAckSize > 0 && bytesRead - lastAck >= windowAckSize) { lastAck = bytesRead; return true }
            return false
        }

        /** Next byte, or -1 at end of stream. */
        private fun byteOrEnd(): Int {
            if (pos >= have) {
                val n = input.read(buffer, 0, buffer.size)
                if (n <= 0) return -1
                have = n; pos = 0
            }
            bytesRead++
            return buffer[pos++].toInt() and 0xFF
        }

        private fun u8(): Int { val v = byteOrEnd(); if (v < 0) throw IOException("Connection closed"); return v }
        private fun u24(): Int = (u8() shl 16) or (u8() shl 8) or u8()
        private fun u32be(): Long = (u8().toLong() shl 24) or (u8().toLong() shl 16) or (u8().toLong() shl 8) or u8().toLong()

        private fun fill(into: ByteArray, offset: Int, length: Int) {
            var done = 0
            while (done < length) {
                if (pos < have) {
                    val n = minOf(have - pos, length - done)
                    buffer.copyInto(into, offset + done, pos, pos + n)
                    pos += n; done += n
                } else {
                    val n = input.read(buffer, 0, buffer.size)
                    if (n <= 0) throw IOException("Connection closed")
                    have = n; pos = 0
                }
            }
            bytesRead += length
        }

        /** Next complete message, or null at end of stream. */
        fun next(): Message? {
            while (true) {
                val first = byteOrEnd()
                if (first < 0) return null
                val fmt = first ushr 6
                var csid = first and 0x3F
                if (csid == 0) csid = 64 + u8()
                else if (csid == 1) csid = 64 + u8() + (u8() shl 8)
                val st = states.getOrPut(csid) { State() }
                when (fmt) {
                    0 -> {
                        var ts = u24().toLong()
                        st.length = u24(); st.type = u8()
                        st.streamId = u8() or (u8() shl 8) or (u8() shl 16) or (u8() shl 24)
                        st.extended = ts == 0xFFFFFFL
                        if (st.extended) ts = u32be()
                        st.ts = ts
                    }
                    1 -> {
                        var d = u24().toLong(); st.length = u24(); st.type = u8()
                        st.extended = d == 0xFFFFFFL
                        if (st.extended) d = u32be()
                        st.ts += d
                    }
                    2 -> {
                        var d = u24().toLong()
                        st.extended = d == 0xFFFFFFL
                        if (st.extended) d = u32be()
                        st.ts += d
                    }
                    else -> if (st.extended) u32be()
                }
                if (st.length < 0 || st.length > 16 * 1024 * 1024) throw IOException("Bad message from the server")
                val buf = st.buf ?: ByteArray(st.length).also { st.buf = it; st.filled = 0 }
                val n = minOf(chunkSize, st.length - st.filled)
                fill(buf, st.filled, n)
                st.filled += n
                if (st.filled >= st.length) {
                    st.buf = null
                    val msg = Message(st.type, st.streamId, buf)
                    if (msg.type == TYPE_SET_CHUNK_SIZE && msg.body.size >= 4) chunkSize = readInt32(msg.body, 0) and 0x7FFFFFFF
                    return msg
                }
            }
        }
    }

    companion object {
        private const val HANDSHAKE_SIZE = 1536
        private const val OUT_CHUNK_SIZE = 4096
        private const val TYPE_SET_CHUNK_SIZE = 1
        private const val TYPE_ACK = 3
        private const val TYPE_USER_CONTROL = 4
        private const val TYPE_WINDOW_ACK = 5
        private const val TYPE_AUDIO = 8
        private const val TYPE_VIDEO = 9
        private const val TYPE_DATA_AMF0 = 18
        private const val TYPE_COMMAND_AMF3 = 17
        private const val TYPE_COMMAND_AMF0 = 20

        /** Video waiting longer than this → tell the encoder to back off. */
        private const val CONGESTION_MS = 1_200L
        private const val CONGESTION_CLEAR_MS = 300L
        /** …and past this, drop queued P-frames outright. */
        private const val DROP_THRESHOLD_MS = 3_000L

        private fun int32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
        private fun readInt32(b: ByteArray, o: Int) =
            ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)
        private fun readInt16(b: ByteArray, o: Int) = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)

        private fun readFully(t: RtmpTransport, into: ByteArray) {
            var done = 0
            while (done < into.size) {
                val n = t.read(into, done, into.size - done)
                if (n <= 0) throw IOException("The server closed the connection")
                done += n
            }
        }

        /**
         * Server URL + key → endpoint. Accepts what services hand out:
         *  - `rtmp://a.rtmp.youtube.com/live2` + key
         *  - `rtmps://live-api-s.facebook.com:443/rtmp/` + key
         *  - a full URL with the key already on the end and the key box empty.
         */
        fun parseEndpoint(serverUrl: String, streamKey: String): Endpoint {
            val raw = serverUrl.trim()
            val schemeEnd = raw.indexOf("://")
            if (schemeEnd <= 0) throw IOException("The server URL must start with rtmp:// or rtmps://")
            val scheme = raw.substring(0, schemeEnd).lowercase()
            val secure = when (scheme) {
                "rtmp" -> false
                "rtmps" -> true
                else -> throw IOException("The server URL must start with rtmp:// or rtmps://")
            }
            val rest = raw.substring(schemeEnd + 3)
            val authority = rest.substringBefore('/').substringBefore('?')
            if (authority.isEmpty() || authority.any { it.isWhitespace() }) throw IOException("The server URL has no host")
            val hostPort = authority.substringAfterLast('@')
            val host = hostPort.substringBefore(':')
            if (host.isEmpty()) throw IOException("The server URL has no host")
            val explicitPort = hostPort.substringAfter(':', "").toIntOrNull()?.takeIf { it > 0 }
            val port = explicitPort ?: if (secure) 443 else 1935
            val afterAuthority = rest.substring(authority.length)
            val query = afterAuthority.substringAfter('?', "").substringBefore('#')
            var path = afterAuthority.substringBefore('?').substringBefore('#').trim('/')
            var key = streamKey.trim()
            if (key.isEmpty()) {
                // Key pasted onto the URL: the last path segment is the stream.
                val cut = path.lastIndexOf('/')
                if (cut <= 0) throw IOException("Enter your stream key")
                key = path.substring(cut + 1)
                path = path.substring(0, cut)
            }
            if (path.isEmpty()) throw IOException("The server URL is missing its app path (e.g. /live2)")
            val app = path + if (query.isNotEmpty()) "?$query" else ""
            val portPart = if (explicitPort != null) ":$explicitPort" else ""
            val tcUrl = "$scheme://$host$portPart/$app"
            return Endpoint(secure, host, port, app, tcUrl, key)
        }
    }
}

/** A growing byte buffer (what ByteArrayOutputStream is on Android). */
internal class ByteSink(capacity: Int = 256) {
    private var data = ByteArray(capacity)
    private var size = 0
    private fun room(more: Int) { if (size + more > data.size) data = data.copyOf(maxOf(data.size * 2, size + more)) }
    fun write(v: Int) { room(1); data[size++] = v.toByte() }
    fun write(b: ByteArray) { room(b.size); b.copyInto(data, size); size += b.size }
    fun toByteArray(): ByteArray = data.copyOf(size)
}

/** Minimal AMF0 — only the types RTMP command messages actually use. */
internal object Amf0 {
    fun write(o: ByteSink, v: Any?) {
        when (v) {
            null -> writeNull(o)
            is String -> writeString(o, v)
            is Number -> writeNumber(o, v.toDouble())
            is Boolean -> { o.write(1); o.write(if (v) 1 else 0) }
            is Map<*, *> -> @Suppress("UNCHECKED_CAST") writeObject(o, v as Map<String, Any?>)
            else -> writeString(o, v.toString())
        }
    }
    fun writeNumber(o: ByteSink, d: Double) {
        o.write(0)
        val bits = d.toRawBits()
        for (i in 7 downTo 0) o.write((bits ushr (i * 8)).toInt() and 0xFF)
    }
    fun writeString(o: ByteSink, s: String) { o.write(2); writeUtf(o, s) }
    fun writeNull(o: ByteSink) = o.write(5)
    private fun writeUtf(o: ByteSink, s: String) {
        val b = s.encodeToByteArray()
        o.write((b.size shr 8) and 0xFF); o.write(b.size and 0xFF); o.write(b)
    }
    fun writeObject(o: ByteSink, m: Map<String, Any?>) {
        o.write(3)
        for ((k, v) in m) { writeUtf(o, k); write(o, v) }
        o.write(0); o.write(0); o.write(9)
    }
    fun writeEcmaArray(o: ByteSink, m: Map<String, Any?>) {
        o.write(8)
        o.write(0); o.write(0); o.write((m.size shr 8) and 0xFF); o.write(m.size and 0xFF)
        for ((k, v) in m) { writeUtf(o, k); write(o, v) }
        o.write(0); o.write(0); o.write(9)
    }

    private class Source(val b: ByteArray) {
        var at = 0
        fun available() = b.size - at
        fun u8(): Int { if (at >= b.size) throw IOException("Truncated AMF0"); return b[at++].toInt() and 0xFF }
        fun u16(): Int = (u8() shl 8) or u8()
        fun i32(): Int = (u8() shl 24) or (u8() shl 16) or (u8() shl 8) or u8()
        fun double(): Double { var bits = 0L; repeat(8) { bits = (bits shl 8) or u8().toLong() }; return Double.fromBits(bits) }
        fun text(n: Int): String {
            if (n < 0 || at + n > b.size) throw IOException("Truncated AMF0")
            return b.decodeToString(at, at + n).also { at += n }
        }
    }

    fun readAll(b: ByteArray): List<Any?> {
        val input = Source(b)
        val out = ArrayList<Any?>()
        while (input.available() > 0) out.add(read(input))
        return out
    }

    private object End

    private fun read(i: Source): Any? {
        return when (val t = i.u8()) {
            0 -> i.double()
            1 -> i.u8() != 0
            2 -> i.text(i.u16())
            3 -> readProps(i, LinkedHashMap())
            5, 6 -> null
            8 -> { i.i32(); readProps(i, LinkedHashMap()) }
            9 -> End
            10 -> { val n = i.i32(); List(n.coerceIn(0, 4096)) { read(i) } }
            11 -> { val d = i.double(); i.u16(); d }
            12 -> i.text(i.i32())
            else -> throw IOException("Unsupported AMF0 type $t")
        }
    }
    private fun readProps(i: Source, m: LinkedHashMap<String, Any?>): Map<String, Any?> {
        while (true) {
            val key = i.text(i.u16())
            val v = read(i)
            if (key.isEmpty() && v === End) return m
            m[key] = v
        }
    }
}
