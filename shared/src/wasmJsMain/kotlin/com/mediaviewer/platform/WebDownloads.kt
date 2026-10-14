package com.mediaviewer.platform

import com.mediaviewer.network.PlainHttp
import com.mediaviewer.network.isSuccessful
import com.mediaviewer.util.PostDownloads
import com.mediaviewer.worker.BlueskyBlobResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Saving media on the web: the browser downloads the file (to its
 * Downloads folder, or wherever it asks). If a server won't let the page
 * read the file itself, it's opened in a new tab instead, to save from
 * there.
 */
object WebDownloads {
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    /** Hands [bytes] to the browser as a download named [name]. */
    fun saveBytes(bytes: ByteArray, name: String, mimeType: String) {
        val url = createObjectUrl(bytesToBlob(bytes.toJsBytes(), mimeType))
        clickDownload(url, name)
        setTimeoutMs(60_000) { revokeObjectUrl(url) }
    }

    fun save(url: String, filename: String, mimeType: String, postId: String = "") {
        scope.launch {
            val result = runCatching {
                val resp = PlainHttp.get(url)
                if (!resp.isSuccessful || resp.body.isEmpty()) error("HTTP ${resp.code}")
                // What the file really is decides its name: a server's
                // "image/jpeg" is sometimes a PNG or WebP.
                val actual = sniffType(resp.body) ?: mimeType
                saveBytes(resp.body, safeName(filename, actual), actual)
            }
            PostDownloads.jobDone(postId, result.isSuccess)
            if (result.isSuccess) {
                AppEvents.postMessage("Downloaded")
            } else {
                // (The server didn't let the page read it: open it to save from there.)
                openInNewTab(url)
                AppEvents.postMessage("Opened in a new tab to save")
            }
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

private fun clickDownload(url: String, name: String): Unit = js("""{
    const a = document.createElement('a');
    a.href = url; a.download = name; a.style.display = 'none';
    document.body.appendChild(a); a.click(); a.remove();
}""")

/** Opens [url] in a new browser tab. */
fun openInNewTab(url: String): Unit = js("{ window.open(url, '_blank', 'noopener'); }")
