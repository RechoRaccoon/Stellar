package com.mediaviewer.platform

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import com.mediaviewer.util.GifInfo
import com.mediaviewer.util.GifVideo

/**
 * Android: an animated GIF → H.264 mp4 (see util/GifVideo for the frames).
 * Each frame goes into the encoder as YUV 4:2:0 through the codec's own
 * input image (so any device's plane layout works) with its exact time.
 */
internal object GifVideoEncoder {
    private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
    private const val TIMEOUT_US = 10_000L

    fun encode(context: android.content.Context, gif: ByteArray): java.io.File {
        val (gw, gh) = GifInfo.size(gif)
        if (gw <= 0 || gh <= 0) error("This GIF can't be read")

        val codec = MediaCodec.createEncoderByType(MIME)
        val out = java.io.File(context.cacheDir, "gif-video-${System.currentTimeMillis()}.mp4")
        var muxer: MediaMuxer? = null
        try {
            val caps = codec.codecInfo.getCapabilitiesForType(MIME).videoCapabilities
            val align = maxOf(2, caps.widthAlignment, caps.heightAlignment)
            var (w, h) = GifVideo.targetSize(gw, gh, align)
            if (!caps.isSizeSupported(w, h)) {
                w = caps.supportedWidths.clamp(w).let { it - it % align }
                h = caps.getSupportedHeightsFor(w).clamp(h).let { it - it % align }
            }
            val format = MediaFormat.createVideoFormat(MIME, w, h).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE, (w.toLong() * h * 30 * 0.2).toInt().coerceIn(1_000_000, 8_000_000))
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val mux = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = mux
            var track = -1
            var started = false
            val info = MediaCodec.BufferInfo()
            var lastPts = -1L

            fun drain(endOfStream: Boolean) {
                var idle = 0
                while (true) {
                    val idx = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                    when {
                        idx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            if (!endOfStream) return
                            if (++idle > 500) error("The video encoder stopped responding")
                        }
                        idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            track = mux.addTrack(codec.outputFormat)
                            mux.start()
                            started = true
                        }
                        idx >= 0 -> {
                            val buf = codec.getOutputBuffer(idx)
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                            if (buf != null && info.size > 0 && started) {
                                buf.position(info.offset)
                                buf.limit(info.offset + info.size)
                                mux.writeSampleData(track, buf, info)
                            }
                            codec.releaseOutputBuffer(idx, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                        }
                    }
                }
            }

            fun inputIndex(): Int {
                var tries = 0
                while (true) {
                    val i = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (i >= 0) return i
                    drain(false)
                    if (++tries > 1000) error("The video encoder stopped responding")
                }
            }

            GifVideo.frames(gif, w, h) { argb, ms ->
                // Times must keep going up.
                val pts = maxOf(ms * 1000L, lastPts + 1000L)
                lastPts = pts
                val i = inputIndex()
                val image = codec.getInputImage(i) ?: error("The video encoder can't take pictures")
                writeYuv(argb, w, h, image)
                codec.queueInputBuffer(i, 0, w * h * 3 / 2, pts, 0)
                drain(false)
            }
            val i = inputIndex()
            codec.queueInputBuffer(i, 0, 0, lastPts + 1000L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            drain(true)
            if (!started) error("The video encoder made nothing")
            mux.stop()
            return out
        } catch (t: Throwable) {
            out.delete()
            throw t
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { muxer?.release() }
        }
    }

    /** ARGB → the encoder's YUV 4:2:0 planes (BT.601, video range). */
    private fun writeYuv(argb: IntArray, w: Int, h: Int, image: android.media.Image) {
        val planes = image.planes
        val yBuf = planes[0].buffer; val yRow = planes[0].rowStride; val yPix = planes[0].pixelStride
        val uBuf = planes[1].buffer; val uRow = planes[1].rowStride; val uPix = planes[1].pixelStride
        val vBuf = planes[2].buffer; val vRow = planes[2].rowStride; val vPix = planes[2].pixelStride
        for (y in 0 until h) {
            val rowBase = y * w
            for (x in 0 until w) {
                val c = argb[rowBase + x]
                val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
                yBuf.put(y * yRow + x * yPix, (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).coerceIn(0, 255).toByte())
            }
        }
        for (y in 0 until h / 2) {
            for (x in 0 until w / 2) {
                var r = 0; var g = 0; var b = 0
                for (dy in 0..1) for (dx in 0..1) {
                    val c = argb[(y * 2 + dy) * w + x * 2 + dx]
                    r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF
                }
                r /= 4; g /= 4; b /= 4
                val u = (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).coerceIn(0, 255)
                val v = (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).coerceIn(0, 255)
                uBuf.put(y * uRow + x * uPix, u.toByte())
                vBuf.put(y * vRow + x * vPix, v.toByte())
            }
        }
    }
}
