package com.mediaviewer.platform

import com.mediaviewer.network.PlainHttp
import com.mediaviewer.network.isSuccessful
import com.mediaviewer.worker.BlueskyBlobResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import platform.Foundation.*
import platform.Photos.*
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Saving media to the Photos library (the iOS counterpart of Android's
 * DownloadWorker). Needs NSPhotoLibraryAddUsageDescription in Info.plist.
 *
 * Fixes from the first device test:
 *  - The file name came from the URL, and Bluesky CDN URLs have no real
 *    extension (".../bafk…@jpeg"), so the "name" contained slashes and the
 *    temporary file could never be written. Files are now named from the
 *    post id + the MIME type's extension.
 *  - Photos access is asked for first (add-only), and "Saved to Photos" is
 *    only shown once Photos actually accepted the file.
 */
object IosDownloads {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun save(url: String, filename: String, mimeType: String) {
        scope.launch {
            val result = runCatching {
                if (!ensureAccess()) error("Allow Stellar to add to Photos in Settings")
                val resp = PlainHttp.get(url)
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                if (resp.body.isEmpty()) error("Empty file")
                // What the file really is decides its name: a server's
                // "image/jpeg" is sometimes a PNG or WebP, and Photos goes
                // by the extension.
                val actual = sniffType(resp.body) ?: mimeType
                val path = IosPaths.cacheDir() + "/" + safeName(filename, actual)
                if (!writeLocalFile(path, resp.body)) error("Couldn't write the file")
                try { saveFileToPhotos(path, actual.startsWith("video")) } finally { deleteLocalFile(path) }
            }
            AppEvents.postMessage(
                if (result.isSuccess) "Saved to Photos"
                else "Couldn't save: ${result.exceptionOrNull()?.message ?: "unknown error"}"
            )
        }
    }

    /**
     * "Save as GIF". A picture becomes a single-frame GIF; a video is
     * fetched as its original file (the uploaded video itself, not the
     * stream the player uses) and every frame of it is written into an
     * animated GIF by Apple's own frameworks (see IosMediaTools). The GIF
     * goes to Photos like any other download.
     */
    fun saveAsGif(url: String, isVideo: Boolean, postId: String, blobDid: String?, blobCid: String?) {
        val tools = IosMediaBridge.tools
        if (tools == null) { AppEvents.postMessage("Saving as GIF isn't available in this build"); return }
        scope.launch {
            val stamp = currentTimeMillis()
            val source = IosPaths.cacheDir() + "/gif_source_$stamp." + (if (isVideo) "mp4" else "img")
            val out = IosPaths.cacheDir() + "/" + safeName(postId.ifBlank { "stellar_$stamp" }, "image/gif")
            val result = runCatching {
                if (!ensureAccess()) error("Allow Stellar to add to Photos in Settings")
                val sourceUrl = if (isVideo && !blobDid.isNullOrBlank() && !blobCid.isNullOrBlank()) {
                    BlueskyBlobResolver.resolveBlobUrl(blobDid, blobCid)
                } else url
                if (isVideo && sourceUrl.substringBefore('?').endsWith(".m3u8")) error("This video can't be read as a file")
                if (isVideo) AppEvents.postMessage("Making the GIF…")
                val resp = PlainHttp.get(sourceUrl)
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                if (resp.body.isEmpty()) error("Empty file")
                // Already a GIF: saved as it is.
                if (sniffType(resp.body) == "image/gif") {
                    if (!writeLocalFile(out, resp.body)) error("Couldn't write the file")
                } else {
                    if (!writeLocalFile(source, resp.body)) error("Couldn't write the file")
                    val failure: String? = suspendCoroutine { cont ->
                        if (isVideo) tools.gifFromVideo(source, out) { cont.resume(it) }
                        else tools.gifFromImage(source, out) { cont.resume(it) }
                    }
                    if (failure != null) error(failure)
                }
                saveFileToPhotos(out, isVideo = false)
            }
            deleteLocalFile(source)
            deleteLocalFile(out)
            AppEvents.postMessage(
                if (result.isSuccess) "GIF saved to Photos"
                else "Couldn't save the GIF: ${result.exceptionOrNull()?.message ?: "unknown error"}"
            )
        }
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

    fun saveVideoBlob(did: String, cid: String) {
        scope.launch {
            val url = runCatching { BlueskyBlobResolver.resolveBlobUrl(did, cid) }.getOrNull()
            if (url == null) { AppEvents.postMessage("Couldn't find that video"); return@launch }
            save(url, "stellar_${cid.takeLast(12)}.mp4", "video/mp4")
        }
    }

    /** A plain file name (no slashes or URL junk) with an extension Photos
     *  recognises. */
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

    /** Add-only Photos permission (asks once, the system remembers). */
    private suspend fun ensureAccess(): Boolean {
        val current = PHPhotoLibrary.authorizationStatusForAccessLevel(PHAccessLevelAddOnly)
        if (current == PHAuthorizationStatusAuthorized || current == PHAuthorizationStatusLimited) return true
        if (current != PHAuthorizationStatusNotDetermined) return false
        return suspendCoroutine { cont ->
            PHPhotoLibrary.requestAuthorizationForAccessLevel(PHAccessLevelAddOnly) { status ->
                cont.resume(status == PHAuthorizationStatusAuthorized || status == PHAuthorizationStatusLimited)
            }
        }
    }

    private suspend fun saveFileToPhotos(path: String, isVideo: Boolean) {
        val fileUrl = NSURL.fileURLWithPath(path)
        val error: String? = suspendCoroutine { cont ->
            PHPhotoLibrary.sharedPhotoLibrary().performChanges({
                if (isVideo) PHAssetChangeRequest.creationRequestForAssetFromVideoAtFileURL(fileUrl)
                else PHAssetChangeRequest.creationRequestForAssetFromImageAtFileURL(fileUrl)
            }, completionHandler = { ok, err ->
                cont.resume(if (ok) null else (err?.localizedDescription ?: "Photos refused the file"))
            })
        }
        if (error != null) error(error)
    }
}
