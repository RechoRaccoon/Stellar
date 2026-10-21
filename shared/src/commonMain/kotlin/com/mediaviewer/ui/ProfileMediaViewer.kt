package com.mediaviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.mediaviewer.i18n.Text
import com.mediaviewer.ui.compat.navBarSpace
import com.mediaviewer.ui.theme.RoundedCornerShape
import com.mediaviewer.util.rememberHapticTap

/**
 * A profile's picture or banner, full screen over the blurred page: the
 * picture (or, when they have one, its video) as big as it fits. Pinch,
 * double-tap or scroll the mouse wheel to zoom in, drag to look around; a
 * tap closes it only when it isn't zoomed in (and never as part of a
 * pinch). Download, near the bottom, saves what's showing — the video
 * when there is one, else the full-size picture.
 */
@Composable
fun ProfileMediaViewer(
    did: String,
    imageUrl: String?,
    videoCid: String?,
    /** Width / height (1 for the picture; a banner's own shape). */
    aspect: Float,
    tint: Color,
    liquidGlass: Boolean,
    onDownload: () -> Unit,
    onClose: () -> Unit
) {
    com.mediaviewer.ui.compat.BackHandler(onBack = onClose)
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    // When a pinch (or wheel zoom) last happened: a tap right after one
    // is part of it, not a close.
    var lastZoomMs by remember { mutableLongStateOf(0L) }
    fun now() = com.mediaviewer.platform.currentTimeMillis()
    fun zoomTo(s: Float) {
        scale = s.coerceIn(1f, MAX_ZOOM)
        if (scale <= 1.01f) { scale = 1f; offset = Offset.Zero }
        lastZoomMs = now()
    }
    BoxWithConstraints(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.3f))
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { if (scale <= 1.01f && now() - lastZoomMs > 300) onClose() },
                    onDoubleTap = { if (scale > 1.01f) zoomTo(1f) else zoomTo(2.5f) }
                )
            }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    if (zoom != 1f) zoomTo(scale * zoom)
                    if (scale > 1f) { offset += pan; lastZoomMs = now() }
                }
            }
            .pointerInput(Unit) {
                // The mouse wheel zooms too (desktop, the web).
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent()
                        if (e.type != PointerEventType.Scroll) continue
                        val dy = e.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                        if (dy != 0f) { zoomTo(scale * (1f - dy * 0.12f)); e.changes.forEach { it.consume() } }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        val a = aspect.coerceIn(0.2f, 8f)
        val w = minOf(maxWidth * 0.94f, maxHeight * 0.8f * a)
        val h = w / a
        Box(
            Modifier.size(w, h).graphicsLayer {
                scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y
            }
        ) {
            val poster: @Composable () -> Unit = {
                if (imageUrl != null) AsyncImage(model = imageUrl, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            }
            if (videoCid != null) ProfileBannerVideo(did, videoCid, Modifier.fillMaxSize(), poster = poster, fill = false)
            else poster()
        }
        DownloadPill(
            liquidGlass, tint, onDownload,
            Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.navBarSpace).padding(bottom = 22.dp)
        )
    }
}

private const val MAX_ZOOM = 6f

/** Bluesky's full-size version of a profile picture or banner link. */
fun fullSizeProfileImage(url: String?): String? =
    url?.replace("/avatar_thumbnail/", "/avatar/")

@Composable
private fun DownloadPill(liquidGlass: Boolean, tint: Color, onClick: () -> Unit, modifier: Modifier) {
    val tap = rememberHapticTap()
    val shape = RoundedCornerShape(50)
    val content: @Composable () -> Unit = {
        Row(
            Modifier.clickable { tap(); onClick() }.height(46.dp).padding(horizontal = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(Icons.Filled.Download, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
            Text("Download", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
    }
    if (liquidGlass) LiquidGlassSurface(modifier = modifier, shape = shape, tint = tint) { content() }
    else Box(modifier.clip(shape).background(Color.Black.copy(alpha = 0.55f))) { content() }
}
