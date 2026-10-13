package com.mediaviewer.platform

import com.mediaviewer.network.DesktopFileStreamSource
import com.mediaviewer.network.RequestBody
import com.mediaviewer.network.streamRequestBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

actual object MediaBridge {
    /** Local path of a picked/saved file uri. */
    fun pathOf(uri: PlatformUri): String = uri.file?.absolutePath ?: uri.toString()

    actual fun readBytes(context: PlatformContext, uri: PlatformUri): ByteArray? =
        runCatching { uri.file?.takeIf { it.isFile }?.readBytes() }.getOrNull()

    actual fun mimeTypeOf(context: PlatformContext, uri: PlatformUri): String? =
        when (pathOf(uri).substringAfterLast('.', "").lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "heic" -> "image/heic"
            "heif" -> "image/heif"
            "bmp" -> "image/bmp"
            "mp4", "m4v" -> "video/mp4"
            "mov" -> "video/quicktime"
            "webm" -> "video/webm"
            "mkv" -> "video/x-matroska"
            else -> null
        }

    actual fun imageSize(bytes: ByteArray): Pair<Int, Int> {
        val image = DesktopImages.decode(bytes) ?: return -1 to -1
        return image.width to image.height
    }

    actual fun prepareImageForUpload(context: PlatformContext, uri: PlatformUri, maxBytes: Int, maxDimension: Int): Pair<ByteArray, String> {
        val original = readBytes(context, uri) ?: error("Couldn't read image")
        val declaredType = mimeTypeOf(context, uri)
        val isJpeg = com.mediaviewer.util.JpegMeta.isJpeg(original)
        val orientation = com.mediaviewer.util.JpegMeta.orientation(original)
        val (w0, h0) = imageSize(original)
        // Fast path: within the limits and upright — the original picture.
        // (A JPEG only loses its EXIF: camera details and, often, where the
        // photo was taken.)
        if (w0 in 1..maxDimension && h0 in 1..maxDimension && orientation == 1) {
            if (isJpeg) {
                val clean = com.mediaviewer.util.JpegMeta.stripMetadata(original)
                if (clean != null && clean.size <= maxBytes) return clean to "image/jpeg"
            } else if (declaredType == "image/png" || declaredType == "image/webp" || declaredType == "image/gif") {
                // (Their text/EXIF/XMP/comment metadata comes out too.)
                val clean = com.mediaviewer.util.MetadataScrub.image(original)
                if (clean.size <= maxBytes) return clean to declaredType
            }
        }
        // Otherwise redrawn upright (Skia applies a JPEG's EXIF orientation
        // as it decodes), scaled to fit, and encoded under the limit:
        // quality steps down, then the size. A see-through picture stays a PNG.
        val image = DesktopImages.decode(original) ?: error("Couldn't decode image")
        val transparent = !isJpeg && DesktopImages.hasAlpha(image)
        val pw = image.width
        val ph = image.height
        var scale = minOf(1f, maxDimension.toFloat() / maxOf(pw, ph).coerceAtLeast(1))
        while (true) {
            val w = (pw * scale).toInt().coerceAtLeast(1)
            val h = (ph * scale).toInt().coerceAtLeast(1)
            val drawn = DesktopImages.draw(image, w, h)
            if (transparent) {
                val png = DesktopImages.png(drawn)
                if (png.size <= maxBytes) return png to "image/png"
            } else {
                for (q in intArrayOf(92, 85, 78, 70, 62)) {
                    val out = DesktopImages.jpeg(drawn, q)
                    if (out.size <= maxBytes) return out to "image/jpeg"
                }
            }
            scale *= 0.8f
            if (maxOf(pw, ph) * scale < 320f) error("This picture can't be made small enough to upload")
        }
    }

    actual fun scaledJpeg(context: PlatformContext, uri: PlatformUri, maxW: Int, maxH: Int, maxBytes: Int): ByteArray {
        val original = readBytes(context, uri) ?: error("Couldn't read the picture")
        // (Decoded upright: Skia applies the EXIF orientation.)
        val image = DesktopImages.decode(original) ?: error("Couldn't decode the picture")
        val w0 = image.width
        val h0 = image.height
        val scale = minOf(1f, maxW.toFloat() / w0, maxH.toFloat() / h0)
        val w = (w0 * scale).toInt().coerceAtLeast(1)
        val h = (h0 * scale).toInt().coerceAtLeast(1)
        val drawn = DesktopImages.draw(image, w, h)
        var quality = 90
        var bytes: ByteArray
        do {
            bytes = DesktopImages.jpeg(drawn, quality)
            quality -= 10
        } while (bytes.size > maxBytes && quality > 20)
        return bytes
    }

    actual fun squareJpeg(context: PlatformContext, uri: PlatformUri, size: Int): ByteArray? = runCatching {
        val original = readBytes(context, uri) ?: return null
        val upright = DesktopImages.decode(original) ?: return null
        val uw = upright.width
        val uh = upright.height
        val side = minOf(uw, uh)
        val surface = org.jetbrains.skia.Surface.makeRasterN32Premul(size, size)
        surface.canvas.drawImageRect(
            upright,
            org.jetbrains.skia.Rect.makeXYWH(((uw - side) / 2).toFloat(), ((uh - side) / 2).toFloat(), side.toFloat(), side.toFloat()),
            org.jetbrains.skia.Rect.makeWH(size.toFloat(), size.toFloat()),
            org.jetbrains.skia.SamplingMode.LINEAR, org.jetbrains.skia.Paint(), true
        )
        DesktopImages.jpeg(surface.makeImageSnapshot(), 90)
    }.getOrNull()

    actual fun encodePng(bitmap: PlatformBitmap): ByteArray = bitmap.pngBytes
    actual fun bitmapWidth(bitmap: PlatformBitmap): Int = bitmap.width
    actual fun bitmapHeight(bitmap: PlatformBitmap): Int = bitmap.height

    actual fun videoDimensions(context: PlatformContext, uri: PlatformUri): Pair<Int, Int> =
        DesktopFfmpeg.displaySize(pathOf(uri))

    actual fun videoUploadBody(context: PlatformContext, uri: PlatformUri, mimeType: String): RequestBody {
        val file = uri.file ?: error("Couldn't read the video")
        // (The real length: Bluesky's video service refuses an upload without one.)
        return streamRequestBody(mimeType, DesktopFileStreamSource(file, file.length()))
    }

    actual suspend fun scrubVideoForUpload(context: PlatformContext, uri: PlatformUri): PlatformUri = withContext(Dispatchers.IO) {
        runCatching {
            val source = uri.file ?: error("Not a file")
            // Stellar's own temporary files are cleaned where they are;
            // anything else is copied first (the original is never changed).
            val own = source.absolutePath.startsWith(DesktopPaths.cacheDir.absolutePath)
            val target = if (own) source else {
                val ext = source.extension.lowercase().takeIf { it == "mov" || it == "mp4" || it == "m4v" } ?: "mp4"
                File(DesktopPaths.cacheDir, "upload-clean-" + currentTimeMillis() + "." + ext).also { source.copyTo(it, overwrite = true) }
            }
            val ok = java.io.RandomAccessFile(target, "rw").use { raf ->
                com.mediaviewer.util.MetadataScrub.mp4(object : com.mediaviewer.util.MetadataScrub.Seekable {
                    override val size: Long = raf.length()
                    override fun read(at: Long, count: Int): ByteArray {
                        val out = ByteArray(count)
                        raf.seek(at)
                        raf.readFully(out)
                        return out
                    }
                    override fun write(at: Long, bytes: ByteArray) {
                        raf.seek(at)
                        raf.write(bytes)
                    }
                })
            }
            if (!ok) error("Not an MP4/MOV")
            DesktopUri.of(target)
        }.getOrDefault(uri)
    }

    actual suspend fun prepareVideoForUpload(context: PlatformContext, uri: PlatformUri): PlatformUri = withContext(Dispatchers.IO) {
        val path = pathOf(uri)
        val out = DesktopFfmpeg.prepareForUpload(path)
        if (out == path) uri else DesktopUri.of(File(out))
    }

    actual suspend fun stitchVideoThumbnail(context: PlatformContext, video: PlatformUri, thumbnail: PlatformUri): PlatformUri =
        withContext(Dispatchers.IO) { DesktopUri.of(File(DesktopFfmpeg.stitchThumbnail(pathOf(video), pathOf(thumbnail)))) }

    actual suspend fun videoFromGif(context: PlatformContext, gif: ByteArray): PlatformUri =
        withContext(Dispatchers.IO) { DesktopUri.of(File(DesktopFfmpeg.videoFromGif(gif))) }
}
