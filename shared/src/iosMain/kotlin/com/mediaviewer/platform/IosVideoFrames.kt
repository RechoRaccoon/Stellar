package com.mediaviewer.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.readValue
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import platform.AVFoundation.AVAssetImageGenerator
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.duration
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextDrawImage
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageGetHeight
import platform.CoreGraphics.CGImageGetWidth
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMakeWithSeconds
import platform.CoreMedia.kCMTimeZero
import platform.Foundation.NSURL

/** The start of a video as small upright frames (MediaBridge.videoFrames),
 *  read with Apple's own frame grabber. */
@OptIn(ExperimentalForeignApi::class)
internal object IosVideoFrames {
    suspend fun read(path: String, maxSide: Int, fps: Int, maxSeconds: Double): VideoFrames = withContext(Dispatchers.IO) {
        val asset = AVURLAsset.URLAssetWithURL(NSURL.fileURLWithPath(path), null)
        val generator = AVAssetImageGenerator(asset = asset)
        generator.appliesPreferredTrackTransform = true
        generator.maximumSize = CGSizeMake(maxSide.toDouble(), maxSide.toDouble())
        generator.requestedTimeToleranceBefore = kCMTimeZero.readValue()
        generator.requestedTimeToleranceAfter = kCMTimeZero.readValue()
        val seconds = CMTimeGetSeconds(asset.duration).takeIf { it.isFinite() && it > 0.0 } ?: 0.0
        val end = minOf(seconds, maxSeconds)
        val step = 1.0 / fps.coerceAtLeast(1)
        val frames = ArrayList<IntArray>()
        var fw = 0; var fh = 0
        var t = 0.0
        while (t < end || frames.isEmpty()) {
            val image = generator.copyCGImageAtTime(CMTimeMakeWithSeconds(t, 600), actualTime = null, error = null) ?: break
            try {
                val w = CGImageGetWidth(image).toInt()
                val h = CGImageGetHeight(image).toInt()
                if (w <= 0 || h <= 0) break
                if (fw == 0) { val (a, b) = com.mediaviewer.util.EmojiMedia.fit(w, h, maxSide); fw = a; fh = b }
                val rgba = ByteArray(fw * fh * 4)
                rgba.usePinned { pin ->
                    val space = CGColorSpaceCreateDeviceRGB()
                    val ctx = CGBitmapContextCreate(
                        pin.addressOf(0), fw.toULong(), fh.toULong(), 8u, (fw * 4).toULong(), space,
                        CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value
                    )
                    if (ctx != null) {
                        CGContextDrawImage(ctx, CGRectMake(0.0, 0.0, fw.toDouble(), fh.toDouble()), image)
                        CGContextRelease(ctx)
                    }
                    CGColorSpaceRelease(space)
                }
                frames += IntArray(fw * fh) { i ->
                    val r = rgba[i * 4].toInt() and 0xFF
                    val g = rgba[i * 4 + 1].toInt() and 0xFF
                    val b = rgba[i * 4 + 2].toInt() and 0xFF
                    (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            } finally {
                CGImageRelease(image)
            }
            t += step
            if (t >= end) break
        }
        if (frames.isEmpty()) error("Couldn't read the video")
        VideoFrames(fw, fh, frames, (1000 / fps.coerceAtLeast(1)))
    }
}
