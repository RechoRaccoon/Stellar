package com.mediaviewer.stream

import com.mediaviewer.platform.Log
import com.mediaviewer.platform.currentTimeMillis
import com.mediaviewer.platform.nanoTime
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreVideo.CVPixelBufferRef
import kotlin.concurrent.Volatile

/** The same three presets as Android. Sizes are for an upright phone; held
 *  sideways, width and height swap. */
enum class StreamQuality(val label: String, val shortSide: Int, val fps: Int, val bitrate: Int) {
    LOW("432p", 432, 24, 1_000_000),
    MEDIUM("576p", 576, 30, 2_000_000),
    HIGH("720p", 720, 30, 3_500_000)
}

/** Whatever supplies a live stream's pictures: VRM mode's stage, or the
 *  Camera page's camera. */
interface LiveFeeder {
    /** The frame size for a stream [shortSide] pixels across its shorter
     *  side, in this feeder's own shape; null = nothing to show yet. */
    fun frameSize(shortSide: Int): Pair<Int, Int>?
    /** Start handing frames to [streamer] ([IosLiveStreamer.wantsFrame],
     *  then [IosLiveStreamer.submit]). Called on a background thread. */
    fun begin(width: Int, height: Int, streamer: IosLiveStreamer)
    fun end()
}

/**
 * Going live on iOS: the pictures a [LiveFeeder] supplies (the avatar as
 * it's drawn on stage, or the camera),
 * encoded by the iPhone's hardware H.264 encoder, your voice as AAC, pushed
 * over RTMP or RTMPS by the same publisher code as Android's.
 *
 * Like Android's LiveStreamer: when the upload can't keep up the bitrate
 * steps down (to 40% of the target at most) and climbs back once it clears;
 * a dropped connection is retried three times (2 / 4 / 8 seconds) without
 * stopping the encoders, then gives up cleanly.
 */
@OptIn(ExperimentalForeignApi::class)
class IosLiveStreamer(
    private val feeder: LiveFeeder,
    /** Called on a background thread whenever the state changes. */
    private val onState: (State, String?) -> Unit
) {
    enum class State { IDLE, CONNECTING, LIVE, RECONNECTING, ENDED, FAILED }

    @Volatile var state = State.IDLE
        private set
    @Volatile var micMuted = false
        set(value) { field = value; audio?.muted = value }
    /** Voice pitch in semitones (0 = natural); follows the slider live. */
    @Volatile var pitchSemitones = 0f
        set(value) { field = value; audio?.pitchSemitones = value }
    /** Rough upload rate for the readout, bits per second. */
    @Volatile var measuredBps = 0L
        private set
    val droppedFrames: Long get() = publisher?.droppedFrames ?: 0L

    private val lock = reentrantLock()
    @Volatile private var publisher: RtmpPublisher? = null
    private var encoder: IosH264Encoder? = null
    private var audio: IosAudioRig? = null
    @Volatile private var running = false
    @Volatile private var stopRequested = false
    private var serverUrl = ""
    private var streamKey = ""
    private var quality = StreamQuality.MEDIUM
    private var width = 0
    private var height = 0
    private var startNanos = 0L
    private var lastFrameMs = -1000L
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    @Volatile private var sentConfigTo: RtmpPublisher? = null
    @Volatile private var currentBitrate = 0
    @Volatile private var congested = false
    @Volatile private var lastCongestionMs = 0L

    val isActive: Boolean get() = state == State.CONNECTING || state == State.LIVE || state == State.RECONNECTING

    /** Main thread. Connects in the background; watch [onState]. */
    fun start(serverUrl: String, streamKey: String, quality: StreamQuality, withMic: Boolean) {
        if (isActive) return
        val size = feeder.frameSize(quality.shortSide)
        if (size == null) { setState(State.FAILED, "There's nothing on screen to stream yet"); return }
        width = size.first; height = size.second
        this.serverUrl = serverUrl
        this.streamKey = streamKey
        this.quality = quality
        stopRequested = false
        setState(State.CONNECTING, null)
        IosRtmpThreads.start("live-start") { connectAndRun(withMic) }
    }

    private fun connectAndRun(withMic: Boolean) {
        val pub = RtmpPublisher(publisherListener, IosRtmpThreads)
        try {
            pub.connect(serverUrl, streamKey)
        } catch (e: Throwable) {
            setState(State.FAILED, e.message ?: "Couldn't connect")
            return
        }
        if (stopRequested) { pub.close(); setState(State.ENDED, null); return }
        val enc = IosH264Encoder(width, height, quality.fps, quality.bitrate, ::onVideoConfig, ::onVideoFrame)
        if (enc.error != null) {
            pub.close(); enc.close()
            setState(State.FAILED, enc.error)
            return
        }
        currentBitrate = quality.bitrate
        // The sound desk runs with or without the mic: muted or refused,
        // it still carries the soundboard (over silence).
        val mic = IosAudioRig(::onAudioFrame).also { it.muted = micMuted; it.pitchSemitones = pitchSemitones }
        val micOn = mic.start(withMic)
        pub.sendMetadata(width, height, quality.fps, quality.bitrate / 1000, if (micOn) 96 else 0, mic.sampleRate, false)
        if (micOn) pub.sendAudioConfig(mic.audioSpecificConfig)
        lock.withLock {
            publisher = pub; encoder = enc; audio = if (micOn) mic else null
            startNanos = nanoTime(); lastFrameMs = -1000L
            sps = null; pps = null; sentConfigTo = null; audioOffsetMs = -1L
            running = true
        }
        feeder.begin(width, height, this)
        setState(State.LIVE, if (!micOn) "Live without sound: the phone's sound couldn't be used" else null)
        IosRtmpThreads.start("live-monitor") { monitor() }
    }

    /** Main thread or any other. */
    fun stop() {
        stopRequested = true
        if (!running) { if (state == State.CONNECTING) return else { setState(State.ENDED, null); return } }
        teardown()
        setState(State.ENDED, null)
    }

    private fun teardown() {
        runCatching { feeder.end() }
        var pub: RtmpPublisher? = null
        var enc: IosH264Encoder? = null
        var mic: IosAudioRig? = null
        lock.withLock {
            running = false
            pub = publisher; enc = encoder; mic = audio
            publisher = null; encoder = null; audio = null
        }
        runCatching { mic?.stop() }
        runCatching { enc?.close() }
        runCatching { pub?.close() }
    }

    private fun setState(s: State, message: String?) {
        state = s
        runCatching { onState(s, message) }
    }

    // ── video ──

    /** Is it time for another frame (and is anyone there to receive it)?
     *  Feeders ask before going to the trouble of making one. */
    fun wantsFrame(): Boolean {
        if (!running) return false
        val ms = (nanoTime() - startNanos) / 1_000_000L
        if (ms - lastFrameMs < 1000L / quality.fps - 3) return false
        return publisher?.isConnected == true
    }

    /** One frame for the stream, [width]×[height]. The caller keeps
     *  ownership of [buffer] (and releases it, if it made it). */
    fun submit(buffer: CVPixelBufferRef) {
        lock.withLock {
            if (!running) return
            val enc = encoder ?: return
            val ms = (nanoTime() - startNanos) / 1_000_000L
            enc.encode(buffer, ms)
            lastFrameMs = ms
        }
    }

    private fun onVideoConfig(s: ByteArray, p: ByteArray) {
        sps = s; pps = p
    }

    private fun onVideoFrame(avcc: ByteArray, timestampMs: Long, keyframe: Boolean) {
        val pub = publisher ?: return
        if (!pub.isConnected) return
        // Each connection needs the stream's description before its first
        // picture, and that can only go out with a keyframe.
        if (sentConfigTo !== pub) {
            val s = sps; val p = pps
            if (!keyframe || s == null || p == null) { encoder?.requestKeyframe(); return }
            pub.sendVideoConfig(s, p)
            sentConfigTo = pub
        }
        pub.sendVideoAvcc(avcc, timestampMs, keyframe)
    }

    // ── audio ──

    private var audioOffsetMs = -1L

    private fun onAudioFrame(frame: ByteArray, timestampMs: Long) {
        val pub = publisher ?: return
        if (!pub.isConnected) return
        // The mic's clock starts at its own first frame; line it up with
        // the video's the first time a frame arrives.
        if (audioOffsetMs < 0) audioOffsetMs = (nanoTime() - startNanos) / 1_000_000L - timestampMs
        pub.sendAudio(frame, (timestampMs + audioOffsetMs).coerceAtLeast(0))
    }

    // ── the connection ──

    private val publisherListener = object : RtmpPublisher.Listener {
        override fun onDisconnected(reason: String) {
            if (stopRequested || !running) return
            IosRtmpThreads.start("live-reconnect") { reconnect(reason) }
        }

        override fun onCongestion(queuedMs: Long) {
            congested = true
            lastCongestionMs = currentTimeMillis()
            setBitrate(maxOf((quality.bitrate * 0.4).toInt(), (currentBitrate * 0.7).toInt()))
        }

        override fun onCongestionCleared() { congested = false }

        override fun onKeyframeNeeded() { encoder?.requestKeyframe() }
    }

    private fun setBitrate(bps: Int) {
        if (bps == currentBitrate) return
        currentBitrate = bps
        encoder?.setBitrate(bps)
    }

    private fun reconnect(reason: String) {
        Log.w("IosLiveStreamer", "Stream dropped: $reason — reconnecting")
        setState(State.RECONNECTING, reason)
        var delay = 2000
        for (attempt in 1..3) {
            var waited = 0
            while (waited < delay) { if (stopRequested) return; IosRtmpThreads.sleep(100); waited += 100 }
            val pub = RtmpPublisher(publisherListener, IosRtmpThreads)
            val ok = runCatching { pub.connect(serverUrl, streamKey) }.isSuccess
            if (stopRequested) { if (ok) pub.close(); return }
            if (ok) {
                val mic = audio
                pub.sendMetadata(width, height, quality.fps, quality.bitrate / 1000, if (mic != null) 96 else 0, mic?.sampleRate ?: 44100, false)
                if (mic != null) pub.sendAudioConfig(mic.audioSpecificConfig)
                publisher = pub
                // (The video description goes out with the next keyframe.)
                encoder?.requestKeyframe()
                setState(State.LIVE, null)
                return
            }
            delay *= 2
        }
        teardown()
        setState(State.FAILED, "The connection was lost: $reason")
    }

    /** Once a second: the upload-rate readout, and letting the bitrate
     *  climb back after the upload has been keeping up for a while. */
    private fun monitor() {
        var lastBytes = 0L
        var lastPublisher: RtmpPublisher? = null
        while (running && !stopRequested) {
            IosRtmpThreads.sleep(1000)
            val pub = publisher ?: continue
            val bytes = pub.bytesSent
            if (pub === lastPublisher) measuredBps = (bytes - lastBytes).coerceAtLeast(0) * 8
            lastBytes = bytes
            lastPublisher = pub
            val now = currentTimeMillis()
            if (!congested && now - lastCongestionMs > 8_000 && currentBitrate < quality.bitrate) {
                setBitrate(minOf(quality.bitrate, (currentBitrate * 1.15).toInt()))
            }
        }
    }

}
