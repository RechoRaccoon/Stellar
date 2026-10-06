import com.mediaviewer.platform.IOException
import com.mediaviewer.stream.*
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * Pushes a real H.264 + AAC stream through the iOS RTMP publisher into a
 * listening ffmpeg, on a desktop. Arguments: rtmp URL, stream key, an
 * Annex-B .h264 file, an ADTS .aac file, frames per second.
 * (run.sh makes the two files and starts ffmpeg.)
 */
class JvmThreads : RtmpThreads {
    override fun open(host: String, port: Int, secure: Boolean, timeoutMs: Int): RtmpTransport {
        val s = Socket()
        try { s.connect(InetSocketAddress(host, port), timeoutMs) } catch (e: Exception) { throw IOException("Couldn't connect: ${e.message}") }
        s.tcpNoDelay = true
        val out = s.getOutputStream().buffered(64 * 1024)
        val input = s.getInputStream()
        return object : RtmpTransport {
            override fun write(bytes: ByteArray, offset: Int, length: Int) = try { out.write(bytes, offset, length) } catch (e: Exception) { throw IOException(e.message) }
            override fun flush() = try { out.flush() } catch (e: Exception) { throw IOException(e.message) }
            override fun read(into: ByteArray, offset: Int, length: Int): Int = try { input.read(into, offset, length) }
                catch (e: SocketTimeoutException) { throw RtmpTimeout() } catch (e: Exception) { throw IOException(e.message) }
            override fun setReadTimeout(milliseconds: Int) { s.soTimeout = milliseconds }
            override fun close() { runCatching { s.close() } }
        }
    }
    override fun start(name: String, block: () -> Unit) { Thread(block, name).also { it.isDaemon = true }.start() }
    override fun sleep(milliseconds: Int) = Thread.sleep(milliseconds.toLong())
}

fun splitAnnexB(d: ByteArray): List<ByteArray> {
    val starts = ArrayList<Int>(); var i = 0
    while (i + 2 < d.size) {
        if (d[i].toInt() == 0 && d[i + 1].toInt() == 0) {
            if (d[i + 2].toInt() == 1) { starts.add(i + 3); i += 3; continue }
            if (i + 3 < d.size && d[i + 2].toInt() == 0 && d[i + 3].toInt() == 1) { starts.add(i + 4); i += 4; continue }
        }
        i++
    }
    val out = ArrayList<ByteArray>()
    for ((k, s) in starts.withIndex()) {
        var e = if (k + 1 < starts.size) starts[k + 1] - 3 else d.size
        if (k + 1 < starts.size && e > s && d[e - 1].toInt() == 0) e--
        if (e > s) out.add(d.copyOfRange(s, e))
    }
    return out
}

fun main(args: Array<String>) {
    val (url, key, h264Path, aacPath) = args
    val fps = args[4].toInt()
    // Endpoint parsing, the forms services hand out.
    val e1 = RtmpPublisher.parseEndpoint("rtmp://a.rtmp.youtube.com/live2", "abcd-1234")
    check(!e1.secure && e1.host == "a.rtmp.youtube.com" && e1.port == 1935 && e1.app == "live2" && e1.streamName == "abcd-1234" && e1.tcUrl == "rtmp://a.rtmp.youtube.com/live2")
    val e2 = RtmpPublisher.parseEndpoint("rtmps://live-api-s.facebook.com:443/rtmp/", "FB-1?s_bl=1")
    check(e2.secure && e2.port == 443 && e2.app == "rtmp" && e2.tcUrl == "rtmps://live-api-s.facebook.com:443/rtmp")
    val e3 = RtmpPublisher.parseEndpoint("rtmp://live.twitch.tv/app/live_123_abc", "")
    check(e3.app == "app" && e3.streamName == "live_123_abc")
    check(runCatching { RtmpPublisher.parseEndpoint("https://example.com/x", "k") }.isFailure)
    println("endpoint parsing ok")

    var lost: String? = null
    val pub = RtmpPublisher(object : RtmpPublisher.Listener {
        override fun onDisconnected(reason: String) { lost = reason }
    }, JvmThreads())
    pub.connect(url, key)
    println("connected and publishing")

    val nals = splitAnnexB(File(h264Path).readBytes())
    val sps = nals.first { (it[0].toInt() and 0x1F) == 7 }
    val pps = nals.first { (it[0].toInt() and 0x1F) == 8 }
    // ADTS → raw AAC frames (+ the 2-byte config its headers describe).
    val adts = File(aacPath).readBytes()
    val audio = ArrayList<ByteArray>(); var p = 0; var asc: ByteArray? = null; var sampleRate = 44100
    val rates = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)
    while (p + 7 <= adts.size) {
        val len = ((adts[p + 3].toInt() and 3) shl 11) or ((adts[p + 4].toInt() and 0xFF) shl 3) or ((adts[p + 5].toInt() and 0xFF) ushr 5)
        val header = if ((adts[p + 1].toInt() and 1) == 1) 7 else 9
        if (asc == null) {
            val profile = ((adts[p + 2].toInt() and 0xC0) ushr 6) + 1
            val freq = (adts[p + 2].toInt() and 0x3C) ushr 2
            val channels = ((adts[p + 2].toInt() and 1) shl 2) or ((adts[p + 3].toInt() and 0xC0) ushr 6)
            sampleRate = rates[freq]
            asc = byteArrayOf(((profile shl 3) or (freq ushr 1)).toByte(), (((freq and 1) shl 7) or (channels shl 3)).toByte())
        }
        audio.add(adts.copyOfRange(p + header, p + len)); p += len
    }
    pub.sendMetadata(320, 240, fps, 500, 96, sampleRate, false)
    pub.sendVideoConfig(sps, pps)
    pub.sendAudioConfig(asc!!)

    // Frames in real time (a server reads a live stream at its own pace).
    var frame = 0; var audioIndex = 0
    val pending = ArrayList<ByteArray>()
    val start = System.currentTimeMillis()
    for (n in nals) {
        val type = n[0].toInt() and 0x1F
        if (type == 7 || type == 8 || type == 9 || type == 6) continue
        pending.add(n)
        val ts = frame * 1000L / fps
        while (System.currentTimeMillis() - start < ts) Thread.sleep(2)
        pub.sendVideo(pending.toList(), ts, keyframe = type == 5)
        pending.clear(); frame++
        while (audioIndex < audio.size && audioIndex * 1024L * 1000L / sampleRate <= ts) {
            pub.sendAudio(audio[audioIndex], audioIndex * 1024L * 1000L / sampleRate); audioIndex++
        }
    }
    Thread.sleep(400)
    println("sent $frame video frames, $audioIndex audio frames, ${pub.bytesSent} bytes, dropped ${pub.droppedFrames}")
    pub.close()
    check(lost == null) { "disconnected: $lost" }
    println("closed cleanly")
}
