package com.mediaviewer.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import com.mediaviewer.ui.compat.LocalContext

/**
 * Explore's video tiles play, muted, while the pointer rests on them
 * (desktop and web) or while a finger rests on one (phones): a resting
 * finger is the touch hover, so the preview starts the moment it lands,
 * like a mouse entering. It stops when the finger lifts or leaves the
 * tile — scrolling under a resting finger keeps it playing, because the
 * tile travels with the finger; it only ends when the finger isn't on
 * that tile anymore. A quick tap never sees it (the preview shows
 * nothing until its first frame) and still opens the post; letting go
 * after a real rest doesn't open the post.
 */
fun Modifier.videoPreviewTrigger(onPreview: (Boolean) -> Unit): Modifier = this
    .pointerInput(Unit) {
        // Mouse hover.
        awaitPointerEventScope {
            while (true) {
                val e = awaitPointerEvent(PointerEventPass.Initial)
                if (e.changes.firstOrNull()?.type != PointerType.Mouse) continue
                when (e.type) {
                    PointerEventType.Enter -> onPreview(true)
                    PointerEventType.Exit -> onPreview(false)
                }
            }
        }
    }
    .pointerInput(Unit) {
        // Touch: a resting finger. The preview starts at the press itself —
        // it used to wait for a still hold of 350 ms, checked only when a
        // pointer event arrived, and a truly still finger sends no events,
        // so the check never ran and the preview (almost) never started.
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (down.type == PointerType.Mouse) return@awaitEachGesture
            onPreview(true)
            try {
                while (true) {
                    val e = awaitPointerEvent(PointerEventPass.Initial)
                    if (e.changes.count { it.pressed } > 1) break
                    val ch = e.changes.firstOrNull { it.id == down.id } ?: break
                    if (!ch.pressed) {
                        // (A rest, not a tap: don't let go into opening the post.)
                        if (ch.uptimeMillis - down.uptimeMillis >= 350) ch.consume()
                        break
                    }
                    // Movement alone doesn't end it — scrolling with the
                    // finger still on the tile keeps it playing, since the
                    // tile travels with the finger. It ends when the finger
                    // actually leaves the tile.
                    if (ch.position.x < 0f || ch.position.y < 0f ||
                        ch.position.x > size.width || ch.position.y > size.height) break
                }
            } finally {
                onPreview(false)
            }
        }
    }

/** The muted, looping preview over a tile ([url]: the post's HLS video). */
@Composable
fun VideoTilePreview(url: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val player = remember(url) { FeedVideos.acquire(context, url) }
    var showing by remember(url) { mutableStateOf(false) }
    DisposableEffect(player) {
        val l = object : FeedVideoListener { override fun onFirstFrame() { showing = true } }
        player.addListener(l)
        player.setVolumeMuted(true)
        player.play()
        onDispose {
            player.removeListener(l)
            player.pause()
            player.setVolumeMuted(false)
            FeedVideos.recycle(url, player)
        }
    }
    LaunchedEffect(player) {
        while (!showing) {
            if (player.isPlaying && player.currentPosition > 150L) showing = true
            kotlinx.coroutines.delay(150)
        }
    }
    // (Drawn only once it's moving, so the thumbnail never flashes black.)
    Box(modifier.graphicsLayer { alpha = if (showing) 1f else 0f }) { FeedVideoView(player, Modifier.matchParentSize(), fill = true) }
}
