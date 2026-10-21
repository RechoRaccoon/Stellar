package com.mediaviewer.util

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mediaviewer.platform.JsBytes
import com.mediaviewer.platform.PlatformContext
import com.mediaviewer.platform.toByteArray
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.await
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The web's visualizer listens to sound output, never the microphone. On
 * its own it hears what Stellar itself is playing (its videos are routed
 * through the page's own analyser). "Listen" in Settings adds another tab's
 * sound, or on Windows and ChromeOS the whole computer's ("Share system
 * audio"), where the browser can share it — Firefox can't, so there it stays
 * on Stellar's own sound. Nothing is recorded: the analyser only hands over
 * a spectrum. Sharing stops when the page closes or the browser's own Stop
 * sharing is pressed.
 */
actual object AudioVisualizerEngine {
    private const val BAR_COUNT = 28
    private var _levels by mutableStateOf(FloatArray(BAR_COUNT))
    actual val levels: FloatArray get() = _levels
    private var _status by mutableStateOf("Hearing Stellar's own sound · tap Listen to add another tab's")
    actual val status: String get() = _status
    private var _listening by mutableStateOf(false)
    actual val listening: Boolean get() = _listening
    private val bands = VisualizerBands(BAR_COUNT)
    private val scope = MainScope()
    private var users = 0
    private var job: Job? = null

    actual fun hasPermission(context: PlatformContext): Boolean = true
    actual fun watchPlayerSessions(context: PlatformContext) {}

    actual fun acquire(context: PlatformContext) {
        users++
        if (users == 1) startLoop()
    }

    actual fun release() {
        users = (users - 1).coerceAtLeast(0)
        if (users == 0) { job?.cancel(); job = null; _levels = FloatArray(BAR_COUNT) }
    }

    actual fun startListening(context: PlatformContext) {
        // (Asked for straight from the tap: browsers only share sound while
        // a tap is fresh.)
        val asked = vizStart()
        scope.launch {
            // The page answers in words, never by failing: a JavaScript error's
            // own message doesn't make it through to here.
            val answer = runCatching { asked.await<JsString>().toString() }.getOrDefault("error")
            _status = when (answer) {
                "ok" -> "Listening to the shared sound"
                "denied" -> "Not shared · hearing Stellar's own sound"
                "no-audio" -> "Nothing with sound was shared · hearing Stellar's own sound"
                "unsupported" -> "This browser can't share other tabs' sound · hearing Stellar's own sound"
                else -> "Couldn't start listening · hearing Stellar's own sound"
            }
            _listening = answer == "ok"
            if (users > 0) startLoop()
        }
    }

    private fun startLoop() {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                if (_listening && !vizSharedActive()) { _listening = false; _status = "Sharing stopped · hearing Stellar's own sound" }
                // Stellar's own videos join the analyser as they appear.
                vizAttachOwn()
                val data = vizRead()
                _levels = if (data == null) bands.decay() else {
                    val bytes = data.toByteArray()
                    bands.update(FloatArray(bytes.size) { (bytes[it].toInt() and 0xFF) / 255f }, vizRate().toFloat())
                }
                delay(40)
            }
        }
    }
}

private fun vizStart(): kotlin.js.Promise<JsString> = js("""(() => {
    const S = window.__stellarViz || (window.__stellarViz = {});
    const md = navigator.mediaDevices;
    // Firefox shares no sound with a screen or tab.
    if (!md || !md.getDisplayMedia || /firefox/i.test(navigator.userAgent)) return Promise.resolve("unsupported");
    return md.getDisplayMedia({
        video: true,
        audio: { echoCancellation: false, noiseSuppression: false, autoGainControl: false },
        systemAudio: 'include'
    }).then((stream) => {
        const audio = stream.getAudioTracks();
        if (!audio.length) { stream.getTracks().forEach((t) => t.stop()); return "no-audio"; }
        if (S.stream && S.stream !== stream) S.stream.getTracks().forEach((t) => t.stop());
        if (S.ctx) { try { S.ctx.close(); } catch (e) {} }
        const ctx = new AudioContext();
        const an = ctx.createAnalyser();
        an.fftSize = 2048; an.smoothingTimeConstant = 0.55;
        ctx.createMediaStreamSource(new MediaStream(audio)).connect(an);
        if (ctx.state === 'suspended') ctx.resume();
        S.stream = stream; S.ctx = ctx; S.an = an; S.data = new Uint8Array(an.frequencyBinCount); S.rate = ctx.sampleRate;
        audio[0].addEventListener('ended', () => { S.an = null; });
        return "ok";
    }).catch((e) => (e && e.name === 'NotAllowedError') ? "denied" : "error");
})()""")

/**
 * Routes each of Stellar's own <video> elements through one analyser on its
 * way to the speakers. Only ones whose sound the page may read (hls.js
 * streams, which play from blob: URLs): a cross-site file without sharing
 * headers would go silent once routed. And only while the page's audio is
 * running (after the first tap), so nothing is ever routed into a stopped
 * context and muted.
 */
private fun vizAttachOwn(): Unit = js("""{
    const S = window.__stellarViz || (window.__stellarViz = {});
    if (!S.own) {
        try {
            const ctx = new AudioContext();
            const an = ctx.createAnalyser();
            an.fftSize = 2048; an.smoothingTimeConstant = 0.55;
            an.connect(ctx.destination);
            S.own = { ctx: ctx, an: an, data: new Uint8Array(an.frequencyBinCount) };
            const wake = () => { if (ctx.state !== 'running') ctx.resume().catch(() => {}); };
            for (const name of ['pointerdown', 'keydown', 'touchend']) document.addEventListener(name, wake, { capture: true });
        } catch (e) { S.own = { failed: true }; }
    }
    const own = S.own;
    if (!own.failed && own.ctx.state === 'running') {
        document.querySelectorAll('#stellar-video-layer video').forEach((v) => {
            if (v.__vizSource) return;
            const readable = !!v.__hls || (v.currentSrc || '').startsWith('blob:');
            if (!readable) return;
            try { v.__vizSource = own.ctx.createMediaElementSource(v); v.__vizSource.connect(own.an); }
            catch (e) { v.__vizSource = 'none'; }
        });
    }
}""")

private fun vizSharedActive(): Boolean = js("!!(window.__stellarViz && window.__stellarViz.an)")
/** The shared sound's spectrum if there is one, else Stellar's own. */
private fun vizRead(): JsBytes? = js("""(() => {
    const S = window.__stellarViz; if (!S) return null;
    if (S.an) { S.an.getByteFrequencyData(S.data); return S.data; }
    if (S.own && S.own.an && S.own.ctx.state === 'running') { S.own.an.getByteFrequencyData(S.own.data); return S.own.data; }
    return null;
})()""")
private fun vizRate(): Double = js("(window.__stellarViz && (window.__stellarViz.an ? window.__stellarViz.rate : (window.__stellarViz.own && window.__stellarViz.own.ctx && window.__stellarViz.own.ctx.sampleRate))) || 48000")
