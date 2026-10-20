package com.mediaviewer.util

import com.mediaviewer.platform.MediaBridge
import com.mediaviewer.platform.PlatformContext
import com.mediaviewer.platform.PlatformUri
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Custom emoji pictures, sized and squeezed the way Discord does its own:
 * at most [MAX_SIDE] pixels on the long side, and an animated emoji (from
 * a GIF or a video) a looping GIF of at most [MAX_BYTES]. Anything bigger
 * is made smaller — fewer frames first, then fewer pixels — until it fits.
 */
object EmojiMedia {
    const val MAX_SIDE = 128
    const val MAX_BYTES = 256 * 1024
    /** A video emoji: its first [VIDEO_SECONDS] seconds at [VIDEO_FPS]. */
    const val VIDEO_SECONDS = 6.0
    const val VIDEO_FPS = 12
    private const val MAX_FRAMES = 150

    fun isVideoName(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in setOf("mp4", "m4v", "mov", "webm", "mkv", "3gp", "avi")

    /** True for a stored animated emoji (kept as a GIF). */
    fun isAnimatedFile(file: String): Boolean = file.endsWith(".gif", ignoreCase = true)

    /** An animated GIF ([bytes]) as an emoji GIF; null when it isn't
     *  animated (a one-frame GIF is just a picture) or can't be read. */
    fun fromGif(bytes: ByteArray): ByteArray? {
        if (!GifInfo.isAnimated(bytes)) return null
        val (gw, gh) = GifInfo.size(bytes)
        if (gw <= 0 || gh <= 0) return null
        val (w, h) = fit(gw, gh, MAX_SIDE)
        val frames = ArrayList<IntArray>()
        val delays = ArrayList<Int>()
        runCatching {
            GifDecoder.decode(bytes, background = 0) { canvas, cw, ch, delay ->
                if (frames.size < MAX_FRAMES * 2) {
                    frames += scale(canvas, cw, ch, w, h)
                    delays += delay.toInt()
                }
            }
        }
        if (frames.size < 2) return null
        return encode(frames, delays, w, h)
    }

    /** The start of a video as an emoji GIF. Throws when it can't be read. */
    suspend fun fromVideo(context: PlatformContext, uri: PlatformUri): ByteArray {
        val v = MediaBridge.videoFrames(context, uri, MAX_SIDE, VIDEO_FPS, VIDEO_SECONDS)
        if (v.frames.isEmpty()) error("Couldn't read the video")
        return encode(v.frames, List(v.frames.size) { v.frameMs }, v.width, v.height)
            ?: error("Couldn't make the emoji")
    }

    /** Frames → a GIF within [MAX_BYTES]: every frame at full size first,
     *  then every 2nd/3rd frame, then smaller. */
    fun encode(frames: List<IntArray>, delays: List<Int>, w: Int, h: Int): ByteArray? {
        if (frames.isEmpty()) return null
        var best: ByteArray? = null
        for ((sideScale, step) in listOf(1f to 1, 1f to 2, 0.75f to 2, 0.75f to 3, 0.5f to 3, 0.5f to 4, 0.375f to 5)) {
            val tw = (w * sideScale).roundToInt().coerceAtLeast(1)
            val th = (h * sideScale).roundToInt().coerceAtLeast(1)
            // Never more than MAX_FRAMES, whatever the step.
            val s = max(step, (frames.size + MAX_FRAMES - 1) / MAX_FRAMES)
            val writer = GifWriter(tw, th)
            var i = 0
            while (i < frames.size) {
                // Dropped frames' time goes to the one kept before them.
                var ms = 0
                for (k in i until minOf(i + s, frames.size)) ms += delays.getOrElse(k) { 100 }
                val px = if (tw == w && th == h) frames[i] else scale(frames[i], w, h, tw, th)
                writer.addFrame(px, ms.coerceAtLeast(20))
                i += s
            }
            val gif = writer.finish()
            best = gif
            if (gif.size <= MAX_BYTES) return gif
        }
        return best?.takeIf { it.size <= MAX_BYTES * 2 }
    }

    /** [w]×[h] scaled to fit within [side] (never up). */
    fun fit(w: Int, h: Int, side: Int): Pair<Int, Int> {
        val longest = max(w, h)
        if (longest <= side) return w to h
        val s = side.toFloat() / longest
        return (w * s).roundToInt().coerceAtLeast(1) to (h * s).roundToInt().coerceAtLeast(1)
    }

    /** Area-average scale of ARGB [src] (premultiplied while averaging, so
     *  see-through edges don't go dark). */
    fun scale(src: IntArray, sw: Int, sh: Int, dw: Int, dh: Int): IntArray {
        if (sw == dw && sh == dh) return src.copyOf()
        val out = IntArray(dw * dh)
        val xr = sw.toFloat() / dw
        val yr = sh.toFloat() / dh
        for (y in 0 until dh) {
            val y0 = (y * yr).toInt(); val y1 = minOf(sh, maxOf(y0 + 1, ((y + 1) * yr).toInt()))
            for (x in 0 until dw) {
                val x0 = (x * xr).toInt(); val x1 = minOf(sw, maxOf(x0 + 1, ((x + 1) * xr).toInt()))
                var a = 0L; var r = 0L; var g = 0L; var b = 0L; var n = 0
                for (yy in y0 until y1) {
                    val row = yy * sw
                    for (xx in x0 until x1) {
                        val p = src[row + xx]
                        val pa = (p ushr 24) and 0xFF
                        a += pa
                        r += ((p shr 16) and 0xFF) * pa; g += ((p shr 8) and 0xFF) * pa; b += (p and 0xFF) * pa
                        n++
                    }
                }
                if (n == 0 || a == 0L) { out[y * dw + x] = 0; continue }
                val oa = (a / n).toInt()
                out[y * dw + x] = (oa shl 24) or ((r / a).toInt() shl 16) or ((g / a).toInt() shl 8) or (b / a).toInt()
            }
        }
        return out
    }
}
