package com.mediaviewer.platform

import com.mediaviewer.util.GifInfo
import com.mediaviewer.util.GifVideo
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.AVFoundation.AVAssetWriter
import platform.AVFoundation.AVAssetWriterInput
import platform.AVFoundation.AVAssetWriterInputPixelBufferAdaptor
import platform.AVFoundation.AVAssetWriterStatusCompleted
import platform.AVFoundation.AVFileTypeMPEG4
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVVideoAverageBitRateKey
import platform.AVFoundation.AVVideoCodecKey
import platform.AVFoundation.AVVideoCodecTypeH264
import platform.AVFoundation.AVVideoCompressionPropertiesKey
import platform.AVFoundation.AVVideoHeightKey
import platform.AVFoundation.AVVideoWidthKey
import platform.CoreMedia.CMTimeMake
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
import platform.posix.usleep
import kotlin.coroutines.resume

/** iOS: an animated GIF → H.264 mp4 with AVAssetWriter (frames from
 *  util/GifVideo, each with its exact time). */
@OptIn(ExperimentalForeignApi::class)
internal object IosGifVideo {
    suspend fun encode(gif: ByteArray): String = withContext(Dispatchers.IO) {
        val (gw, gh) = GifInfo.size(gif)
        if (gw <= 0 || gh <= 0) error("This GIF can't be read")
        val (w, h) = GifVideo.targetSize(gw, gh, 2)
        val path = IosPaths.cacheDir() + "/gif-video-${currentTimeMillis()}.mp4"
        deleteLocalFile(path)
        try {
            val writer = AVAssetWriter(uRL = NSURL.fileURLWithPath(path), fileType = AVFileTypeMPEG4, error = null)
            val input = AVAssetWriterInput(
                mediaType = AVMediaTypeVideo ?: "vide",
                outputSettings = mapOf<Any?, Any?>(
                    AVVideoCodecKey to AVVideoCodecTypeH264,
                    AVVideoWidthKey to w,
                    AVVideoHeightKey to h,
                    AVVideoCompressionPropertiesKey to mapOf<Any?, Any?>(
                        AVVideoAverageBitRateKey to (w.toLong() * h * 30 / 5).coerceIn(1_000_000L, 8_000_000L)
                    )
                )
            )
            input.setExpectsMediaDataInRealTime(false)
            val adaptor = AVAssetWriterInputPixelBufferAdaptor(assetWriterInput = input, sourcePixelBufferAttributes = null)
            if (!writer.canAddInput(input)) error("Couldn't make the video")
            writer.addInput(input)
            if (!writer.startWriting()) error(writer.error?.localizedDescription ?: "Couldn't make the video")
            writer.startSessionAtSourceTime(CMTimeMake(0, 1000))

            var lastMs = -1L
            val end = GifVideo.frames(gif, w, h) { argb, ms ->
                val t = maxOf(ms, lastMs + 1)
                lastMs = t
                memScoped {
                    val bufferVar = alloc<CVPixelBufferRefVar>()
                    if (CVPixelBufferCreate(null, w.toULong(), h.toULong(), kCVPixelFormatType_32BGRA, null, bufferVar.ptr) != kCVReturnSuccess) {
                        error("Couldn't make the video")
                    }
                    val buffer = bufferVar.value ?: error("Couldn't make the video")
                    try {
                        CVPixelBufferLockBaseAddress(buffer, 0u)
                        val base = CVPixelBufferGetBaseAddress(buffer)?.reinterpret<UIntVar>()
                        val rowWords = (CVPixelBufferGetBytesPerRow(buffer) / 4u).toInt()
                        if (base != null) {
                            // 32BGRA in memory = 0xAARRGGBB as a little-endian word.
                            for (y in 0 until h) {
                                val row = y * rowWords
                                val src = y * w
                                for (x in 0 until w) base[row + x] = argb[src + x].toUInt()
                            }
                        }
                        CVPixelBufferUnlockBaseAddress(buffer, 0u)
                        if (base == null) error("Couldn't make the video")
                        var waited = 0
                        while (!input.readyForMoreMediaData && waited < 5000) { usleep(2000u); waited++ }
                        if (!adaptor.appendPixelBuffer(buffer, withPresentationTime = CMTimeMake(t, 1000))) {
                            error(writer.error?.localizedDescription ?: "Couldn't make the video")
                        }
                    } finally {
                        CVPixelBufferRelease(buffer)
                    }
                }
            }
            input.markAsFinished()
            writer.endSessionAtSourceTime(CMTimeMake(maxOf(end, lastMs) + 1, 1000))
            suspendCancellableCoroutine { cont ->
                writer.finishWritingWithCompletionHandler { if (cont.isActive) cont.resume(Unit) }
            }
            if (writer.status != AVAssetWriterStatusCompleted || !localFileExists(path)) {
                error(writer.error?.localizedDescription ?: "Couldn't make the video")
            }
            path
        } catch (t: Throwable) {
            deleteLocalFile(path)
            throw t
        }
    }
}
