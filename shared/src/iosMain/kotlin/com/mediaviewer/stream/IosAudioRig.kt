package com.mediaviewer.stream

import com.mediaviewer.platform.IosAudioGuardBridge
import com.mediaviewer.platform.Log
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFAudio.AVAudioEngine
import platform.AVFAudio.AVAudioFile
import platform.AVFAudio.AVAudioFormat
import platform.AVFAudio.AVAudioMixerNode
import platform.AVFAudio.AVAudioNode
import platform.AVFAudio.AVAudioPlayer
import platform.AVFAudio.AVAudioPlayerNode
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryOptionAllowBluetooth
import platform.AVFAudio.AVAudioSessionCategoryOptionDefaultToSpeaker
import platform.AVFAudio.AVAudioSessionCategoryPlayAndRecord
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.AVAudioSessionRecordPermissionGranted
import platform.AVFAudio.AVAudioUnitTimePitch
import platform.AVFAudio.setActive
import platform.Foundation.NSURL
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.dispatch_after
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_time
import kotlin.concurrent.Volatile

/**
 * The sound of a recording or a live stream, as AAC frames.
 *
 * One of Apple's audio engines, wired like a small mixing desk:
 *
 *     microphone → mic fader → pitch ─┐
 *     soundboard sounds ──────────────┴→ mix → (listened to here) → muted → speaker
 *
 *  - **Pitch** is VRM Settings › Voice pitch (semitones; 0 passes the
 *    voice through untouched).
 *  - **Mic fader**: muting pulls it to zero, so silence is sent rather
 *    than nothing (services stall on a stream whose sound disappears).
 *  - **Soundboard** sounds are mixed in cleanly (see [IosSoundboard]),
 *    not picked up from the speaker.
 *  - The mix goes on to the speaker through a fader at zero: the engine
 *    only runs what leads to an output, and you shouldn't hear yourself.
 *
 * Android does the same with an AudioRecord loop, its own pitch shifter
 * and SoundboardMixer.
 */
@OptIn(ExperimentalForeignApi::class)
class IosAudioRig(
    /** One raw AAC frame and when it belongs (ms from the first frame).
     *  Called on the engine's own thread. */
    private val onFrame: (frame: ByteArray, timestampMs: Long) -> Unit
) {
    private var engine: AVAudioEngine? = null
    private var micFader: AVAudioMixerNode? = null
    private var mix: AVAudioMixerNode? = null
    private var pitchUnit: AVAudioUnitTimePitch? = null
    private var encoder: IosAacEncoder? = null
    private var playing = 0
    @Volatile private var running = false

    @Volatile var muted = false
        set(value) { field = value; runCatching { micFader?.setOutputVolume(if (value) 0f else 1f) } }

    /** Semitones, -12 … 12. Can be changed while running. */
    @Volatile var pitchSemitones = 0f
        set(value) {
            field = value
            runCatching {
                pitchUnit?.setPitch(value.coerceIn(-12f, 12f) * 100f)
                pitchUnit?.setBypass(value == 0f)
            }
        }

    val sampleRate: Int get() = encoder?.sampleRate ?: RATE.toInt()
    val audioSpecificConfig: ByteArray get() = encoder?.audioSpecificConfig ?: byteArrayOf(0x12, 0x08)

    /**
     * Starts the desk. [withMic] false (no permission, or not wanted)
     * still carries the soundboard, over silence. False if sound can't be
     * had at all — the recording or stream then goes without.
     */
    fun start(withMic: Boolean): Boolean {
        // With the mic first; if the engine won't take it, again without
        // (soundboard over silence) rather than no sound at all.
        if (startOnce(withMic)) return true
        return withMic && startOnce(false)
    }

    /** [connect] inside Objective-C's @try when the app provides it. */
    private fun connect(e: AVAudioEngine, from: AVAudioNode, to: AVAudioNode, format: AVAudioFormat?): Boolean {
        val guard = IosAudioGuardBridge.audioGuard ?: run { e.connect(from, to = to, format = format); return true }
        val error = guard.connect(e, from, to, format) ?: return true
        Log.e("IosAudioRig", "Audio wiring refused: $error")
        return false
    }

    private fun startEngine(e: AVAudioEngine): Boolean {
        val guard = IosAudioGuardBridge.audioGuard ?: run { e.prepare(); return e.startAndReturnError(null) }
        val error = guard.start(e) ?: return true
        Log.e("IosAudioRig", "Audio engine didn't start: $error")
        return false
    }

    private fun startOnce(withMic: Boolean): Boolean {
        pitchUnit = null; micFader = null
        return runCatching {
            val session = AVAudioSession.sharedInstance()
            session.setCategory(
                AVAudioSessionCategoryPlayAndRecord,
                withOptions = AVAudioSessionCategoryOptionDefaultToSpeaker or AVAudioSessionCategoryOptionAllowBluetooth,
                error = null
            )
            session.setActive(true, error = null)
            val e = AVAudioEngine()
            // What comes off the desk: plain samples, one channel, 44.1 kHz.
            val format = AVAudioFormat(standardFormatWithSampleRate = RATE, channels = 1u)
            val out = AVAudioMixerNode()
            val silencer = AVAudioMixerNode()
            e.attachNode(out)
            e.attachNode(silencer)
            if (!connect(e, out, silencer, format) || !connect(e, silencer, e.mainMixerNode, format)) return@runCatching false
            silencer.setOutputVolume(0f)

            // The microphone is only wired in when iOS really has one to
            // give right now. Apple's engine doesn't report a bad wiring as
            // an error — it throws an Objective-C exception, which used to
            // close the whole app (the Camera page's camera can change the
            // phone's audio format underneath it). Every connection and the
            // start now go through StellarTry's @try (IosAudioGuard), and a
            // refusal means recording without the mic instead of a crash:
            //
            //     microphone → fader → pitch → mix
            //
            // The mic goes straight into a mixer (the fader), in whatever
            // format the hardware is using this moment; mixers take any
            // format and convert it. After that, in one fixed ordinary
            // format (stereo, 44.1 kHz), it passes through the pitch effect.
            if (withMic && session.recordPermission == AVAudioSessionRecordPermissionGranted) {
                val input = e.inputNode
                val hardware = input.inputFormatForBus(0u)
                val inFormat = input.outputFormatForBus(0u)
                if (hardware.sampleRate < 8000.0 || hardware.channelCount == 0u ||
                    inFormat.sampleRate != hardware.sampleRate || inFormat.channelCount == 0u
                ) return@runCatching false
                val effectFormat = AVAudioFormat(standardFormatWithSampleRate = RATE, channels = 2u)
                val pitch = AVAudioUnitTimePitch()
                val fader = AVAudioMixerNode()
                e.attachNode(pitch)
                e.attachNode(fader)
                if (!connect(e, input, fader, inFormat) || !connect(e, fader, pitch, effectFormat) || !connect(e, pitch, out, effectFormat)) {
                    return@runCatching false
                }
                pitch.setPitch(pitchSemitones.coerceIn(-12f, 12f) * 100f)
                pitch.setBypass(pitchSemitones == 0f)
                fader.setOutputVolume(if (muted) 0f else 1f)
                pitchUnit = pitch
                micFader = fader
            }

            val enc = IosAacEncoder(format, onFrame)
            encoder = enc
            running = true
            out.installTapOnBus(0u, bufferSize = 2048u, format = format) { buffer, _ ->
                if (buffer != null && running) runCatching { enc.encode(buffer) }.onFailure { Log.e("IosAudioRig", "Encoding failed", it) }
            }
            if (!startEngine(e)) {
                running = false
                runCatching { out.removeTapOnBus(0u) }
                runCatching { e.stop() }
                encoder = null; pitchUnit = null; micFader = null
                return@runCatching false
            }
            engine = e
            mix = out
            current = this
            true
        }.getOrElse { Log.e("IosAudioRig", "Sound couldn't start", it); running = false; false }
    }

    /** Mixes the sound file at [path] into what's being captured. Main thread. */
    fun playSound(path: String) {
        val e = engine ?: return
        val out = mix ?: return
        if (!running || playing >= MAX_SOUNDS) return
        runCatching {
            val file = AVAudioFile(forReading = NSURL.fileURLWithPath(path), error = null)
            if (file.length <= 0L) return
            val node = AVAudioPlayerNode()
            e.attachNode(node)
            // (The mixer takes each sound at its own rate and channel count.)
            if (!connect(e, node, out, file.processingFormat)) { runCatching { e.detachNode(node) }; return }
            playing++
            node.scheduleFile(file, atTime = null) {
                // A moment later (the last of it is still on its way out),
                // the player comes off the desk.
                dispatch_after(dispatch_time(DISPATCH_TIME_NOW, 400_000_000L), dispatch_get_main_queue()) {
                    playing--
                    runCatching {
                        node.stop()
                        if (engine === e) e.detachNode(node)
                    }
                }
            }
            node.play()
        }.onFailure { Log.e("IosAudioRig", "Couldn't mix in $path", it) }
    }

    fun stop() {
        running = false
        if (current === this) current = null
        val e = engine
        engine = null
        runCatching { mix?.removeTapOnBus(0u) }
        runCatching { e?.stop() }
        mix = null; micFader = null; pitchUnit = null; encoder = null
        // Back to ordinary playback (videos elsewhere in the app).
        runCatching { AVAudioSession.sharedInstance().setCategory(AVAudioSessionCategoryPlayback, error = null) }
    }

    companion object {
        private const val RATE = 44100.0
        private const val MAX_SOUNDS = 6
        /** The desk that's running, if a recording or stream is. */
        @Volatile var current: IosAudioRig? = null
            private set
    }
}

/**
 * VRM mode's soundboard: a tapped sound plays on the phone so you hear it,
 * and — while a recording or a live stream is running — is mixed straight
 * into its sound as well.
 */
@OptIn(ExperimentalForeignApi::class)
object IosSoundboard {
    private val players = ArrayList<AVAudioPlayer>()

    /** Main thread. */
    fun play(path: String) {
        runCatching {
            players.removeAll { !it.playing }
            if (players.size < 8) {
                val p = AVAudioPlayer(contentsOfURL = NSURL.fileURLWithPath(path), error = null)
                players.add(p)
                p.play()
            }
        }.onFailure { Log.e("IosSoundboard", "Couldn't play $path", it) }
        dispatch_async(dispatch_get_main_queue()) { IosAudioRig.current?.playSound(path) }
    }

    fun stopAll() {
        runCatching { players.forEach { it.stop() } }
        players.clear()
    }
}
