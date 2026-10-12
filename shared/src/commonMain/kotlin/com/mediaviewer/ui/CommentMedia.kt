package com.mediaviewer.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.mediaviewer.model.CommentMedia
import com.mediaviewer.ui.compat.LocalContext
import com.mediaviewer.ui.compat.coilContext
import com.mediaviewer.ui.compat.navBarSpace
import com.mediaviewer.util.rememberHapticTap

/**
 * A comment's pictures / video / GIF, under its text — each at its full
 * shape (nothing cropped), kept small: one is at most [MAX_SINGLE] tall;
 * several sit side by side in a row at [ROW_HEIGHT] (scrolls if they don't
 * fit). Rounded, with the same soft rim as Stellar's grid tiles. GIFs play
 * right there; tap anything to open it full screen — full pictures are
 * fetched ahead so that's quick.
 */
@Composable
fun CommentMediaBlock(media: List<CommentMedia>, tint: Color, onOpen: (Int) -> Unit, modifier: Modifier = Modifier) {
    if (media.isEmpty()) return
    val tap = rememberHapticTap()
    val shape = RoundedCornerShape(12.dp)
    val rim = lerp(tint, Color.White, 0.35f).copy(alpha = 0.45f)
    val context = LocalContext.current
    // Full pictures fetched before they're tapped, so they open at once.
    // (Videos aren't: the player pool's preload belongs to the feed.)
    LaunchedEffect(media) {
        val loader = coil3.SingletonImageLoader.get(context.coilContext)
        media.filter { it.kind == CommentMedia.Kind.IMAGE && it.fullUrl.isNotBlank() }.forEach { m ->
            loader.enqueue(coil3.request.ImageRequest.Builder(context.coilContext).data(m.fullUrl).build())
        }
    }
    @Composable
    fun Tile(i: Int, m: Modifier) {
        val item = media[i]
        Box(
            m.clip(shape).background(Color.White.copy(alpha = 0.05f)).border(1.dp, rim, shape)
                .clickable { tap(); onOpen(i) },
            contentAlignment = Alignment.Center
        ) {
            when (item.kind) {
                CommentMedia.Kind.GIF -> AnimatedGifImage(item.fullUrl, item.alt.ifBlank { null }, Modifier.fillMaxSize(), ContentScale.Crop)
                else -> AsyncImage(
                    model = item.thumbUrl.ifBlank { item.fullUrl }, contentDescription = item.alt.ifBlank { null },
                    contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()
                )
            }
            if (item.kind == CommentMedia.Kind.VIDEO) PlayBadge(tint, Modifier.align(Alignment.Center))
            if (item.kind == CommentMedia.Kind.GIF) CornerLabel("GIF")
        }
    }
    // Its own shape; an unknown shape counts as 4:3.
    fun aspectOf(i: Int) = media[i].aspect.takeIf { it > 0f }?.coerceIn(0.4f, 2.5f) ?: (4f / 3f)
    if (media.size == 1) {
        androidx.compose.foundation.layout.BoxWithConstraints(modifier.fillMaxWidth().padding(top = 6.dp)) {
            // Exactly the picture's shape: as wide as fits, but never taller
            // than MAX_SINGLE (worked out here, so it can never spill past
            // its space onto the text around it).
            val a = aspectOf(0)
            val w = minOf(maxWidth, MAX_SINGLE * a)
            Tile(0, Modifier.size(w, w / a))
        }
    } else {
        androidx.compose.foundation.lazy.LazyRow(
            modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(media.size) { i ->
                Tile(i, Modifier.size(ROW_HEIGHT * aspectOf(i), ROW_HEIGHT))
            }
        }
    }
}

private val MAX_SINGLE = 200.dp
private val ROW_HEIGHT = 130.dp

@Composable
private fun PlayBadge(tint: Color, modifier: Modifier = Modifier) {
    Box(
        modifier.size(44.dp).clip(CircleShape)
            .background(lerp(tint, Color.Black, 0.35f).copy(alpha = 0.7f))
            .border(1.dp, Color.White.copy(alpha = 0.35f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Default.PlayArrow, contentDescription = "Play", tint = Color.White, modifier = Modifier.size(26.dp))
    }
}

@Composable
private fun BoxScope.CornerLabel(text: String) {
    Box(
        Modifier.align(Alignment.BottomStart).padding(6.dp).clip(RoundedCornerShape(6.dp))
            .background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 5.dp, vertical = 1.dp)
    ) { Text(text, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
}

/**
 * A comment's media full screen, over the comments: swipe between them, a
 * video plays (tap to pause), a GIF loops. The round X — or Back, or a tap
 * on a picture — closes it.
 */
@Composable
fun CommentMediaViewer(media: List<CommentMedia>, startIndex: Int, tint: Color, liquidGlass: Boolean, backdrop: GlassBackdrop?, onClose: () -> Unit) {
    val tap = rememberHapticTap()
    com.mediaviewer.ui.compat.BackHandler(onBack = onClose)
    val pager = rememberPagerState(initialPage = startIndex.coerceIn(0, (media.size - 1).coerceAtLeast(0))) { media.size }
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.3f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
    ) {
        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
            val item = media[page]
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                when (item.kind) {
                    CommentMedia.Kind.IMAGE -> Box(
                        Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onClose() }
                    ) {
                        // The thumbnail (already loaded) at once, the full
                        // picture over it as soon as it's in.
                        AsyncImage(model = item.thumbUrl.ifBlank { item.fullUrl }, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                        if (item.fullUrl != item.thumbUrl) {
                            AsyncImage(model = item.fullUrl, contentDescription = item.alt.ifBlank { null }, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                        }
                    }
                    CommentMedia.Kind.GIF -> AnimatedGifImage(
                        item.fullUrl, item.alt.ifBlank { null },
                        Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onClose() },
                        ContentScale.Fit
                    )
                    CommentMedia.Kind.VIDEO -> CommentVideo(item, active = pager.currentPage == page, tint = tint, liquidGlass = liquidGlass, backdrop = backdrop)
                }
            }
        }
        // Close.
        Box(Modifier.windowInsetsPadding(WindowInsets.navBarSpace).padding(top = rememberTopCutoutClearance()).padding(12.dp)) {
            val m = Modifier.size(40.dp)
            @Composable
            fun X() {
                Box(Modifier.fillMaxSize().clickable { tap(); onClose() }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White, modifier = Modifier.size(20.dp))
                }
            }
            if (liquidGlass) LiquidGlassSurface(m, shape = CircleShape, tint = tint, backdrop = backdrop) { X() }
            else Box(m.clip(CircleShape).background(Color.White.copy(alpha = 0.14f))) { X() }
        }
        // Which one of several.
        if (media.size > 1) {
            Row(
                Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.navBarSpace).padding(bottom = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                media.indices.forEach { i ->
                    Box(
                        Modifier.size(if (i == pager.currentPage) 8.dp else 6.dp).clip(CircleShape)
                            .background(if (i == pager.currentPage) vividAccent(tint) else Color.White.copy(alpha = 0.4f))
                    )
                }
            }
        }
    }
}

/** A comment's video, full screen: plays while it's the page shown; tap to
 *  pause / play. */
@Composable
private fun CommentVideo(item: CommentMedia, active: Boolean, tint: Color, liquidGlass: Boolean, backdrop: GlassBackdrop?) {
    val url = item.videoUrl ?: return
    val context = LocalContext.current
    val player = remember(url) { FeedVideos.acquire(context, url) }
    DisposableEffect(player) { onDispose { player.pause(); FeedVideos.recycle(url, player) } }
    var playing by remember(url) { mutableStateOf(false) }
    var aspect by remember(url) { mutableStateOf(player.aspectRatio.takeIf { it > 0f } ?: item.aspect.takeIf { it > 0f } ?: (16f / 9f)) }
    var firstFrame by remember(url) { mutableStateOf(false) }
    // The same controls as a video post: tap shows / hides them; play,
    // back / forward 10 seconds and the seek bar.
    var controls by remember(url) { mutableStateOf(true) }
    var positionMs by remember(url) { mutableStateOf(0L) }
    var durationMs by remember(url) { mutableStateOf(0L) }
    var seeking by remember(url) { mutableStateOf(false) }
    var seekPreviewMs by remember(url) { mutableStateOf(0L) }
    DisposableEffect(player) {
        val l = object : FeedVideoListener {
            override fun onAspectRatio(ratio: Float) { if (ratio > 0f) aspect = ratio }
            override fun onFirstFrame() { firstFrame = true }
        }
        player.addListener(l)
        onDispose { player.removeListener(l) }
    }
    LaunchedEffect(active) { if (active) player.play() else player.pause() }
    LaunchedEffect(player) {
        while (true) {
            playing = player.isPlaying
            if (player.duration > 0) durationMs = player.duration
            if (!seeking) positionMs = player.currentPosition.coerceAtLeast(0L)
            kotlinx.coroutines.delay(200)
        }
    }
    fun seekTo(ms: Long) {
        val target = ms.coerceIn(0L, if (durationMs > 0) durationMs else Long.MAX_VALUE)
        positionMs = target
        player.seekTo(target)
    }
    Box(
        Modifier.fillMaxSize()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { controls = !controls },
        contentAlignment = Alignment.Center
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(aspect.coerceIn(0.4f, 2.5f)), contentAlignment = Alignment.Center) {
            if (!firstFrame && item.thumbUrl.isNotBlank()) {
                AsyncImage(model = item.thumbUrl, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            }
            FeedVideoView(player, Modifier.fillMaxSize())
        }
        AnimatedVisibility(
            visible = controls, enter = fadeIn(tween(160)), exit = fadeOut(tween(160)),
            modifier = Modifier.align(Alignment.Center)
        ) {
            VideoTransportButtons(
                liquidGlass = liquidGlass, dominantColor = tint, backdrop = backdrop,
                isPlaying = playing,
                onPlayPause = { if (player.isPlaying) player.pause() else player.play(); playing = !playing },
                onSkip = { delta -> seekTo(positionMs + delta) }
            )
        }
        AnimatedVisibility(
            visible = controls, enter = fadeIn(tween(160)), exit = fadeOut(tween(160)),
            modifier = Modifier.align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navBarSpace)
                .padding(start = 14.dp, end = 14.dp, bottom = 30.dp)
        ) {
            VideoSeekBar(
                liquidGlass = liquidGlass, dominantColor = tint, backdrop = backdrop,
                positionMs = if (seeking) seekPreviewMs else positionMs,
                durationMs = durationMs,
                onSeeking = { ms -> seeking = true; seekPreviewMs = ms },
                onSeekFinish = { seeking = false; seekTo(seekPreviewMs) }
            )
        }
    }
}
