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
import androidx.compose.ui.input.pointer.positionChange
import com.mediaviewer.ui.compat.LocalContext

/**
 * Explore's video tiles play, muted, while the pointer rests on them
 * (desktop and web) or while a finger holds still on one (phones). Letting
 * go after a hold doesn't open the post; a tap still does, and a finger
 * that moves (scrolling) never starts it.
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
        // Touch: a still hold.
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (down.type == PointerType.Mouse) return@awaitEachGesture
            val holdAt = down.uptimeMillis + 350
            var moved = 0f
            var holding = false
            while (true) {
                val e = awaitPointerEvent(PointerEventPass.Initial)
                val ch = e.changes.firstOrNull { it.id == down.id } ?: break
                if (!ch.pressed) {
                    // (The hold was the point: no tap to open the post.)
                    if (holding) { ch.consume(); onPreview(false) }
                    break
                }
                moved += ch.positionChange().getDistance()
                if (!holding && moved > viewConfiguration.touchSlop) break
                if (!holding && ch.uptimeMillis >= holdAt) { holding = true; onPreview(true) }
            }
            if (holding) onPreview(false)
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
