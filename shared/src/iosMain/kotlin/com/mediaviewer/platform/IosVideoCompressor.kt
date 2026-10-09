package com.mediaviewer.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.AVFoundation.AVAssetExportPreset1280x720
import platform.AVFoundation.AVAssetExportPreset1920x1080
import platform.AVFoundation.AVAssetExportPreset960x540
import platform.AVFoundation.AVAssetExportSession
import platform.AVFoundation.AVAssetExportSessionStatusCompleted
import platform.AVFoundation.AVAssetTrack
import platform.AVFoundation.AVFileTypeMPEG4
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.duration
import platform.AVFoundation.naturalSize
import platform.AVFoundation.tracksWithMediaType
import platform.CoreMedia.CMTimeGetSeconds
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import kotlin.coroutines.resume

/**
 * iOS's side of VideoCompressor (Android): a video bigger than Bluesky
 * plays (1080p) or too large a file is re-encoded with Apple's own exporter
 * before it's uploaded — at 1080p, or 720p/540p when the length needs a
 * lower bit rate to stay under the size limit. Anything already within the
 * limits is uploaded untouched. If exporting fails, the original goes.
 */
@OptIn(ExperimentalForeignApi::class)
internal object IosVideoCompressor {
    private const val MAX_SHORT_SIDE = 1080.0
    private const val MAX_BYTES = 90L * 1024 * 1024

    suspend fun prepare(path: String): String = withContext(Dispatchers.IO) {
        val asset = AVURLAsset.URLAssetWithURL(NSURL.fileURLWithPath(path), null)
        val track = asset.tracksWithMediaType(AVMediaTypeVideo ?: "vide").firstOrNull() as? AVAssetTrack
            ?: return@withContext path
        val short = track.naturalSize.useContents { minOf(width, height) }
        val bytes = (NSFileManager.defaultManager.attributesOfItemAtPath(path, null)?.get(NSFileSize) as? NSNumber)?.longLongValue ?: 0L
        if (short <= MAX_SHORT_SIDE && bytes <= MAX_BYTES) return@withContext path

        val seconds = CMTimeGetSeconds(asset.duration).takeIf { it.isFinite() && it > 0.0 } ?: 1.0
        val budget = MAX_BYTES * 8 * 0.85 / seconds
        val preset = when {
            budget >= 9_000_000 -> AVAssetExportPreset1920x1080
            budget >= 4_500_000 -> AVAssetExportPreset1280x720
            else -> AVAssetExportPreset960x540
        }
        val out = IosPaths.cacheDir() + "/upload-${currentTimeMillis()}.mp4"
        deleteLocalFile(out)
        val export = AVAssetExportSession(asset = asset, presetName = preset)
        export.setOutputURL(NSURL.fileURLWithPath(out))
        export.setOutputFileType(AVFileTypeMPEG4)
        export.setShouldOptimizeForNetworkUse(true)
        val ok = suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { export.cancelExport() }
            export.exportAsynchronouslyWithCompletionHandler {
                if (cont.isActive) cont.resume(export.status == AVAssetExportSessionStatusCompleted)
            }
        }
        val outBytes = (NSFileManager.defaultManager.attributesOfItemAtPath(out, null)?.get(NSFileSize) as? NSNumber)?.longLongValue ?: 0L
        if (ok && outBytes > 0L && (bytes == 0L || outBytes < bytes)) out
        else { deleteLocalFile(out); path }
    }
}
