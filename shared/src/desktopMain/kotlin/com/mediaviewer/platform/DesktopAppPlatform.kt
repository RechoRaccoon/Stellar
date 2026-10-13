package com.mediaviewer.platform

import com.mediaviewer.model.LiveNowPlatform
import com.mediaviewer.network.DesktopHttpClients
import com.mediaviewer.tagging.TaggingService
import com.mediaviewer.tagging.UnavailableTaggingService
import com.mediaviewer.util.GifEncoder
import com.mediaviewer.util.PostDownloads
import com.mediaviewer.worker.BlueskyBlobResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.Request
import java.io.File

/** The desktop side of [AppPlatform]. Features a computer can't do report
 *  themselves unavailable (Android keeps all of them). */
class DesktopAppPlatform(
    @Suppress("UNUSED_PARAMETER") bskyRepo: com.mediaviewer.repository.BlueskyRepository,
    @Suppress("UNUSED_PARAMETER") e621Repo: com.mediaviewer.repository.E621Repository
) : AppPlatform {
    override val context: PlatformContext get() = DesktopContext
    override val tagging: TaggingService = UnavailableTaggingService

    override fun haptic() {}

    override fun toast(message: String) = AppEvents.postMessage(message)

    override fun restartApp() = AppEvents.requestRestart()

    override fun preloadImages(urls: List<String>) {
        DesktopImagePreloader.preload(urls)
    }

    override fun enqueueDownload(url: String, filename: String, mimeType: String, postId: String) =
        DesktopDownloads.save(url, filename, mimeType, postId)

    override fun enqueueVideoBlobDownload(did: String, cid: String, postId: String) =
        DesktopDownloads.saveVideoBlob(did, cid, postId)

    override fun enqueueGifDownload(url: String, isVideo: Boolean, postId: String, blobDid: String?, blobCid: String?) =
        DesktopDownloads.saveAsGif(url, isVideo, postId, blobDid, blobCid)

    // (Fonts are imported through FontStore — Settings → App Font.)
    override fun importCustomFont(uri: PlatformUri): FontImport =
        FontImport.Error("Import fonts from Settings › App Font")

    override fun deleteFile(path: String) { runCatching { File(path).delete() } }

    override fun writeTextToUri(uri: PlatformUri, text: String) {
        val file = uri.file ?: throw IOException("Couldn't write the file")
        try { file.writeText(text) } catch (e: Exception) { throw IOException("Couldn't write the file", e) }
    }

    override fun readTextFromUri(uri: PlatformUri): String? = runCatching { uri.file?.readText() }.getOrNull()

    override suspend fun renderTextshot(text: String): TextshotImage {
        val emoji = com.mediaviewer.util.EmojiStore.get(DesktopContext)
        emoji.load()
        val side = 1080
        val picture = com.mediaviewer.util.SkiaTextshotRenderer.render(text, side, emoji = emoji::imageForChar)
        val png = picture.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)?.bytes
            ?: throw IOException("Couldn't make the Textshot picture")
        // (Alt text never carries a custom emoji's name.)
        return TextshotImage(PlatformBitmap(side, side, png), emoji.stripEmoji(text), emoji.containsEmoji(text))
    }

    override suspend fun goLive(platform: LiveNowPlatform, channelUrl: String): Result<Unit> =
        Result.failure(UnsupportedOperationException("Live Link isn't available on desktop"))

    override suspend fun endLive(): Result<Unit> =
        Result.failure(UnsupportedOperationException("Live Link isn't available on desktop"))

    override fun requestPinLiveLinkWidget(): Boolean = false
}

/** Warms Coil's cache for upcoming pictures (set up by the app shell). */
object DesktopImagePreloader {
    @Volatile var preloader: ((List<String>) -> Unit)? = null
    fun preload(urls: List<String>) { preloader?.invoke(urls) }
}

/**
 * Saving media on a computer (the desktop counterpart of Android's
 * DownloadWorker): files go to the Pictures folder, in "Stellar".
 */
object DesktopDownloads {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Where saved files go, for the messages. */
    private val whereLabel: String
        get() = if (DesktopOs.isMac || DesktopOs.isWindows) "Pictures › Stellar" else DesktopPaths.savedMediaDir.absolutePath

    /** Streams [url] into [out]; throws on failure. */
    private fun download(url: String, out: File) {
        DesktopHttpClients.downloadClient.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            val body = resp.body ?: error("Empty file")
            body.byteStream().use { input -> out.outputStream().use { input.copyTo(it) } }
        }
        if (out.length() <= 0L) { out.delete(); error("Empty file") }
    }

    fun save(url: String, filename: String, mimeType: String, postId: String = "") {
        scope.launch {
            val result = runCatching {
                val temp = File(DesktopPaths.cacheDir, "download-${currentTimeMillis()}")
                try {
                    download(url, temp)
                    // What the file really is decides its name: a server's
                    // "image/jpeg" is sometimes a PNG or WebP.
                    val head = temp.inputStream().use { it.readNBytes(16) }
                    val actual = sniffType(head) ?: mimeType
                    val dest = uniqueFile(safeName(filename, actual))
                    temp.copyTo(dest)
                } finally {
                    temp.delete()
                }
            }
            PostDownloads.jobDone(postId, result.isSuccess)
            AppEvents.postMessage(
                if (result.isSuccess) "Saved to $whereLabel"
                else "Couldn't save: ${result.exceptionOrNull()?.message ?: "unknown error"}"
            )
        }
    }

    fun saveVideoBlob(did: String, cid: String, postId: String = "") {
        scope.launch {
            val url = runCatching { BlueskyBlobResolver.resolveBlobUrl(did, cid) }.getOrNull()
            if (url == null) {
                AppEvents.postMessage("Couldn't find that video")
                PostDownloads.jobDone(postId, false)
                return@launch
            }
            save(url, "stellar_${cid.takeLast(12)}.mp4", "video/mp4", postId)
        }
    }

    /**
     * "Save as GIF" (like Android's GifDownloadWorker): a picture becomes a
     * single-frame GIF; a video is fetched as its original file and every
     * frame (up to 50 a second, the first 30 seconds) is written into an
     * animated GIF with Android's encoder. A GIF already is saved as is.
     */
    fun saveAsGif(url: String, isVideo: Boolean, postId: String, blobDid: String?, blobCid: String?) {
        scope.launch {
            val stamp = currentTimeMillis()
            val source = File(DesktopPaths.cacheDir, "gif_source_$stamp." + (if (isVideo) "mp4" else "img"))
            val result = runCatching {
                val sourceUrl = if (isVideo && !blobDid.isNullOrBlank() && !blobCid.isNullOrBlank()) {
                    BlueskyBlobResolver.resolveBlobUrl(blobDid, blobCid)
                } else url
                if (isVideo) AppEvents.postMessage("Making the GIF…")
                download(sourceUrl, source)
                val dest = uniqueFile(safeName(postId.ifBlank { "stellar_$stamp" }, "image/gif"))
                val head = source.inputStream().use { it.readNBytes(16) }
                if (sniffType(head) == "image/gif") {
                    source.copyTo(dest)
                } else {
                    try {
                        dest.outputStream().buffered().use { out ->
                            val encoder = GifEncoder(out)
                            encoder.start()
                            if (isVideo) {
                                var last = -1L
                                var pending: Triple<IntArray, Int, Int>? = null
                                DesktopFfmpeg.frames(source.absolutePath, maxFps = 50.0, maxSeconds = 30.0, maxSide = 1080) { px, w, h, ms ->
                                    // Each frame waits for the next one's time to know its length.
                                    pending?.let { (p, pw, ph) ->
                                        encoder.addFrame(p, pw, ph, delayCs = ((ms - last) / 10).toInt().coerceAtLeast(2), ditherStrength = 0.75f)
                                    }
                                    pending = Triple(px.copyOf(), w, h)
                                    last = ms
                                }
                                pending?.let { (p, pw, ph) -> encoder.addFrame(p, pw, ph, delayCs = 4, ditherStrength = 0.75f) }
                            } else {
                                val image = DesktopImages.decode(source.readBytes()) ?: error("Couldn't read the picture")
                                encoder.addFrame(DesktopImages.argb(image), image.width, image.height, delayCs = 10, ditherStrength = 1f)
                            }
                            encoder.finish()
                        }
                        if (dest.length() <= 0L) error("Couldn't make the GIF")
                    } catch (t: Throwable) {
                        dest.delete()
                        throw t
                    }
                }
            }
            source.delete()
            PostDownloads.jobDone(postId, result.isSuccess)
            AppEvents.postMessage(
                if (result.isSuccess) "GIF saved to $whereLabel"
                else "Couldn't save the GIF: ${result.exceptionOrNull()?.message ?: "unknown error"}"
            )
        }
    }

    /** A file in the save folder that doesn't exist yet ("name (2).jpg", …). */
    private fun uniqueFile(name: String): File {
        val dir = DesktopPaths.savedMediaDir
        var f = File(dir, name)
        var n = 2
        while (f.exists()) {
            f = File(dir, name.substringBeforeLast('.') + " ($n)." + name.substringAfterLast('.'))
            n++
        }
        return f
    }

    /** The file's real type, from its first bytes (null = not one of these). */
    private fun sniffType(b: ByteArray): String? {
        fun at(i: Int) = if (i < b.size) b[i].toInt() and 0xFF else -1
        fun text(from: Int, s: String) = s.indices.all { at(from + it) == s[it].code }
        return when {
            at(0) == 0xFF && at(1) == 0xD8 -> "image/jpeg"
            at(0) == 0x89 && text(1, "PNG") -> "image/png"
            text(0, "GIF8") -> "image/gif"
            text(0, "RIFF") && text(8, "WEBP") -> "image/webp"
            text(4, "ftyp") && (text(8, "heic") || text(8, "heix") || text(8, "mif1")) -> "image/heic"
            text(4, "ftyp") && text(8, "qt") -> "video/quicktime"
            text(4, "ftyp") -> "video/mp4"
            at(0) == 0x1A && at(1) == 0x45 && at(2) == 0xDF && at(3) == 0xA3 -> "video/webm"
            else -> null
        }
    }

    /** A plain file name (no slashes or URL junk) with the right extension. */
    private fun safeName(filename: String, mimeType: String): String {
        val ext = when (mimeType) {
            "video/mp4" -> "mp4"
            "video/quicktime" -> "mov"
            "video/webm" -> "webm"
            "image/png" -> "png"
            "image/gif" -> "gif"
            "image/webp" -> "webp"
            "image/heic" -> "heic"
            else -> "jpg"
        }
        val base = filename.substringAfterLast('/').substringBeforeLast('.')
            .filter { it.isLetterOrDigit() || it == '_' || it == '-' }
            .take(80)
            .ifBlank { "stellar_" + currentTimeMillis() }
        return "$base.$ext"
    }
}
