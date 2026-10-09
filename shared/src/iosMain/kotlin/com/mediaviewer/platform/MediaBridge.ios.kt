package com.mediaviewer.platform

import platform.Foundation.*
import platform.AVFoundation.*
import platform.CoreGraphics.*
import platform.UIKit.*
import com.mediaviewer.network.IosFileStreamSource
import com.mediaviewer.network.RequestBody
import com.mediaviewer.network.streamRequestBody
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents

@OptIn(ExperimentalForeignApi::class)
actual object MediaBridge {
    /** Local filesystem path of a file:// PlatformUri. */
    fun pathOf(uri: PlatformUri): String {
        val s = uri.toString()
        if (!s.startsWith("file:")) return s
        return NSURL.URLWithString(s)?.path ?: s.removePrefix("file://")
    }

    actual fun readBytes(context: PlatformContext, uri: PlatformUri): ByteArray? = readLocalFile(pathOf(uri))

    actual fun mimeTypeOf(context: PlatformContext, uri: PlatformUri): String? =
        when (pathOf(uri).substringAfterLast('.', "").lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "heic" -> "image/heic"
            "heif" -> "image/heif"
            "mp4", "m4v" -> "video/mp4"
            "mov" -> "video/quicktime"
            else -> null
        }

    private fun decode(bytes: ByteArray): UIImage? = UIImage.imageWithData(bytes.toNSData())

    /** Pixel size (points × scale). */
    private fun UIImage.pixelSize(): Pair<Int, Int> =
        size.useContents { (width * scale).toInt() to (height * scale).toInt() }

    actual fun imageSize(bytes: ByteArray): Pair<Int, Int> = decode(bytes)?.pixelSize() ?: (-1 to -1)

    /** Redraws [image] at [w]×[h] pixels (also applies its orientation). */
    private fun redraw(image: UIImage, w: Int, h: Int): UIImage {
        UIGraphicsBeginImageContextWithOptions(CGSizeMake(w.toDouble(), h.toDouble()), false, 1.0)
        image.drawInRect(CGRectMake(0.0, 0.0, w.toDouble(), h.toDouble()))
        val out = UIGraphicsGetImageFromCurrentImageContext()
        UIGraphicsEndImageContext()
        return out ?: image
    }

    private fun jpeg(image: UIImage, quality: Int): ByteArray =
        UIImageJPEGRepresentation(image, quality / 100.0)?.toByteArray() ?: error("Couldn't encode image")

    /** Whether [image] has see-through pixels to keep (it carries alpha). */
    private fun hasAlpha(image: UIImage): Boolean {
        val info = CGImageGetAlphaInfo(image.CGImage ?: return false)
        return info != CGImageAlphaInfo.kCGImageAlphaNone &&
            info != CGImageAlphaInfo.kCGImageAlphaNoneSkipFirst &&
            info != CGImageAlphaInfo.kCGImageAlphaNoneSkipLast
    }

    /** Like [redraw], but keeping transparency. */
    private fun redrawClear(image: UIImage, w: Int, h: Int): UIImage {
        UIGraphicsBeginImageContextWithOptions(CGSizeMake(w.toDouble(), h.toDouble()), false, 1.0)
        image.drawInRect(CGRectMake(0.0, 0.0, w.toDouble(), h.toDouble()))
        val out = UIGraphicsGetImageFromCurrentImageContext()
        UIGraphicsEndImageContext()
        return out ?: image
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
        // Otherwise redrawn upright (UIImage applies the orientation),
        // scaled to fit, and encoded under the limit: quality steps down,
        // then the size. A see-through picture stays a PNG.
        val image = decode(original) ?: error("Couldn't decode image")
        val transparent = !isJpeg && hasAlpha(image)
        val (pw, ph) = image.pixelSize()
        var scale = minOf(1f, maxDimension.toFloat() / maxOf(pw, ph).coerceAtLeast(1))
        while (true) {
            val w = (pw * scale).toInt().coerceAtLeast(1)
            val h = (ph * scale).toInt().coerceAtLeast(1)
            if (transparent) {
                val drawn = redrawClear(image, w, h)
                val png = UIImagePNGRepresentation(drawn)?.toByteArray() ?: error("Couldn't encode image")
                if (png.size <= maxBytes) return png to "image/png"
            } else {
                val drawn = redraw(image, w, h)
                for (q in intArrayOf(92, 85, 78, 70, 62)) {
                    val out = jpeg(drawn, q)
                    if (out.size <= maxBytes) return out to "image/jpeg"
                }
            }
            scale *= 0.8f
            if (maxOf(pw, ph) * scale < 320f) error("This picture can't be made small enough to upload")
        }
    }

    actual fun scaledJpeg(context: PlatformContext, uri: PlatformUri, maxW: Int, maxH: Int, maxBytes: Int): ByteArray {
        val original = readBytes(context, uri) ?: error("Couldn't read the picture")
        val image = decode(original) ?: error("Couldn't decode the picture")
        val (w0, h0) = image.pixelSize()
        val scale = minOf(1f, maxW.toFloat() / w0, maxH.toFloat() / h0)
        val w = (w0 * scale).toInt().coerceAtLeast(1)
        val h = (h0 * scale).toInt().coerceAtLeast(1)
        val drawn = redraw(image, w, h)
        var quality = 90
        var bytes: ByteArray
        do {
            bytes = jpeg(drawn, quality)
            quality -= 10
        } while (bytes.size > maxBytes && quality > 20)
        return bytes
    }

    actual fun squareJpeg(context: PlatformContext, uri: PlatformUri, size: Int): ByteArray? = runCatching {
        val image = decode(readBytes(context, uri) ?: return null) ?: return null
        val (w, h) = image.size.useContents { width to height }
        if (w <= 0.0 || h <= 0.0) return null
        val scale = maxOf(size / w, size / h)
        val dw = w * scale; val dh = h * scale
        UIGraphicsBeginImageContextWithOptions(CGSizeMake(size.toDouble(), size.toDouble()), true, 1.0)
        image.drawInRect(CGRectMake((size - dw) / 2.0, (size - dh) / 2.0, dw, dh))
        val out = UIGraphicsGetImageFromCurrentImageContext()
        UIGraphicsEndImageContext()
        out?.let { jpeg(it, 90) }
    }.getOrNull()

    actual fun encodePng(bitmap: PlatformBitmap): ByteArray = bitmap.pngBytes
    actual fun bitmapWidth(bitmap: PlatformBitmap): Int = bitmap.width
    actual fun bitmapHeight(bitmap: PlatformBitmap): Int = bitmap.height

    actual fun videoDimensions(context: PlatformContext, uri: PlatformUri): Pair<Int, Int> = runCatching {
        val asset = AVURLAsset.URLAssetWithURL(NSURL.fileURLWithPath(pathOf(uri)), null)
        val track = asset.tracksWithMediaType(AVMediaTypeVideo ?: "vide").firstOrNull() as? AVAssetTrack ?: return@runCatching 0 to 0
        val (w, h) = track.naturalSize.useContents { width.toInt() to height.toInt() }
        val rotated = track.preferredTransform.useContents { b != 0.0 || c != 0.0 }
        if (rotated) h to w else w to h
    }.getOrDefault(0 to 0)

    actual fun videoUploadBody(context: PlatformContext, uri: PlatformUri, mimeType: String): RequestBody {
        val path = pathOf(uri)
        // (The real length: Bluesky's video service refuses an upload
        // without one.)
        val size = runCatching {
            (NSFileManager.defaultManager.attributesOfItemAtPath(path, null)?.get(NSFileSize) as? NSNumber)?.longLongValue
        }.getOrNull() ?: -1L
        return streamRequestBody(mimeType, IosFileStreamSource(path, size))
    }

    actual suspend fun scrubVideoForUpload(context: PlatformContext, uri: PlatformUri): PlatformUri =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            runCatching {
                val path = pathOf(uri)
                // Stellar's own temporary files are cleaned where they are;
                // anything else is copied first (the original is never changed).
                val own = path.startsWith(IosPaths.cacheDir())
                val target = if (own) path else {
                    val ext = path.substringAfterLast('.', "mp4").lowercase().takeIf { it == "mov" || it == "mp4" || it == "m4v" } ?: "mp4"
                    val copy = IosPaths.cacheDir() + "/upload-clean-" + currentTimeMillis() + "." + ext
                    deleteLocalFile(copy)
                    if (!NSFileManager.defaultManager.copyItemAtPath(path, toPath = copy, error = null)) error("Couldn't copy the video")
                    copy
                }
                if (!IosSeekableFile.scrub(target)) error("Not an MP4/MOV")
                IosUri("file://$target")
            }.getOrDefault(uri)
        }

    actual suspend fun prepareVideoForUpload(context: PlatformContext, uri: PlatformUri): PlatformUri {
        val path = pathOf(uri)
        val out = IosVideoCompressor.prepare(path)
        return if (out == path) uri else IosUri("file://$out")
    }

    actual suspend fun stitchVideoThumbnail(context: PlatformContext, video: PlatformUri, thumbnail: PlatformUri): PlatformUri =
        IosUri("file://" + IosVideoStitcher.stitch(pathOf(video), pathOf(thumbnail)))
}
