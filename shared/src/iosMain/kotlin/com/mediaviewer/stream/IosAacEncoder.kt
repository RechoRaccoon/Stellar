package com.mediaviewer.stream

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.get
import kotlinx.cinterop.plus
import kotlinx.cinterop.pointed
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.value
import platform.AVFAudio.AVAudioBuffer
import platform.AVFAudio.AVAudioCompressedBuffer
import platform.AVFAudio.AVAudioConverter
import platform.AVFAudio.AVAudioConverterInputStatus_HaveData
import platform.AVFAudio.AVAudioConverterInputStatus_NoDataNow
import platform.AVFAudio.AVAudioConverterOutputStatus_HaveData
import platform.AVFAudio.AVAudioFormat
import platform.AVFAudio.AVAudioPCMBuffer
import platform.AVFAudio.AVFormatIDKey
import platform.AVFAudio.AVNumberOfChannelsKey
import platform.AVFAudio.AVSampleRateKey
import platform.AVFAudio.maximumOutputPacketSize
import platform.AVFAudio.setBitRate
import platform.CoreAudioTypes.kAudioFormatMPEG4AAC

/**
 * Sound as AAC frames: Apple's converter turns the audio rig's plain
 * samples (see [IosAudioRig]) into AAC-LC, mono, 96 kbit/s — what live
 * ingest servers and mp4 files both take.
 */
@OptIn(ExperimentalForeignApi::class)
class IosAacEncoder(
    /** The samples coming in. */
    from: AVAudioFormat,
    /** One raw AAC frame (1024 samples) and when it belongs, counted in
     *  milliseconds from the first frame. */
    private val onFrame: (frame: ByteArray, timestampMs: Long) -> Unit
) {
    val sampleRate: Int = from.sampleRate.toInt()
    private val outFormat = AVAudioFormat(settings = mapOf<Any?, Any?>(
        AVFormatIDKey to kAudioFormatMPEG4AAC.toInt(),
        AVSampleRateKey to from.sampleRate,
        AVNumberOfChannelsKey to 1
    ))
    private val converter = AVAudioConverter(fromFormat = from, toFormat = outFormat).also { it.setBitRate(96_000) }
    private var frames = 0L

    /** The two bytes that tell a player what this AAC is (AAC-LC, this
     *  rate, one channel — an "AudioSpecificConfig"). */
    val audioSpecificConfig: ByteArray = run {
        val index = rateIndex(sampleRate)
        byteArrayOf(((2 shl 3) or (index ushr 1)).toByte(), (((index and 1) shl 7) or (1 shl 3)).toByte())
    }

    fun encode(pcm: AVAudioPCMBuffer) {
        var given = false
        // The converter asks for input until it has had this buffer, then
        // hands back as many whole AAC frames as that made.
        while (true) {
            val out = AVAudioCompressedBuffer(format = outFormat, packetCapacity = 16u, maximumPacketSize = converter.maximumOutputPacketSize)
            val status = converter.convertToBuffer(out, error = null) { _, inputStatus ->
                if (given) {
                    inputStatus?.pointed?.value = AVAudioConverterInputStatus_NoDataNow
                    null
                } else {
                    given = true
                    inputStatus?.pointed?.value = AVAudioConverterInputStatus_HaveData
                    pcm as AVAudioBuffer
                }
            }
            val count = out.packetCount.toInt()
            val descriptions = out.packetDescriptions
            if (count > 0 && descriptions != null) {
                val base = out.data?.reinterpret<kotlinx.cinterop.ByteVar>() ?: break
                for (i in 0 until count) {
                    val d = descriptions[i]
                    val size = d.mDataByteSize.toInt()
                    if (size <= 0) continue
                    val bytes = (base + d.mStartOffset)?.readBytes(size) ?: continue
                    onFrame(bytes, frames * 1024L * 1000L / sampleRate)
                    frames++
                }
            }
            if (status != AVAudioConverterOutputStatus_HaveData || count == 0) break
        }
    }

    companion object {
        private val RATES = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)
        fun rateIndex(rate: Int): Int = RATES.indexOf(rate).let { if (it < 0) 4 else it }

        /**
         * [frame] with the 7-byte header that makes a run of AAC frames a
         * playable ".aac" file (ADTS): AAC-LC, [sampleRate], one channel.
         */
        fun adts(frame: ByteArray, sampleRate: Int): ByteArray {
            val length = frame.size + 7
            val index = rateIndex(sampleRate)
            val out = ByteArray(length)
            out[0] = 0xFF.toByte()
            out[1] = 0xF1.toByte()
            out[2] = ((1 shl 6) or (index shl 2)).toByte()
            out[3] = ((1 shl 6) or (length shr 11)).toByte()
            out[4] = ((length shr 3) and 0xFF).toByte()
            out[5] = (((length and 7) shl 5) or 0x1F).toByte()
            out[6] = 0xFC.toByte()
            frame.copyInto(out, 7)
            return out
        }
    }
}
