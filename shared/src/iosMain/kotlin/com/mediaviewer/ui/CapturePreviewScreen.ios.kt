package com.mediaviewer.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import coil3.compose.AsyncImage
import com.mediaviewer.platform.AppEvents
import com.mediaviewer.platform.IosContext
import com.mediaviewer.platform.IosCrop
import com.mediaviewer.platform.IosDownloads
import com.mediaviewer.platform.IosUri
import com.mediaviewer.platform.MediaBridge
import com.mediaviewer.platform.PlatformUri
import com.mediaviewer.ui.compat.BackHandler
import com.mediaviewer.ui.compat.navBarSpace
import com.mediaviewer.util.rememberHapticTap
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import platform.AVFoundation.AVLayerVideoGravityResize
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerLayer
import platform.AVFoundation.AVPlayerLooper
import platform.AVFoundation.AVQueuePlayer
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSURL
import platform.QuartzCore.CATransaction
import platform.UIKit.UIColor
import platform.UIKit.UIView

/** A crop choice on the review page; [ratio] = width / height, null = none. */
private class CaptureCrop(val label: String, val ratio: Float?)

private val PORTRAIT_CROPS = listOf(
    CaptureCrop("Default", null), CaptureCrop("9:16", 9f / 16f), CaptureCrop("2:3", 2f / 3f),
    CaptureCrop("3:4", 3f / 4f), CaptureCrop("4:5", 4f / 5f), CaptureCrop("1:1", 1f)
)
private val LANDSCAPE_CROPS = listOf(
    CaptureCrop("Default", null), CaptureCrop("16:9", 16f / 9f), CaptureCrop("3:2", 3f / 2f),
    CaptureCrop("4:3", 4f / 3f), CaptureCrop("5:4", 5f / 4f), CaptureCrop("1:1", 1f)
)

/**
 * The page a photo/video from the camera or VRM mode lands on — the same
 * as Android's:
 *
 *  - Top left: back to where it was taken.
 *  - Top right: crop — Default plus the common shapes for the capture's
 *    orientation. With a crop picked, drag to position and pinch to zoom;
 *    the box shows exactly what's kept.
 *  - Middle: the capture (videos play; with a crop they loop).
 *  - Bottom: "Save to Device" (into Photos) and "Create Post". Both apply
 *    the crop first.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
actual fun CapturePreviewScreen(
    uri: PlatformUri,
    isVideo: Boolean,
    liquidGlass: Boolean,
    tint: Color,
    onClose: () -> Unit,
    onCreatePost: (PlatformUri) -> Unit
) {
    val tap = rememberHapticTap()
    val scope = rememberCoroutineScope()
    BackHandler(onBack = onClose)

    val path = remember(uri) { MediaBridge.pathOf(uri) }
    var mediaSize by remember(uri) { mutableStateOf<Pair<Int, Int>?>(null) }
    LaunchedEffect(uri) {
        mediaSize = withContext(Dispatchers.IO) {
            (if (isVideo) MediaBridge.videoDimensions(IosContext, uri) else IosCrop.imageSize(path))?.takeIf { it.first > 0 && it.second > 0 }
        }
    }
    val portrait = mediaSize?.let { it.second >= it.first } ?: true
    val crops = if (portrait) PORTRAIT_CROPS else LANDSCAPE_CROPS
    var crop by remember(uri, portrait) { mutableStateOf(crops.first()) }
    var cropMenu by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }

    val mediaAspect = mediaSize?.let { it.first.toFloat() / it.second.coerceAtLeast(1) } ?: (9f / 16f)
    val shownAspect = crop.ratio ?: mediaAspect
    val animatedAspect by animateFloatAsState(shownAspect, label = "cropAspect")

    // Positioning inside the crop: drag to move, pinch to zoom (1× = the
    // media just filling the crop). Reset whenever the crop changes.
    var cropZoom by remember(uri, crop) { mutableFloatStateOf(1f) }
    var cropPan by remember(uri, crop) { mutableStateOf(Offset.Zero) }
    /** The part of the media the crop box shows, as fractions of the whole
     *  frame (left, top, width, height) — what the export keeps. */
    var cropWindow by remember(uri, crop) { mutableStateOf<FloatArray?>(null) }

    suspend fun cropped(): PlatformUri? {
        if (crop.ratio == null) return uri
        val window = cropWindow ?: return uri
        val out = if (isVideo) IosCrop.cropVideo(path, window) else IosCrop.cropImage(path, window)
        return out?.let { IosUri("file://$it") }
    }

    Box(
        Modifier.fillMaxSize().background(dimSpaceColor(tint))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { cropMenu = false }
    ) {
        SpaceSky(tint, Modifier.matchParentSize())
        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.height(rememberTopCutoutClearance() + 56.dp))
            // The capture, as large as fits, at the chosen crop.
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 18.dp), contentAlignment = Alignment.Center) {
                val maxW = maxWidth
                val maxH = maxHeight
                val w = if (maxW / maxH > animatedAspect) maxH * animatedAspect else maxW
                val h = w / animatedAspect
                // The media's own size inside the box: exactly the box with
                // no crop; with a crop, just big enough to cover it (the
                // overflow is what you can drag into view).
                val cropping = crop.ratio != null
                val boxAspect = w / h
                val dispW = if (!cropping) w else if (mediaAspect > boxAspect) h * mediaAspect else w
                val dispH = if (!cropping) h else if (mediaAspect > boxAspect) h else w / mediaAspect
                val density = LocalDensity.current
                val boxWpx = with(density) { w.toPx() }; val boxHpx = with(density) { h.toPx() }
                val dispWpx = with(density) { dispW.toPx() }; val dispHpx = with(density) { dispH.toPx() }
                fun clampPan(p: Offset, z: Float): Offset {
                    val mx = ((dispWpx * z - boxWpx) / 2f).coerceAtLeast(0f)
                    val my = ((dispHpx * z - boxHpx) / 2f).coerceAtLeast(0f)
                    return Offset(p.x.coerceIn(-mx, mx), p.y.coerceIn(-my, my))
                }
                // Keep the export window in step with what's on screen.
                SideEffect {
                    cropWindow = if (!cropping || dispWpx <= 0f || dispHpx <= 0f) null else {
                        val cw = dispWpx * cropZoom; val ch = dispHpx * cropZoom
                        val left = ((cw - boxWpx) / 2f - cropPan.x) / cw
                        val top = ((ch - boxHpx) / 2f - cropPan.y) / ch
                        floatArrayOf(left.coerceIn(0f, 1f), top.coerceIn(0f, 1f), (boxWpx / cw).coerceIn(0f, 1f), (boxHpx / ch).coerceIn(0f, 1f))
                    }
                }
                Box(
                    Modifier.size(w, h).clip(RoundedCornerShape(22.dp)).background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    if (isVideo && !cropping) {
                        // No crop: Apple's player, with its controls.
                        InlineVideoPlayer(uri, Modifier.fillMaxSize())
                    } else if (isVideo) {
                        // Cropping: the video loops inside a view exactly the
                        // size of the crop box, which moves and scales the
                        // picture within itself — so the box shows what the
                        // export keeps and nothing spills out of it.
                        val scale = density.density
                        CropVideo(
                            path = path,
                            width = (dispWpx * cropZoom / scale).toDouble(), height = (dispHpx * cropZoom / scale).toDouble(),
                            panX = (cropPan.x / scale).toDouble(), panY = (cropPan.y / scale).toDouble(),
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Box(
                            Modifier.requiredSize(dispW, dispH).graphicsLayer {
                                scaleX = cropZoom; scaleY = cropZoom
                                translationX = cropPan.x; translationY = cropPan.y
                            }
                        ) {
                            AsyncImage(
                                model = uri.toString(), contentDescription = "Captured photo",
                                contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                    if (cropping) {
                        // The gestures sit over the media (a video's own
                        // view doesn't take touches).
                        Box(
                            Modifier.fillMaxSize().pointerInput(crop, dispWpx, dispHpx) {
                                detectTransformGestures { _, panChange, zoomChange, _ ->
                                    val before = cropZoom
                                    val z = (cropZoom * zoomChange).coerceIn(1f, 5f)
                                    if ((before > 1f && z == 1f) || (before < 5f && z == 5f)) tap()
                                    cropZoom = z
                                    cropPan = clampPan(cropPan + panChange, z)
                                }
                            }
                        )
                        // A hint the first moments after picking a crop.
                        var hintVisible by remember(crop) { mutableStateOf(true) }
                        LaunchedEffect(crop) { delay(2600); hintVisible = false }
                        val hintAlpha by animateFloatAsState(if (hintVisible) 1f else 0f, label = "cropHint")
                        if (hintAlpha > 0f) Text(
                            "Drag to position · pinch to zoom",
                            color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp)
                                .graphicsLayer { alpha = hintAlpha }
                                .clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = 0.45f))
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp)
                    .windowInsetsPadding(WindowInsets.navBarSpace).padding(bottom = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                PreviewActionButton(
                    label = if (busy == "save") "Saving…" else "Save to Device",
                    icon = { Icon(Icons.Default.Download, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp)) },
                    liquidGlass = liquidGlass, tint = tint, filled = false, enabled = busy == null, busy = busy == "save",
                    modifier = Modifier.weight(1f)
                ) {
                    tap(); busy = "save"
                    scope.launch {
                        val out = cropped()
                        val failure = if (out == null) "the crop didn't work — try Default" else IosDownloads.saveLocalFile(MediaBridge.pathOf(out), isVideo)
                        busy = null
                        AppEvents.postMessage(if (failure == null) "Saved to Photos" else "Couldn't save: $failure")
                    }
                }
                PreviewActionButton(
                    label = if (busy == "post") "Preparing…" else "Create Post",
                    icon = { Icon(Icons.Default.Edit, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp)) },
                    liquidGlass = liquidGlass, tint = tint, filled = true, enabled = busy == null, busy = busy == "post",
                    modifier = Modifier.weight(1f)
                ) {
                    tap(); busy = "post"
                    scope.launch {
                        val out = cropped()
                        busy = null
                        if (out != null) onCreatePost(out) else AppEvents.postMessage("Couldn't crop that — try Default")
                    }
                }
            }
        }

        // Top row: back (left), crop (right).
        Row(
            Modifier.fillMaxWidth().padding(top = rememberTopCutoutClearance(), start = 16.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RoundBackButton(liquidGlass = liquidGlass, tint = tint, backdrop = null, onClick = onClose, size = 44.dp)
            Spacer(Modifier.weight(1f))
            Box {
                val shape = RoundedCornerShape(22.dp)
                Row(
                    Modifier.height(44.dp).clip(shape)
                        .then(if (liquidGlass) Modifier.glassPanel(true, tint = tint, shape = shape) else Modifier.background(Color.Black.copy(alpha = 0.45f)))
                        .border(1.dp, tint.copy(alpha = 0.6f), shape)
                        .clickable(enabled = busy == null) { tap(); cropMenu = true }
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Crop, contentDescription = "Aspect ratio", tint = Color.White, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(crop.label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
                DropdownMenu(
                    expanded = cropMenu, onDismissRequest = { cropMenu = false },
                    modifier = Modifier.background(lerp(Color(0xFF151518), tint, 0.25f))
                ) {
                    for (c in crops) {
                        DropdownMenuItem(
                            text = { Text(c.label, color = Color.White, fontWeight = if (c === crop) FontWeight.Bold else FontWeight.Normal) },
                            onClick = { tap(); crop = c; cropMenu = false }
                        )
                    }
                }
            }
        }
    }
}

/** A looping video drawn [width]×[height] points, centred in its view and
 *  slid by ([panX], [panY]); whatever falls outside the view isn't shown. */
@OptIn(ExperimentalForeignApi::class, ExperimentalComposeUiApi::class)
@Composable
private fun CropVideo(path: String, width: Double, height: Double, panX: Double, panY: Double, modifier: Modifier) {
    val holder = remember(path) { CropVideoView(path) }
    DisposableEffect(holder) { onDispose { holder.stop() } }
    UIKitView(
        factory = { holder },
        modifier = modifier,
        update = { it.place(width, height, panX, panY) },
        properties = UIKitInteropProperties(interactionMode = null)
    )
}

@OptIn(ExperimentalForeignApi::class)
private class CropVideoView(path: String) : UIView(frame = CGRectMake(0.0, 0.0, 10.0, 10.0)) {
    private val player = AVQueuePlayer()
    private val looper = AVPlayerLooper.playerLooperWithPlayer(player, templateItem = AVPlayerItem(uRL = NSURL.fileURLWithPath(path)))
    private val video = AVPlayerLayer.playerLayerWithPlayer(player)
    private var w = 0.0
    private var h = 0.0
    private var x = 0.0
    private var y = 0.0

    init {
        setClipsToBounds(true)
        setBackgroundColor(UIColor.blackColor)
        // (The layer is given the video's own shape, so nothing is cut or boxed.)
        video.setVideoGravity(AVLayerVideoGravityResize)
        layer.addSublayer(video)
        player.play()
    }

    fun place(width: Double, height: Double, panX: Double, panY: Double) {
        w = width; h = height; x = panX; y = panY
        arrange()
    }

    override fun layoutSubviews() {
        super.layoutSubviews()
        arrange()
    }

    private fun arrange() {
        val (bw, bh) = bounds.useContents { size.width to size.height }
        if (w <= 0.0 || h <= 0.0) return
        // (Without the usual quarter-second glide: it follows the finger.)
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        video.setFrame(CGRectMake((bw - w) / 2.0 + x, (bh - h) / 2.0 + y, w, h))
        CATransaction.commit()
    }

    fun stop() {
        runCatching { player.pause() }
        runCatching { looper.disableLooping() }
    }
}

/** One half of the review page's bottom bar. */
@Composable
private fun PreviewActionButton(
    label: String,
    icon: @Composable () -> Unit,
    liquidGlass: Boolean,
    tint: Color,
    filled: Boolean,
    enabled: Boolean,
    busy: Boolean,
    modifier: Modifier = Modifier,
    height: Dp = 52.dp,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(26.dp)
    Box(
        modifier.height(height).clip(shape)
            .then(
                if (filled) Modifier.background(lerp(tint, Color.Black, 0.12f))
                else if (liquidGlass) Modifier.glassPanel(true, tint = tint, shape = shape)
                else Modifier.background(Color.White.copy(alpha = 0.12f))
            )
            .border(1.dp, tint.copy(alpha = 0.6f), shape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (busy) CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
            else icon()
            Text(
                label, color = Color.White.copy(alpha = if (enabled) 1f else 0.5f),
                fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1
            )
        }
    }
}
