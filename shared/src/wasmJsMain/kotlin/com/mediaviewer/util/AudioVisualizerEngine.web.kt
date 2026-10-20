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
 * The web's visualizer: a page can't hear other apps on its own, so it
 * listens to sound the person shares with it — "Listen" in Settings opens
 * the browser's share prompt (a tab's sound, or on Windows and ChromeOS the
 * whole computer's, with "Share system audio"). Nothing is recorded: the
 * browser's analyser only hands over a spectrum. Sharing stops when the
 * page closes or the browser's own Stop sharing is pressed.
 */
actual object AudioVisualizerEngine {
    private const val BAR_COUNT = 28
    private var _levels by mutableStateOf(FloatArray(BAR_COUNT))
    actual val levels: FloatArray get() = _levels
    private var _status by mutableStateOf("Tap Listen to choose what to hear")
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
        scope.launch {
            runCatching { vizStart().await<JsAny>() }
                .onSuccess { _status = "Listening"; _listening = true; if (users > 0) startLoop() }
                .onFailure { _status = it.message?.takeIf { m -> m.isNotBlank() && !m.contains("Permission denied") } ?: "Not shared" }
        }
    }

    private fun startLoop() {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                val active = vizActive()
                if (_listening && !active) { _listening = false; _status = "Sharing stopped — tap Listen to start again" }
                val data = if (active) vizRead() else null
                _levels = if (data == null) bands.decay() else {
                    val bytes = data.toByteArray()
                    bands.update(FloatArray(bytes.size) { (bytes[it].toInt() and 0xFF) / 255f }, vizRate().toFloat())
                }
                delay(40)
            }
        }
    }
}

private fun vizStart(): kotlin.js.Promise<JsAny> = js("""(() => {
    const S = window.__stellarViz || (window.__stellarViz = {});
    if (!navigator.mediaDevices || !navigator.mediaDevices.getDisplayMedia) return Promise.reject(new Error("This browser can't share audio"));
    return navigator.mediaDevices.getDisplayMedia({
        video: true,
        audio: { echoCancellation: false, noiseSuppression: false, autoGainControl: false },
        systemAudio: 'include'
    }).then((stream) => {
        const audio = stream.getAudioTracks();
        if (!audio.length) { stream.getTracks().forEach((t) => t.stop()); throw new Error("No sound was shared — tick Share audio"); }
        if (S.stream) S.stream.getTracks().forEach((t) => t.stop());
        if (S.ctx) S.ctx.close();
        const ctx = new AudioContext();
        const an = ctx.createAnalyser();
        an.fftSize = 2048; an.smoothingTimeConstant = 0.55;
        ctx.createMediaStreamSource(new MediaStream(audio)).connect(an);
        S.stream = stream; S.ctx = ctx; S.an = an; S.data = new Uint8Array(an.frequencyBinCount); S.rate = ctx.sampleRate;
        audio[0].addEventListener('ended', () => { S.an = null; });
        return 1;
    });
})()""")

private fun vizActive(): Boolean = js("!!(window.__stellarViz && window.__stellarViz.an)")
private fun vizRead(): JsBytes? = js("(() => { const S = window.__stellarViz; if (!S || !S.an) return null; S.an.getByteFrequencyData(S.data); return S.data; })()")
private fun vizRate(): Double = js("(window.__stellarViz && window.__stellarViz.rate) || 48000")
