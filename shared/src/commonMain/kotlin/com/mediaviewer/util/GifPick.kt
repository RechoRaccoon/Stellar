package com.mediaviewer.util

import com.mediaviewer.platform.MediaBridge
import com.mediaviewer.platform.PlatformContext
import com.mediaviewer.platform.PlatformUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

/**
 * A picked file, ready for the composer: an animated GIF becomes a video
 * (an mp4 made on the phone, so it previews and posts like any video —
 * Bluesky takes GIFs as looping videos). If that can't be made here the
 * GIF itself is kept as the video, and the upload sends it to Bluesky's
 * video service instead. Either way it's never treated as a picture.
 */
object GifPick {
    class Picked(val uri: PlatformUri, val isVideo: Boolean)

    // The videos made from GIFs here, so the upload still posts them as GIFs.
    @kotlin.concurrent.Volatile private var fromGifs = setOf<String>()
    private fun remember(uri: PlatformUri) { fromGifs = fromGifs + uri.toString() }

    /** True when [uri] is a video made here from a GIF. */
    fun wasGif(uri: PlatformUri): Boolean = uri.toString() in fromGifs

    suspend fun prepare(context: PlatformContext, uri: PlatformUri): Picked = withContext(Dispatchers.IO) {
        val type = MediaBridge.mimeTypeOf(context, uri)?.lowercase().orEmpty()
        if (type.startsWith("video/")) return@withContext Picked(uri, true)
        val bytes = runCatching { MediaBridge.readBytes(context, uri) }.getOrNull()
        if (bytes == null || !GifInfo.isAnimated(bytes)) return@withContext Picked(uri, false)
        val video = runCatching { MediaBridge.videoFromGif(context, bytes) }
            .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
            .getOrNull()
        video?.let { remember(it) }
        Picked(video ?: uri, true)
    }
}
