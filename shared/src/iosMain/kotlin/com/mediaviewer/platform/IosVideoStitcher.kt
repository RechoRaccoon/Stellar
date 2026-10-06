package com.mediaviewer.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.useContents
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.AVFoundation.AVAssetExportPresetHighestQuality
import platform.AVFoundation.AVAssetExportSession
import platform.AVFoundation.AVAssetExportSessionStatusCompleted
import platform.AVFoundation.AVAssetTrack
import platform.AVFoundation.AVAssetWriter
import platform.AVFoundation.AVAssetWriterInput
import platform.AVFoundation.AVAssetWriterInputPixelBufferAdaptor
import platform.AVFoundation.AVAssetWriterStatusCompleted
import platform.AVFoundation.AVFileTypeMPEG4
import platform.AVFoundation.AVMediaTypeAudio
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVMutableComposition
import platform.AVFoundation.AVMutableVideoComposition
import platform.AVFoundation.AVMutableVideoCompositionInstruction
import platform.AVFoundation.AVMutableVideoCompositionLayerInstruction
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.AVVideoCodecKey
import platform.AVFoundation.AVVideoCodecTypeH264
import platform.AVFoundation.AVVideoHeightKey
import platform.AVFoundation.AVVideoWidthKey
import platform.AVFoundation.addMutableTrackWithMediaType
import platform.AVFoundation.duration
import platform.AVFoundation.naturalSize
import platform.AVFoundation.nominalFrameRate
import platform.AVFoundation.preferredTransform
import platform.AVFoundation.setTimeRange
import platform.AVFoundation.setVideoComposition
import platform.AVFoundation.tracksWithMediaType
import platform.CoreGraphics.CGAffineTransformMake
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextDrawImage
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.CoreGraphics.kCGBitmapByteOrder32Little
import platform.CoreMedia.CMTimeAdd
import platform.CoreMedia.CMTimeMake
import platform.CoreMedia.CMTimeRangeMake
import platform.CoreMedia.kCMPersistentTrackID_Invalid
import platform.CoreVideo.CVPixelBufferCreate
import platform.CoreVideo.CVPixelBufferGetBaseAddress
import platform.CoreVideo.CVPixelBufferGetBytesPerRow
import platform.CoreVideo.CVPixelBufferLockBaseAddress
import platform.CoreVideo.CVPixelBufferRefVar
import platform.CoreVideo.CVPixelBufferRelease
import platform.CoreVideo.CVPixelBufferUnlockBaseAddress
import platform.CoreVideo.kCVPixelFormatType_32BGRA
import platform.CoreVideo.kCVReturnSuccess
import platform.Foundation.NSURL
import platform.UIKit.UIGraphicsBeginImageContextWithOptions
import platform.UIKit.UIGraphicsEndImageContext
import platform.UIKit.UIGraphicsGetImageFromCurrentImageContext
import platform.UIKit.UIImage
import platform.posix.usleep
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Custom video thumbnails on iOS: the same idea as Android's
 * VideoThumbnailStitcher. Bluesky always shows a video's first frame as
 * its thumbnail, so the picked picture is made the first second of the
 * video itself.
 *
 * Done with Apple's own AVFoundation, in two steps:
 *  1. the picture (centre-cropped to the video's shape) is written as a
 *     one-second clip at the video's size;
 *  2. that clip and the video are joined — the sound moved a second later
 *     to stay in step — and exported as one mp4.
 * The whole video is re-encoded, like on Android, so a long video takes a
 * little while.
 */
@OptIn(ExperimentalForeignApi::class)
internal object IosVideoStitcher {
    /** How long the picture stays on screen (Android uses the same). */
    private const val THUMBNAIL_SECONDS = 1
    private const val CLIP_FPS = 30

    suspend fun stitch(videoPath: String, thumbnailPath: String): String = withContext(Dispatchers.IO) {
        val asset = AVURLAsset.URLAssetWithURL(NSURL.fileURLWithPath(videoPath), null)
        val videoTrack = asset.tracksWithMediaType(AVMediaTypeVideo ?: "vide").firstOrNull() as? AVAssetTrack
            ?: error("Couldn't read the video")
        val audioTrack = asset.tracksWithMediaType(AVMediaTypeAudio ?: "soun").firstOrNull() as? AVAssetTrack

        // The video's shape as it's shown (a phone's portrait video is
        // stored on its side with a "turn me" note), with even sides.
        val (naturalW, naturalH) = videoTrack.naturalSize.useContents { width to height }
        val turned = videoTrack.preferredTransform.useContents { b != 0.0 || c != 0.0 }
        val showW = even(if (turned) naturalH else naturalW)
        val showH = even(if (turned) naturalW else naturalH)

        val stamp = currentTimeMillis()
        val clipPath = IosPaths.cacheDir() + "/thumb-clip-$stamp.mp4"
        val outPath = IosPaths.cacheDir() + "/stitched-$stamp.mp4"
        deleteLocalFile(clipPath)
        deleteLocalFile(outPath)
        try {
            val picture = fittedPicture(thumbnailPath, showW, showH) ?: error("Couldn't read the thumbnail image")
            writeClip(picture, showW, showH, clipPath)

            val clip = AVURLAsset.URLAssetWithURL(NSURL.fileURLWithPath(clipPath), null)
            val clipTrack = clip.tracksWithMediaType(AVMediaTypeVideo ?: "vide").firstOrNull() as? AVAssetTrack
                ?: error("Couldn't make the thumbnail clip")

            val zero = CMTimeMake(0, 600)
            val lead = CMTimeMake(THUMBNAIL_SECONDS.toLong(), 1)
            val composition = AVMutableComposition.composition()
            val leadVideo = composition.addMutableTrackWithMediaType(AVMediaTypeVideo ?: "vide", kCMPersistentTrackID_Invalid)
                ?: error("Couldn't set up the export")
            val mainVideo = composition.addMutableTrackWithMediaType(AVMediaTypeVideo ?: "vide", kCMPersistentTrackID_Invalid)
                ?: error("Couldn't set up the export")
            if (!leadVideo.insertTimeRange(CMTimeRangeMake(zero, lead), ofTrack = clipTrack, atTime = zero, error = null)) {
                error("Couldn't add the thumbnail")
            }
            if (!mainVideo.insertTimeRange(CMTimeRangeMake(zero, asset.duration), ofTrack = videoTrack, atTime = lead, error = null)) {
                error("Couldn't add the video")
            }
            if (audioTrack != null) {
                composition.addMutableTrackWithMediaType(AVMediaTypeAudio ?: "soun", kCMPersistentTrackID_Invalid)
                    ?.insertTimeRange(CMTimeRangeMake(zero, asset.duration), ofTrack = audioTrack, atTime = lead, error = null)
            }

            // Which picture is on screen when: the clip for the first
            // second (already upright), then the video, turned the way its
            // own note says.
            val leadLayer = AVMutableVideoCompositionLayerInstruction.videoCompositionLayerInstructionWithAssetTrack(leadVideo)
            leadLayer.setTransform(CGAffineTransformMake(1.0, 0.0, 0.0, 1.0, 0.0, 0.0), atTime = zero)
            val leadPart = AVMutableVideoCompositionInstruction.videoCompositionInstruction()
            leadPart.setTimeRange(CMTimeRangeMake(zero, lead))
            leadPart.setLayerInstructions(listOf(leadLayer))

            val mainLayer = AVMutableVideoCompositionLayerInstruction.videoCompositionLayerInstructionWithAssetTrack(mainVideo)
            mainLayer.setTransform(videoTrack.preferredTransform, atTime = zero)
            val mainPart = AVMutableVideoCompositionInstruction.videoCompositionInstruction()
            mainPart.setTimeRange(CMTimeRangeMake(lead, asset.duration))
            mainPart.setLayerInstructions(listOf(mainLayer))

            val fps = videoTrack.nominalFrameRate.toInt().coerceIn(24, 60)
            val layout = AVMutableVideoComposition.videoComposition()
            layout.setRenderSize(CGSizeMake(showW.toDouble(), showH.toDouble()))
            layout.setFrameDuration(CMTimeMake(1, fps))
            layout.setInstructions(listOf(leadPart, mainPart))

            val export = AVAssetExportSession(asset = composition, presetName = AVAssetExportPresetHighestQuality)
            export.setOutputURL(NSURL.fileURLWithPath(outPath))
            export.setOutputFileType(AVFileTypeMPEG4)
            export.setVideoComposition(layout)
            export.setShouldOptimizeForNetworkUse(true)
            export.setTimeRange(CMTimeRangeMake(zero, CMTimeAdd(lead, asset.duration)))
            suspendCancellableCoroutine { cont ->
                cont.invokeOnCancellation { export.cancelExport() }
                export.exportAsynchronouslyWithCompletionHandler {
                    if (!cont.isActive) return@exportAsynchronouslyWithCompletionHandler
                    if (export.status == AVAssetExportSessionStatusCompleted) cont.resume(Unit)
                    else cont.resumeWithException(IOException(export.error?.localizedDescription ?: "The video couldn't be exported"))
                }
            }
            if (!localFileExists(outPath)) error("The video couldn't be exported")
            outPath
        } catch (t: Throwable) {
            deleteLocalFile(outPath)
            throw t
        } finally {
            deleteLocalFile(clipPath)
        }
    }

    private fun even(v: Double): Int = ((v.toInt() / 2) * 2).coerceAtLeast(2)

    /** The picked picture, upright, centre-cropped to [w]:[h] and scaled
     *  to exactly [w]×[h] pixels. */
    private fun fittedPicture(path: String, w: Int, h: Int): UIImage? {
        val bytes = readLocalFile(path) ?: return null
        val image = UIImage.imageWithData(bytes.toNSData()) ?: return null
        val (srcW, srcH) = image.size.useContents { width to height }
        if (srcW <= 0.0 || srcH <= 0.0) return null
        // Scaled to cover the frame; whatever hangs over the edges is cut.
        val scale = maxOf(w / srcW, h / srcH)
        val drawW = srcW * scale
        val drawH = srcH * scale
        UIGraphicsBeginImageContextWithOptions(CGSizeMake(w.toDouble(), h.toDouble()), true, 1.0)
        image.drawInRect(CGRectMake((w - drawW) / 2.0, (h - drawH) / 2.0, drawW, drawH))
        val out = UIGraphicsGetImageFromCurrentImageContext()
        UIGraphicsEndImageContext()
        return out
    }

    /** [picture] as a [THUMBNAIL_SECONDS]-second H.264 clip at [path]. */
    private suspend fun writeClip(picture: UIImage, w: Int, h: Int, path: String) {
        val cgImage = picture.CGImage ?: error("Couldn't read the thumbnail image")
        val writer = AVAssetWriter(uRL = NSURL.fileURLWithPath(path), fileType = AVFileTypeMPEG4, error = null)
        val input = AVAssetWriterInput(
            mediaType = AVMediaTypeVideo ?: "vide",
            outputSettings = mapOf<Any?, Any?>(
                AVVideoCodecKey to AVVideoCodecTypeH264,
                AVVideoWidthKey to w,
                AVVideoHeightKey to h
            )
        )
        input.setExpectsMediaDataInRealTime(false)
        val adaptor = AVAssetWriterInputPixelBufferAdaptor(assetWriterInput = input, sourcePixelBufferAttributes = null)
        if (!writer.canAddInput(input)) error("Couldn't make the thumbnail clip")
        writer.addInput(input)
        if (!writer.startWriting()) error(writer.error?.localizedDescription ?: "Couldn't make the thumbnail clip")
        writer.startSessionAtSourceTime(CMTimeMake(0, CLIP_FPS))

        memScoped {
            val bufferVar = alloc<CVPixelBufferRefVar>()
            if (CVPixelBufferCreate(null, w.toULong(), h.toULong(), kCVPixelFormatType_32BGRA, null, bufferVar.ptr) != kCVReturnSuccess) {
                error("Couldn't make the thumbnail clip")
            }
            val buffer = bufferVar.value ?: error("Couldn't make the thumbnail clip")
            try {
                // The picture, drawn into the frame's own memory.
                CVPixelBufferLockBaseAddress(buffer, 0u)
                val space = CGColorSpaceCreateDeviceRGB()
                val context = CGBitmapContextCreate(
                    CVPixelBufferGetBaseAddress(buffer), w.toULong(), h.toULong(), 8u,
                    CVPixelBufferGetBytesPerRow(buffer), space,
                    CGImageAlphaInfo.kCGImageAlphaNoneSkipFirst.value or kCGBitmapByteOrder32Little
                )
                if (context != null) {
                    CGContextDrawImage(context, CGRectMake(0.0, 0.0, w.toDouble(), h.toDouble()), cgImage)
                    CGContextRelease(context)
                }
                CGColorSpaceRelease(space)
                CVPixelBufferUnlockBaseAddress(buffer, 0u)
                if (context == null) error("Couldn't make the thumbnail clip")

                // The same frame for the whole second.
                val frames = THUMBNAIL_SECONDS * CLIP_FPS
                for (i in 0 until frames) {
                    var waited = 0
                    while (!input.readyForMoreMediaData && waited < 2000) { usleep(2000u); waited++ }
                    if (!adaptor.appendPixelBuffer(buffer, withPresentationTime = CMTimeMake(i.toLong(), CLIP_FPS))) {
                        error(writer.error?.localizedDescription ?: "Couldn't make the thumbnail clip")
                    }
                }
            } finally {
                CVPixelBufferRelease(buffer)
            }
        }
        input.markAsFinished()
        writer.endSessionAtSourceTime(CMTimeMake((THUMBNAIL_SECONDS * CLIP_FPS).toLong(), CLIP_FPS))
        suspendCancellableCoroutine { cont ->
            writer.finishWritingWithCompletionHandler { if (cont.isActive) cont.resume(Unit) }
        }
        if (writer.status != AVAssetWriterStatusCompleted) {
            error(writer.error?.localizedDescription ?: "Couldn't make the thumbnail clip")
        }
    }
}
