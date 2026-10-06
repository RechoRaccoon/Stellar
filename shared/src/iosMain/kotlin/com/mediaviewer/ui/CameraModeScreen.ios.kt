package com.mediaviewer.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import com.mediaviewer.platform.IosContext
import com.mediaviewer.platform.IosPaths
import com.mediaviewer.platform.IosUri
import com.mediaviewer.platform.PlatformUri
import com.mediaviewer.platform.currentTimeMillis
import com.mediaviewer.platform.sharedPreferences
import com.mediaviewer.platform.toByteArray
import com.mediaviewer.platform.writeLocalFile
import com.mediaviewer.stream.IosCameraRig
import com.mediaviewer.stream.IosFrameRecorder
import com.mediaviewer.stream.IosLiveStreamer
import com.mediaviewer.stream.StreamQuality
import com.mediaviewer.ui.compat.BackHandler
import com.mediaviewer.ui.compat.IosCamera
import com.mediaviewer.util.IosStreamBadge
import com.mediaviewer.util.rememberHapticTap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import platform.UIKit.UIApplication
import platform.UIKit.UIImageJPEGRepresentation

private const val KEY_FRONT = "camera_page_front"
private const val KEY_CAMERA_VIDEO_MODE = "camera_page_video_mode"
private const val MAX_CAMERA_RECORDING_SECONDS = 600

/**
 * The notch bubble's "Camera" page on iOS: VRM mode's page and controls,
 * but showing your real camera instead of an avatar — no trackers, no
 * model. The same as Android's:
 *
 *  - The shared bottom bar ([CaptureControlsBar]): mic, photo/video,
 *    capture, Live, and — where VRM mode has Settings — a flip-camera
 *    button.
 *  - Photos, videos (with the mic, VRM mode's voice pitch and any browser
 *    windows set to show in captures) and live streams work like VRM
 *    mode's, and share its saved stream settings and browser windows.
 *  - A photo or video goes on to the review page, or straight into the
 *    post being written when the camera was opened from the posting page.
 *
 * If this camera can't be started at all, Apple's own camera page is
 * opened instead (see [IosCamera]), so there's always a way to take the
 * picture.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
actual fun CameraModeScreen(
    liquidGlass: Boolean,
    tint: Color,
    onClose: () -> Unit,
    onCapture: (imageUri: PlatformUri?, videoUri: PlatformUri?) -> Unit
) {
    val tap = rememberHapticTap()
    val scope = rememberCoroutineScope()
    val prefs = remember { IosContext.sharedPreferences(VRM_PREFS) }
    val rig = remember { IosCameraRig() }
    val close by rememberUpdatedState(onClose)
    val capture by rememberUpdatedState(onCapture)

    var front by remember { mutableStateOf(prefs.getBoolean(KEY_FRONT, true)) }
    var videoMode by remember { mutableStateOf(prefs.getBoolean(KEY_CAMERA_VIDEO_MODE, false)) }
    var micMuted by remember { mutableStateOf(prefs.getBoolean(KEY_MIC_MUTED, false)) }
    val voicePitch = remember { prefs.getInt(KEY_VOICE_PITCH, 0).coerceIn(-8, 8) }
    // Browser windows set up in VRM mode show (and capture) here too.
    val overlaysEnabled = remember { BrowserOverlayStore.enabled(prefs) }
    var browserOverlays by remember { mutableStateOf(BrowserOverlayStore.load(prefs)) }
    fun setOverlays(next: List<BrowserOverlaySpec>) { browserOverlays = next; BrowserOverlayStore.save(prefs, next) }
    val overlayRegistry = remember { BrowserOverlayRegistry() }
    val captureOverlayIds = if (overlaysEnabled) browserOverlays.filter { it.inCapture }.map { it.id } else emptyList()

    var ready by remember { mutableStateOf(false) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var recording by remember { mutableStateOf(false) }
    var recordSeconds by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    // A dip to black hides the switch between cameras (and the first frame).
    val cover = remember { Animatable(1f) }

    // ── Live (the same saved settings as VRM mode) ──
    var liveState by remember { mutableStateOf(IosLiveStreamer.State.IDLE) }
    var liveSeconds by remember { mutableLongStateOf(0L) }
    var liveMbps by remember { mutableFloatStateOf(0f) }
    var goLiveOpen by remember { mutableStateOf(false) }
    var endLiveOpen by remember { mutableStateOf(false) }
    var streamUrl by remember { mutableStateOf(prefs.getString(KEY_STREAM_URL, null).orEmpty()) }
    var streamKey by remember { mutableStateOf(prefs.getString(KEY_STREAM_KEY, null).orEmpty()) }
    var streamLink by remember { mutableStateOf(prefs.getString(KEY_STREAM_LINK, null).orEmpty()) }
    var streamQuality by remember {
        mutableStateOf(StreamQuality.entries.firstOrNull { it.name == prefs.getString(KEY_STREAM_QUALITY, null) } ?: StreamQuality.MEDIUM)
    }
    var liveBadgeUrl by remember { mutableStateOf<String?>(null) }
    val streamer = remember {
        IosLiveStreamer(rig) { state, note ->
            // (Called on a background thread.)
            platform.darwin.dispatch_async(platform.darwin.dispatch_get_main_queue()) {
                liveState = state
                if (note != null && (state == IosLiveStreamer.State.FAILED || state == IosLiveStreamer.State.LIVE)) message = note
            }
        }
    }
    val live = liveState == IosLiveStreamer.State.LIVE || liveState == IosLiveStreamer.State.RECONNECTING
    val connecting = liveState == IosLiveStreamer.State.CONNECTING
    val capturing = recording || live

    fun clearLiveBadge() {
        if (liveBadgeUrl == null) return
        liveBadgeUrl = null
        CoroutineScope(Dispatchers.IO).launch { IosStreamBadge.clear() }
    }
    LaunchedEffect(live) {
        liveSeconds = 0
        var sinceBadge = 0
        while (live) {
            delay(1000); liveSeconds++; liveMbps = streamer.measuredBps / 1_000_000f
            val badge = liveBadgeUrl
            if (badge != null && ++sinceBadge >= 3600) { sinceBadge = 0; launch(Dispatchers.IO) { IosStreamBadge.setLive(badge, "Live now") } }
        }
    }
    LaunchedEffect(liveState) {
        if (liveState == IosLiveStreamer.State.FAILED || liveState == IosLiveStreamer.State.ENDED) clearLiveBadge()
    }
    LaunchedEffect(message) {
        if (message != null) { delay(if ((message?.length ?: 0) > 60) 7000L else 3000L); message = null }
    }
    LaunchedEffect(micMuted) { streamer.micMuted = micMuted }

    // Opening: ask for the camera, start it, lift the cover on its first frame.
    LaunchedEffect(Unit) {
        if (!IosCameraRig.cameraAllowed()) {
            cameraError = "Stellar needs the camera for the Camera page. Allow it in the Settings app › Stellar."
            return@LaunchedEffect
        }
        rig.start(front) { error ->
            if (error == null) { ready = true; return@start }
            // This camera won't start: Apple's own camera page instead.
            val opened = IosCamera.open { image, video -> if (image == null && video == null) close() else capture(image, video) }
            if (!opened) cameraError = error
        }
    }
    LaunchedEffect(ready) {
        if (!ready) return@LaunchedEffect
        withTimeoutOrNull(4000) { while (rig.frameCount == 0) delay(16) }
        cover.animateTo(0f, tween(220))
    }

    val badgeNow = rememberUpdatedState(liveBadgeUrl)
    DisposableEffect(Unit) {
        val wasIdleDisabled = UIApplication.sharedApplication.idleTimerDisabled
        UIApplication.sharedApplication.idleTimerDisabled = true
        rig.recorder.pitchSemitones = voicePitch.toFloat()
        streamer.pitchSemitones = voicePitch.toFloat()
        onDispose {
            UIApplication.sharedApplication.idleTimerDisabled = wasIdleDisabled
            if (rig.recorder.isRecording) CoroutineScope(Dispatchers.Main).launch { runCatching { rig.recorder.stop() } }
            runCatching { streamer.stop() }
            if (badgeNow.value != null) CoroutineScope(Dispatchers.IO).launch { IosStreamBadge.clear() }
            overlayRegistry.release()
            rig.release()
        }
    }

    // Browser windows set to show in captures: a fresh picture of each a
    // few times a second while recording or live.
    val overlayIdsNow by rememberUpdatedState(captureOverlayIds)
    LaunchedEffect(capturing) {
        if (!capturing) { rig.captureLayers = emptyList(); return@LaunchedEffect }
        try {
            while (true) {
                val ids = overlayIdsNow
                if (ids.isNotEmpty()) overlayRegistry.refresh(ids)
                rig.captureLayers = overlayRegistry.layers(ids)
                delay(150)
            }
        } finally {
            rig.captureLayers = emptyList()
        }
    }

    LaunchedEffect(recording) {
        recordSeconds = 0
        while (recording) { delay(1000); recordSeconds++ }
    }

    fun takePhoto() {
        if (busy || !ready) return
        busy = true
        scope.launch {
            val ids = captureOverlayIds
            if (ids.isNotEmpty()) { overlayRegistry.refresh(ids); delay(180) }
            val before = rig.captureLayers
            rig.captureLayers = overlayRegistry.layers(ids)
            val image = rig.takePhoto()
            rig.captureLayers = before
            val path = IosPaths.cacheDir() + "/camera-photo-" + currentTimeMillis() + ".jpg"
            val ok = image != null && withContext(Dispatchers.IO) {
                val bytes = UIImageJPEGRepresentation(image, 0.95)?.toByteArray()
                bytes != null && writeLocalFile(path, bytes)
            }
            busy = false
            if (ok) capture(IosUri("file://$path"), null) else message = "Couldn't capture the photo"
        }
    }

    fun finishRecording() {
        if (!recording || busy) return
        busy = true
        scope.launch {
            val path = runCatching { rig.recorder.stop() }.getOrNull()
            recording = false
            busy = false
            if (path != null) capture(null, IosUri("file://$path")) else message = "Recording failed — nothing was saved"
        }
    }
    LaunchedEffect(recordSeconds) { if (recording && recordSeconds >= MAX_CAMERA_RECORDING_SECONDS) finishRecording() }

    fun toggleRecording() {
        if (busy || !ready) return
        if (recording) { finishRecording(); return }
        val size = rig.frameSize()
        if (size == null) { message = "The camera isn't ready yet"; return }
        busy = true
        scope.launch {
            val mic = !micMuted && IosFrameRecorder.micAllowed()
            if (!micMuted && !mic) message = "No microphone access — recording without your voice"
            val error = rig.recorder.start(size.first, size.second, withMic = mic, bitrate = 10_000_000)
            busy = false
            if (error == null) recording = true else message = error
        }
    }

    fun goLive() {
        goLiveOpen = false
        prefs.edit().putString(KEY_STREAM_URL, streamUrl.trim()).putString(KEY_STREAM_KEY, streamKey.trim())
            .putString(KEY_STREAM_QUALITY, streamQuality.name).putString(KEY_STREAM_LINK, streamLink.trim()).apply()
        scope.launch {
            val mic = IosFrameRecorder.micAllowed()
            if (!mic && !micMuted) message = "No microphone access — going live without your voice"
            streamer.micMuted = micMuted
            streamer.start(streamUrl.trim(), streamKey.trim(), streamQuality, withMic = mic)
            val link = IosStreamBadge.normalizeStreamLink(streamLink, streamUrl)
            if (link != null) {
                liveBadgeUrl = link
                launch(Dispatchers.IO) {
                    IosStreamBadge.setLive(link, "Live now").onFailure { e ->
                        withContext(Dispatchers.Main) {
                            message = "The Bluesky Live badge didn't update — " + (e.message?.removePrefix("setLiveNowStatus failed: ")?.take(160) ?: "unknown error")
                        }
                    }
                }
            }
        }
    }

    BackHandler(enabled = !busy) {
        when {
            endLiveOpen -> endLiveOpen = false
            goLiveOpen -> goLiveOpen = false
            live || connecting -> endLiveOpen = true
            else -> onClose()
        }
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .onSizeChanged { rig.setViewSize(it.width, it.height) }
    ) {
        if (ready) {
            UIKitView(
                factory = { rig.view },
                modifier = Modifier.fillMaxSize(),
                properties = UIKitInteropProperties(interactionMode = null)
            )
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = cover.value }.background(Color.Black))
        }
        cameraError?.let { error ->
            Text(
                error, color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp, lineHeight = 19.sp, textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center).padding(32.dp)
            )
        }

        VrmBubble(
            40.dp, liquidGlass, tint,
            modifier = Modifier.align(Alignment.TopStart).padding(top = rememberTopCutoutClearance(), start = 16.dp),
            onClick = { tap(); if (!busy) { if (live || connecting) endLiveOpen = true else onClose() } }
        ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White, modifier = Modifier.size(20.dp)) }

        CaptureControlsBar(
            liquidGlass = liquidGlass, tint = tint,
            status = when {
                message != null -> message
                connecting -> "Connecting…"
                liveState == IosLiveStreamer.State.RECONNECTING -> "Reconnecting…"
                live -> formatLiveClock(liveSeconds) + if (liveMbps > 0f) "  ·  ${oneDecimal(liveMbps)} Mbps" else ""
                busy -> if (videoMode) "Saving video…" else "Saving photo…"
                recording -> formatLiveClock(maxOf(1, recordSeconds).toLong())
                else -> null
            },
            statusDot = (recording || liveState == IosLiveStreamer.State.LIVE) && message == null,
            micMuted = micMuted, micEnabled = !recording && !busy,
            onToggleMic = { tap(); micMuted = !micMuted; prefs.edit().putBoolean(KEY_MIC_MUTED, micMuted).apply() },
            videoMode = videoMode, swapEnabled = !recording && !busy && !live && !connecting,
            onToggleVideoMode = { tap(); videoMode = !videoMode; prefs.edit().putBoolean(KEY_CAMERA_VIDEO_MODE, videoMode).apply() },
            connecting = connecting, isLive = live, captureBusy = busy, recording = recording,
            captureEnabled = ready,
            onCapture = {
                tap()
                when {
                    live || connecting -> endLiveOpen = true
                    videoMode -> toggleRecording()
                    else -> takePhoto()
                }
            },
            liveEnabled = ready && !recording && !busy && !live && !connecting,
            onLive = { tap(); goLiveOpen = true },
            rightIcon = Icons.Default.Cameraswitch, rightDescription = "Flip camera",
            // Flipping mid-recording/stream would restart the camera under
            // the encoder — allowed only when idle.
            rightEnabled = ready && !recording && !busy && !live && !connecting,
            onRight = {
                tap()
                scope.launch {
                    cover.animateTo(1f, tween(120))
                    val next = !front
                    var done = false
                    rig.flip(next) { error ->
                        if (error == null) { front = next; prefs.edit().putBoolean(KEY_FRONT, next).apply() } else message = error
                        done = true
                    }
                    // Wait for a frame from the new camera (with a limit, so
                    // the cover can never stay down).
                    withTimeoutOrNull(3000) { while (!done || rig.frameCount == 0) delay(16) }
                    cover.animateTo(0f, tween(220))
                }
            },
            modifier = Modifier.align(Alignment.BottomCenter)
        )

        if (overlaysEnabled && browserOverlays.isNotEmpty()) {
            VrmBrowserOverlays(
                overlays = browserOverlays, tint = tint, registry = overlayRegistry,
                onChange = { updated -> setOverlays(browserOverlays.map { if (it.id == updated.id) updated else it }) },
                onRemove = { id -> setOverlays(browserOverlays.filterNot { it.id == id }) }
            )
        }

        if (goLiveOpen) {
            VrmLiveDialog(
                tint = tint, what = "your camera",
                url = streamUrl, onUrl = { streamUrl = it }, key = streamKey, onKey = { streamKey = it },
                quality = streamQuality, onQuality = { streamQuality = it },
                link = streamLink, onLink = { streamLink = it },
                onGoLive = { goLive() }, onDismiss = { goLiveOpen = false }
            )
        }
        if (endLiveOpen) {
            VrmConfirmDialog(
                tint = tint, title = "End stream?", message = "You've been live for ${formatLiveClock(liveSeconds)}.",
                confirmLabel = "End stream",
                onConfirm = {
                    endLiveOpen = false
                    scope.launch(Dispatchers.IO) { runCatching { streamer.stop() } }
                    clearLiveBadge()
                },
                onDismiss = { endLiveOpen = false }
            )
        }
    }
}
