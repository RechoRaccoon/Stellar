package com.mediaviewer.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.AVFoundation.AVAssetExportPresetHighestQuality
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
import platform.AVFoundation.nominalFrameRate
import platform.AVFoundation.preferredTransform
import platform.AVFoundation.setVideoComposition
import platform.AVFoundation.tracksWithMediaType
import platform.CoreGraphics.CGAffineTransformConcat
import platform.CoreGraphics.CGAffineTransformMakeTranslation
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.CoreMedia.CMTimeMake
import platform.CoreMedia.CMTimeRangeMake
import platform.Foundation.NSURL
import platform.UIKit.UIGraphicsBeginImageContextWithOptions
import platform.UIKit.UIGraphicsEndImageContext
import platform.UIKit.UIGraphicsGetImageFromCurrentImageContext
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import kotlin.coroutines.resume

/**
 * The review page's crop, applied for real: a photo or a video cut down
 * to the part that was framed. The part is given as fractions of the
 * whole picture — left, top, width, height — exactly as it was shown.
 */
@OptIn(ExperimentalForeignApi::class)
object IosCrop {
    /** A photo's size in pixels, the right way up; null if it can't be read. */
    fun imageSize(path: String): Pair<Int, Int>? {
        val image = UIImage.imageWithContentsOfFile(path) ?: return null
        val scale = image.scale
        return image.size.useContents { (width * scale).toInt() to (height * scale).toInt() }.takeIf { it.first > 0 && it.second > 0 }
    }

    /** The framed part of the photo at [path], as a new JPEG. */
    suspend fun cropImage(path: String, window: FloatArray): String? = withContext(Dispatchers.IO) {
        runCatching {
            val image = UIImage.imageWithContentsOfFile(path) ?: return@runCatching null
            val scale = image.scale
            val (w, h) = image.size.useContents { width * scale to height * scale }
            val cw = (window[2] * w).coerceIn(1.0, w)
            val ch = (window[3] * h).coerceIn(1.0, h)
            val x = (window[0] * w).coerceIn(0.0, w - cw)
            val y = (window[1] * h).coerceIn(0.0, h - ch)
            // Drawn shifted into a canvas the size of the part that's kept
            // (which also turns a sideways-stored photo upright).
            UIGraphicsBeginImageContextWithOptions(CGSizeMake(cw.toInt().toDouble(), ch.toInt().toDouble()), true, 1.0)
            image.drawInRect(CGRectMake(-x, -y, w, h))
            val out = UIGraphicsGetImageFromCurrentImageContext()
            UIGraphicsEndImageContext()
            val bytes = out?.let { UIImageJPEGRepresentation(it, 0.95) }?.toByteArray() ?: return@runCatching null
            val target = IosPaths.cacheDir() + "/crop-" + currentTimeMillis() + ".jpg"
            if (writeLocalFile(target, bytes)) target else null
        }.onFailure { Log.e("IosCrop", "Cropping the photo failed", it) }.getOrNull()
    }

    /** The framed part of the video at [path], as a new mp4 (sound kept). */
    suspend fun cropVideo(path: String, window: FloatArray): String? = withContext(Dispatchers.IO) {
        runCatching {
            val asset = AVURLAsset.URLAssetWithURL(NSURL.fileURLWithPath(path), null)
            val track = asset.tracksWithMediaType(AVMediaTypeVideo ?: "vide").firstOrNull() as? AVAssetTrack ?: return@runCatching null
            // The video's shape as it's shown (a phone's portrait video is
            // stored on its side with a "turn me" note).
            val (naturalW, naturalH) = track.naturalSize.useContents { width to height }
            val turned = track.preferredTransform.useContents { b != 0.0 || c != 0.0 }
            val w = if (turned) naturalH else naturalW
            val h = if (turned) naturalW else naturalH
            val cw = even((window[2] * w).coerceIn(2.0, w))
            val ch = even((window[3] * h).coerceIn(2.0, h))
            val x = (window[0] * w).coerceIn(0.0, w - cw)
            val y = (window[1] * h).coerceIn(0.0, h - ch)

            val zero = CMTimeMake(0, 600)
            // Each frame: turned upright, then slid so the kept part's
            // corner is the picture's corner; the rest falls outside.
            val layer = AVMutableVideoCompositionLayerInstruction.videoCompositionLayerInstructionWithAssetTrack(track)
            layer.setTransform(CGAffineTransformConcat(track.preferredTransform, CGAffineTransformMakeTranslation(-x, -y)), atTime = zero)
            val part = AVMutableVideoCompositionInstruction.videoCompositionInstruction()
            part.setTimeRange(CMTimeRangeMake(zero, asset.duration))
            part.setLayerInstructions(listOf(layer))
            val layout = AVMutableVideoComposition.videoComposition()
            layout.setRenderSize(CGSizeMake(cw.toDouble(), ch.toDouble()))
            layout.setFrameDuration(CMTimeMake(1, track.nominalFrameRate.toInt().coerceIn(24, 60)))
            layout.setInstructions(listOf(part))

            val target = IosPaths.cacheDir() + "/crop-" + currentTimeMillis() + ".mp4"
            deleteLocalFile(target)
            val export = AVAssetExportSession(asset = asset, presetName = AVAssetExportPresetHighestQuality)
            export.setOutputURL(NSURL.fileURLWithPath(target))
            export.setOutputFileType(AVFileTypeMPEG4)
            export.setVideoComposition(layout)
            export.setShouldOptimizeForNetworkUse(true)
            val ok: Boolean = suspendCancellableCoroutine { cont ->
                cont.invokeOnCancellation { export.cancelExport() }
                export.exportAsynchronouslyWithCompletionHandler {
                    if (cont.isActive) cont.resume(export.status == AVAssetExportSessionStatusCompleted)
                }
            }
            if (ok && localFileExists(target)) target else { deleteLocalFile(target); null }
        }.onFailure { Log.e("IosCrop", "Cropping the video failed", it) }.getOrNull()
    }

    private fun even(v: Double): Int = ((v.toInt() / 2) * 2).coerceAtLeast(2)
}
