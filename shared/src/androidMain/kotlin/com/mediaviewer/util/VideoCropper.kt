package com.mediaviewer.util

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.Crop
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
 * A profile's video icon or banner: the picked video cut down to the part
 * chosen in the crop popup, scaled down, without sound (they always play
 * muted), as H.264. Only this cropped file is uploaded.
 */
object VideoCropper {
    suspend fun crop(
        context: Context, uri: Uri, left: Float, top: Float, width: Float, height: Float, maxW: Int, maxH: Int, maxBytes: Long = 0L
    ): Uri {
        val (w, h) = withContext(Dispatchers.IO) { uprightSize(context, uri) } ?: error("Couldn't read the video")
        val seconds = withContext(Dispatchers.IO) { durationSeconds(context, uri) }
        // 4 Mbps, or less when that would come out over [maxBytes]; if it
        // still does (encoders overshoot), once more, lower again.
        var bitrate = com.mediaviewer.platform.profileVideoBitrate(maxBytes, seconds)
        repeat(3) { attempt ->
            val out = encode(context, uri, left, top, width, height, maxW, maxH, w, h, bitrate)
            val size = out.length()
            if (maxBytes <= 0L || size <= maxBytes || attempt == 2) return Uri.fromFile(out)
            out.delete()
            bitrate = (bitrate * (maxBytes.toDouble() / size) * 0.85).toInt().coerceAtLeast(100_000)
        }
        error("Cropping the video failed")
    }

    private suspend fun encode(
        context: Context, uri: Uri, left: Float, top: Float, width: Float, height: Float, maxW: Int, maxH: Int,
        w: Int, h: Int, bitrate: Int
    ): File {
        val cropW = w * width
        val cropH = h * height
        val scale = minOf(1f, maxW / cropW, maxH / cropH)
        val outW = ((cropW * scale).toInt() / 2 * 2).coerceAtLeast(2)
        val outH = ((cropH * scale).toInt() / 2 * 2).coerceAtLeast(2)
        // Crop takes the edges in normalized device coordinates: -1 to 1,
        // with y pointing up.
        val crop = Crop(
            left * 2f - 1f, (left + width) * 2f - 1f,
            1f - (top + height) * 2f, 1f - top * 2f
        )
        val output = withContext(Dispatchers.IO) { File.createTempFile("profile-video-", ".mp4", context.cacheDir) }
        try {
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine<Unit> { cont ->
                    val item = EditedMediaItem.Builder(MediaItem.fromUri(uri))
                        .setRemoveAudio(true)
                        .setEffects(Effects(emptyList(), listOf(crop, Presentation.createForWidthAndHeight(outW, outH, Presentation.LAYOUT_SCALE_TO_FIT))))
                        .build()
                    val encoders = DefaultEncoderFactory.Builder(context.applicationContext)
                        .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(bitrate).build())
                        .build()
                    val transformer = Transformer.Builder(context.applicationContext)
                        .setVideoMimeType(MimeTypes.VIDEO_H264)
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
            throw t
        }
        if (output.length() <= 0L) { output.delete(); error("Cropping the video failed") }
        return output
    }

    /** How long the video is, in seconds (0 when it can't be told). */
    private fun durationSeconds(context: Context, uri: Uri): Double {
        val r = android.media.MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri)
            (r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) / 1000.0
        } catch (_: Exception) {
            0.0
        } finally {
            runCatching { r.release() }
        }
    }

    /** The picture's size once turned upright, or null. */
    private fun uprightSize(context: Context, uri: Uri): Pair<Int, Int>? {
        val r = android.media.MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri)
            val w = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: return null
            val h = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: return null
            val rot = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rot == 90 || rot == 270) h to w else w to h
        } catch (_: Exception) {
            null
        } finally {
            runCatching { r.release() }
        }
    }
}
