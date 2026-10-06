package com.mediaviewer.stream

import com.mediaviewer.platform.IosPaths
import com.mediaviewer.platform.currentTimeMillis
import com.mediaviewer.platform.deleteLocalFile
import com.mediaviewer.platform.localFileExists
import com.mediaviewer.platform.nanoTime
import com.mediaviewer.platform.writeLocalFile
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.AVFoundation.AVAssetExportPresetHighestQuality
import platform.AVFoundation.AVAssetExportPresetPassthrough
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
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.AVURLAssetPreferPreciseDurationAndTimingKey
import platform.AVFoundation.AVVideoAverageBitRateKey
import platform.AVFoundation.AVVideoCodecKey
import platform.AVFoundation.AVVideoCodecTypeH264
import platform.AVFoundation.AVVideoCompressionPropertiesKey
import platform.AVFoundation.AVVideoHeightKey
import platform.AVFoundation.AVVideoWidthKey
import platform.AVFoundation.addMutableTrackWithMediaType
import platform.AVFoundation.duration
import platform.AVFoundation.tracksWithMediaType
import platform.CoreMedia.CMTimeCompare
import platform.CoreMedia.CMTimeMake
import platform.CoreMedia.CMTimeRangeMake
import platform.CoreMedia.kCMPersistentTrackID_Invalid
import platform.CoreVideo.CVPixelBufferRef
import platform.Foundation.NSURL
import kotlin.concurrent.Volatile
import kotlin.coroutines.resume

/**
 * Makes an mp4 out of frames handed to it one at a time, with sound.
 *
 * The pictures (VRM mode's stage, or the Camera page's camera with
 * whatever is drawn over it) go into the file as they arrive, about 30 a
 * second. The sound — your voice at the chosen pitch, plus soundboard
 * sounds — comes off the audio rig as AAC, is kept beside the picture and
 * the two are joined when the recording stops.
 */
@OptIn(ExperimentalForeignApi::class)
class IosFrameRecorder {
    private val lock = reentrantLock()
    private var writer: AVAssetWriter? = null
    private var input: AVAssetWriterInput? = null
    private var adaptor: AVAssetWriterInputPixelBufferAdaptor? = null
    private var audio: IosAudioRig? = null
    /** The sound so far, as an ".aac" file's worth of frames. */
    private val audioFrames = ArrayList<ByteArray>()
    private var audioRate = 44100
    /** How long after the picture began the sound's first frame arrived. */
    private var audioStartMs = -1L
    private var videoPath = ""
    private var audioPath = ""
    private var startNanos = 0L
    private var lastFrameMs = -1000L
    private var frames = 0
    @Volatile var isRecording = false
        private set

    /** Voice pitch in semitones; follows the slider while recording. */
    @Volatile var pitchSemitones = 0f
        set(value) { field = value; audio?.pitchSemitones = value }

    /** Starts a [width]×[height] recording. Returns null, or why it couldn't start. */
    fun start(width: Int, height: Int, withMic: Boolean, bitrate: Int = 6_000_000): String? {
        if (isRecording) return null
        val stamp = currentTimeMillis()
        videoPath = IosPaths.cacheDir() + "/capture-video-$stamp.mp4"
        audioPath = IosPaths.cacheDir() + "/capture-audio-$stamp.aac"
        deleteLocalFile(videoPath); deleteLocalFile(audioPath)

        val w = runCatching { AVAssetWriter(uRL = NSURL.fileURLWithPath(videoPath), fileType = AVFileTypeMPEG4, error = null) }.getOrNull()
            ?: return "Couldn't start the recording"
        val i = AVAssetWriterInput(
            mediaType = AVMediaTypeVideo ?: "vide",
            outputSettings = mapOf<Any?, Any?>(
                AVVideoCodecKey to AVVideoCodecTypeH264,
                AVVideoWidthKey to width,
                AVVideoHeightKey to height,
                AVVideoCompressionPropertiesKey to mapOf<Any?, Any?>(AVVideoAverageBitRateKey to bitrate)
            )
        )
        i.setExpectsMediaDataInRealTime(true)
        val a = AVAssetWriterInputPixelBufferAdaptor(assetWriterInput = i, sourcePixelBufferAttributes = null)
        if (!w.canAddInput(i)) return "Couldn't start the recording"
        w.addInput(i)
        if (!w.startWriting()) return w.error?.localizedDescription ?: "Couldn't start the recording"
        w.startSessionAtSourceTime(CMTimeMake(0, 1000))
        lock.withLock {
            writer = w; input = i; adaptor = a
            startNanos = nanoTime(); lastFrameMs = -1000L; frames = 0
            audioFrames.clear(); audioStartMs = -1L
            isRecording = true
        }
        // The sound desk runs even with the mic off or refused: it still
        // carries the soundboard.
        val rig = IosAudioRig { frame, _ ->
            lock.withLock {
                if (isRecording) {
                    if (audioStartMs < 0) audioStartMs = (nanoTime() - startNanos) / 1_000_000L
                    audioFrames.add(IosAacEncoder.adts(frame, audioRate))
                }
            }
        }
        rig.pitchSemitones = pitchSemitones
        if (rig.start(withMic)) { audioRate = rig.sampleRate; audio = rig }
        return null
    }

    /** Is it time for another frame? (About 30 a second, and never faster
     *  than the file can take.) Asked before going to the trouble of
     *  making one. */
    fun wantsFrame(): Boolean {
        if (!isRecording) return false
        val ms = (nanoTime() - startNanos) / 1_000_000L
        return ms - lastFrameMs >= 30L && input?.readyForMoreMediaData == true
    }

    /** One frame. The caller keeps ownership of [buffer]. Any thread. */
    fun append(buffer: CVPixelBufferRef) {
        lock.withLock {
            if (!isRecording) return
            val a = adaptor ?: return
            if (input?.readyForMoreMediaData != true) return
            val ms = (nanoTime() - startNanos) / 1_000_000L
            if (ms <= lastFrameMs) return
            if (a.appendPixelBuffer(buffer, withPresentationTime = CMTimeMake(ms, 1000))) { lastFrameMs = ms; frames++ }
        }
    }

    /**
     * Stops and finishes the file. Returns its path, or null if nothing
     * usable was recorded.
     */
    suspend fun stop(): String? {
        if (!isRecording) return null
        var w: AVAssetWriter? = null
        var count = 0
        var endMs = 0L
        var sound: ByteArray? = null
        var soundStart = 0L
        lock.withLock {
            isRecording = false
            w = writer; count = frames; endMs = lastFrameMs
            input?.markAsFinished()
            writer = null; input = null; adaptor = null
            if (audioFrames.isNotEmpty()) {
                val all = ByteArray(audioFrames.sumOf { it.size })
                var at = 0
                for (f in audioFrames) { f.copyInto(all, at); at += f.size }
                sound = all
            }
            soundStart = audioStartMs.coerceAtLeast(0L)
            audioFrames.clear()
        }
        val finished = w
        val rig = audio
        audio = null
        runCatching { rig?.stop() }
        if (finished == null) return null
        if (count == 0) {
            runCatching { finished.cancelWriting() }
            deleteLocalFile(videoPath)
            return null
        }
        // (A thirtieth of a second past the last frame, so it isn't cut short.)
        finished.endSessionAtSourceTime(CMTimeMake(endMs + 33, 1000))
        suspendCancellableCoroutine { cont -> finished.finishWritingWithCompletionHandler { if (cont.isActive) cont.resume(Unit) } }
        if (finished.status != AVAssetWriterStatusCompleted || !localFileExists(videoPath)) {
            deleteLocalFile(videoPath)
            return null
        }
        val soundBytes = sound
        if (soundBytes == null || !writeLocalFile(audioPath, soundBytes)) return videoPath
        val joined = runCatching { join(videoPath, audioPath, soundStart) }.getOrNull()
        deleteLocalFile(audioPath)
        if (joined == null) return videoPath
        deleteLocalFile(videoPath)
        return joined
    }

    /** The picture and the sound in one file; null if they can't be joined
     *  (the picture alone is used then). */
    private suspend fun join(video: String, sound: String, soundStartsAtMs: Long): String? {
        val videoAsset = AVURLAsset.URLAssetWithURL(NSURL.fileURLWithPath(video), null)
        // (A bare .aac file doesn't say how long it is; this has it measured.)
        val soundAsset = AVURLAsset.URLAssetWithURL(
            NSURL.fileURLWithPath(sound), mapOf<Any?, Any?>(AVURLAssetPreferPreciseDurationAndTimingKey to true)
        )
        val videoTrack = videoAsset.tracksWithMediaType(AVMediaTypeVideo ?: "vide").firstOrNull() as? AVAssetTrack ?: return null
        val soundTrack = soundAsset.tracksWithMediaType(AVMediaTypeAudio ?: "soun").firstOrNull() as? AVAssetTrack ?: return null
        val zero = CMTimeMake(0, 1000)
        val composition = AVMutableComposition.composition()
        val v = composition.addMutableTrackWithMediaType(AVMediaTypeVideo ?: "vide", kCMPersistentTrackID_Invalid) ?: return null
        val s = composition.addMutableTrackWithMediaType(AVMediaTypeAudio ?: "soun", kCMPersistentTrackID_Invalid) ?: return null
        if (!v.insertTimeRange(CMTimeRangeMake(zero, videoAsset.duration), ofTrack = videoTrack, atTime = zero, error = null)) return null
        // The sound is trimmed to the picture's length, and starts as late
        // as it actually did.
        val soundLength = if (CMTimeCompare(soundAsset.duration, videoAsset.duration) > 0) videoAsset.duration else soundAsset.duration
        if (!s.insertTimeRange(CMTimeRangeMake(zero, soundLength), ofTrack = soundTrack, atTime = CMTimeMake(soundStartsAtMs.coerceAtMost(2000L), 1000), error = null)) return null
        val out = video.removeSuffix(".mp4") + "-sound.mp4"
        // As they are when that's possible; re-encoded otherwise.
        for (preset in listOf(AVAssetExportPresetPassthrough, AVAssetExportPresetHighestQuality)) {
            deleteLocalFile(out)
            val export = AVAssetExportSession(asset = composition, presetName = preset)
            export.setOutputURL(NSURL.fileURLWithPath(out))
            export.setOutputFileType(AVFileTypeMPEG4)
            export.setShouldOptimizeForNetworkUse(true)
            val ok: Boolean = suspendCancellableCoroutine { cont ->
                export.exportAsynchronouslyWithCompletionHandler {
                    if (cont.isActive) cont.resume(export.status == AVAssetExportSessionStatusCompleted)
                }
            }
            if (ok && localFileExists(out)) return out
        }
        deleteLocalFile(out)
        return null
    }

    companion object {
        /** Asks for the microphone (iOS shows its prompt the first time). */
        suspend fun micAllowed(): Boolean = suspendCancellableCoroutine { cont ->
            runCatching {
                platform.AVFAudio.AVAudioSession.sharedInstance().requestRecordPermission { granted -> if (cont.isActive) cont.resume(granted) }
            }.onFailure { if (cont.isActive) cont.resume(false) }
        }
    }
}
