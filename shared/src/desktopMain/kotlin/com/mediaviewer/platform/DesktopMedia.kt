package com.mediaviewer.platform

import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.FFmpegFrameFilter
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.FFmpegFrameRecorder
import org.bytedeco.javacv.Frame
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.Surface
import java.io.File
import java.nio.ByteBuffer

/**
 * Picture work on a computer, with Skia (the engine Compose draws with):
 * decoding, turning upright, scaling and encoding.
 */
object DesktopImages {
    private val SMOOTH = FilterMipmap(FilterMode.LINEAR, MipmapMode.LINEAR)

    /** Decodes [bytes] (anything Skia reads; otherwise Java's own decoder
     *  is tried), or null. */
    fun decode(bytes: ByteArray): Image? =
        runCatching { Image.makeFromEncoded(bytes) }.getOrNull()
            ?: reencodeAsPng(bytes)?.let { runCatching { Image.makeFromEncoded(it) }.getOrNull() }

    /** A picture only Java's ImageIO reads (BMP variants, …) as PNG bytes. */
    fun reencodeAsPng(bytes: ByteArray): ByteArray? = runCatching {
        val img = javax.imageio.ImageIO.read(bytes.inputStream()) ?: return null
        val out = java.io.ByteArrayOutputStream()
        javax.imageio.ImageIO.write(img, "png", out)
        out.toByteArray()
    }.getOrNull()

    /** [image] drawn at [w]×[h] (transparency kept, or on [background]). */
    fun draw(image: Image, w: Int, h: Int, background: Int? = null): Image {
        val surface = Surface.makeRasterN32Premul(w, h)
        val c = surface.canvas
        c.clear(background ?: 0)
        c.drawImageRect(image, Rect.makeWH(image.width.toFloat(), image.height.toFloat()), Rect.makeWH(w.toFloat(), h.toFloat()), SMOOTH, Paint(), true)
        return surface.makeImageSnapshot()
    }

    fun jpeg(image: Image, quality: Int): ByteArray {
        // (JPEG has no transparency: see-through areas become white, not black.)
        val flat = if (image.imageInfo.colorAlphaType == ColorAlphaType.OPAQUE) image else draw(image, image.width, image.height, background = -1)
        return flat.encodeToData(EncodedImageFormat.JPEG, quality)?.bytes ?: error("Couldn't encode image")
    }

    fun png(image: Image): ByteArray = image.encodeToData(EncodedImageFormat.PNG)?.bytes ?: error("Couldn't encode image")

    /** True when the picture has see-through pixels worth keeping. */
    fun hasAlpha(image: Image): Boolean {
        if (image.imageInfo.colorAlphaType == ColorAlphaType.OPAQUE) return false
        // Look at the pixels themselves (a PNG often declares alpha it doesn't use).
        val bmp = org.jetbrains.skia.Bitmap()
        val small = draw(image, minOf(image.width, 256), minOf(image.height, 256))
        bmp.allocPixels(ImageInfo(small.width, small.height, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL))
        if (!small.readPixels(bmp)) return true
        val px = bmp.readPixels() ?: return true
        var i = 3
        while (i < px.size) { if ((px[i].toInt() and 0xFF) < 250) return true; i += 4 }
        return false
    }

    /** ARGB pixels of [image] (w×h). */
    fun argb(image: Image): IntArray {
        val bmp = org.jetbrains.skia.Bitmap()
        bmp.allocPixels(ImageInfo(image.width, image.height, ColorType.BGRA_8888, ColorAlphaType.UNPREMUL))
        image.readPixels(bmp)
        val bytes = bmp.readPixels() ?: return IntArray(image.width * image.height)
        val out = IntArray(image.width * image.height)
        ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(out)
        return out
    }
}

/**
 * Video work on a computer, with FFmpeg (bundled through JavaCV): reading a
 * video's size, re-encoding for upload, the custom-thumbnail stitch, GIF →
 * mp4 and video → GIF frames.
 */
object DesktopFfmpeg {
    private const val MAX_SHORT_SIDE = 1080
    private const val MAX_BYTES = 90L * 1024 * 1024

    init { runCatching { avutil.av_log_set_level(avutil.AV_LOG_ERROR) } }

    class Probe(val width: Int, val height: Int, val rotation: Int, val durationUs: Long, val frameRate: Double, val hasAudio: Boolean)

    private fun grabber(path: String) = FFmpegFrameGrabber(path)

    fun probe(path: String): Probe? = runCatching {
        val g = grabber(path)
        try {
            g.start()
            val rot = ((360 - (g.displayRotation.toInt() % 360)) % 360).let { (it + 45) / 90 * 90 % 360 }
            Probe(g.imageWidth, g.imageHeight, rot, g.lengthInTime, g.frameRate, g.audioChannels > 0)
        } finally {
            runCatching { g.stop(); g.release() }
        }
    }.getOrNull()

    /** Upright display size (rotation applied), or (0, 0). */
    fun displaySize(path: String): Pair<Int, Int> {
        val p = probe(path) ?: return 0 to 0
        return if (p.rotation == 90 || p.rotation == 270) p.height to p.width else p.width to p.height
    }

    private fun newCacheFile(prefix: String, ext: String): File =
        File(DesktopPaths.cacheDir, "$prefix-${currentTimeMillis()}-${(0..9999).random()}.$ext")

    private fun recorder(out: File, w: Int, h: Int, fps: Double, bitrate: Int, audioChannels: Int, sampleRate: Int): FFmpegFrameRecorder =
        FFmpegFrameRecorder(out, w, h, audioChannels).apply {
            format = "mp4"
            videoCodec = avcodec.AV_CODEC_ID_H264
            pixelFormat = avutil.AV_PIX_FMT_YUV420P
            frameRate = if (fps.isFinite() && fps in 1.0..120.0) fps else 30.0
            videoBitrate = bitrate
            gopSize = (frameRate * 2).toInt().coerceAtLeast(1)
            setVideoOption("preset", "veryfast")
            setOption("movflags", "+faststart")
            if (audioChannels > 0) {
                audioCodec = avcodec.AV_CODEC_ID_AAC
                this.sampleRate = sampleRate
                audioBitrate = 128_000
            }
        }

    /** The filter that turns a [rotation]° (clockwise) video upright, or null. */
    private fun uprightFilter(rotation: Int): String? = when (rotation) {
        90 -> "transpose=1"
        180 -> "hflip,vflip"
        270 -> "transpose=2"
        else -> null
    }

    /**
     * The video at [path] re-encoded under Bluesky's limits (1080p, 90 MB),
     * or [path] itself when it's already within them or encoding fails.
     */
    fun prepareForUpload(path: String): String {
        val p = probe(path) ?: return path
        val bytes = File(path).length()
        if (minOf(p.width, p.height) <= MAX_SHORT_SIDE && bytes <= MAX_BYTES) return path
        val seconds = (p.durationUs / 1_000_000.0).takeIf { it > 0.0 } ?: 1.0
        val budget = (MAX_BYTES * 8 * 0.85 / seconds).toInt()
        val short = when {
            budget >= 9_000_000 -> 1080
            budget >= 4_500_000 -> 720
            else -> 540
        }
        val out = newCacheFile("upload", "mp4")
        return runCatching {
            transcode(path, out, short, minOf(budget - 128_000, 12_000_000).coerceAtLeast(600_000), p)
            if (out.length() > 0L && out.length() < bytes) out.absolutePath else { out.delete(); path }
        }.getOrElse { out.delete(); path }
    }

    private fun transcode(path: String, out: File, shortSide: Int, bitrate: Int, p: Probe, prefix: ((FFmpegFrameRecorder, Int, Int) -> Long)? = null) {
        val turned = p.rotation == 90 || p.rotation == 270
        val uw = if (turned) p.height else p.width
        val uh = if (turned) p.width else p.height
        val scale = minOf(1.0, shortSide.toDouble() / minOf(uw, uh).coerceAtLeast(1))
        val w = ((uw * scale).toInt() / 2 * 2).coerceAtLeast(2)
        val h = ((uh * scale).toInt() / 2 * 2).coerceAtLeast(2)
        val g = grabber(path)
        g.start()
        val channels = g.audioChannels.coerceAtMost(2)
        val rec = recorder(out, w, h, g.frameRate, bitrate, channels, g.sampleRate.takeIf { it > 0 } ?: 44_100)
        val filterSpec = listOfNotNull(uprightFilter(p.rotation), "scale=$w:$h", "format=yuv420p").joinToString(",")
        val filter = FFmpegFrameFilter(filterSpec, g.imageWidth, g.imageHeight).apply {
            pixelFormat = g.pixelFormat
            frameRate = g.frameRate
            start()
        }
        try {
            rec.start()
            // Anything shown before the video (the thumbnail), and how long it lasts.
            val offsetUs = prefix?.invoke(rec, w, h) ?: 0L
            while (true) {
                val f: Frame = g.grabFrame(true, true, true, false) ?: break
                if (f.image != null) {
                    filter.push(f)
                    while (true) {
                        val ff = filter.pull() ?: break
                        rec.timestamp = offsetUs + f.timestamp
                        rec.record(ff, avutil.AV_PIX_FMT_YUV420P)
                    }
                } else if (f.samples != null && channels > 0) {
                    rec.timestamp = offsetUs + f.timestamp
                    rec.record(f)
                }
            }
        } finally {
            runCatching { filter.stop(); filter.release() }
            runCatching { rec.stop(); rec.release() }
            runCatching { g.stop(); g.release() }
        }
    }

    /**
     * A profile's video icon or banner (see MediaBridge.cropVideo): the
     * [left]/[top]/[width]/[height] part (fractions) of the upright picture,
     * scaled to fit [maxW]×[maxH], without sound.
     */
    fun crop(path: String, left: Float, top: Float, width: Float, height: Float, maxW: Int, maxH: Int): String {
        val p = probe(path) ?: error("Couldn't read the video")
        val turned = p.rotation == 90 || p.rotation == 270
        val uw = if (turned) p.height else p.width
        val uh = if (turned) p.width else p.height
        val cw = ((uw * width).toInt() / 2 * 2).coerceIn(2, uw)
        val ch = ((uh * height).toInt() / 2 * 2).coerceIn(2, uh)
        val cx = (uw * left).toInt().coerceIn(0, uw - cw)
        val cy = (uh * top).toInt().coerceIn(0, uh - ch)
        val scale = minOf(1.0, maxW.toDouble() / cw, maxH.toDouble() / ch)
        val w = ((cw * scale).toInt() / 2 * 2).coerceAtLeast(2)
        val h = ((ch * scale).toInt() / 2 * 2).coerceAtLeast(2)
        val out = newCacheFile("profile-video", "mp4")
        val g = grabber(path)
        g.start()
        val rec = recorder(out, w, h, g.frameRate, 4_000_000, 0, 0)
        val filterSpec = listOfNotNull(uprightFilter(p.rotation), "crop=$cw:$ch:$cx:$cy", "scale=$w:$h", "format=yuv420p").joinToString(",")
        val filter = FFmpegFrameFilter(filterSpec, g.imageWidth, g.imageHeight).apply {
            pixelFormat = g.pixelFormat
            frameRate = g.frameRate
            start()
        }
        try {
            rec.start()
            while (true) {
                val f: Frame = g.grabFrame(false, true, true, false) ?: break
                if (f.image == null) continue
                filter.push(f)
                while (true) {
                    val ff = filter.pull() ?: break
                    rec.timestamp = f.timestamp
                    rec.record(ff, avutil.AV_PIX_FMT_YUV420P)
                }
            }
        } catch (t: Throwable) {
            runCatching { filter.stop(); filter.release() }
            runCatching { rec.stop(); rec.release() }
            runCatching { g.stop(); g.release() }
            out.delete()
            throw t
        }
        runCatching { filter.stop(); filter.release() }
        runCatching { rec.stop(); rec.release() }
        runCatching { g.stop(); g.release() }
        if (out.length() <= 0L) { out.delete(); error("Cropping the video failed") }
        return out.absolutePath
    }

    /**
     * Custom video thumbnails (like Android's VideoThumbnailStitcher and the
     * iOS one): the picture, centre-cropped to the video's shape, becomes
     * the first second of the video — Bluesky shows a video's first frame as
     * its thumbnail. The sound moves a second later to stay in step.
     */
    fun stitchThumbnail(videoPath: String, thumbnailPath: String): String {
        val p = probe(videoPath) ?: error("Couldn't read the video")
        val thumb = DesktopImages.decode(File(thumbnailPath).readBytes()) ?: error("Couldn't read the thumbnail")
        val out = newCacheFile("thumbnailed", "mp4")
        val bitrate = (File(videoPath).length() * 8 / (p.durationUs / 1_000_000.0).coerceAtLeast(1.0)).toInt()
            .coerceIn(1_500_000, 12_000_000)
        try {
            transcode(videoPath, out, MAX_SHORT_SIDE, bitrate, p) { rec, w, h ->
                // Centre-crop the picture to the video's shape.
                val scale = maxOf(w.toFloat() / thumb.width, h.toFloat() / thumb.height)
                val sw = w / scale
                val sh = h / scale
                val surface = Surface.makeRasterN32Premul(w, h)
                surface.canvas.clear(-0x1000000)
                surface.canvas.drawImageRect(
                    thumb,
                    Rect.makeXYWH((thumb.width - sw) / 2f, (thumb.height - sh) / 2f, sw, sh),
                    Rect.makeWH(w.toFloat(), h.toFloat()),
                    SamplingMode.LINEAR, Paint(), true
                )
                val frame = argbFrame(DesktopImages.argb(surface.makeImageSnapshot()), w, h)
                // One frame: frame 0 (what Bluesky shows as the thumbnail),
                // too short to notice while the video plays.
                val fps = rec.frameRate.coerceAtLeast(1.0)
                rec.timestamp = 0L
                rec.record(frame, avutil.AV_PIX_FMT_BGRA)
                (1_000_000L / fps).toLong()
            }
        } catch (t: Throwable) {
            out.delete()
            throw t
        }
        return out.absolutePath
    }

    /** ARGB pixels as a BGRA video frame. */
    fun argbFrame(argb: IntArray, w: Int, h: Int): Frame {
        val f = Frame(w, h, Frame.DEPTH_UBYTE, 4)
        val buf = f.image[0] as ByteBuffer
        val stride = f.imageStride
        for (y in 0 until h) {
            buf.position(y * stride)
            for (x in 0 until w) {
                val c = argb[y * w + x]
                buf.put((c and 0xFF).toByte())
                buf.put(((c shr 8) and 0xFF).toByte())
                buf.put(((c shr 16) and 0xFF).toByte())
                buf.put(0xFF.toByte())
            }
        }
        buf.rewind()
        return f
    }

    /** An animated GIF as a looping H.264 mp4 (how Bluesky takes GIFs). */
    fun videoFromGif(gif: ByteArray): String {
        val (gw, gh) = com.mediaviewer.util.GifInfo.size(gif)
        val (w, h) = com.mediaviewer.util.GifVideo.targetSize(gw, gh)
        val out = newCacheFile("gif", "mp4")
        val rec = recorder(out, w, h, 30.0, (w * h * 4).coerceIn(800_000, 6_000_000), 0, 0)
        try {
            rec.start()
            var lastFrame: Frame? = null
            var lastMs = -1L
            com.mediaviewer.util.GifVideo.frames(gif, w, h) { pixels, ms ->
                // (The final call repeats the last picture at the end time,
                // so the last frame keeps its length.)
                if (ms == lastMs) return@frames
                val frame = argbFrame(pixels, w, h)
                rec.timestamp = ms * 1000L
                rec.record(frame, avutil.AV_PIX_FMT_BGRA)
                lastFrame = frame
                lastMs = ms
            }
            lastFrame?.let { rec.timestamp = (lastMs + 1) * 1000L }
        } catch (t: Throwable) {
            runCatching { rec.stop(); rec.release() }
            out.delete()
            throw t
        }
        rec.stop()
        rec.release()
        if (out.length() <= 0L) { out.delete(); error("Couldn't make the video") }
        return out.absolutePath
    }

    /**
     * Every frame of the video at [path] (up to [maxFps] a second, the first
     * [maxSeconds]) as ARGB pixels with its time in ms, upright, at most
     * [maxSide] on the long side.
     */
    fun frames(path: String, maxFps: Double, maxSeconds: Double, maxSide: Int, emit: (IntArray, Int, Int, Long) -> Unit) {
        val p = probe(path) ?: error("Couldn't read the video")
        val turned = p.rotation == 90 || p.rotation == 270
        val uw = if (turned) p.height else p.width
        val uh = if (turned) p.width else p.height
        val scale = minOf(1.0, maxSide.toDouble() / maxOf(uw, uh).coerceAtLeast(1))
        val w = (uw * scale).toInt().coerceAtLeast(1)
        val h = (uh * scale).toInt().coerceAtLeast(1)
        val g = grabber(path)
        g.start()
        val filterSpec = listOfNotNull(uprightFilter(p.rotation), "scale=$w:$h", "format=bgra").joinToString(",")
        val filter = FFmpegFrameFilter(filterSpec, g.imageWidth, g.imageHeight).apply {
            pixelFormat = g.pixelFormat
            start()
        }
        val minGapMs = (1000.0 / maxFps).toLong()
        var lastMs = Long.MIN_VALUE
        val px = IntArray(w * h)
        try {
            while (true) {
                val f = g.grabImage() ?: break
                val ms = f.timestamp / 1000L
                if (ms > maxSeconds * 1000) break
                if (lastMs != Long.MIN_VALUE && ms - lastMs < minGapMs) continue
                filter.push(f)
                val out = filter.pull() ?: continue
                val buf = out.image[0] as ByteBuffer
                val stride = out.imageStride
                for (y in 0 until h) {
                    for (x in 0 until w) {
                        val i = y * stride + x * 4
                        val b = buf.get(i).toInt() and 0xFF
                        val gr = buf.get(i + 1).toInt() and 0xFF
                        val r = buf.get(i + 2).toInt() and 0xFF
                        px[y * w + x] = (0xFF shl 24) or (r shl 16) or (gr shl 8) or b
                    }
                }
                emit(px, w, h, ms)
                lastMs = ms
            }
        } finally {
            runCatching { filter.stop(); filter.release() }
            runCatching { g.stop(); g.release() }
        }
    }
}
