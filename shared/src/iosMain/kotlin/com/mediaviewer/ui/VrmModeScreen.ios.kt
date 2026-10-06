package com.mediaviewer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import com.mediaviewer.platform.IosContext
import com.mediaviewer.platform.IosFaceBridge
import com.mediaviewer.platform.IosPaths
import com.mediaviewer.platform.IosUri
import com.mediaviewer.platform.MediaBridge
import com.mediaviewer.platform.PlatformUri
import com.mediaviewer.platform.PrivateFiles
import com.mediaviewer.platform.currentTimeMillis
import com.mediaviewer.platform.localFileExists
import com.mediaviewer.platform.randomUuidString
import com.mediaviewer.platform.sharedPreferences
import com.mediaviewer.platform.toByteArray
import com.mediaviewer.platform.writeLocalFile
import com.mediaviewer.stream.IosLiveStreamer
import com.mediaviewer.stream.IosSoundboard
import com.mediaviewer.stream.StreamQuality
import com.mediaviewer.ui.compat.ActivityResultContracts
import com.mediaviewer.ui.compat.BackHandler
import com.mediaviewer.ui.compat.PickVisualMediaRequest
import com.mediaviewer.ui.compat.rememberLauncherForActivityResult
import com.mediaviewer.ui.compat.toUIImage
import com.mediaviewer.util.IosStreamBadge
import com.mediaviewer.util.Supporter
import com.mediaviewer.util.rememberHapticTap
import com.mediaviewer.vrm.CaptureLayer
import com.mediaviewer.vrm.VisionLift
import com.mediaviewer.vrm.VisionSample
import com.mediaviewer.vrm.VrmLiveFeeder
import com.mediaviewer.vrm.VrmPart
import com.mediaviewer.vrm.VrmRecorder
import com.mediaviewer.vrm.VrmSceneOptions
import com.mediaviewer.vrm.VrmStage
import com.mediaviewer.vrm.VrmStageStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import platform.UIKit.UIApplication
import platform.UIKit.UIImageJPEGRepresentation
import kotlin.math.roundToInt

// Where VRM mode keeps its settings: the same "vrm_settings" file and the
// same names as Android (see VrmSettingsStore there), so a backup made on
// one carries over to the other. iOS's own extras have an "ios_" name.
internal const val VRM_PREFS = "vrm_settings"
private const val KEY_UPPER_BODY = "upper_body"
private const val KEY_HAND_TRACKING = "hand_tracking"
private const val KEY_FULL_BODY = "full_body"
private const val KEY_FOLLOW_HEAD = "follow_head"
private const val KEY_SMOOTHING = "smoothing"
private const val KEY_FAST_TRACKING = "fast_tracking"
private const val KEY_MANUAL_EYES = "manual_eyes"
private const val KEY_EYE_CLOSED = "eye_closed"
private const val KEY_SPRING = "spring_bones"
private const val KEY_SHOW_DEBUG = "show_debug"
private const val KEY_SHOW_PREVIEW = "show_preview"
private const val KEY_VIDEO_MODE = "video_mode"
private const val KEY_FULL_BRIGHT = "full_bright"
private const val KEY_ARM_IK = "arm_ik"
private const val KEY_ARMS_NEED_HANDS = "arms_need_hands"
private const val KEY_HIDDEN_PARTS = "hidden_parts"
internal const val KEY_MIC_MUTED = "mic_muted"
private const val KEY_LIGHT_LEVEL = "light_level"
internal const val KEY_STREAM_URL = "stream_url"
internal const val KEY_STREAM_KEY = "stream_key"
internal const val KEY_STREAM_QUALITY = "stream_quality"
internal const val KEY_STREAM_LINK = "stream_link"
private const val KEY_BACKGROUND = "background_color"
internal const val KEY_VOICE_PITCH = "voice_pitch"
private const val KEY_HEAD_FALLBACK = "head_fallback"
private const val KEY_FRAME_RATE_CAP = "frame_rate_cap"
private const val KEY_CAPTURE_MODE = "capture_mode" // 0 photo, 1 video, 2 live
private const val KEY_BACKGROUND_MEDIA = "ios_background_media" // file name in the app's storage, "" = none
private const val KEY_BACKGROUND_MEDIA_VIDEO = "background_media_video"
private const val KEY_AVATAR = "ios_avatar_file"
private const val KEY_FLIP_TEXTURES = "ios_flip_textures"
private const val KEY_BLEND_CUTOUTS = "ios_blend_cutouts"
private const val KEY_ABSOLUTE_MORPHS = "ios_absolute_morphs"
private const val KEY_FLIP_HEAD = "ios_flip_head"
private const val KEY_SWAP_FACE = "ios_swap_face"
private const val KEY_FLIP_FOLLOW = "ios_flip_follow"
private const val KEY_SWAP_ARMS = "ios_swap_arms"
private const val KEY_MIRROR_BODY = "ios_mirror_body"
private const val KEY_VISION_ORIENTATION = "ios_vision_orientation"
private const val AVATAR_FOLDER = "vrm"
private const val BACKGROUND_MEDIA_FOLDER = "vrm_background"
private val FRAME_RATE_CAPS = listOf(30, 60, 120)
/** Recordings stop by themselves after ten minutes, like Android's. */
private const val MAX_RECORDING_SECONDS = 600

/**
 * VRM mode on iOS: your own .vrm avatar, following you.
 *
 *  - The avatar is drawn by SceneKit. Your face comes from ARKit's face
 *    tracking (the Face ID camera): head turns, blinks, mouth, brows. Your
 *    hands and — when switched on — your arms and legs come from Apple's
 *    Vision framework looking at the same camera picture. All of it goes
 *    through the same pose and expression code as Android, with the same
 *    spring bones for hair.
 *  - "Follow my head" places and sizes the avatar's head like yours in
 *    the camera's picture; with it off, drag to turn or raise the avatar
 *    and pinch to zoom.
 *  - Bottom bar, like Android's: mic · photo / video / live · capture ·
 *    Activity · Settings. A photo or video goes to the review page; in
 *    Live the button opens Go Live and the avatar is streamed over
 *    RTMP(S) — see IosLiveStreamer.
 *  - Activity (supporters): scene cards, a soundboard and effects, all of
 *    which are in what's captured too.
 *  - Settings has Android's four tabs plus "Fixes": a switch for each
 *    thing about the drawing and tracking that couldn't be checked
 *    without a phone in hand.
 *
 * What Android has and this doesn't: the music visualizer (iOS doesn't
 * let apps listen to other apps' sound) and the live blur behind the
 * buttons (what's behind them here isn't Compose's to blur — they're
 * tinted instead, like Android's Performance mode).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
actual fun VrmModeScreen(
    liquidGlass: Boolean,
    tint: Color,
    onClose: () -> Unit,
    onCapture: (imageUri: PlatformUri?, videoUri: PlatformUri?) -> Unit
) {
    val tap = rememberHapticTap()
    val scope = rememberCoroutineScope()
    val prefs = remember { IosContext.sharedPreferences(VRM_PREFS) }
    val stage = remember { VrmStage() }
    val recorder = remember { VrmRecorder(stage) }
    val tracking = remember { IosFaceBridge.supported }
    val supporter = Supporter.active

    var avatarFile by remember { mutableStateOf(prefs.getString(KEY_AVATAR, null).orEmpty()) }
    var loading by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var hasAvatar by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<VrmStageStatus?>(null) }
    var settingsOpen by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    // ── settings (read once, written back whenever one changes) ──
    var handTracking by remember { mutableStateOf(prefs.getBoolean(KEY_HAND_TRACKING, true)) }
    var trackUpperBody by remember { mutableStateOf(prefs.getBoolean(KEY_UPPER_BODY, false)) }
    var trackFullBody by remember { mutableStateOf(prefs.getBoolean(KEY_FULL_BODY, false)) }
    var armIk by remember { mutableStateOf(prefs.getBoolean(KEY_ARM_IK, false)) }
    var armsNeedHands by remember { mutableStateOf(prefs.getBoolean(KEY_ARMS_NEED_HANDS, false)) }
    var headFallback by remember { mutableStateOf(prefs.getBoolean(KEY_HEAD_FALLBACK, true)) }
    var fastTracking by remember { mutableStateOf(prefs.getBoolean(KEY_FAST_TRACKING, false)) }
    var smoothing by remember { mutableIntStateOf(prefs.getInt(KEY_SMOOTHING, 5).coerceIn(0, 10)) }
    var followHead by remember { mutableStateOf(prefs.getBoolean(KEY_FOLLOW_HEAD, true)) }
    var springBones by remember { mutableStateOf(prefs.getBoolean(KEY_SPRING, true)) }
    var manualEyes by remember { mutableStateOf(prefs.getBoolean(KEY_MANUAL_EYES, false)) }
    var eyeClosed by remember { mutableFloatStateOf(prefs.getFloat(KEY_EYE_CLOSED, 0f).coerceIn(0f, 1f)) }
    var fullBright by remember { mutableStateOf(prefs.getBoolean(KEY_FULL_BRIGHT, false)) }
    var lightLevel by remember { mutableIntStateOf(prefs.getInt(KEY_LIGHT_LEVEL, 5).coerceIn(0, 10)) }
    var showPreview by remember { mutableStateOf(prefs.getBoolean(KEY_SHOW_PREVIEW, true)) }
    var showDebug by remember { mutableStateOf(prefs.getBoolean(KEY_SHOW_DEBUG, false)) }
    var frameRateCap by remember { mutableIntStateOf(prefs.getInt(KEY_FRAME_RATE_CAP, 120).let { if (it in FRAME_RATE_CAPS) it else 120 }) }
    var background by remember { mutableIntStateOf(prefs.getInt(KEY_BACKGROUND, 0)) }
    var backgroundMedia by remember { mutableStateOf(prefs.getString(KEY_BACKGROUND_MEDIA, null).orEmpty()) }
    var backgroundIsVideo by remember { mutableStateOf(prefs.getBoolean(KEY_BACKGROUND_MEDIA_VIDEO, false)) }
    var voicePitch by remember { mutableIntStateOf(prefs.getInt(KEY_VOICE_PITCH, 0).coerceIn(-8, 8)) }
    var micMuted by remember { mutableStateOf(prefs.getBoolean(KEY_MIC_MUTED, false)) }
    var hiddenParts by remember { mutableStateOf(prefs.getStringSet(KEY_HIDDEN_PARTS, null) ?: emptySet()) }
    var avatarParts by remember { mutableStateOf<List<VrmPart>>(emptyList()) }
    // 0 photo, 1 video, 2 live.
    var mode by remember {
        mutableIntStateOf(if (prefs.contains(KEY_CAPTURE_MODE)) prefs.getInt(KEY_CAPTURE_MODE, 0).coerceIn(0, 2) else if (prefs.getBoolean(KEY_VIDEO_MODE, false)) 1 else 0)
    }
    val videoMode = mode == 1
    // Fixes.
    var flipTextures by remember { mutableStateOf(prefs.getBoolean(KEY_FLIP_TEXTURES, false)) }
    var blendCutouts by remember { mutableStateOf(prefs.getBoolean(KEY_BLEND_CUTOUTS, false)) }
    var absoluteMorphs by remember { mutableStateOf(prefs.getBoolean(KEY_ABSOLUTE_MORPHS, false)) }
    var flipHead by remember { mutableStateOf(prefs.getBoolean(KEY_FLIP_HEAD, false)) }
    var swapFace by remember { mutableStateOf(prefs.getBoolean(KEY_SWAP_FACE, false)) }
    var flipFollow by remember { mutableStateOf(prefs.getBoolean(KEY_FLIP_FOLLOW, false)) }
    var swapArms by remember { mutableStateOf(prefs.getBoolean(KEY_SWAP_ARMS, false)) }
    var mirrorBody by remember { mutableStateOf(prefs.getBoolean(KEY_MIRROR_BODY, false)) }
    fun save(key: String, value: Boolean) { prefs.edit().putBoolean(key, value).apply() }
    fun save(key: String, value: Int) { prefs.edit().putInt(key, value).apply() }

    // ── Activity: the scene shown and the effect playing over the avatar ──
    var activityOpen by remember { mutableStateOf(false) }
    var scene by remember { mutableStateOf(VrmStageScene.VRM) }
    var stageEffect by remember { mutableStateOf<DmEffect?>(null) }
    var stageEffectKey by remember { mutableIntStateOf(0) }
    var stageBusyUntil by remember { mutableLongStateOf(0L) }
    val stageLayer = rememberGraphicsLayer()
    val stageSmall = rememberGraphicsLayer()
    fun stageShowing() = supporter && (scene != VrmStageScene.VRM || currentTimeMillis() < stageBusyUntil)

    // ── browser overlays (chat, alerts …) ──
    var overlaysEnabled by remember { mutableStateOf(BrowserOverlayStore.enabled(prefs)) }
    var browserOverlays by remember { mutableStateOf(BrowserOverlayStore.load(prefs)) }
    fun setOverlays(next: List<BrowserOverlaySpec>) { browserOverlays = next; BrowserOverlayStore.save(prefs, next) }
    val overlayRegistry = remember { BrowserOverlayRegistry() }
    val captureOverlayIds = if (overlaysEnabled) browserOverlays.filter { it.inCapture }.map { it.id } else emptyList()

    var recording by remember { mutableStateOf(false) }
    var recordSeconds by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }

    // ── Live ──
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
    // The Bluesky Live badge this stream put up, if any — taken down when it ends.
    var liveBadgeUrl by remember { mutableStateOf<String?>(null) }
    val feeder = remember { VrmLiveFeeder(stage) }
    val streamer = remember {
        IosLiveStreamer(feeder) { state, note ->
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
            // The badge expires after 4 hours; renew it hourly while live.
            val badge = liveBadgeUrl
            if (badge != null && ++sinceBadge >= 3600) { sinceBadge = 0; launch(Dispatchers.IO) { IosStreamBadge.setLive(badge, "Live now") } }
        }
    }
    // A stream that failed or dropped for good takes its badge down too.
    LaunchedEffect(liveState) {
        if (liveState == IosLiveStreamer.State.FAILED || liveState == IosLiveStreamer.State.ENDED) clearLiveBadge()
    }
    LaunchedEffect(message) {
        if (message != null) { delay(if ((message?.length ?: 0) > 60) 7000L else 3000L); message = null }
    }

    BackHandler(enabled = !busy) {
        when {
            goLiveOpen -> goLiveOpen = false
            endLiveOpen -> endLiveOpen = false
            activityOpen -> activityOpen = false
            settingsOpen -> settingsOpen = false
            live || connecting -> endLiveOpen = true
            else -> onClose()
        }
    }

    // The stage lives exactly as long as this page: tracking on, screen
    // kept awake; everything released when the page goes.
    val badgeNow = rememberUpdatedState(liveBadgeUrl)
    DisposableEffect(Unit) {
        stage.onStatus = { status = it }
        stage.onVisionOrientation = { prefs.edit().putInt(KEY_VISION_ORIENTATION, it).apply() }
        stage.setVisionOrientation(prefs.getInt(KEY_VISION_ORIENTATION, 6))
        stage.startTracking()
        val wasIdleDisabled = UIApplication.sharedApplication.idleTimerDisabled
        UIApplication.sharedApplication.idleTimerDisabled = true
        onDispose {
            UIApplication.sharedApplication.idleTimerDisabled = wasIdleDisabled
            if (recorder.isRecording) CoroutineScope(Dispatchers.Main).launch { runCatching { recorder.stop() } }
            runCatching { streamer.stop() }
            if (badgeNow.value != null) CoroutineScope(Dispatchers.IO).launch { IosStreamBadge.clear() }
            IosSoundboard.stopAll()
            overlayRegistry.release()
            stage.release()
        }
    }

    // Settings the stage reads directly.
    LaunchedEffect(handTracking, trackUpperBody, trackFullBody, headFallback, fastTracking) {
        stage.setTracking(body = trackUpperBody, legs = trackFullBody, hands = handTracking, headFallback = headFallback, fast = fastTracking)
    }
    LaunchedEffect(smoothing) { stage.setSmoothing(smoothing / 5.0) }
    LaunchedEffect(swapArms, mirrorBody) { stage.setVisionFixes(swapLabels = swapArms != mirrorBody, flipX = mirrorBody) }
    LaunchedEffect(armIk, armsNeedHands) { stage.armIk = armIk; stage.armsNeedHands = armsNeedHands }
    LaunchedEffect(followHead, springBones) { stage.followHead = followHead; stage.springBones = springBones }
    LaunchedEffect(manualEyes, eyeClosed) { stage.manualEyes = manualEyes; stage.eyeClosed = eyeClosed }
    LaunchedEffect(fullBright, lightLevel) { stage.fullBright = fullBright; stage.lightLevel = 0.4f + lightLevel * 0.12f }
    LaunchedEffect(hiddenParts) { stage.hiddenParts = hiddenParts }
    LaunchedEffect(flipHead, swapFace, flipFollow) { stage.flipHead = flipHead; stage.swapFaceSides = swapFace; stage.flipFollow = flipFollow }
    LaunchedEffect(frameRateCap, fastTracking) { stage.setMaxFps(minOf(frameRateCap, if (fastTracking) 120 else 60)) }
    LaunchedEffect(voicePitch, micMuted) {
        recorder.pitchSemitones = voicePitch.toFloat()
        streamer.pitchSemitones = voicePitch.toFloat()
        streamer.micMuted = micMuted
    }
    LaunchedEffect(background, tint) {
        // No colour picked: a dim version of your profile colour, like Android.
        val c = if (background != 0) Color(background) else dimSpaceColor(tint)
        stage.setBackground(c.red, c.green, c.blue)
    }
    LaunchedEffect(backgroundMedia, backgroundIsVideo, supporter) {
        val path = PrivateFiles.rootUri(IosContext).removePrefix("file://") + "/" + BACKGROUND_MEDIA_FOLDER + "/" + backgroundMedia
        stage.setBackgroundMedia(if (supporter && backgroundMedia.isNotBlank() && localFileExists(path)) path else null, backgroundIsVideo)
    }

    // (Re)loads the avatar whenever the file or a drawing option changes.
    LaunchedEffect(avatarFile, flipTextures, blendCutouts, absoluteMorphs) {
        avatarParts = emptyList()
        if (avatarFile.isBlank()) { hasAvatar = false; return@LaunchedEffect }
        loading = true
        loadError = null
        val bytes = withContext(Dispatchers.IO) {
            PrivateFiles.read(IosContext, PrivateFiles.rootUri(IosContext) + "/" + AVATAR_FOLDER + "/" + avatarFile)
        }
        val error = if (bytes == null) "The avatar file is missing — choose it again."
            else stage.load(bytes, VrmSceneOptions(flipTextures, blendCutouts, absoluteMorphs))
        loading = false
        loadError = error
        hasAvatar = stage.hasAvatar()
        val parts = stage.parts()
        avatarParts = parts
        // Keep choices for the same file; drop ids it no longer has.
        if (parts.isNotEmpty()) {
            val ids = parts.map { it.id }.toSet()
            val kept = hiddenParts.filter { it in ids }.toSet()
            if (kept != hiddenParts) { hiddenParts = kept; prefs.edit().putStringSet(KEY_HIDDEN_PARTS, kept).apply() }
        }
    }

    val pickAvatar = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            loading = true
            // Kept in the app's own storage under a new name each time, so
            // the page notices the change and the old one can be removed.
            val name = "avatar_" + currentTimeMillis() + ".vrm"
            val saved = withContext(Dispatchers.IO) { PrivateFiles.copyIn(IosContext, uri, AVATAR_FOLDER, name) }
            if (saved == null) {
                loading = false
                loadError = "That file couldn't be read."
            } else {
                val old = avatarFile
                // A newly picked avatar starts with all of its parts showing.
                hiddenParts = emptySet()
                prefs.edit().putString(KEY_AVATAR, name).putStringSet(KEY_HIDDEN_PARTS, emptySet()).apply()
                avatarFile = name
                if (old.isNotBlank() && old != name) withContext(Dispatchers.IO) {
                    PrivateFiles.delete(IosContext, PrivateFiles.rootUri(IosContext) + "/" + AVATAR_FOLDER + "/" + old)
                }
            }
        }
    }
    val pickBackground = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val type = MediaBridge.mimeTypeOf(IosContext, uri).orEmpty()
            val isVideo = type.startsWith("video")
            val extension = MediaBridge.pathOf(uri).substringAfterLast('.', "").lowercase().filter { it.isLetterOrDigit() }.take(5)
            val name = "background_" + currentTimeMillis() + if (extension.isEmpty()) "" else ".$extension"
            val saved = withContext(Dispatchers.IO) {
                PrivateFiles.deleteFolder(IosContext, BACKGROUND_MEDIA_FOLDER)
                PrivateFiles.copyIn(IosContext, uri, BACKGROUND_MEDIA_FOLDER, name)
            }
            if (saved != null) {
                prefs.edit().putString(KEY_BACKGROUND_MEDIA, name).putBoolean(KEY_BACKGROUND_MEDIA_VIDEO, isVideo).apply()
                backgroundIsVideo = isVideo
                backgroundMedia = name
            } else message = "Couldn't open that file"
        }
    }

    // What's captured over the avatar — the scene card / effect, then the
    // browser windows set to show — kept fresh while a recording or stream
    // is running: about twenty looks a second at the stage layer, and a
    // new picture of each web page a few times a second.
    val overlayIdsNow by rememberUpdatedState(captureOverlayIds)
    LaunchedEffect(capturing) {
        if (!capturing) { stage.captureLayers = emptyList(); return@LaunchedEffect }
        var tick = 0
        try {
            while (true) {
                val ids = overlayIdsNow
                if (ids.isNotEmpty() && tick % 3 == 0) overlayRegistry.refresh(ids)
                val card = if (stageShowing()) runCatching { stageSmall.toImageBitmap().toUIImage() }.getOrNull() else null
                stage.captureLayers = listOfNotNull(card?.let { CaptureLayer(it, 0f, 0f, 1f, 1f) }) + overlayRegistry.layers(ids)
                tick++
                delay(50)
            }
        } finally {
            stage.captureLayers = emptyList()
        }
    }

    // The recording clock: 0:01 … 10:00, then it stops by itself.
    LaunchedEffect(recording) {
        recordSeconds = 0
        while (recording) { delay(1000); recordSeconds++ }
    }

    fun takePhoto() {
        if (busy) return
        busy = true
        scope.launch {
            // One sharp look at everything that goes over the avatar.
            val ids = captureOverlayIds
            if (ids.isNotEmpty()) { overlayRegistry.refresh(ids); delay(180) }
            val card = if (stageShowing()) runCatching { stageLayer.toImageBitmap().toUIImage() }.getOrNull() else null
            val before = stage.captureLayers
            stage.captureLayers = listOfNotNull(card?.let { CaptureLayer(it, 0f, 0f, 1f, 1f) }) + overlayRegistry.layers(ids)
            val image = stage.snapshot()
            stage.captureLayers = before
            val path = IosPaths.cacheDir() + "/vrm-photo-" + currentTimeMillis() + ".jpg"
            val ok = image != null && withContext(Dispatchers.IO) {
                val bytes = UIImageJPEGRepresentation(image, 0.95)?.toByteArray()
                bytes != null && writeLocalFile(path, bytes)
            }
            busy = false
            if (ok) onCapture(IosUri("file://$path"), null) else message = "Couldn't capture the photo"
        }
    }

    fun finishRecording() {
        if (!recording || busy) return
        busy = true
        scope.launch {
            val path = runCatching { recorder.stop() }.getOrNull()
            recording = false
            busy = false
            if (path != null) onCapture(null, IosUri("file://$path")) else message = "Recording failed — nothing was saved"
        }
    }
    LaunchedEffect(recordSeconds) { if (recording && recordSeconds >= MAX_RECORDING_SECONDS) finishRecording() }

    fun toggleRecording() {
        if (busy) return
        if (recording) { finishRecording(); return }
        busy = true
        scope.launch {
            val mic = !micMuted && VrmRecorder.micAllowed()
            if (!micMuted && !mic) message = "No microphone access — recording without your voice"
            val error = recorder.start(withMic = mic)
            busy = false
            if (error == null) recording = true else message = error
        }
    }

    fun goLive() {
        goLiveOpen = false
        prefs.edit().putString(KEY_STREAM_URL, streamUrl.trim()).putString(KEY_STREAM_KEY, streamKey.trim())
            .putString(KEY_STREAM_QUALITY, streamQuality.name).putString(KEY_STREAM_LINK, streamLink.trim()).apply()
        scope.launch {
            val mic = VrmRecorder.micAllowed()
            if (!mic && !micMuted) message = "No microphone access — going live without your voice"
            feeder.measure()
            streamer.micMuted = micMuted
            // (The mic is opened even when muted: muted sends silence, which
            // services handle better than a stream with no sound at all.)
            streamer.start(streamUrl.trim(), streamKey.trim(), streamQuality, withMic = mic)
            // Bluesky's Live badge, pointing at the stream link (if one was given).
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

    fun endLive() {
        scope.launch(Dispatchers.IO) { runCatching { streamer.stop() } }
        clearLiveBadge()
    }

    Box(Modifier.fillMaxSize().background(Color.Black).onSizeChanged { stage.setViewSize(it.width, it.height) }) {
        UIKitView(
            factory = { stage.view },
            modifier = Modifier.fillMaxSize(),
            // Touches go to the gestures below, not to SceneKit.
            properties = UIKitInteropProperties(interactionMode = null)
        )
        // Drag: turn / raise. Pinch: zoom.
        Box(
            Modifier.fillMaxSize().pointerInput(Unit) {
                detectTransformGestures { _, pan, zoomChange, _ ->
                    stage.zoom = (stage.zoom * zoomChange).coerceIn(0.35f, 4f)
                    stage.yawDegrees = (stage.yawDegrees + pan.x * 0.25f).coerceIn(-180f, 180f)
                    stage.lift = (stage.lift + pan.y / size.height.coerceAtLeast(1) * 0.4f / stage.zoom).coerceIn(-0.9f, 0.35f)
                }
            }
        )

        // Scene cards and effects: over the avatar, under the buttons.
        if (supporter && hasAvatar) VrmStageLayer(scene, stageEffect, stageEffectKey, stageLayer, stageSmall, effectColors = listOf(tint))

        // Middle of the page: what's needed before there's an avatar.
        Column(
            Modifier.align(Alignment.Center).padding(horizontal = 32.dp).widthIn(max = 420.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            when {
                loading -> {
                    CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
                    Text("Loading avatar…", color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
                }
                !hasAvatar -> {
                    Text(
                        loadError ?: "No avatar picked yet — choose a .vrm file from the Files app.",
                        color = if (loadError != null) Color(0xFFFF8A80) else Color.White.copy(alpha = 0.85f),
                        fontSize = 14.sp, lineHeight = 19.sp, textAlign = TextAlign.Center
                    )
                    VrmPill("Choose .vrm file", tint, filled = true) { tap(); pickAvatar.launch(arrayOf("*/*")) }
                }
            }
        }

        // Top: back on the left; on the right, what the tracker sees.
        VrmBubble(
            40.dp, liquidGlass, tint,
            modifier = Modifier.align(Alignment.TopStart).padding(top = rememberTopCutoutClearance(), start = 16.dp),
            onClick = { tap(); if (!busy) { if (live || connecting) endLiveOpen = true else onClose() } }
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back",
                tint = Color.White, modifier = Modifier.size(20.dp)
            )
        }
        Column(
            Modifier.align(Alignment.TopEnd).padding(top = rememberTopCutoutClearance(), end = 16.dp),
            horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (showPreview && hasAvatar && tracking) TrackingPreview(stage, status?.faceVisible == true, tint)
            val note = when {
                !tracking -> "This iPhone has no Face ID camera, so the avatar can't follow your face"
                hasAvatar && status?.faceVisible == false && !capturing -> "Looking for your face…"
                else -> null
            }
            if (note != null) VrmNote(note)
        }
        if (showDebug) status?.let { s ->
            Text(
                "${s.bones} bones · ${s.expressions} expressions · ${s.springJoints} spring joints\n" +
                    "${s.vertices} vertices · ${s.triangles} triangles · ${s.morphTargets} morph targets\n" +
                    "face ${if (s.faceVisible) "seen" else "not seen"} · body joints ${s.bodyJoints} · hands ${s.hands} · camera picture read as ${s.visionOrientation}",
                color = Color.White.copy(alpha = 0.75f), fontSize = 10.sp, lineHeight = 13.sp,
                modifier = Modifier.align(Alignment.TopStart).padding(top = rememberTopCutoutClearance() + 52.dp, start = 16.dp)
                    .clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.4f)).padding(6.dp)
            )
        }

        // Bottom bar: [mic] [photo/video/live] [capture] [Activity] [settings].
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
            onToggleMic = { tap(); micMuted = !micMuted; save(KEY_MIC_MUTED, micMuted) },
            videoMode = videoMode, swapEnabled = !recording && !busy && !live && !connecting,
            onToggleVideoMode = { tap(); mode = (mode + 1) % 3; prefs.edit().putInt(KEY_CAPTURE_MODE, mode).putBoolean(KEY_VIDEO_MODE, mode == 1).apply() },
            connecting = connecting, isLive = live, captureBusy = busy, recording = recording,
            captureEnabled = hasAvatar,
            onCapture = {
                tap()
                when {
                    live || connecting -> endLiveOpen = true
                    mode == 2 -> goLiveOpen = true
                    videoMode -> toggleRecording()
                    else -> takePhoto()
                }
            },
            liveEnabled = hasAvatar && !recording && !busy,
            onLive = { tap(); goLiveOpen = true },
            liveMode = mode == 2,
            activityLocked = !supporter,
            onActivity = {
                tap()
                if (supporter) activityOpen = true else { onClose(); Supporter.openPage() }
            },
            rightIcon = Icons.Default.Settings, rightDescription = "VRM Settings",
            onRight = { tap(); settingsOpen = true },
            modifier = Modifier.align(Alignment.BottomCenter)
        )

        // Browser windows float over everything except this page's popups.
        if (overlaysEnabled && browserOverlays.isNotEmpty()) {
            VrmBrowserOverlays(
                overlays = browserOverlays, tint = tint, registry = overlayRegistry,
                onChange = { updated -> setOverlays(browserOverlays.map { if (it.id == updated.id) updated else it }) },
                onRemove = { id -> setOverlays(browserOverlays.filterNot { it.id == id }) }
            )
        }

        if (activityOpen) {
            VrmActivityDialog(
                tint = tint, scene = scene,
                onScene = { scene = it; stageBusyUntil = currentTimeMillis() + SCENE_FADE_MS + 200L },
                onEffect = { stageEffect = it; stageEffectKey++; stageBusyUntil = currentTimeMillis() + EFFECT_MAX_MS },
                onDismiss = { activityOpen = false }
            )
        }
        if (goLiveOpen) {
            VrmLiveDialog(
                tint = tint, what = "your avatar",
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
                onConfirm = { endLiveOpen = false; endLive() }, onDismiss = { endLiveOpen = false }
            )
        }

        if (settingsOpen) {
            var tab by remember { mutableIntStateOf(0) }
            var partsExpanded by remember { mutableStateOf(false) }
            var colorPicker by remember { mutableStateOf(false) }
            val dim = Color.White.copy(alpha = 0.55f)
            VrmScrim({ tap(); settingsOpen = false }) {
                VrmPanel(tint, Modifier.padding(horizontal = 16.dp).widthIn(max = 440.dp).fillMaxWidth()) {
                    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 16.dp)) {
                        VrmPopupHeader("VRM Settings", Icons.Default.Settings, tint) { settingsOpen = false }
                        Spacer(Modifier.height(12.dp))
                        // Tabs: a segmented pill; the selected tab is filled with your colour.
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = 0.08f)).padding(3.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            listOf("Tracking", "Avatar", "Display", "Audio & Web", "Fixes").forEachIndexed { i, label ->
                                val selected = i == tab
                                Box(
                                    Modifier.height(32.dp).clip(RoundedCornerShape(11.dp))
                                        .background(if (selected) lerp(tint, Color.Black, 0.12f) else Color.Transparent)
                                        .clickable { if (!selected) { tap(); tab = i } },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        label, color = if (selected) Color.White else Color.White.copy(alpha = 0.6f),
                                        fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false,
                                        modifier = Modifier.padding(horizontal = 7.dp)
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Box(Modifier.fillMaxWidth().heightIn(min = 260.dp, max = 430.dp).verticalScroll(rememberScrollState())) {
                            Column(Modifier.fillMaxWidth()) {
                                when (tab) {
                                    0 -> {
                                        VrmTileGrid(tint, listOf(
                                            VrmTile("Hands", handTracking) { handTracking = it; save(KEY_HAND_TRACKING, it) },
                                            VrmTile("Upper body", trackUpperBody) {
                                                trackUpperBody = it; save(KEY_UPPER_BODY, it)
                                                if (!it) { trackFullBody = false; save(KEY_FULL_BODY, false) }
                                            },
                                            VrmTile("Full body", trackFullBody, enabled = trackUpperBody) { trackFullBody = it; save(KEY_FULL_BODY, it) },
                                            VrmTile("Hand IK", armIk, enabled = handTracking) { armIk = it; save(KEY_ARM_IK, it) },
                                            VrmTile("Arms need hands", armsNeedHands, enabled = trackUpperBody && handTracking) { armsNeedHands = it; save(KEY_ARMS_NEED_HANDS, it) },
                                            VrmTile("Head fallback", headFallback) { headFallback = it; save(KEY_HEAD_FALLBACK, it) },
                                            VrmTile("Fast (30 fps)", fastTracking) { fastTracking = it; save(KEY_FAST_TRACKING, it) }
                                        ))
                                        Spacer(Modifier.height(6.dp))
                                        VrmSlider("Smoothing", if (smoothing == 0) "Off" else smoothing.toString(), smoothing.toFloat(), 0f..10f, 9, tint) {
                                            smoothing = it.roundToInt(); save(KEY_SMOOTHING, smoothing)
                                        }
                                        Text(
                                            if (handTracking) "Face is always tracked." else "Hands off — head-only VTubing.",
                                            color = dim, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp)
                                        )
                                    }
                                    1 -> {
                                        VrmTileGrid(tint, listOf(
                                            VrmTile("Follow my head", followHead) {
                                                followHead = it; save(KEY_FOLLOW_HEAD, it)
                                                if (it) { stage.yawDegrees = 0f; stage.lift = 0f; stage.zoom = 1f }
                                            },
                                            VrmTile("Physics", springBones) { springBones = it; save(KEY_SPRING, it) },
                                            VrmTile("Manual eyes", manualEyes) { manualEyes = it; save(KEY_MANUAL_EYES, it) },
                                            // Shading: flat, unlit colours.
                                            VrmTile("Full bright", fullBright) { fullBright = it; save(KEY_FULL_BRIGHT, it) }
                                        ))
                                        if (!followHead) Text("Drag to turn or raise · pinch to zoom", color = dim, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
                                        if (manualEyes) {
                                            Spacer(Modifier.height(4.dp))
                                            VrmSlider(
                                                "Eyes",
                                                when {
                                                    eyeClosed <= 0.02f -> "Open"
                                                    eyeClosed >= 0.98f -> "Closed"
                                                    else -> "${((1f - eyeClosed) * 100).toInt()}% open"
                                                },
                                                eyeClosed, 0f..1f, 0, tint
                                            ) { eyeClosed = it; prefs.edit().putFloat(KEY_EYE_CLOSED, it).apply() }
                                        }
                                        Spacer(Modifier.height(6.dp))
                                        VrmActionRow(if (hasAvatar) "Change avatar" else "Choose avatar", ".vrm", tint) { settingsOpen = false; pickAvatar.launch(arrayOf("*/*")) }
                                        VrmActionRow("Reset camera", "Reset", tint) { stage.yawDegrees = 0f; stage.zoom = 1f; stage.lift = 0f }
                                        // Avatar parts: every mesh piece (clothes, hair,
                                        // accessories …) can be hidden.
                                        if (avatarParts.isNotEmpty()) {
                                            val shown = avatarParts.count { it.id !in hiddenParts }
                                            VrmActionRow("Avatar parts", "$shown/${avatarParts.size}  ${if (partsExpanded) "▴" else "▾"}", tint) { partsExpanded = !partsExpanded }
                                            if (partsExpanded) {
                                                fun setHidden(next: Set<String>) { hiddenParts = next; prefs.edit().putStringSet(KEY_HIDDEN_PARTS, next).apply() }
                                                VrmTileGrid(tint, avatarParts.map { part ->
                                                    VrmTile(part.label, part.id !in hiddenParts) { visible -> setHidden(if (visible) hiddenParts - part.id else hiddenParts + part.id) }
                                                })
                                                if (hiddenParts.isNotEmpty()) {
                                                    Text(
                                                        "Show all", color = lerp(tint, Color.White, 0.35f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                                                        modifier = Modifier.padding(top = 6.dp).clip(RoundedCornerShape(8.dp))
                                                            .clickable { tap(); setHidden(emptySet()) }.padding(horizontal = 6.dp, vertical = 4.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    2 -> {
                                        VrmTileGrid(tint, listOf(
                                            VrmTile("Tracking preview", showPreview) { showPreview = it; save(KEY_SHOW_PREVIEW, it) },
                                            VrmTile("Debug info", showDebug) { showDebug = it; save(KEY_SHOW_DEBUG, it) }
                                        ))
                                        Spacer(Modifier.height(6.dp))
                                        VrmChoiceRow("Frame rate", FRAME_RATE_CAPS, frameRateCap, tint, { "$it" }) { frameRateCap = it; save(KEY_FRAME_RATE_CAP, it) }
                                        if (!fullBright) {
                                            VrmSlider("Brightness", if (lightLevel == 5) "Default" else "$lightLevel", lightLevel.toFloat(), 0f..10f, 9, tint) {
                                                lightLevel = it.roundToInt(); save(KEY_LIGHT_LEVEL, lightLevel)
                                            }
                                        }
                                        // Background colour: a swatch that opens the colour wheel.
                                        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Text("Background", color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
                                            val pill = RoundedCornerShape(10.dp)
                                            Row(
                                                Modifier.clip(pill).background(Color.White.copy(alpha = 0.1f)).clickable { tap(); colorPicker = true }
                                                    .padding(start = 6.dp, end = 10.dp, top = 5.dp, bottom = 5.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Box(
                                                    Modifier.size(20.dp).clip(CircleShape).background(if (background != 0) Color(background) else dimSpaceColor(tint))
                                                        .border(1.dp, Color.White.copy(alpha = 0.6f), CircleShape)
                                                )
                                                Spacer(Modifier.width(7.dp))
                                                Text(
                                                    if (background == 0) "Pick color" else "#" + (background and 0xFFFFFF).toString(16).uppercase().padStart(6, '0'),
                                                    color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold
                                                )
                                            }
                                            Spacer(Modifier.width(8.dp))
                                            Box(
                                                Modifier.clip(pill).background(Color.White.copy(alpha = if (background != 0) 0.1f else 0.05f))
                                                    .clickable(enabled = background != 0) { tap(); background = 0; save(KEY_BACKGROUND, 0) }
                                                    .padding(horizontal = 10.dp, vertical = 7.dp)
                                            ) { Text("Reset", color = if (background != 0) Color.White else Color.White.copy(alpha = 0.4f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
                                        }
                                        val hasMedia = backgroundMedia.isNotBlank()
                                        VrmActionRow("Background Image or Video", if (hasMedia && supporter) "Change" else "Choose", tint, supporterOnly = !supporter) {
                                            if (supporter) { settingsOpen = false; pickBackground.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) }
                                            else { onClose(); Supporter.openPage() }
                                        }
                                        if (hasMedia && supporter) {
                                            VrmActionRow("Remove Background", "Remove", tint) {
                                                backgroundMedia = ""
                                                prefs.edit().putString(KEY_BACKGROUND_MEDIA, "").apply()
                                                PrivateFiles.deleteFolder(IosContext, BACKGROUND_MEDIA_FOLDER)
                                            }
                                        }
                                    }
                                    3 -> {
                                        VrmSlider(
                                            "Voice pitch",
                                            when { voicePitch == 0 -> "Natural"; voicePitch > 0 -> "+$voicePitch"; else -> "$voicePitch" },
                                            voicePitch.toFloat(), -8f..8f, 15, tint, startLabel = "Deeper", endLabel = "Higher"
                                        ) { voicePitch = it.roundToInt(); save(KEY_VOICE_PITCH, voicePitch) }
                                        Spacer(Modifier.height(8.dp))
                                        VrmBrowserOverlaySettings(
                                            enabled = overlaysEnabled,
                                            onToggleEnabled = { overlaysEnabled = it; BrowserOverlayStore.setEnabled(prefs, it) },
                                            overlays = browserOverlays, tint = tint,
                                            onAdd = { url ->
                                                val u = BrowserOverlayStore.normalizeUrl(url)
                                                if (u.isNotBlank()) {
                                                    val n = browserOverlays.size
                                                    setOverlays(browserOverlays + BrowserOverlaySpec(
                                                        id = randomUuidString(), url = u,
                                                        x = (0.06f + 0.05f * n).coerceAtMost(0.4f), y = (0.16f + 0.05f * n).coerceAtMost(0.5f)
                                                    ))
                                                }
                                            },
                                            onUpdate = { updated -> setOverlays(browserOverlays.map { if (it.id == updated.id) updated else it }) },
                                            onRemove = { id -> setOverlays(browserOverlays.filterNot { it.id == id }) }
                                        )
                                    }
                                    else -> {
                                        Text(
                                            "If the avatar looks or moves wrong, try the switch that matches. Tell Recho which ones fixed it, so they can become the default.",
                                            color = dim, fontSize = 11.sp, lineHeight = 14.sp
                                        )
                                        Spacer(Modifier.height(6.dp))
                                        Text("FACE", color = lerp(tint, Color.White, 0.5f), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                                        VrmToggleRow("Head turns the wrong way", flipHead, tint) { flipHead = it; save(KEY_FLIP_HEAD, it) }
                                        VrmToggleRow("A wink closes the wrong eye", swapFace, tint) { swapFace = it; save(KEY_SWAP_FACE, it) }
                                        VrmToggleRow("Avatar slides the opposite way to me", flipFollow, tint, sub = "With Follow my head on.") { flipFollow = it; save(KEY_FLIP_FOLLOW, it) }
                                        Spacer(Modifier.height(6.dp))
                                        Text("BODY AND HANDS", color = lerp(tint, Color.White, 0.5f), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                                        VrmToggleRow("Arms cross over the chest", swapArms, tint, sub = "Each arm follows the right hand but reaches the wrong way.") { swapArms = it; save(KEY_SWAP_ARMS, it) }
                                        VrmToggleRow("The wrong arm moves", mirrorBody, tint, sub = "Raising your right arm raises the one on the other side of the screen.") { mirrorBody = it; save(KEY_MIRROR_BODY, it) }
                                        Spacer(Modifier.height(6.dp))
                                        Text("DRAWING", color = lerp(tint, Color.White, 0.5f), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                                        VrmToggleRow("Textures look scrambled", flipTextures, tint) { flipTextures = it; save(KEY_FLIP_TEXTURES, it) }
                                        VrmToggleRow("Lashes or hair edges show as blocks", blendCutouts, tint) { blendCutouts = it; save(KEY_BLEND_CUTOUTS, it) }
                                        VrmToggleRow("Face explodes when it moves", absoluteMorphs, tint) { absoluteMorphs = it; save(KEY_ABSOLUTE_MORPHS, it) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (colorPicker) {
                ColorWheelDialog(
                    initial = if (background != 0) Color(background) else dimSpaceColor(tint),
                    title = "Background color",
                    onDismiss = { colorPicker = false },
                    onPick = { picked ->
                        // Fully opaque; 0 is kept for "default".
                        val argb = picked.toArgb() or 0xFF000000.toInt()
                        background = argb
                        save(KEY_BACKGROUND, argb)
                        colorPicker = false
                    }
                )
            }
        }
    }
}

/**
 * Small black "what the tracker sees" box: dots for the body and hand
 * joints Vision found and a dot for the face — never the camera picture
 * itself (VRM mode deliberately never shows your real face).
 */
@Composable
private fun TrackingPreview(stage: VrmStage, faceSeen: Boolean, tint: Color) {
    var sample by remember { mutableStateOf<VisionSample?>(null) }
    LaunchedEffect(stage) {
        while (true) {
            val s = stage.lastVision
            sample = if (s != null && com.mediaviewer.platform.nanoTime() - s.atNanos < 700_000_000L) s else null
            delay(90)
        }
    }
    val s = sample
    val aspect = s?.aspect ?: 0.75f
    val shape = RoundedCornerShape(10.dp)
    Canvas(
        Modifier.width(64.dp).height((64f / aspect.coerceIn(0.4f, 2.5f)).dp).clip(shape)
            .background(Color.Black.copy(alpha = 0.7f)).border(1.dp, tint.copy(alpha = 0.5f), shape)
    ) {
        // (Drawn mirrored, like the avatar: your right is on the right.)
        fun at(x: Float, y: Float) = Offset((1f - x) * size.width, (1f - y) * size.height)
        if (s != null) {
            val bodyColor = lerp(tint, Color.White, 0.5f)
            var i = 0
            while (i + 2 < s.body.size) {
                if (s.body[i + 2] >= VisionLift.MIN_CONFIDENCE) drawCircle(bodyColor, 2.2.dp.toPx(), at(s.body[i], s.body[i + 1]))
                i += 3
            }
            i = 0
            while (i + 2 < s.hands.size) {
                if (s.hands[i + 2] >= VisionLift.MIN_CONFIDENCE) drawCircle(Color.White, 1.2.dp.toPx(), at(s.hands[i], s.hands[i + 1]))
                i += 3
            }
        }
        // The face tracker's own light: on while it sees you.
        drawCircle(if (faceSeen) Color(0xFF4CD964) else Color(0xFFFF6B61), 3.dp.toPx(), Offset(size.width - 7.dp.toPx(), 7.dp.toPx()))
    }
}
