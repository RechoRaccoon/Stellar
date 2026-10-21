package com.mediaviewer.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readValue
import kotlinx.cinterop.useContents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.AVFoundation.AVAssetExportPresetHighestQuality
import platform.AVFoundation.AVAssetExportPreset1280x720
import platform.AVFoundation.AVAssetExportPreset960x540
import platform.AVFoundation.AVAssetExportPreset640x480
import platform.AVFoundation.AVAssetExportPresetMediumQuality
import platform.AVFoundation.AVAssetExportPresetLowQuality
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSNumber
import platform.AVFoundation.AVAssetExportSession
import platform.AVFoundation.AVAssetExportSessionStatusCompleted
import platform.AVFoundation.AVAssetTrack
import platform.AVFoundation.AVFileTypeMPEG4
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVMutableVideoComposition
import platform.AVFoundation.AVMutableVideoCompositionInstruction
import platform.AVFoundation.AVMutableVideoCompositionLayerInstruction
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.duration
import platform.AVFoundation.naturalSize
import platform.AVFoundation.preferredTransform
import platform.AVFoundation.setVideoComposition
import platform.AVFoundation.tracksWithMediaType
import platform.CoreGraphics.CGAffineTransformConcat
import platform.CoreGraphics.CGAffineTransformMakeScale
import platform.CoreGraphics.CGAffineTransformMakeTranslation
import platform.CoreGraphics.CGRectApplyAffineTransform
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.CoreMedia.CMTimeMake
import platform.CoreMedia.CMTimeRangeMake
import platform.CoreMedia.kCMTimeZero
import platform.Foundation.NSURL
import kotlin.coroutines.resume

/**
 * A profile's video icon or banner (see MediaBridge.cropVideo): the chosen
 * part of the upright picture, scaled down, exported with Apple's own
 * exporter. Only this cropped file is uploaded.
 */
@OptIn(ExperimentalForeignApi::class)
internal object IosVideoCropper {
    suspend fun crop(path: String, fracLeft: Float, fracTop: Float, fracWidth: Float, fracHeight: Float, maxW: Int, maxH: Int, maxBytes: Long = 0L): String = withContext(Dispatchers.IO) {
        val asset = AVURLAsset.URLAssetWithURL(NSURL.fileURLWithPath(path), null)
        val track = asset.tracksWithMediaType(AVMediaTypeVideo ?: "vide").firstOrNull() as? AVAssetTrack
            ?: error("Couldn't read the video")
        val pref = track.preferredTransform
        val (nw, nh) = track.naturalSize.useContents { width to height }
        // The upright picture: where the preferred transform puts it.
        val (ox, oy, uw, uh) = CGRectApplyAffineTransform(CGRectMake(0.0, 0.0, nw, nh), pref).useContents {
            listOf(origin.x, origin.y, kotlin.math.abs(size.width), kotlin.math.abs(size.height))
        }
        val cx = uw * fracLeft
        val cy = uh * fracTop
        val cw = uw * fracWidth
        val ch = uh * fracHeight
        val scale = minOf(1.0, maxW / cw, maxH / ch)
        val outW = ((cw * scale).toInt() / 2 * 2).coerceAtLeast(2)
        val outH = ((ch * scale).toInt() / 2 * 2).coerceAtLeast(2)
        // Upright, then the chosen part moved to the corner, then scaled.
        var transform = CGAffineTransformConcat(pref, CGAffineTransformMakeTranslation(-ox - cx, -oy - cy))
        transform = CGAffineTransformConcat(transform, CGAffineTransformMakeScale(outW / cw, outH / ch))

        val layer = AVMutableVideoCompositionLayerInstruction.videoCompositionLayerInstructionWithAssetTrack(track)
        layer.setTransform(transform, atTime = kCMTimeZero.readValue())
        val instruction = AVMutableVideoCompositionInstruction.videoCompositionInstruction()
        instruction.setTimeRange(CMTimeRangeMake(kCMTimeZero.readValue(), asset.duration))
        instruction.setLayerInstructions(listOf(layer))
        val composition = AVMutableVideoComposition.videoComposition()
        composition.setRenderSize(CGSizeMake(outW.toDouble(), outH.toDouble()))
        composition.setFrameDuration(CMTimeMake(1, 30))
        composition.setInstructions(listOf(instruction))

        // Best quality first; when that comes out over [maxBytes], the next
        // preset down (each compresses more), until one fits.
        val presets = listOfNotNull(
            AVAssetExportPresetHighestQuality, AVAssetExportPreset1280x720, AVAssetExportPreset960x540,
            AVAssetExportPreset640x480, AVAssetExportPresetMediumQuality, AVAssetExportPresetLowQuality
        )
        var last: String? = null
        for ((i, preset) in presets.withIndex()) {
            val out = IosPaths.cacheDir() + "/profile-video-${currentTimeMillis()}-$i.mp4"
            deleteLocalFile(out)
            val export = AVAssetExportSession(asset = asset, presetName = preset)
            export.setVideoComposition(composition)
            export.setOutputURL(NSURL.fileURLWithPath(out))
            export.setOutputFileType(AVFileTypeMPEG4)
            export.setShouldOptimizeForNetworkUse(true)
            val ok = suspendCancellableCoroutine { cont ->
                cont.invokeOnCancellation { export.cancelExport() }
                export.exportAsynchronouslyWithCompletionHandler {
                    if (cont.isActive) cont.resume(export.status == AVAssetExportSessionStatusCompleted)
                }
            }
            if (!ok) { deleteLocalFile(out); if (last != null) break else continue }
            last?.let { deleteLocalFile(it) }
            last = out
            val bytes = (NSFileManager.defaultManager.attributesOfItemAtPath(out, null)?.get(NSFileSize) as? NSNumber)?.longLongValue ?: 0L
            if (maxBytes <= 0L || bytes <= maxBytes) break
        }
        last ?: error("Cropping the video failed")
    }
}
