package com.mediaviewer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.mediaviewer.platform.PlatformContext

/** Same pooling rules as Android's FeedVideoPool and the iOS one: a few
 *  players, the next posts' videos already loading. */
actual object FeedVideos {
    private class Entry(val url: String, val player: DesktopVideoPlayer) {
        var inUse = false
        var lastUsed = 0L
    }

    private val entries = LinkedHashMap<String, Entry>()
    private var wanted: Set<String> = emptySet()
    private const val MAX_PLAYERS = 3
    private var clock = 0L

    actual fun acquire(context: PlatformContext, url: String): FeedVideoPlayer = synchronized(this) {
        val existing = entries[url]
        if (existing != null && !existing.inUse) {
            existing.inUse = true
            existing.lastUsed = ++clock
            return existing.player
        }
        val p = DesktopVideoPlayer(url)
        if (existing == null) entries[url] = Entry(url, p).also { it.inUse = true; it.lastUsed = ++clock }
        trim()
        return p
    }

    actual fun recycle(url: String, player: FeedVideoPlayer): Unit = synchronized(this) {
        val p = player as DesktopVideoPlayer
        val e = entries[url]
        if (e == null || e.player !== p) { p.release(); return }
        e.inUse = false
        e.lastUsed = ++clock
        p.pause()
        p.seekTo(0L)
        if (url !in wanted) { entries.remove(url); p.release() }
        trim()
    }

    actual fun preload(context: PlatformContext, urls: List<String>): Unit = synchronized(this) {
        val list = urls.filter { it.isNotBlank() }.distinct().take(MAX_PLAYERS)
        wanted = list.toSet()
        entries.values.filter { !it.inUse && it.url !in wanted }.forEach { entries.remove(it.url); it.player.release() }
        for (url in list) {
            if (entries.containsKey(url)) continue
            if (entries.size >= MAX_PLAYERS) break
            entries[url] = Entry(url, DesktopVideoPlayer(url))
        }
    }

    actual fun releaseIdle(): Unit = synchronized(this) {
        wanted = emptySet()
        entries.values.filter { !it.inUse }.forEach { entries.remove(it.url); it.player.release() }
    }

    private fun trim() {
        if (entries.size <= MAX_PLAYERS) return
        val idle = entries.values.filter { !it.inUse }.sortedWith(compareBy<Entry> { it.url in wanted }.thenBy { it.lastUsed })
        for (e in idle) {
            if (entries.size <= MAX_PLAYERS) break
            entries.remove(e.url)
            e.player.release()
        }
    }
}

/** Draws [player]'s current picture, fitted inside the view (letterboxed)
 *  and turned upright. */
@Composable
internal fun DesktopVideoSurface(player: DesktopVideoPlayer, modifier: Modifier, backdropKey: Any? = null) {
    var viewRect by remember { mutableStateOf(Rect.Zero) }
    if (backdropKey != null) {
        DisposableEffect(backdropKey) { onDispose { NativeVideoBackdrop.frames.remove(backdropKey) } }
        // The frame under the glass controls, for their blur.
        LaunchedEffect(player, backdropKey) {
            snapshotFlow { player.frame to viewRect }.collect { (image, box) ->
                if (image == null || box.width < 1f || box.height < 1f) return@collect
                val ratio = player.aspectRatio.takeIf { it > 0f } ?: (box.width / box.height)
                val fitW = minOf(box.width, box.height * ratio)
                val fitH = fitW / ratio
                val fit = Rect(
                    box.left + (box.width - fitW) / 2f, box.top + (box.height - fitH) / 2f,
                    box.left + (box.width + fitW) / 2f, box.top + (box.height + fitH) / 2f
                )
                NativeVideoBackdrop.frames[backdropKey] = NativeVideoBackdrop.Frame(image, fit)
            }
        }
    }
    Canvas(modifier.onGloballyPositioned {
        val p = it.positionInRoot()
        viewRect = Rect(p.x, p.y, p.x + it.size.width, p.y + it.size.height)
    }) {
        val image = player.frame ?: return@Canvas
        val turned = player.rotation == 90 || player.rotation == 270
        // The picture's size once upright.
        val iw = if (turned) image.height.toFloat() else image.width.toFloat()
        val ih = if (turned) image.width.toFloat() else image.height.toFloat()
        val scale = minOf(size.width / iw, size.height / ih)
        val dw = iw * scale
        val dh = ih * scale
        rotate(player.rotation.toFloat(), pivot = Offset(size.width / 2f, size.height / 2f)) {
            // Drawn un-rotated around the centre, then turned.
            val rw = if (turned) dh else dw
            val rh = if (turned) dw else dh
            drawImage(
                image,
                dstOffset = IntOffset(((size.width - rw) / 2f).toInt(), ((size.height - rh) / 2f).toInt()),
                dstSize = IntSize(rw.toInt(), rh.toInt()),
                filterQuality = FilterQuality.Medium
            )
        }
    }
}

@Composable
actual fun FeedVideoView(player: FeedVideoPlayer, modifier: Modifier) {
    val key = remember { Any() }
    DesktopVideoSurface(player as DesktopVideoPlayer, modifier, backdropKey = key)
}
