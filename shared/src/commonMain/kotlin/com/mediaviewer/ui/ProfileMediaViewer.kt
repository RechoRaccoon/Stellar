package com.mediaviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
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
 * picture (or, when they have one, its video) as big as it fits. Pinch
 * or scroll the mouse wheel to zoom in, drag to look around (never past
 * the picture's own edges); a tap closes it, zoomed in or not (but never
 * as part of a pinch). Download, near the bottom, saves what's showing —
 * the video when there is one, else the full-size picture.
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
    onClose: () -> Unit,
    /** Take the shape from the picture once it's shown (a banner's). */
    measureImage: Boolean = false
) {
    com.mediaviewer.ui.compat.BackHandler(onBack = onClose)
    var measured by remember(imageUrl) { mutableStateOf<Float?>(null) }
    // The original file as it was uploaded (not the CDN's re-encoded copy).
    var raw by remember(imageUrl) { mutableStateOf<String?>(null) }
    androidx.compose.runtime.LaunchedEffect(did, imageUrl) { raw = rawProfileImageUrl(did, imageUrl) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    // When a pinch (or wheel zoom) last happened: a tap right after one
    // is part of it, not a close.
    var lastZoomMs by remember { mutableLongStateOf(0L) }
    fun now() = com.mediaviewer.platform.currentTimeMillis()
    // The page's size and the picture's (unzoomed), in pixels: dragging
    // stops where the zoomed picture's edge meets the page's.
    var pageSize by remember { mutableStateOf(androidx.compose.ui.geometry.Size.Zero) }
    var mediaSize by remember { mutableStateOf(androidx.compose.ui.geometry.Size.Zero) }
    fun clamped(o: Offset): Offset {
        val maxX = ((mediaSize.width * scale - pageSize.width) / 2f).coerceAtLeast(0f)
        val maxY = ((mediaSize.height * scale - pageSize.height) / 2f).coerceAtLeast(0f)
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }
    fun zoomTo(s: Float) {
        scale = s.coerceIn(1f, MAX_ZOOM)
        if (scale <= 1.01f) { scale = 1f; offset = Offset.Zero }
        offset = clamped(offset)
        lastZoomMs = now()
    }
    BoxWithConstraints(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.3f))
            .onSizeChanged { pageSize = androidx.compose.ui.geometry.Size(it.width.toFloat(), it.height.toFloat()) }
            .pointerInput(Unit) {
                // (No double-tap zoom: waiting to see if a second tap comes
                // is what held every close back. Pinch or the wheel zoom.)
                detectTapGestures(
                    onTap = { if (now() - lastZoomMs > 300) onClose() }
                )
            }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    if (zoom != 1f) zoomTo(scale * zoom)
                    if (scale > 1f) { offset = clamped(offset + pan); lastZoomMs = now() }
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
        val a = (measured ?: aspect).coerceIn(0.2f, 8f)
        val w = minOf(maxWidth * 0.94f, maxHeight * 0.8f * a)
        val h = w / a
        Box(
            Modifier.size(w, h)
                .onSizeChanged { mediaSize = androidx.compose.ui.geometry.Size(it.width.toFloat(), it.height.toFloat()) }
                .graphicsLayer {
                scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y
            }
        ) {
            val measure: (coil3.compose.AsyncImagePainter.State.Success) -> Unit = { st ->
                val sz = st.painter.intrinsicSize
                if (measureImage && sz.width > 0f && sz.height > 0f && sz.width.isFinite() && sz.height.isFinite()) measured = sz.width / sz.height
            }
            val poster: @Composable () -> Unit = {
                if (imageUrl != null) Box(Modifier.fillMaxSize()) {
                    // The CDN's copy straight away (it's usually already
                    // loaded), then the original file over it once it's in.
                    AsyncImage(model = imageUrl, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize(), onSuccess = measure)
                    if (raw != null) AsyncImage(model = raw, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize(), onSuccess = measure)
                }
            }
            if (videoCid != null) ProfileBannerVideo(did, videoCid, Modifier.fillMaxSize(), poster = poster, fill = false, borrow = true)
            else poster()
        }
        DownloadPill(
            liquidGlass, tint, onDownload,
            Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.navBarSpace).padding(bottom = 22.dp)
        )
    }
}

private const val MAX_ZOOM = 6f

/** The original file behind a profile picture or banner link (Bluesky's
 *  CDN links end in ".../<did>/<cid>@jpeg"): the blob itself, straight from
 *  the account's own PDS, in whatever format and size it was uploaded. */
suspend fun rawProfileImageUrl(did: String, url: String?): String? {
    val cid = url?.substringBefore('?')?.substringAfterLast('/')?.substringBefore('@')
        ?.takeIf { it.startsWith("baf") } ?: return null
    return runCatching { com.mediaviewer.worker.BlueskyBlobResolver.resolveBlobUrl(did, cid) }.getOrNull()
}

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
    // (The page under it is already blurred, and the profile's backdrop only
    // holds its banner, so the glass is filled in here: tinted, frosted,
    // with its rim — not just an outline over the page.)
    val fill = Modifier.clip(shape).background(
        Brush.verticalGradient(listOf(lerp(tint, Color.Black, 0.25f).copy(alpha = 0.72f), lerp(tint, Color.Black, 0.5f).copy(alpha = 0.72f))),
        shape
    )
    if (liquidGlass) LiquidGlassSurface(modifier = modifier.then(fill), shape = shape, tint = tint) { content() }
    else Box(modifier.then(fill).border(1.dp, lerp(tint, Color.White, 0.4f).copy(alpha = 0.6f), shape)) { content() }
}
