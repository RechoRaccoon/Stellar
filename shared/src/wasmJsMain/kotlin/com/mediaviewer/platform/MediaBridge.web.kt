package com.mediaviewer.platform

import com.mediaviewer.network.RequestBody
import com.mediaviewer.network.WebBlobStreamSource
import com.mediaviewer.network.streamRequestBody
import com.mediaviewer.network.toRequestBody
import kotlinx.coroutines.await

actual object MediaBridge {
    actual fun readBytes(context: PlatformContext, uri: PlatformUri): ByteArray? = uri.webPath?.let { WebFiles.read(it) }

    actual fun mimeTypeOf(context: PlatformContext, uri: PlatformUri): String? {
        val path = uri.webPath
        path?.let { WebBlobs.typeOf(it) }?.let { return it }
        val name = path?.let { WebBlobs.nameOf(it) } ?: uri.toString().substringBefore('?')
        return mimeForName(name).takeIf { it != "application/octet-stream" }
    }

    actual fun imageSize(bytes: ByteArray): Pair<Int, Int> {
        val image = WebImages.decode(bytes) ?: return -1 to -1
        return image.width to image.height
    }

    actual fun prepareImageForUpload(context: PlatformContext, uri: PlatformUri, maxBytes: Int, maxDimension: Int): Pair<ByteArray, String> {
        val original = readBytes(context, uri) ?: error("Couldn't read image")
        val declaredType = mimeTypeOf(context, uri)
        val isJpeg = com.mediaviewer.util.JpegMeta.isJpeg(original)
        val orientation = com.mediaviewer.util.JpegMeta.orientation(original)
        val (w0, h0) = imageSize(original)
        // Fast path: within the limits and upright — the original picture,
        // minus its metadata (camera details, often where it was taken).
        if (w0 in 1..maxDimension && h0 in 1..maxDimension && orientation == 1) {
            if (isJpeg) {
                val clean = com.mediaviewer.util.JpegMeta.stripMetadata(original)
                if (clean != null && clean.size <= maxBytes) return clean to "image/jpeg"
            } else if (declaredType == "image/png" || declaredType == "image/webp" || declaredType == "image/gif") {
                val clean = com.mediaviewer.util.MetadataScrub.image(original)
                if (clean.size <= maxBytes) return clean to declaredType
            }
        }
        // Otherwise redrawn upright, scaled to fit, and encoded under the
        // limit: quality steps down, then the size. A see-through picture
        // stays a PNG.
        val image = WebImages.decode(original) ?: error("Couldn't decode image")
        val transparent = !isJpeg && WebImages.hasAlpha(image)
        val pw = image.width
        val ph = image.height
        var scale = minOf(1f, maxDimension.toFloat() / maxOf(pw, ph).coerceAtLeast(1))
        while (true) {
            val w = (pw * scale).toInt().coerceAtLeast(1)
            val h = (ph * scale).toInt().coerceAtLeast(1)
            val drawn = WebImages.draw(image, w, h)
            if (transparent) {
                val png = WebImages.png(drawn)
                if (png.size <= maxBytes) return png to "image/png"
            } else {
                for (q in intArrayOf(92, 85, 78, 70, 62)) {
                    val out = WebImages.jpeg(drawn, q)
                    if (out.size <= maxBytes) return out to "image/jpeg"
                }
            }
            scale *= 0.8f
            if (maxOf(pw, ph) * scale < 320f) error("This picture can't be made small enough to upload")
        }
    }

    actual fun scaledJpeg(context: PlatformContext, uri: PlatformUri, maxW: Int, maxH: Int, maxBytes: Int): ByteArray {
        val original = readBytes(context, uri) ?: error("Couldn't read the picture")
        val image = WebImages.decode(original) ?: error("Couldn't decode the picture")
        val scale = minOf(1f, maxW.toFloat() / image.width, maxH.toFloat() / image.height)
        val w = (image.width * scale).toInt().coerceAtLeast(1)
        val h = (image.height * scale).toInt().coerceAtLeast(1)
        val drawn = WebImages.draw(image, w, h)
        var quality = 90
        var bytes: ByteArray
        do {
            bytes = WebImages.jpeg(drawn, quality)
            quality -= 10
        } while (bytes.size > maxBytes && quality > 20)
        return bytes
    }

    actual fun squareJpeg(context: PlatformContext, uri: PlatformUri, size: Int): ByteArray? = runCatching {
        val original = readBytes(context, uri) ?: return null
        val upright = WebImages.decode(original) ?: return null
        val side = minOf(upright.width, upright.height)
        val surface = org.jetbrains.skia.Surface.makeRasterN32Premul(size, size)
        surface.canvas.drawImageRect(
            upright,
            org.jetbrains.skia.Rect.makeXYWH(((upright.width - side) / 2).toFloat(), ((upright.height - side) / 2).toFloat(), side.toFloat(), side.toFloat()),
            org.jetbrains.skia.Rect.makeWH(size.toFloat(), size.toFloat()),
            org.jetbrains.skia.SamplingMode.LINEAR, org.jetbrains.skia.Paint(), true
        )
        WebImages.jpeg(surface.makeImageSnapshot(), 90)
    }.getOrNull()

    actual fun encodePng(bitmap: PlatformBitmap): ByteArray = bitmap.pngBytes
    actual fun bitmapWidth(bitmap: PlatformBitmap): Int = bitmap.width
    actual fun bitmapHeight(bitmap: PlatformBitmap): Int = bitmap.height

    /** (Read by the browser when the video was picked.) */
    actual fun videoDimensions(context: PlatformContext, uri: PlatformUri): Pair<Int, Int> =
        uri.webPath?.let { WebBlobs.videoSizeOf(it) } ?: (0 to 0)

    actual fun videoUploadBody(context: PlatformContext, uri: PlatformUri, mimeType: String): RequestBody {
        val path = uri.webPath ?: error("Couldn't read the video")
        // A picked file is sent straight from the browser; one Stellar made
        // (a cleaned copy) from memory.
        if (WebBlobs.blobOf(path) != null && !WebFiles.isInMemory(path)) {
            return streamRequestBody(mimeType, WebBlobStreamSource(path, WebFiles.size(path)))
        }
        val bytes = WebFiles.read(path) ?: error("Couldn't read the video")
        return bytes.toRequestBody(mimeType)
    }

    /** The video's personal metadata (where and when it was filmed) taken
     *  out, on a copy — for files small enough to hold in memory. */
    actual suspend fun scrubVideoForUpload(context: PlatformContext, uri: PlatformUri): PlatformUri = runCatching {
        val path = uri.webPath ?: error("Not a file")
        if (WebFiles.size(path) > MAX_SCRUB_BYTES) error("Too large to clean in the browser")
        val data = (WebBlobs.readAll(path) ?: WebFiles.read(path))?.copyOf() ?: error("Couldn't read the video")
        val ok = com.mediaviewer.util.MetadataScrub.mp4(object : com.mediaviewer.util.MetadataScrub.Seekable {
            override val size: Long = data.size.toLong()
            override fun read(at: Long, count: Int): ByteArray = data.copyOfRange(at.toInt(), at.toInt() + count)
            override fun write(at: Long, bytes: ByteArray) { bytes.copyInto(data, at.toInt()) }
        })
        if (!ok) error("Not an MP4/MOV")
        val out = WebPaths.TEMP + "/upload-clean-" + currentTimeMillis() + ".mp4"
        WebFiles.write(out, data)
        WebUri(WebFiles.uriOf(out))
    }.getOrDefault(uri)

    private const val MAX_SCRUB_BYTES = 200L * 1024 * 1024

    /** No re-encoding in a browser: the video is uploaded as it is (Bluesky
     *  converts it). */
    actual suspend fun prepareVideoForUpload(context: PlatformContext, uri: PlatformUri): PlatformUri = uri

    /** (No FFmpeg in a browser: the picked video is played, the chosen part
     *  drawn onto a canvas each frame, and that recorded — so it takes as
     *  long as the video does.) */
    actual suspend fun cropVideo(
        context: PlatformContext, uri: PlatformUri,
        left: Float, top: Float, width: Float, height: Float, maxWidth: Int, maxHeight: Int
    ): PlatformUri {
        val path = uri.webPath ?: error("Couldn't read the video")
        val url = WebBlobs.objectUrl(path) ?: error("Couldn't read the video")
        val blob = cropVideoInBrowser(url, left.toDouble(), top.toDouble(), width.toDouble(), height.toDouble(), maxWidth, maxHeight)
            .await<JsAny>()
        val type = blobType(blob).ifBlank { "video/webm" }
        val ext = if (type.contains("mp4")) "mp4" else "webm"
        val out = WebPaths.TEMP + "/profile-video-" + currentTimeMillis() + "." + ext
        WebBlobs.put(out, blob, "profile-video.$ext", type, blobSize(blob).toLong())
        return WebUri(WebFiles.uriOf(out))
    }

    actual suspend fun stitchVideoThumbnail(context: PlatformContext, video: PlatformUri, thumbnail: PlatformUri): PlatformUri =
        throw UnsupportedOperationException("Custom video thumbnails aren't available on the web")

    /** (Without FFmpeg, a GIF is sent to Bluesky's video service as is; it converts GIFs itself.) */
    actual suspend fun videoFromGif(context: PlatformContext, gif: ByteArray): PlatformUri =
        throw UnsupportedOperationException("Not available on the web")
}

private fun blobType(blob: JsAny): String = js("blob.type || ''")
private fun blobSize(blob: JsAny): Double = js("blob.size")

private fun cropVideoInBrowser(url: String, l: Double, t: Double, w: Double, h: Double, maxW: Int, maxH: Int): kotlin.js.Promise<JsAny> = js("""new Promise((resolve, reject) => {
    const v = document.createElement('video');
    v.muted = true; v.playsInline = true; v.preload = 'auto'; v.src = url;
    v.onerror = () => reject(new Error("Couldn't read the video"));
    v.onloadedmetadata = () => {
        const vw = v.videoWidth, vh = v.videoHeight;
        if (!vw || !vh) { reject(new Error("Couldn't read the video")); return; }
        const cx = vw * l, cy = vh * t, cw = vw * w, ch = vh * h;
        const s = Math.min(1, maxW / cw, maxH / ch);
        const ow = Math.max(2, Math.floor(cw * s / 2) * 2), oh = Math.max(2, Math.floor(ch * s / 2) * 2);
        const c = document.createElement('canvas'); c.width = ow; c.height = oh;
        const g = c.getContext('2d');
        const types = ['video/mp4;codecs=avc1.42E01E', 'video/mp4;codecs=avc1', 'video/mp4', 'video/webm;codecs=vp9', 'video/webm;codecs=vp8', 'video/webm'];
        const type = window.MediaRecorder ? types.find(x => MediaRecorder.isTypeSupported(x)) : null;
        if (!type) { reject(new Error("This browser can't make videos")); return; }
        const rec = new MediaRecorder(c.captureStream(30), { mimeType: type, videoBitsPerSecond: 4000000 });
        const chunks = [];
        rec.ondataavailable = (e) => { if (e.data && e.data.size) chunks.push(e.data); };
        rec.onstop = () => resolve(new Blob(chunks, { type: type.split(';')[0] }));
        let done = false;
        const draw = () => {
            if (done) return;
            g.drawImage(v, cx, cy, cw, ch, 0, 0, ow, oh);
            if (v.requestVideoFrameCallback) v.requestVideoFrameCallback(draw); else requestAnimationFrame(draw);
        };
        v.onended = () => { done = true; setTimeout(() => { try { rec.stop(); } catch (e) { reject(e); } }, 120); };
        v.play().then(() => { g.drawImage(v, cx, cy, cw, ch, 0, 0, ow, oh); rec.start(250); draw(); }).catch(reject);
    };
})""")
