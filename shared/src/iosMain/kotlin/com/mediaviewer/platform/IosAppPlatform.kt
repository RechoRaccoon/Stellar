package com.mediaviewer.platform

import com.mediaviewer.model.LiveNowPlatform
import com.mediaviewer.tagging.TaggingService
import com.mediaviewer.tagging.UnavailableTaggingService

/** iOS side of [AppPlatform]. Features iOS can't do yet report themselves
 *  unavailable (Android keeps all of them). */
class IosAppPlatform(
    bskyRepo: com.mediaviewer.repository.BlueskyRepository,
    e621Repo: com.mediaviewer.repository.E621Repository
) : AppPlatform {
    override val context: PlatformContext get() = IosContext
    // AI Tagging: the same model as Android, when the Swift app brought
    // its tagger along (see registerIosTagger); otherwise it stays gated.
    override val tagging: TaggingService =
        com.mediaviewer.tagging.IosTaggerBridge.tagger
            ?.let { com.mediaviewer.tagging.IosTaggingRepository(it, bskyRepo, e621Repo) }
            ?: UnavailableTaggingService

    override fun haptic() = IosHaptics.tick()

    override fun toast(message: String) = AppEvents.postMessage(message)

    override fun restartApp() = AppEvents.requestRestart()

    override fun preloadImages(urls: List<String>) {
        IosImagePreloader.preload?.invoke(urls)
    }

    override fun enqueueDownload(url: String, filename: String, mimeType: String, postId: String) {
        IosDownloads.save(url, filename, mimeType)
    }

    override fun enqueueVideoBlobDownload(did: String, cid: String, postId: String) {
        IosDownloads.saveVideoBlob(did, cid)
    }

    override fun enqueueGifDownload(url: String, isVideo: Boolean, postId: String, blobDid: String?, blobCid: String?) {
        IosDownloads.saveAsGif(url, isVideo, postId, blobDid, blobCid)
    }

    // (Fonts are imported through FontStore — Settings → App Font.)
    override fun importCustomFont(uri: PlatformUri): FontImport =
        FontImport.Error("Import fonts from Settings › App Font")

    override fun deleteFile(path: String) { platform.posix.remove(path) }

    override fun writeTextToUri(uri: PlatformUri, text: String) {
        val path = uri.toString().removePrefix("file://")
        if (!writeLocalFile(path, text.encodeToByteArray())) throw IOException("Couldn't write the file")
    }

    override fun readTextFromUri(uri: PlatformUri): String? =
        readLocalFile(uri.toString().removePrefix("file://"))?.decodeToString()

    override suspend fun renderTextshot(text: String): TextshotImage {
        val emoji = com.mediaviewer.util.EmojiStore.get(IosContext)
        emoji.load()
        val side = 1080
        val picture = com.mediaviewer.util.IosTextshotRenderer.render(text, side, emoji = emoji::imageForChar)
        val png = picture.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)?.bytes
            ?: throw IOException("Couldn't make the Textshot picture")
        // (Alt text never carries a custom emoji's name.)
        return TextshotImage(PlatformBitmap(side, side, png), emoji.stripEmoji(text), emoji.containsEmoji(text))
    }

    override suspend fun goLive(platform: LiveNowPlatform, channelUrl: String): Result<Unit> =
        Result.failure(UnsupportedOperationException("Live Link isn't available on iOS yet"))

    override suspend fun endLive(): Result<Unit> =
        Result.failure(UnsupportedOperationException("Live Link isn't available on iOS yet"))

    override fun requestPinLiveLinkWidget(): Boolean = false
}

/** Filled in by the iOS UI layer once its image loader exists. */
object IosImagePreloader { var preload: ((List<String>) -> Unit)? = null }
