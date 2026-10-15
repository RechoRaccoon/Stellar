package com.mediaviewer.platform

import com.mediaviewer.model.LiveNowPlatform
import com.mediaviewer.tagging.TaggingService

/** The web side of [AppPlatform]. Features a browser can't do report
 *  themselves unavailable (Android keeps all of them). */
class WebAppPlatform(
    bskyRepo: com.mediaviewer.repository.BlueskyRepository,
    e621Repo: com.mediaviewer.repository.E621Repository
) : AppPlatform {
    override val context: PlatformContext get() = WebContext
    // AI Tagging: the same model as Android, through onnxruntime-web.
    override val tagging: TaggingService =
        com.mediaviewer.tagging.EngineTaggingRepository(com.mediaviewer.tagging.WebTaggerEngine(), bskyRepo, e621Repo)

    override fun haptic() {}

    override fun toast(message: String) = AppEvents.postMessage(message)

    override fun restartApp() = AppEvents.requestRestart()

    override fun preloadImages(urls: List<String>) = WebImagePreloader.preload(urls)

    override fun enqueueDownload(url: String, filename: String, mimeType: String, postId: String) =
        WebDownloads.save(url, filename, mimeType, postId)

    override fun enqueueVideoBlobDownload(did: String, cid: String, postId: String) =
        WebDownloads.saveVideoBlob(did, cid, postId)

    /** (Grayed out on the web: making a GIF needs FFmpeg.) */
    override fun enqueueGifDownload(url: String, isVideo: Boolean, postId: String, blobDid: String?, blobCid: String?) {
        com.mediaviewer.util.PostDownloads.jobDone(postId, false)
        AppEvents.postMessage("Saving as a GIF isn't available on the web")
    }

    // (Fonts are imported through FontStore — Settings → App Font.)
    override fun importCustomFont(uri: PlatformUri): FontImport =
        FontImport.Error("Import fonts from Settings › App Font")

    override fun deleteFile(path: String) = WebFiles.delete(path)

    override fun writeTextToUri(uri: PlatformUri, text: String) {
        val path = uri.webPath ?: throw IOException("Couldn't write the file")
        WebFiles.write(path, text.encodeToByteArray())
    }

    override fun readTextFromUri(uri: PlatformUri): String? = uri.webPath?.let { WebFiles.read(it) }?.decodeToString()

    override suspend fun renderTextshot(text: String): TextshotImage {
        val emoji = com.mediaviewer.util.EmojiStore.get(WebContext)
        emoji.load()
        val side = 1080
        val picture = com.mediaviewer.util.SkiaTextshotRenderer.render(text, side, emoji = emoji::imageForChar)
        val png = picture.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)?.bytes
            ?: throw IOException("Couldn't make the Textshot picture")
        // (Alt text never carries a custom emoji's name.)
        return TextshotImage(PlatformBitmap(side, side, png), emoji.stripEmoji(text), emoji.containsEmoji(text))
    }

    override suspend fun goLive(platform: LiveNowPlatform, channelUrl: String): Result<Unit> =
        Result.failure(UnsupportedOperationException("Live Link isn't available on the web"))

    override suspend fun endLive(): Result<Unit> =
        Result.failure(UnsupportedOperationException("Live Link isn't available on the web"))

    override fun requestPinLiveLinkWidget(): Boolean = false
}

/** Warms Coil's cache for upcoming pictures (set up by WebImageLoading). */
object WebImagePreloader {
    var preloader: ((List<String>) -> Unit)? = null
    fun preload(urls: List<String>) { preloader?.invoke(urls) }
}
