// check:jvm
package com.mediaviewer.stream

import android.media.AudioAttributes
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaPlayer
import android.util.Log
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * VRM mode's soundboard (Activity popup). A tapped sound is played on the
 * phone so you hear it, and — while a recording or a live stream is
 * running — mixed straight into that recording's / stream's own audio
 * (see [mix], called by the two audio loops), so viewers hear it cleanly
 * instead of whatever the microphone picks up from the speaker.
 */
object SoundboardMixer {
    private const val TAG = "SoundboardMixer"
    /** The rate both audio loops run at. */
    const val RATE = 44_100
    /** Longest sound kept for mixing (a soundboard is short clips). */
    private const val MAX_SECONDS = 90

    private class Voice(val pcm: ShortArray, val startedAtMs: Long) {
        @Volatile var position = 0
    }

    /** Sounds currently being mixed into a recording / stream. */
    private val voices = CopyOnWriteArrayList<Voice>()
    /** Decoded sounds (mono, [RATE] Hz), by file path. */
    private val decoded = ConcurrentHashMap<String, ShortArray>()
    private val players = CopyOnWriteArrayList<MediaPlayer>()

    /** True while a recording or stream is taking audio from [mix]. */
    @Volatile var capturing = false

    /** Plays the sound file at [path] now. */
    fun play(path: String) {
        // On the phone itself.
        try {
            val p = MediaPlayer()
            p.setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            )
            p.setDataSource(path)
            p.setOnCompletionListener { done -> players.remove(done); runCatching { done.release() } }
            p.setOnErrorListener { failed, _, _ -> players.remove(failed); runCatching { failed.release() }; true }
            p.setOnPreparedListener { it.start() }
            players.add(p)
            p.prepareAsync()
        } catch (e: Exception) {
            Log.e(TAG, "Couldn't play $path", e)
        }
        // …and into the recording / stream, when there is one.
        if (!capturing) return
        Thread({
            val pcm = decoded[path] ?: decode(path)?.also { decoded[path] = it }
            if (pcm != null && capturing) {
                prune()
                voices.add(Voice(pcm, System.currentTimeMillis()))
            }
        }, "soundboard-decode").start()
    }

    fun stopAll() {
        voices.clear()
        players.forEach { p -> runCatching { p.stop() }; runCatching { p.release() } }
        players.clear()
    }

    /** A sound was removed from the board: its decoded copy goes too. */
    fun forget(path: String) { decoded.remove(path) }

    private fun prune() {
        val now = System.currentTimeMillis()
        voices.removeAll { v -> v.position >= v.pcm.size || now - v.startedAtMs > (v.pcm.size * 1000L / RATE) + 5000L }
    }

    /**
     * Adds the playing sounds onto [n] samples of [buffer] (mono, 16-bit,
     * [RATE] Hz) — called once per block by whichever audio loop is running.
     */
    fun mix(buffer: ShortArray, n: Int) {
        if (voices.isEmpty()) return
        for (v in voices) {
            val pcm = v.pcm
            var pos = v.position
            val count = minOf(n, pcm.size - pos)
            for (i in 0 until count) {
                val sum = buffer[i] + pcm[pos + i]
                buffer[i] = (if (sum > Short.MAX_VALUE) Short.MAX_VALUE.toInt() else if (sum < Short.MIN_VALUE) Short.MIN_VALUE.toInt() else sum).toShort()
            }
            pos += maxOf(count, 0)
            v.position = pos
            if (pos >= pcm.size) voices.remove(v)
        }
    }

    /** Any audio file → mono 16-bit samples at [RATE] Hz. Null if it can't
     *  be read. */
    private fun decode(path: String): ShortArray? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(path)
            var track = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { track = i; format = f; break }
            }
            val inFormat = format ?: return null
            extractor.selectTrack(track)
            val c = MediaCodec.createDecoderByType(inFormat.getString(MediaFormat.KEY_MIME)!!)
            codec = c
            c.configure(inFormat, null, null, 0)
            c.start()
            var sourceRate = inFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = inFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            val maxSourceSamples = MAX_SECONDS.toLong() * 192_000L
            var mono = ShortArray(1 shl 16)
            var length = 0
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var idle = 0
            while (!outputDone && idle < 400) {
                if (!inputDone) {
                    val inIndex = c.dequeueInputBuffer(5_000)
                    if (inIndex >= 0) {
                        val buf = c.getInputBuffer(inIndex)
                        val size = if (buf != null) extractor.readSampleData(buf, 0) else -1
                        if (size < 0) {
                            c.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            c.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = c.dequeueOutputBuffer(info, 5_000)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = c.outputFormat
                        if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) sourceRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                    }
                    outIndex >= 0 -> {
                        idle = 0
                        val out = c.getOutputBuffer(outIndex)
                        if (out != null && info.size > 0) {
                            out.position(info.offset)
                            out.limit(info.offset + info.size)
                            val shorts = out.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                            val frames = shorts.remaining() / channels
                            if (length + frames > mono.size) mono = mono.copyOf(maxOf(mono.size * 2, length + frames))
                            for (i in 0 until frames) {
                                var sum = 0
                                for (ch in 0 until channels) sum += shorts.get(i * channels + ch)
                                mono[length + i] = (sum / channels).toShort()
                            }
                            length += frames
                        }
                        c.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 || length >= maxSourceSamples) outputDone = true
                    }
                    else -> idle++
                }
            }
            if (length == 0 || sourceRate <= 0) return null
            // To the mixer's rate (straight-line interpolation is plenty for clips).
            if (sourceRate == RATE) return mono.copyOf(length)
            val outLength = (length.toLong() * RATE / sourceRate).toInt().coerceAtMost(MAX_SECONDS * RATE)
            val result = ShortArray(outLength)
            val step = sourceRate.toDouble() / RATE
            for (i in 0 until outLength) {
                val src = i * step
                val a = src.toInt().coerceAtMost(length - 1)
                val b = (a + 1).coerceAtMost(length - 1)
                val t = src - a
                result[i] = (mono[a] * (1.0 - t) + mono[b] * t).toInt().toShort()
            }
            return result
        } catch (e: Exception) {
            Log.e(TAG, "Couldn't decode $path", e)
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }
}
