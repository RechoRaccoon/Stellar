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
import com.mediaviewer.ui.compat.navBarSpace
import com.mediaviewer.util.rememberHapticTap

/**
 * A comment's pictures / video / GIF, under its text: one fills the
 * comment's width at its own shape; several sit in a tidy grid (up to four,
 * "+N" on the last). Rounded, with the same soft rim as Stellar's grid
 * tiles. GIFs play right there; tap anything to open it full screen.
 */
@Composable
fun CommentMediaBlock(media: List<CommentMedia>, tint: Color, onOpen: (Int) -> Unit, modifier: Modifier = Modifier) {
    if (media.isEmpty()) return
    val tap = rememberHapticTap()
    val shape = RoundedCornerShape(14.dp)
    val rim = lerp(tint, Color.White, 0.35f).copy(alpha = 0.45f)
    @Composable
    fun Tile(i: Int, m: Modifier, extra: Int = 0) {
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
            if (extra > 0) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                    Text("+$extra", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
    Column(modifier.fillMaxWidth().padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (media.size == 1) {
            // Its own shape, kept within sensible bounds.
            val a = media[0].aspect.takeIf { it > 0f }?.coerceIn(0.7f, 1.9f) ?: 1.5f
            Tile(0, Modifier.fillMaxWidth(if (a < 1f) 0.72f else 1f).heightIn(max = 300.dp).aspectRatio(a))
        } else {
            val shown = media.take(4)
            shown.chunked(2).forEachIndexed { row, pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    pair.forEachIndexed { col, _ ->
                        val i = row * 2 + col
                        val extra = if (i == 3 && media.size > 4) media.size - 4 else 0
                        // A lone last tile (3 pictures) spans the row.
                        val wide = pair.size == 1
                        Tile(i, Modifier.weight(1f).aspectRatio(if (wide) 2f else 1f), extra)
                    }
                }
            }
        }
    }
}

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
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.94f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
    ) {
        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
            val item = media[page]
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                when (item.kind) {
                    CommentMedia.Kind.IMAGE -> AsyncImage(
                        model = item.fullUrl, contentDescription = item.alt.ifBlank { null },
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onClose() }
                    )
                    CommentMedia.Kind.GIF -> AnimatedGifImage(
                        item.fullUrl, item.alt.ifBlank { null },
                        Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onClose() },
                        ContentScale.Fit
                    )
                    CommentMedia.Kind.VIDEO -> CommentVideo(item, active = pager.currentPage == page, tint = tint)
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
private fun CommentVideo(item: CommentMedia, active: Boolean, tint: Color) {
    val url = item.videoUrl ?: return
    val context = LocalContext.current
    val player = remember(url) { FeedVideos.acquire(context, url) }
    DisposableEffect(player) { onDispose { player.pause(); FeedVideos.recycle(url, player) } }
    var playing by remember(url) { mutableStateOf(false) }
    var aspect by remember(url) { mutableStateOf(player.aspectRatio.takeIf { it > 0f } ?: item.aspect.takeIf { it > 0f } ?: (16f / 9f)) }
    var firstFrame by remember(url) { mutableStateOf(false) }
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
        while (true) { playing = player.isPlaying; kotlinx.coroutines.delay(250) }
    }
    val tap = rememberHapticTap()
    Box(
        Modifier.fillMaxWidth().aspectRatio(aspect.coerceIn(0.4f, 2.5f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                tap(); if (player.isPlaying) player.pause() else player.play()
            },
        contentAlignment = Alignment.Center
    ) {
        if (!firstFrame && item.thumbUrl.isNotBlank()) {
            AsyncImage(model = item.thumbUrl, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        }
        FeedVideoView(player, Modifier.fillMaxSize())
        AnimatedVisibility(!playing, enter = fadeIn(tween(150)) + scaleIn(initialScale = 0.85f), exit = fadeOut(tween(150)) + scaleOut(targetScale = 0.85f)) {
            PlayBadge(tint)
        }
    }
}
