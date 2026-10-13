package com.mediaviewer.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.mediaviewer.platform.fileOfUri
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.FFmpegLogCallback
import org.bytedeco.javacv.Frame
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.FloatControl
import javax.sound.sampled.SourceDataLine
import kotlin.math.abs
import kotlin.math.log10

/**
 * The desktop app's video player (ExoPlayer on Android, AVPlayer on iOS):
 * FFmpeg (through JavaCV, bundled with the app) reads the video — Bluesky's
 * HLS streams, mp4/webm files, GIF-videos — on a thread of its own. Pictures
 * become Compose images that [FeedVideoView] draws; sound goes to the
 * computer's speakers through Java's audio line, and its clock paces the
 * pictures (or the wall clock, for a silent video). Loops, like the feed.
 */
internal class DesktopVideoPlayer(val url: String, private val muted: Boolean = false) : FeedVideoPlayer {
    /** The picture to draw now (Compose state). */
    var frame: ImageBitmap? by mutableStateOf(null)
        private set

    /** Clockwise degrees the picture must be turned to stand upright (a
     *  phone video's rotation flag). */
    @Volatile var rotation: Int = 0
        private set

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<FeedVideoListener>()
    private val lock = Object()
    @Volatile private var wantPlaying = false
    @Volatile private var released = false
    @Volatile private var seekRequestMs: Long = -1L
    @Volatile private var positionMs = 0L
    @Volatile private var durationMs = 0L
    @Volatile private var aspect = 0f
    @Volatile private var buffering = true
    @Volatile private var actuallyPlaying = false
    @Volatile private var firstFrameSent = false
    private var thread: Thread? = null

    init {
        DesktopVideo.init()
        thread = Thread({ run() }, "stellar-video").apply { isDaemon = true; start() }
    }

    override val isPlaying: Boolean get() = actuallyPlaying
    override val currentPosition: Long get() = positionMs
    override val duration: Long get() = durationMs
    override val aspectRatio: Float get() = aspect
    override val isBuffering: Boolean get() = buffering

    override fun play() { wantPlaying = true; wake() }
    override fun pause() { wantPlaying = false; wake() }
    override fun seekTo(positionMs: Long) { seekRequestMs = positionMs.coerceAtLeast(0L); this.positionMs = positionMs; wake() }

    override fun addListener(listener: FeedVideoListener) { listeners += listener }
    override fun removeListener(listener: FeedVideoListener) { listeners -= listener }

    fun release() {
        released = true
        wantPlaying = false
        listeners.clear()
        wake()
    }

    private fun wake() = synchronized(lock) { lock.notifyAll() }

    private fun setPlaying(p: Boolean) {
        if (p == actuallyPlaying) return
        actuallyPlaying = p
        DesktopVideo.onMain { listeners.forEach { it.onIsPlayingChanged(p) } }
    }

    private fun setBuffering(b: Boolean) {
        if (b == buffering) return
        buffering = b
        DesktopVideo.onMain { listeners.forEach { it.onBuffering(b) } }
    }

    // ── The decoding thread ─────────────────────────────────────────────

    private fun run() {
        var grabber: FFmpegFrameGrabber? = null
        var line: SourceDataLine? = null
        try {
            val source = fileOfUri(url)?.absolutePath ?: url
            val g = FFmpegFrameGrabber(source)
            grabber = g
            g.pixelFormat = avutil.AV_PIX_FMT_BGRA
            g.sampleFormat = avutil.AV_SAMPLE_FMT_S16
            // A stalled network read gives up instead of hanging forever.
            g.setOption("rw_timeout", "20000000")
            g.setOption("reconnect", "1")
            g.setOption("user_agent", "Stellar/1.0 (desktop)")
            g.start()
            if (released) return
            durationMs = (g.lengthInTime / 1000L).coerceAtLeast(0L)
            rotation = ((360 - (g.displayRotation.toInt() % 360)) % 360).let { (it + 45) / 90 * 90 % 360 }
            val w = g.imageWidth
            val h = g.imageHeight
            if (w > 0 && h > 0) {
                // Big videos are scaled down while decoding (the window is
                // never larger than this), which keeps 4K smooth.
                val scale = minOf(1.0, 1920.0 / maxOf(w, h))
                if (scale < 1.0) {
                    g.imageWidth = ((w * scale).toInt() / 2 * 2).coerceAtLeast(2)
                    g.imageHeight = ((h * scale).toInt() / 2 * 2).coerceAtLeast(2)
                }
                val sar = g.aspectRatio.takeIf { it > 0.0 } ?: 1.0
                val a = (w * sar / h).toFloat()
                aspect = if (rotation == 90 || rotation == 270) 1f / a else a
                DesktopVideo.onMain { listeners.forEach { it.onAspectRatio(aspect) } }
            }
            val channels = g.audioChannels
            val rate = g.sampleRate
            if (!muted && channels > 0 && rate > 0) {
                line = runCatching {
                    val fmt = AudioFormat(rate.toFloat(), 16, channels.coerceAtMost(2), true, ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN)
                    AudioSystem.getSourceDataLine(fmt).apply {
                        // About a quarter of a second buffered.
                        open(fmt, (rate * channels.coerceAtMost(2) * 2 / 4).coerceAtLeast(4096))
                        DesktopVideo.applyVolume(this)
                    }
                }.getOrNull()
            }
            loop(g, line, rate, channels.coerceAtMost(2))
        } catch (t: Throwable) {
            if (!released) com.mediaviewer.platform.Log.w("DesktopVideo", "Couldn't play $url", t)
            setBuffering(false)
        } finally {
            setPlaying(false)
            runCatching { line?.stop(); line?.close() }
            runCatching { grabber?.stop(); grabber?.release() }
        }
    }

    private fun loop(g: FFmpegFrameGrabber, line: SourceDataLine?, rate: Int, channels: Int) {
        // The clock: the media time that "now" is. With sound, it's the
        // sound actually heard; without, the wall clock since playback
        // (re)started.
        var clockBaseUs = 0L          // media time at the clock's start
        var wallStartNs = 0L          // System.nanoTime() at the clock's start
        var lineFramesAtBase = 0L     // line.longFramePosition at the clock's start
        var clockRunning = false
        var audioStarted = false
        var audioBytes = ByteArray(0)

        fun nowUs(): Long = when {
            !clockRunning -> clockBaseUs
            line != null && audioStarted -> clockBaseUs + (line.longFramePosition - lineFramesAtBase) * 1_000_000L / rate
            else -> clockBaseUs + (System.nanoTime() - wallStartNs) / 1000L
        }

        fun restartClock(atUs: Long) {
            clockBaseUs = atUs
            wallStartNs = System.nanoTime()
            lineFramesAtBase = line?.longFramePosition ?: 0L
            clockRunning = true
        }

        while (!released) {
            // Seeking.
            val seek = seekRequestMs
            if (seek >= 0L) {
                seekRequestMs = -1L
                runCatching { g.setTimestamp(seek * 1000L, true) }
                line?.let { it.stop(); it.flush(); lineFramesAtBase = it.longFramePosition }
                audioStarted = false
                clockRunning = false
                clockBaseUs = seek * 1000L
            }
            // Paused: wait to be told otherwise.
            if (!wantPlaying) {
                if (clockRunning) { clockBaseUs = nowUs(); clockRunning = false }
                line?.stop()
                audioStarted = false
                setPlaying(false)
                synchronized(lock) { if (!wantPlaying && !released && seekRequestMs < 0L) lock.wait(250) }
                continue
            }
            val f: Frame? = try { g.grab() } catch (t: Throwable) { null }
            if (f == null) {
                // The end: loop from the start (or stop on a broken stream).
                if (durationMs <= 0L && positionMs <= 0L) { setBuffering(false); return }
                runCatching { g.setTimestamp(0L, true) }
                line?.let { it.drain() }
                restartClock(0L)
                lineFramesAtBase = line?.longFramePosition ?: 0L
                continue
            }
            val samples = f.samples
            if (samples != null && line != null) {
                val buf = samples[0] as? ShortBuffer ?: continue
                buf.rewind()
                val n = buf.remaining()
                if (audioBytes.size < n * 2) audioBytes = ByteArray(n * 2)
                val bb = ByteBuffer.wrap(audioBytes, 0, n * 2).order(ByteOrder.nativeOrder())
                bb.asShortBuffer().put(buf)
                if (!clockRunning) restartClock(f.timestamp)
                if (!audioStarted) { line.start(); audioStarted = true; lineFramesAtBase = line.longFramePosition; clockBaseUs = f.timestamp }
                DesktopVideo.applyVolume(line)
                line.write(audioBytes, 0, n * 2)
                setPlaying(true)
                continue
            }
            if (f.image == null) continue
            val pts = f.timestamp
            if (!clockRunning) restartClock(pts)
            val ahead = pts - nowUs()
            // Late by more than a few frames: skip drawing this one.
            if (ahead < -120_000L && firstFrameSent) { positionMs = pts / 1000L; continue }
            val image = runCatching { toImage(f) }.getOrNull() ?: continue
            if (ahead > 0) {
                synchronized(lock) {
                    val until = System.nanoTime() + ahead * 1000L
                    while (!released && wantPlaying && seekRequestMs < 0L) {
                        val left = (until - System.nanoTime()) / 1_000_000L
                        if (left <= 0) break
                        lock.wait(left.coerceAtMost(40L))
                    }
                }
            }
            frame = image
            positionMs = pts / 1000L
            setBuffering(false)
            setPlaying(true)
            if (!firstFrameSent) {
                firstFrameSent = true
                DesktopVideo.onMain { listeners.forEach { it.onFirstFrame() } }
            }
        }
    }

    private fun toImage(f: Frame): ImageBitmap? {
        val w = f.imageWidth
        val h = f.imageHeight
        if (w <= 0 || h <= 0) return null
        val buf = f.image[0] as? ByteBuffer ?: return null
        val rowBytes = f.imageStride.takeIf { it >= w * 4 } ?: (w * 4)
        val bytes = ByteArray(rowBytes * h)
        buf.rewind()
        buf.get(bytes, 0, minOf(bytes.size, buf.remaining()))
        val info = ImageInfo(w, h, ColorType.BGRA_8888, ColorAlphaType.OPAQUE)
        return Image.makeRaster(info, bytes, rowBytes).toComposeImageBitmap()
    }
}

/** Shared bits of the desktop video players. */
internal object DesktopVideo {
    @Volatile private var ready = false

    fun init() {
        if (ready) return
        ready = true
        // FFmpeg's own console chatter only with STELLAR_DEBUG set.
        runCatching {
            if (System.getenv("STELLAR_DEBUG") != null) FFmpegLogCallback.set()
            else avutil.av_log_set_level(avutil.AV_LOG_ERROR)
        }
    }

    /** Runs [block] on the UI thread. */
    fun onMain(block: () -> Unit) {
        javax.swing.SwingUtilities.invokeLater { runCatching(block) }
    }

    /** 0..1, the in-app volume (Settings has none yet: always full). */
    @Volatile var volume: Float = 1f

    fun applyVolume(line: SourceDataLine) {
        runCatching {
            val gain = line.getControl(FloatControl.Type.MASTER_GAIN) as FloatControl
            val db = if (volume <= 0.001f) gain.minimum else (20f * log10(volume)).coerceIn(gain.minimum, gain.maximum)
            if (abs(gain.value - db) > 0.1f) gain.value = db
        }
    }
}
