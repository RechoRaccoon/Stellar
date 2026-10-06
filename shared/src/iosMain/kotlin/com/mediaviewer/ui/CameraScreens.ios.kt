package com.mediaviewer.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.mediaviewer.platform.AppEvents
import com.mediaviewer.platform.IosDownloads
import com.mediaviewer.platform.MediaBridge
import com.mediaviewer.platform.PlatformUri
import com.mediaviewer.ui.compat.BackHandler
import com.mediaviewer.ui.compat.IosCamera
import com.mediaviewer.ui.compat.navBarSpace
import com.mediaviewer.util.rememberHapticTap
import kotlinx.coroutines.launch

// Apple's published size of the Dynamic Island, in points (= Compose dp).
// Every iPhone that has one uses the same pill; only how far down it sits
// differs, and that follows the top safe-area inset (59 → 11 from the top,
// 62 → 14): the island always ends 11 points above the safe area.
private val ISLAND_WIDTH = 126.dp
private val ISLAND_HEIGHT = 37.dp
private val ISLAND_BELOW_GAP = 11.dp
/** Top insets from here up mean a Dynamic Island (notch iPhones stop at 50). */
private val ISLAND_MIN_INSET = 51.dp
/** Top insets below this mean no notch at all (iPhone SE: 20); from here
 *  up to [ISLAND_MIN_INSET] it's the older notch (44–50). */
private val NOTCH_MIN_INSET = 30.dp

/**
 * The notch bubble on iOS: Android's ring around the camera cutout, drawn
 * around the Dynamic Island. Tapping it (or just beside it — the island
 * itself belongs to iOS) opens it out to both sides, "Camera" on the left
 * and "VRM" on the right, exactly like Android. VRM mode is Android only,
 * so its half is dimmed and says so.
 *
 * Where it's drawn:
 *  - iPhones with a Dynamic Island: around the island.
 *  - iPhones with no notch at all (iPhone SE): a small ring at the top
 *    centre, like Android phones without a cutout.
 *  - iPhones with the older notch: the notch reaches the top edge, so
 *    there's nothing to ring; a slim bubble peeks out from under it
 *    instead, in the strip between the notch and the first row of the app.
 *  - Landscape: not drawn (the island is on the side).
 *
 * iOS doesn't tell apps where the island is; the size and position above
 * are Apple's published ones. Worth a look on a real phone.
 */
@Composable
actual fun CameraNotchButton(
    liquidGlass: Boolean,
    tint: Color,
    modifier: Modifier,
    interactive: Boolean,
    onOpenCamera: () -> Unit,
    onOpenVrm: () -> Unit
) {
    val tap = rememberHapticTap()
    var expanded by remember { mutableStateOf(false) }
    // Leaving the Hub/profile while expanded collapses it.
    LaunchedEffect(interactive) { if (!interactive) expanded = false }
    val topInset = WindowInsets.safeDrawing.asPaddingValues().calculateTopPadding()

    BoxWithConstraints(modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
        if (maxHeight < maxWidth) return@BoxWithConstraints
        val island = topInset >= ISLAND_MIN_INSET
        val notch = !island && topInset >= NOTCH_MIN_INSET

        // Island: the ring sits a little outside it so it shows around it.
        // Notch: a 20-point bubble ending just above the app's first row
        // (its top edge is tucked under the notch). Neither: a small ring.
        val pad = if (island) 3.dp else 0.dp
        val ringW = if (island) ISLAND_WIDTH + pad * 2 else if (notch) 72.dp else 22.dp
        val ringH = if (island) ISLAND_HEIGHT + pad * 2 else if (notch) 20.dp else 22.dp
        val top = if (island) topInset - ISLAND_BELOW_GAP - ISLAND_HEIGHT - pad
            else if (notch) topInset - ringH - 1.dp
            else 12.dp
        val sideWidth = 64.dp
        val width by animateDpAsState(
            targetValue = if (expanded) ringW + sideWidth * 2 else ringW,
            animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessHigh),
            label = "notchBubbleWidth"
        )
        val shape = RoundedCornerShape(50)

        // Tap-away catcher: only while expanded.
        if (expanded) {
            Box(
                Modifier.fillMaxSize()
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { expanded = false }
            )
        }

        // Collapsed, the place to tap is the ring plus a band around it:
        // the island's own surface is the system's, so the taps that reach
        // Stellar are the ones just beside and below it.
        if (!expanded && interactive) {
            // (Under a notch the bubble itself can be tapped, so only a
            // little extra is needed, and none below it — that's the app.)
            val reach = if (notch) 6.dp else 12.dp
            val reachBelow = if (notch) 0.dp else reach
            val hitTop = (top - reach).coerceAtLeast(0.dp)
            Box(
                Modifier
                    .offset(x = (maxWidth - ringW) / 2 - reach, y = hitTop)
                    .size(ringW + reach * 2, top + ringH + reachBelow - hitTop)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                        tap(); expanded = true
                    }
            )
        }

        Box(
            Modifier
                .offset(x = (maxWidth - width) / 2, y = top)
                .width(width).height(ringH).clip(shape)
                .then(
                    if (liquidGlass) Modifier.glassPanel(true, tint = tint, shape = shape)
                    else Modifier.background(tint.copy(alpha = 0.28f))
                )
                // Same two hairlines as Android's ring: a light one under
                // the tinted one, so it reads over dark and light pages.
                .border(1.5.dp, Color.White.copy(alpha = 0.35f), shape)
                .border(1.dp, tint.copy(alpha = 0.9f), shape)
        ) {
            if (expanded) {
                Row(Modifier.matchParentSize()) {
                    Box(
                        Modifier.width(sideWidth).fillMaxHeight()
                            .clickable { tap(); expanded = false; onOpenCamera() },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Camera", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                    // The island's own space: tapping it just closes the menu.
                    Box(Modifier.width(ringW).fillMaxHeight().clickable { tap(); expanded = false })
                    Box(
                        Modifier.width(sideWidth).fillMaxHeight()
                            .clickable {
                                tap(); expanded = false
                                AppEvents.postMessage("VRM mode is Android only")
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("VRM", color = Color.White.copy(alpha = 0.4f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            // (Only where the bubble is tall enough for two lines.)
                            if (island) Text("Android only", color = Color.White.copy(alpha = 0.4f), fontSize = 7.sp, lineHeight = 8.sp)
                        }
                    }
                }
            }
        }
    }
}

/**
 * The notch bubble's "Camera" page on iOS: Apple's own camera, opened
 * over Stellar (see [IosCamera]). A photo or video taken there goes on to
 * the review page, or straight into the post being written when the
 * camera was opened from the posting page — the same as Android.
 * (Android's extras — voice pitch, browser windows in the picture, going
 * live — belong to its VRM pipeline and stay Android only.)
 */
@Composable
actual fun CameraModeScreen(
    liquidGlass: Boolean,
    tint: Color,
    onClose: () -> Unit,
    onCapture: (imageUri: PlatformUri?, videoUri: PlatformUri?) -> Unit
) {
    val close by rememberUpdatedState(onClose)
    val capture by rememberUpdatedState(onCapture)
    // Black behind the camera as it slides in and out.
    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
    )
    LaunchedEffect(Unit) {
        val opened = IosCamera.open { image, video ->
            if (image == null && video == null) close() else capture(image, video)
        }
        if (!opened) {
            AppEvents.postMessage("The camera isn't available on this device")
            close()
        }
    }
}

/**
 * The page a photo/video from the camera lands on: the capture, with
 * "Save to Device" (into Photos) and "Create Post" below it and a back
 * button to the camera — Android's review page without its crop menu.
 */
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
    var saving by remember { mutableStateOf(false) }
    BackHandler(onBack = onClose)

    Box(
        Modifier.fillMaxSize().background(dimSpaceColor(tint))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
    ) {
        SpaceSky(tint, Modifier.matchParentSize())
        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.height(rememberTopCutoutClearance() + 56.dp))
            // The capture, as large as fits.
            Box(
                Modifier.weight(1f).fillMaxWidth().padding(horizontal = 18.dp),
                contentAlignment = Alignment.Center
            ) {
                if (isVideo) {
                    InlineVideoPlayer(uri, Modifier.fillMaxSize().clip(RoundedCornerShape(22.dp)))
                } else {
                    AsyncImage(
                        model = uri.toString(), contentDescription = "Photo",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(22.dp))
                    )
                }
            }
            Spacer(Modifier.height(18.dp))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp)
                    .windowInsetsPadding(WindowInsets.navBarSpace).padding(bottom = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                PreviewActionButton(
                    label = if (saving) "Saving…" else "Save to Device",
                    icon = { Icon(Icons.Default.Download, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp)) },
                    liquidGlass = liquidGlass, tint = tint, filled = false, enabled = !saving, busy = saving,
                    modifier = Modifier.weight(1f)
                ) {
                    tap(); saving = true
                    scope.launch {
                        val failure = IosDownloads.saveLocalFile(MediaBridge.pathOf(uri), isVideo)
                        saving = false
                        AppEvents.postMessage(if (failure == null) "Saved to Photos" else "Couldn't save: $failure")
                    }
                }
                PreviewActionButton(
                    label = "Create Post",
                    icon = { Icon(Icons.Default.Edit, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp)) },
                    liquidGlass = liquidGlass, tint = tint, filled = true, enabled = !saving, busy = false,
                    modifier = Modifier.weight(1f)
                ) {
                    tap(); onCreatePost(uri)
                }
            }
        }

        // Top left: back to the camera.
        Box(Modifier.padding(top = rememberTopCutoutClearance(), start = 16.dp)) {
            RoundBackButton(liquidGlass = liquidGlass, tint = tint, backdrop = null, onClick = onClose, size = 44.dp)
        }
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
