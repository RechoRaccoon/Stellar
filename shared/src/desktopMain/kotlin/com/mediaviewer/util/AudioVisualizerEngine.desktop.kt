package com.mediaviewer.util

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mediaviewer.platform.PlatformContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.TargetDataLine
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A computer's visualizer. An app can't simply hear what the computer is
 * playing, but most computers can offer it as a recording input — a
 * "loopback": Stereo Mix on Windows (Sound settings → Recording), a
 * monitor input on Linux, BlackHole or Soundflower on a Mac. The first
 * such input is listened to (nothing is recorded: each moment is turned
 * into a spectrum and thrown away). Without one, [status] says how to get
 * one and the bars stay flat.
 */
actual object AudioVisualizerEngine {
    private const val BAR_COUNT = 28
    private const val N = 2048
    private var _levels by mutableStateOf(FloatArray(BAR_COUNT))
    actual val levels: FloatArray get() = _levels
    private var _status by mutableStateOf("")
    actual val status: String get() = _status
    private var _listening by mutableStateOf(false)
    actual val listening: Boolean get() = _listening
    private val bands = VisualizerBands(BAR_COUNT)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var users = 0
    private var job: Job? = null
    @Volatile private var line: TargetDataLine? = null

    /** Input names that carry what the computer plays. */
    private val loopbackNames = listOf(
        "stereo mix", "what u hear", "wave out", "loopback", "blackhole", "soundflower",
        "monitor of", "cable output", "vb-audio", "voicemeeter", "what you hear"
    )

    actual fun hasPermission(context: PlatformContext): Boolean = true
    actual fun watchPlayerSessions(context: PlatformContext) {}

    actual fun acquire(context: PlatformContext) {
        users++
        if (users == 1) start()
    }

    actual fun release() {
        users = (users - 1).coerceAtLeast(0)
        if (users == 0) stop()
    }

    actual fun startListening(context: PlatformContext) {
        stop()
        if (users > 0) start()
    }

    private fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            val opened = runCatching { openLoopback() }.getOrNull()
            if (opened == null) {
                withContext(Dispatchers.Main) {
                    _listening = false
                    _status = "No loopback input found. Windows: turn on Stereo Mix (Sound settings → Recording). Mac: install BlackHole."
                }
                return@launch
            }
            val (l, name) = opened
            line = l
            withContext(Dispatchers.Main) { _listening = true; _status = "Listening to $name" }
            val frameBytes = l.format.frameSize
            val channels = l.format.channels
            val buf = ByteArray(N * frameBytes)
            val re = DoubleArray(N); val im = DoubleArray(N)
            val window = DoubleArray(N) { 0.5 - 0.5 * cos(2 * PI * it / (N - 1)) }
            try {
                while (isActive) {
                    var got = 0
                    while (got < buf.size && isActive) {
                        val r = l.read(buf, got, buf.size - got)
                        if (r <= 0) break
                        got += r
                    }
                    if (got < buf.size) break
                    for (i in 0 until N) {
                        // 16-bit little-endian, the channels averaged.
                        var sum = 0
                        for (c in 0 until channels) {
                            val o = i * frameBytes + c * 2
                            sum += (buf[o].toInt() and 0xFF) or (buf[o + 1].toInt() shl 8)
                        }
                        re[i] = sum.toDouble() / channels / 32768.0 * window[i]
                        im[i] = 0.0
                    }
                    fft(re, im)
                    val mags = FloatArray(N / 2) { k ->
                        val m = sqrt(re[k] * re[k] + im[k] * im[k]) * 4.0 / N
                        // Onto a loudness scale like Android's.
                        (ln(1.0 + m * 400.0) / ln(401.0)).toFloat().coerceIn(0f, 1f)
                    }
                    val next = bands.update(mags, l.format.sampleRate)
                    withContext(Dispatchers.Main) { _levels = next }
                }
            } finally {
                runCatching { l.stop(); l.close() }
                if (line === l) line = null
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) { _levels = FloatArray(BAR_COUNT) }
            }
        }
    }

    private fun stop() {
        job?.cancel(); job = null
        line?.let { runCatching { it.stop(); it.close() } }
        line = null
    }

    /** The first loopback input that opens, and its name. */
    private fun openLoopback(): Pair<TargetDataLine, String>? {
        val formats = listOf(
            AudioFormat(48000f, 16, 2, true, false), AudioFormat(44100f, 16, 2, true, false),
            AudioFormat(48000f, 16, 1, true, false), AudioFormat(44100f, 16, 1, true, false)
        )
        val mixers = AudioSystem.getMixerInfo().filter { info ->
            val text = (info.name + " " + info.description).lowercase()
            loopbackNames.any { it in text }
        }
        for (info in mixers) {
            val mixer = runCatching { AudioSystem.getMixer(info) }.getOrNull() ?: continue
            for (format in formats) {
                val lineInfo = DataLine.Info(TargetDataLine::class.java, format)
                if (!mixer.isLineSupported(lineInfo)) continue
                val l = runCatching { mixer.getLine(lineInfo) as TargetDataLine }.getOrNull() ?: continue
                val ok = runCatching { l.open(format, N * format.frameSize * 2); l.start(); true }.getOrDefault(false)
                if (ok) return l to info.name
                runCatching { l.close() }
            }
        }
        return null
    }

    /** In-place radix-2 FFT. */
    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang); val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until len / 2) {
                    val ar = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val ai = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k + len / 2] = re[i + k] - ar; im[i + k + len / 2] = im[i + k] - ai
                    re[i + k] += ar; im[i + k] += ai
                    val ncr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }
}
