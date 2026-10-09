package com.mediaviewer.util

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Gets a video within what Bluesky takes before it's uploaded.
 *
 * Phones record 4K at 40–100 Mbit/s, so a minute of video is easily several
 * hundred MB — past Bluesky's size limit, and a long, fragile upload even
 * when it isn't. Bluesky plays videos at up to 1080p, so anything bigger
 * than that (or too large a file) is re-encoded here to H.264 at up to
 * 1080p, at a bit rate that keeps the file comfortably under the limit.
 * Videos already within the limits are uploaded untouched.
 */
object VideoCompressor {
    /** Short side above this is scaled down (Bluesky's top playback size). */
    private const val MAX_SHORT_SIDE = 1080
    /** Files above this are re-encoded. */
    private const val MAX_BYTES = 90L * 1024 * 1024
    private const val MAX_BITRATE = 10_000_000
    private const val MIN_BITRATE = 1_500_000

    private data class Probe(val w: Int, val h: Int, val durationMs: Long, val bytes: Long, val mime: String?)

    suspend fun prepare(context: Context, uri: Uri): Uri {
        val probe = withContext(Dispatchers.IO) { probe(context, uri) } ?: return uri
        val short = minOf(probe.w, probe.h)
        val tooBig = probe.bytes > MAX_BYTES
        val tooSharp = short > MAX_SHORT_SIDE
        if (!tooBig && !tooSharp) return uri

        val scale = if (tooSharp) MAX_SHORT_SIDE.toFloat() / short else 1f
        val outW = ((probe.w * scale).toInt() / 2 * 2).coerceAtLeast(2)
        val outH = ((probe.h * scale).toInt() / 2 * 2).coerceAtLeast(2)
        // Enough bits for the length to land well under the limit, at most
        // 10 Mbit/s (plenty for 1080p), never starved below 1.5 Mbit/s.
        val seconds = (probe.durationMs / 1000.0).coerceAtLeast(1.0)
        val budget = ((MAX_BYTES * 8 * 0.85) / seconds - 192_000).toInt()
        val bitrate = budget.coerceIn(MIN_BITRATE, MAX_BITRATE)

        val output = withContext(Dispatchers.IO) { File.createTempFile("upload-", ".mp4", context.cacheDir) }
        try {
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine<Unit> { cont ->
                    val item = EditedMediaItem.Builder(MediaItem.fromUri(uri))
                        .setEffects(Effects(emptyList(), listOf(Presentation.createForWidthAndHeight(outW, outH, Presentation.LAYOUT_SCALE_TO_FIT))))
                        .build()
                    val encoders = DefaultEncoderFactory.Builder(context.applicationContext)
                        .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(bitrate).build())
                        .build()
                    val transformer = Transformer.Builder(context.applicationContext)
                        .setVideoMimeType(MimeTypes.VIDEO_H264)
                        .setAudioMimeType(MimeTypes.AUDIO_AAC)
                        .setEncoderFactory(encoders)
                        .build()
                    val listener = object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) { cont.resume(Unit) }
                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                            cont.resumeWithException(exportException)
                        }
                    }
                    transformer.addListener(listener)
                    cont.invokeOnCancellation { transformer.removeListener(listener); transformer.cancel() }
                    transformer.start(item, output.absolutePath)
                }
            }
        } catch (t: Throwable) {
            output.delete()
            if (t is kotlinx.coroutines.CancellationException) throw t
            android.util.Log.e("VideoCompressor", "Re-encoding failed — uploading the original", t)
            return uri
        }
        // (Only worth it if it actually came out smaller.)
        return if (output.length() in 1 until probe.bytes.coerceAtLeast(1)) Uri.fromFile(output)
        else { output.delete(); uri }
    }

    private fun probe(context: Context, uri: Uri): Probe? {
        val r = android.media.MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri)
            val w = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: return null
            val h = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: return null
            val rot = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val dur = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val bytes = runCatching { context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } }.getOrNull()
                ?.takeIf { it > 0 }
                ?: runCatching { File(uri.path ?: "").length() }.getOrDefault(0L)
            val (dw, dh) = if (rot == 90 || rot == 270) h to w else w to h
            Probe(dw, dh, dur, bytes, r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_MIMETYPE))
        } catch (_: Exception) {
            null
        } finally {
            runCatching { r.release() }
        }
    }
}
