package com.mediaviewer.stream

import com.mediaviewer.platform.Log
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.readValue
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.CFArrayGetCount
import platform.CoreFoundation.CFArrayGetValueAtIndex
import platform.CoreFoundation.CFDictionaryGetValue
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.kCFBooleanFalse
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreMedia.CMBlockBufferCopyDataBytes
import platform.CoreMedia.CMBlockBufferGetDataLength
import platform.CoreMedia.CMSampleBufferGetDataBuffer
import platform.CoreMedia.CMSampleBufferGetFormatDescription
import platform.CoreMedia.CMSampleBufferGetPresentationTimeStamp
import platform.CoreMedia.CMSampleBufferGetSampleAttachmentsArray
import platform.CoreMedia.CMSampleBufferRef
import platform.CoreMedia.CMTimeMake
import platform.CoreMedia.CMVideoFormatDescriptionGetH264ParameterSetAtIndex
import platform.CoreMedia.kCMSampleAttachmentKey_NotSync
import platform.CoreMedia.kCMTimeInvalid
import platform.CoreMedia.kCMVideoCodecType_H264
import platform.CoreVideo.CVPixelBufferRef
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSNumber
import platform.VideoToolbox.VTCompressionSessionCompleteFrames
import platform.VideoToolbox.VTCompressionSessionCreate
import platform.VideoToolbox.VTCompressionSessionEncodeFrameWithOutputHandler
import platform.VideoToolbox.VTCompressionSessionInvalidate
import platform.VideoToolbox.VTCompressionSessionPrepareToEncodeFrames
import platform.VideoToolbox.VTCompressionSessionRefVar
import platform.VideoToolbox.VTSessionSetProperty
import platform.VideoToolbox.kVTCompressionPropertyKey_AllowFrameReordering
import platform.VideoToolbox.kVTCompressionPropertyKey_AverageBitRate
import platform.VideoToolbox.kVTCompressionPropertyKey_ExpectedFrameRate
import platform.VideoToolbox.kVTCompressionPropertyKey_MaxKeyFrameIntervalDuration
import platform.VideoToolbox.kVTCompressionPropertyKey_ProfileLevel
import platform.VideoToolbox.kVTCompressionPropertyKey_RealTime
import platform.VideoToolbox.kVTEncodeFrameOptionKey_ForceKeyFrame
import platform.VideoToolbox.kVTProfileLevel_H264_Main_AutoLevel
import kotlin.concurrent.Volatile

/**
 * The iPhone's hardware H.264 encoder (VideoToolbox), set up the way live
 * ingest servers expect — the same choices as Android's MediaCodec setup:
 * real-time, no B-frames, a keyframe every two seconds, a steady bitrate
 * that can be turned down while streaming when the upload falls behind.
 *
 * Frames come out exactly as RTMP carries them (each NAL unit preceded by
 * its 4-byte length), so they go to the publisher untouched.
 */
@OptIn(ExperimentalForeignApi::class)
class IosH264Encoder(
    val width: Int,
    val height: Int,
    private val fps: Int,
    bitrate: Int,
    /** The stream's SPS and PPS, whenever a keyframe carries them. */
    private val onConfig: (sps: ByteArray, pps: ByteArray) -> Unit,
    private val onFrame: (avcc: ByteArray, timestampMs: Long, keyframe: Boolean) -> Unit
) {
    private var session: platform.VideoToolbox.VTCompressionSessionRef? = null
    @Volatile private var forceKeyframe = true
    @Volatile private var closed = false

    /** Null when the encoder is ready, else why not. */
    val error: String?

    init {
        error = memScoped {
            val out = alloc<VTCompressionSessionRefVar>()
            val status = VTCompressionSessionCreate(null, width, height, kCMVideoCodecType_H264, null, null, null, null, null, out.ptr)
            val s = out.value
            if (status != 0 || s == null) return@memScoped "This iPhone's video encoder couldn't start ($status)"
            session = s
            VTSessionSetProperty(s, kVTCompressionPropertyKey_RealTime, kCFBooleanTrue)
            VTSessionSetProperty(s, kVTCompressionPropertyKey_AllowFrameReordering, kCFBooleanFalse)
            VTSessionSetProperty(s, kVTCompressionPropertyKey_ProfileLevel, kVTProfileLevel_H264_Main_AutoLevel)
            number(s, kVTCompressionPropertyKey_ExpectedFrameRate, fps)
            number(s, kVTCompressionPropertyKey_MaxKeyFrameIntervalDuration, 2)
            number(s, kVTCompressionPropertyKey_AverageBitRate, bitrate)
            VTCompressionSessionPrepareToEncodeFrames(s)
            null
        }
    }

    private fun number(s: platform.VideoToolbox.VTCompressionSessionRef, key: platform.CoreFoundation.CFStringRef?, value: Int) {
        val n: CFTypeRef? = CFBridgingRetain(NSNumber(int = value))
        VTSessionSetProperty(s, key, n)
        if (n != null) CFRelease(n)
    }

    /** Changes the bitrate while streaming (upload falling behind / recovered). */
    fun setBitrate(bitrate: Int) {
        val s = session ?: return
        if (!closed) number(s, kVTCompressionPropertyKey_AverageBitRate, bitrate)
    }

    /** The next frame will be a keyframe (after dropped frames or a reconnect). */
    fun requestKeyframe() { forceKeyframe = true }

    /** Hands one picture to the encoder; the result arrives through [onFrame]. */
    fun encode(buffer: CVPixelBufferRef, timestampMs: Long) {
        val s = session ?: return
        if (closed) return
        val key = forceKeyframe
        forceKeyframe = false
        var options: CFTypeRef? = null
        if (key) {
            val name = platform.Foundation.CFBridgingRelease(platform.CoreFoundation.CFRetain(kVTEncodeFrameOptionKey_ForceKeyFrame)) as? platform.Foundation.NSString
            if (name != null) options = CFBridgingRetain(mapOf<Any?, Any?>(name to true))
        }
        @Suppress("UNCHECKED_CAST")
        VTCompressionSessionEncodeFrameWithOutputHandler(
            s, buffer, CMTimeMake(timestampMs, 1000), kCMTimeInvalid.readValue(), options as CFDictionaryRef?, null
        ) { status, _, sample ->
            if (status == 0 && sample != null && !closed) runCatching { deliver(sample) }.onFailure { Log.e("IosH264Encoder", "Reading a frame failed", it) }
        }
        if (options != null) CFRelease(options)
    }

    private fun deliver(sample: CMSampleBufferRef) {
        // A frame is a keyframe unless it's marked "not sync".
        var keyframe = true
        val attachments = CMSampleBufferGetSampleAttachmentsArray(sample, false)
        if (attachments != null && CFArrayGetCount(attachments) > 0) {
            val dict: CFDictionaryRef? = CFArrayGetValueAtIndex(attachments, 0)?.reinterpret()
            val notSync = if (dict != null) CFDictionaryGetValue(dict, kCMSampleAttachmentKey_NotSync) else null
            if (notSync != null && notSync == kCFBooleanTrue) keyframe = false
        }
        if (keyframe) {
            val format = CMSampleBufferGetFormatDescription(sample)
            if (format != null) {
                val sps = parameterSet(format, 0)
                val pps = parameterSet(format, 1)
                if (sps != null && pps != null) onConfig(sps, pps)
            }
        }
        val block = CMSampleBufferGetDataBuffer(sample) ?: return
        val length = CMBlockBufferGetDataLength(block).toInt()
        if (length <= 0) return
        val bytes = ByteArray(length)
        val ok = bytes.usePinned { pinned -> CMBlockBufferCopyDataBytes(block, 0u, length.toULong(), pinned.addressOf(0)) == 0 }
        if (!ok) return
        val ms = CMSampleBufferGetPresentationTimeStamp(sample).useContents { if (timescale != 0) value * 1000L / timescale else 0L }
        onFrame(bytes, ms, keyframe)
    }

    private fun parameterSet(format: platform.CoreMedia.CMFormatDescriptionRef, index: Int): ByteArray? = memScoped {
        val pointer = alloc<CPointerVar<UByteVar>>()
        val size = alloc<ULongVar>()
        val status = CMVideoFormatDescriptionGetH264ParameterSetAtIndex(format, index.toULong(), pointer.ptr, size.ptr, null, null)
        val p = pointer.value
        if (status != 0 || p == null || size.value == 0uL) null else p.readBytes(size.value.toInt())
    }

    fun close() {
        if (closed) return
        closed = true
        val s = session ?: return
        session = null
        VTCompressionSessionCompleteFrames(s, kCMTimeInvalid.readValue())
        VTCompressionSessionInvalidate(s)
        CFRelease(s)
    }
}
