package com.mediaviewer.ui.compat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.mediaviewer.platform.JsBytes
import com.mediaviewer.platform.PlatformUri
import com.mediaviewer.platform.WebBlobs
import com.mediaviewer.platform.WebFiles
import com.mediaviewer.platform.WebPaths
import com.mediaviewer.platform.WebUri
import com.mediaviewer.platform.jsArrayAt
import com.mediaviewer.platform.jsArrayLength
import com.mediaviewer.platform.jsGet
import com.mediaviewer.platform.jsGetInt
import com.mediaviewer.platform.jsGetNumber
import com.mediaviewer.platform.jsGetString
import com.mediaviewer.platform.randomUuidString
import com.mediaviewer.platform.toByteArray
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import kotlin.js.Promise

/** The browser's own file picker behind the pickers. */
@Suppress("UNCHECKED_CAST")
@Composable
actual fun <I, O> rememberLauncherForActivityResult(
    contract: ResultContract<I, O>,
    onResult: (O) -> Unit,
): ActivityResultLauncher<I> {
    val latest = rememberUpdatedState(onResult)
    return remember(contract) {
        object : ActivityResultLauncher<I> {
            override fun launch(input: I) {
                WebPickers.launch(contract, input) { result -> latest.value(result as O) }
            }
        }
    }
}

/**
 * Picking files in a browser:
 *  - photos / videos / any file → the browser's file picker; the file is
 *    kept as the browser hands it over (WebBlobs) and pictures are read
 *    right away, so the shared code can use them like files on a phone;
 *  - "save a file" (dataset export, backups) → a download, named as asked;
 *  - a folder, and permissions → not available (null / denied).
 */
object WebPickers {
    private val scope = MainScope()

    fun launch(contract: ResultContract<*, *>, input: Any?, deliver: (Any?) -> Unit) {
        when (contract) {
            is ActivityResultContracts.PickVisualMedia -> {
                val type = (input as? PickVisualMediaRequest)?.mediaType ?: ActivityResultContracts.PickVisualMedia.ImageAndVideo
                pick(acceptFor(type), multiple = false) { deliver(it.firstOrNull()) }
            }
            is ActivityResultContracts.PickMultipleVisualMedia -> {
                val type = (input as? PickVisualMediaRequest)?.mediaType ?: ActivityResultContracts.PickVisualMedia.ImageAndVideo
                pick(acceptFor(type), multiple = true) { deliver(it.take(contract.maxItems)) }
            }
            is ActivityResultContracts.GetContent -> pick(acceptForMime(arrayOf(input as? String ?: "*/*")), false) { deliver(it.firstOrNull()) }
            is ActivityResultContracts.OpenDocument -> {
                val mimes = input as? Array<String> ?: arrayOf("*/*")
                pick(acceptForMime(mimes), false) { deliver(it.firstOrNull()) }
            }
            is ActivityResultContracts.CreateDocument -> {
                val name = (input as? String ?: "Stellar-file").replace('/', '-').replace('\\', '-').ifBlank { "Stellar-file" }
                deliver(WebUri(WebFiles.uriOf(WebFiles.DOWNLOADS + name)))
            }
            is ActivityResultContracts.RequestPermission -> deliver(false)
            is ActivityResultContracts.OpenDocumentTree -> deliver(null)
        }
    }

    private fun acceptFor(type: ActivityResultContracts.PickVisualMedia.VisualMediaType): String = when (type) {
        ActivityResultContracts.PickVisualMedia.ImageOnly -> "image/*"
        ActivityResultContracts.PickVisualMedia.VideoOnly -> "video/*"
        else -> "image/*,video/*"
    }

    private fun acceptForMime(mimes: Array<String>): String {
        if (mimes.any { it == "*/*" || it.isBlank() }) return ""
        return mimes.joinToString(",") { m ->
            when {
                m.startsWith("font/") || m == "application/x-font-ttf" || m == "application/vnd.ms-opentype" -> ".ttf,.otf,.ttc,$m"
                m == "application/json" -> ".json,$m"
                else -> m
            }
        }
    }

    /** Opens the picker; [done] gets the picked files (empty if cancelled). */
    private fun pick(accept: String, multiple: Boolean, done: (List<PlatformUri>) -> Unit) {
        scope.launch {
            val files = runCatching { openPicker(accept, multiple).await<JsAny>() }.getOrNull()
            val out = ArrayList<PlatformUri>()
            if (files != null) {
                for (i in 0 until jsArrayLength(files)) {
                    val f = jsArrayAt(files, i)
                    val name = jsGetString(f, "name") ?: "file"
                    val type = jsGetString(f, "type").orEmpty()
                    val size = jsGetNumber(f, "size").toLong()
                    val safe = name.replace('/', '_').replace('\\', '_')
                    val path = WebPaths.TEMP + "/picked/" + randomUuidString() + "/" + safe
                    WebBlobs.put(path, f, name, type, size)
                    val isVideo = type.startsWith("video/")
                    // Pictures and small files are read now (the shared
                    // code reads them without waiting); a video stays the
                    // browser's file and is sent from there.
                    if (!isVideo || size <= EAGER_VIDEO_BYTES) runCatching { WebBlobs.readAll(path) }
                    if (isVideo) runCatching {
                        val dims = videoSize(f).await<JsAny>()
                        WebBlobs.setVideoSize(path, jsGetInt(dims, "w"), jsGetInt(dims, "h"))
                    }
                    out += WebUri(WebFiles.uriOf(path))
                }
            }
            done(out)
        }
    }

    private const val EAGER_VIDEO_BYTES = 40L * 1024 * 1024
}

/** Shows the browser's file picker; resolves with the picked File objects
 *  (an empty list when it's closed without picking). */
private fun openPicker(accept: String, multiple: Boolean): Promise<JsAny> = js("""new Promise((resolve) => {
    const input = document.createElement('input');
    input.type = 'file';
    if (accept) input.accept = accept;
    input.multiple = multiple;
    input.style.display = 'none';
    let settled = false;
    const finish = (list) => { if (settled) return; settled = true; input.remove(); resolve(list); };
    input.addEventListener('change', () => finish(Array.from(input.files || [])));
    input.addEventListener('cancel', () => finish([]));
    document.body.appendChild(input);
    input.click();
})""")

/** A video file's picture size ({w, h}; 0 when unknown). */
private fun videoSize(file: JsAny): Promise<JsAny> = js("""new Promise((resolve) => {
    const url = URL.createObjectURL(file);
    const v = document.createElement('video');
    v.preload = 'metadata';
    const done = (w, h) => { URL.revokeObjectURL(url); resolve({ w: w, h: h }); };
    v.onloadedmetadata = () => done(v.videoWidth || 0, v.videoHeight || 0);
    v.onerror = () => done(0, 0);
    setTimeout(() => done(0, 0), 8000);
    v.src = url;
})""")
