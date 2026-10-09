// check:jvm
package com.mediaviewer.platform

import com.mediaviewer.network.AndroidUriStreamSource
import com.mediaviewer.network.RequestBody
import com.mediaviewer.network.streamRequestBody

actual object MediaBridge {
    actual fun readBytes(context: PlatformContext, uri: PlatformUri): ByteArray? =
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }

    actual fun mimeTypeOf(context: PlatformContext, uri: PlatformUri): String? =
        context.contentResolver.getType(uri)
            // Files Stellar keeps itself (drafts, notes): file:// has no
            // provider to ask, so go by the extension.
            ?: uri.lastPathSegment?.substringAfterLast('.', "")?.lowercase()?.takeIf { it.isNotEmpty() }
                ?.let { android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }

    actual fun imageSize(bytes: ByteArray): Pair<Int, Int> {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        return bounds.outWidth to bounds.outHeight
    }

    actual fun prepareImageForUpload(context: PlatformContext, uri: PlatformUri, maxBytes: Int, maxDimension: Int): Pair<ByteArray, String> {
        val resolver = context.contentResolver
        val original = resolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Couldn't read image")
        val declaredType = resolver.getType(uri) ?: mimeTypeOf(context, uri)
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(original, 0, original.size, bounds)
        val isJpeg = com.mediaviewer.util.JpegMeta.isJpeg(original)
        val orientation = com.mediaviewer.util.JpegMeta.orientation(original)
        val fits = bounds.outWidth in 1..maxDimension && bounds.outHeight in 1..maxDimension

        // Fast path: within the limits, upright and a format Bluesky takes
        // as-is — the original picture, untouched. (A JPEG only loses its
        // EXIF: camera details and, often, where the photo was taken.)
        if (fits && orientation == 1) {
            if (isJpeg) {
                val clean = com.mediaviewer.util.JpegMeta.stripMetadata(original)
                if (clean != null && clean.size <= maxBytes) return clean to "image/jpeg"
            } else if (declaredType == "image/png" || declaredType == "image/webp" || declaredType == "image/gif") {
                // (Their text/EXIF/XMP/comment metadata comes out too.)
                val clean = com.mediaviewer.util.MetadataScrub.image(original)
                if (clean.size <= maxBytes) return clean to declaredType
            }
        }

        // Otherwise: decoded no bigger than needed (a 50 MP photo decoded in
        // full needs ~200 MB and could run the app out of memory), turned
        // upright, scaled to fit and JPEG-encoded under the size limit —
        // stepping the quality down, then the size, until it fits.
        val w0 = bounds.outWidth.coerceAtLeast(1)
        val h0 = bounds.outHeight.coerceAtLeast(1)
        var sample = 1
        while (maxOf(w0, h0) / (sample * 2) >= maxDimension) sample *= 2
        val opts = android.graphics.BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
        }
        // (HEIC/HEIF and other non-JPEGs go through ImageDecoder, which
        // also turns them upright; JPEG orientation is applied below.)
        var bitmap: android.graphics.Bitmap = (if (!isJpeg && android.os.Build.VERSION.SDK_INT >= 28) runCatching {
            android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(java.nio.ByteBuffer.wrap(original))) { decoder, info, _ ->
                decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                if (sample > 1) decoder.setTargetSize((info.size.width / sample).coerceAtLeast(1), (info.size.height / sample).coerceAtLeast(1))
            }
        }.getOrNull() else null)
            ?: android.graphics.BitmapFactory.decodeByteArray(original, 0, original.size, opts)
            ?: error("Couldn't decode image")
        val matrix = android.graphics.Matrix()
        when (orientation) {
            2 -> matrix.setScale(-1f, 1f)
            3 -> matrix.setRotate(180f)
            4 -> matrix.setScale(1f, -1f)
            5 -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
            6 -> matrix.setRotate(90f)
            7 -> { matrix.setRotate(-90f); matrix.postScale(-1f, 1f) }
            8 -> matrix.setRotate(-90f)
        }
        var scale = minOf(1f, maxDimension.toFloat() / maxOf(bitmap.width, bitmap.height))
        fun render(scale: Float): android.graphics.Bitmap {
            val m = android.graphics.Matrix(matrix).apply { postScale(scale, scale) }
            if (m.isIdentity) return bitmap
            return android.graphics.Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
        }
        var shaped = render(scale)
        // A see-through picture stays a PNG (JPEG has no transparency —
        // it would come out black on Bluesky): only its size steps down.
        val transparent = bitmap.hasAlpha() && !isJpeg
        fun encode(b: android.graphics.Bitmap, q: Int): ByteArray = java.io.ByteArrayOutputStream().also {
            b.compress(if (transparent) android.graphics.Bitmap.CompressFormat.PNG else android.graphics.Bitmap.CompressFormat.JPEG, q, it)
        }.toByteArray()
        val qualities = if (transparent) intArrayOf(100) else intArrayOf(92, 85, 78, 70, 62)
        while (true) {
            for (q in qualities) {
                val out = encode(shaped, q)
                if (out.size <= maxBytes) {
                    if (shaped !== bitmap) shaped.recycle()
                    bitmap.recycle()
                    return out to (if (transparent) "image/png" else "image/jpeg")
                }
            }
            // Still too big: 80% the size, and again.
            scale *= 0.8f
            if (shaped !== bitmap) shaped.recycle()
            if (maxOf(bitmap.width, bitmap.height) * scale < 320f) error("This picture can't be made small enough to upload")
            shaped = render(scale)
        }
    }

    actual fun scaledJpeg(context: PlatformContext, uri: PlatformUri, maxW: Int, maxH: Int, maxBytes: Int): ByteArray {
        val original = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Couldn't read the picture")
        var bitmap = android.graphics.BitmapFactory.decodeByteArray(original, 0, original.size) ?: error("Couldn't decode the picture")
        val scale = minOf(1f, maxW.toFloat() / bitmap.width, maxH.toFloat() / bitmap.height)
        if (scale < 1f) {
            bitmap = android.graphics.Bitmap.createScaledBitmap(
                bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true
            )
        }
        var quality = 90
        var bytes: ByteArray
        do {
            bytes = java.io.ByteArrayOutputStream().also { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
            quality -= 10
        } while (bytes.size > maxBytes && quality > 20)
        return bytes
    }

    actual fun squareJpeg(context: PlatformContext, uri: PlatformUri, size: Int): ByteArray? = runCatching {
        val decoded: android.graphics.Bitmap = if (android.os.Build.VERSION.SDK_INT >= 28) {
            // (ImageDecoder turns the picture upright by itself.)
            android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(context.contentResolver, uri)) { d, info, _ ->
                d.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                val s = maxOf(1, minOf(info.size.width, info.size.height) / (size * 2))
                if (s > 1) d.setTargetSize(info.size.width / s, info.size.height / s)
            }
        } else {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
            val raw = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            val o = com.mediaviewer.util.JpegMeta.orientation(bytes)
            val m = android.graphics.Matrix().apply { when (o) { 3 -> setRotate(180f); 6 -> setRotate(90f); 8 -> setRotate(-90f) } }
            if (m.isIdentity) raw else android.graphics.Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
        }
        val side = minOf(decoded.width, decoded.height)
        val out = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(out).apply {
            drawColor(android.graphics.Color.BLACK)
            drawBitmap(
                decoded,
                android.graphics.Rect((decoded.width - side) / 2, (decoded.height - side) / 2, (decoded.width + side) / 2, (decoded.height + side) / 2),
                android.graphics.Rect(0, 0, size, size),
                android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
            )
        }
        decoded.recycle()
        java.io.ByteArrayOutputStream().also { out.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it); out.recycle() }.toByteArray()
    }.getOrNull()

    actual fun encodePng(bitmap: PlatformBitmap): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }
    actual fun bitmapWidth(bitmap: PlatformBitmap): Int = bitmap.width
    actual fun bitmapHeight(bitmap: PlatformBitmap): Int = bitmap.height

    /** Returns the (width, height) of the video at [uri], swapping the axes
     *  when rotation metadata says the frame is displayed turned 90/270° so
     *  the ratio describes the displayed frame. Returns (0, 0) when probing
     *  fails — callers treat that as "unknown" and omit aspectRatio. */
    actual fun videoDimensions(context: PlatformContext, uri: PlatformUri): Pair<Int, Int> {
        val retriever = android.media.MediaMetadataRetriever()
        return try {
            runCatching {
                retriever.setDataSource(context, uri)
                val w = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                val h = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                val rot = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                if (rot == 90 || rot == 270) h to w else w to h
            }.getOrDefault(0 to 0)
        } finally {
            runCatching { retriever.release() }
        }
    }

    actual fun videoUploadBody(context: PlatformContext, uri: PlatformUri, mimeType: String): RequestBody {
        // Streamed from disk instead of read into one byte array — a long
        // video no longer has to fit in memory at once.
        var length = runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
        }.getOrNull()?.takeIf { it > 0 } ?: -1L
        if (length <= 0L) length = runCatching {
            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L
            }
        }.getOrNull()?.takeIf { it > 0 } ?: -1L
        if (length > 0L) return streamRequestBody(mimeType, AndroidUriStreamSource(context, uri, length))
        // The picker couldn't say how big the file is. Bluesky's video
        // service refuses an upload without a length, so the video is
        // copied into the app's cache first and sent from there.
        val copy = java.io.File(context.cacheDir, "upload-video.tmp")
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { input -> copy.outputStream().use { input.copyTo(it) } }
        }
        return if (copy.length() > 0L) streamRequestBody(mimeType, AndroidUriStreamSource(context, android.net.Uri.fromFile(copy), copy.length()))
        else streamRequestBody(mimeType, AndroidUriStreamSource(context, uri, -1L))
    }

    actual suspend fun scrubVideoForUpload(context: PlatformContext, uri: PlatformUri): PlatformUri =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                // Stellar's own temporary files are cleaned where they are;
                // anything else is copied first (the original is never changed).
                val own = uri.scheme == "file" && uri.path?.startsWith(context.cacheDir.path) == true
                val file = if (own) java.io.File(uri.path!!) else {
                    val ext = if ((mimeTypeOf(context, uri) ?: "").contains("quicktime")) ".mov" else ".mp4"
                    val copy = java.io.File.createTempFile("upload-clean-", ext, context.cacheDir)
                    context.contentResolver.openInputStream(uri)?.use { input -> copy.outputStream().use { input.copyTo(it) } }
                        ?: error("Couldn't read the video")
                    copy
                }
                java.io.RandomAccessFile(file, "rw").use { raf ->
                    com.mediaviewer.util.MetadataScrub.mp4(object : com.mediaviewer.util.MetadataScrub.Seekable {
                        override val size = raf.length()
                        override fun read(at: Long, count: Int): ByteArray { raf.seek(at); return ByteArray(count).also { raf.readFully(it) } }
                        override fun write(at: Long, bytes: ByteArray) { raf.seek(at); raf.write(bytes) }
                    })
                }
                android.net.Uri.fromFile(file)
            }.getOrDefault(uri)
        }

    actual suspend fun prepareVideoForUpload(context: PlatformContext, uri: PlatformUri): PlatformUri =
        com.mediaviewer.util.VideoCompressor.prepare(context, uri)

    actual suspend fun stitchVideoThumbnail(context: PlatformContext, video: PlatformUri, thumbnail: PlatformUri): PlatformUri =
        com.mediaviewer.util.VideoThumbnailStitcher.stitch(context, video, thumbnail)
}
