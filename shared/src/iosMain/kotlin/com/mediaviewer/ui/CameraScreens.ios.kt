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
/** The older notch at its widest (iPhone X–12: 209 points; later ones are narrower). */
private val NOTCH_WIDTH = 210.dp
/** Top insets from here up mean a Dynamic Island (notch iPhones stop at 50). */
private val ISLAND_MIN_INSET = 51.dp
/** Top insets below this mean no notch at all (iPhone SE: 20); from here
 *  up to [ISLAND_MIN_INSET] it's the older notch (44–50). */
private val NOTCH_MIN_INSET = 30.dp

/**
 * The Camera and VRM buttons on iOS: two separate bubbles in the camera
 * row, "Camera" to the left of the Dynamic Island and "VRM" to the right
 * of it. Nothing is drawn around the island itself (Android's ring around
 * the camera cutout is Android's alone), and the two bubbles are only
 * there on the Hub and the posting page ([showButtons]).
 *
 * Where they sit:
 *  - iPhones with a Dynamic Island: level with the island, one either side.
 *  - iPhones with the older notch: in the two corners beside the notch.
 *  - iPhones with no notch at all (iPhone SE): side by side at the top
 *    centre.
 *  - Landscape: not drawn (the island is on the side).
 *
 * iOS doesn't tell apps where the island is; the size and position above
 * are Apple's published ones.
 */
@Composable
actual fun CameraNotchButton(
    liquidGlass: Boolean,
    tint: Color,
    modifier: Modifier,
    interactive: Boolean,
    showButtons: Boolean,
    onOpenCamera: () -> Unit,
    onOpenVrm: () -> Unit
) {
    if (!showButtons) return
    val tap = rememberHapticTap()
    val topInset = WindowInsets.safeDrawing.asPaddingValues().calculateTopPadding()

    BoxWithConstraints(modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
        if (maxHeight < maxWidth) return@BoxWithConstraints
        val island = topInset >= ISLAND_MIN_INSET
        val notch = !island && topInset >= NOTCH_MIN_INSET

        // What the bubbles stand either side of, and how far apart from it.
        val gap = 8.dp
        val middleWidth = if (island) ISLAND_WIDTH else if (notch) NOTCH_WIDTH else 0.dp
        // The room either side of it, less a margin at the screen's edge.
        val room = (maxWidth - middleWidth) / 2 - gap - 10.dp
        val bubbleW = minOf(72.dp, room)
        val bubbleH = if (island) 32.dp else if (notch) 26.dp else 24.dp
        val centerY = if (island) topInset - ISLAND_BELOW_GAP - ISLAND_HEIGHT / 2
            else if (notch) topInset / 2 - 2.dp
            else 16.dp
        val top = (centerY - bubbleH / 2).coerceAtLeast(2.dp)
        val shape = RoundedCornerShape(50)

        @Composable
        fun Bubble(label: String, x: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
            Box(
                Modifier
                    .offset(x = x, y = top)
                    .size(bubbleW, bubbleH).clip(shape)
                    .then(
                        if (liquidGlass) Modifier.glassPanel(true, tint = tint, shape = shape)
                        else Modifier.background(tint.copy(alpha = 0.28f)).border(1.dp, tint.copy(alpha = 0.9f), shape)
                    )
                    .clickable { tap(); onClick() },
                contentAlignment = Alignment.Center
            ) {
                Text(label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
        }
        Bubble("Camera", (maxWidth - middleWidth) / 2 - gap - bubbleW, onOpenCamera)
        Bubble("VRM", (maxWidth + middleWidth) / 2 + gap, onOpenVrm)
    }
}
