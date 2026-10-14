package com.mediaviewer.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.mediaviewer.platform.PlatformContext

/** Same pooling rules as Android's FeedVideoPool and the iOS one: a few
 *  players, the next posts' videos already loading. */
actual object FeedVideos {
    private class Entry(val url: String, val player: WebVideoPlayer) {
        var inUse = false
        var lastUsed = 0L
    }

    private val entries = LinkedHashMap<String, Entry>()
    private var wanted: Set<String> = emptySet()
    private const val MAX_PLAYERS = 3
    private var clock = 0L

    actual fun acquire(context: PlatformContext, url: String): FeedVideoPlayer {
        val existing = entries[url]
        if (existing != null && !existing.inUse) {
            existing.inUse = true
            existing.lastUsed = ++clock
            return existing.player
        }
        val p = WebVideoPlayer(url)
        if (existing == null) entries[url] = Entry(url, p).also { it.inUse = true; it.lastUsed = ++clock }
        trim()
        return p
    }

    actual fun recycle(url: String, player: FeedVideoPlayer) {
        val p = player as WebVideoPlayer
        val e = entries[url]
        if (e == null || e.player !== p) { p.release(); return }
        e.inUse = false
        e.lastUsed = ++clock
        p.pause()
        p.seekTo(0L)
        if (url !in wanted) { entries.remove(url); p.release() }
        trim()
    }

    actual fun preload(context: PlatformContext, urls: List<String>) {
        val list = urls.filter { it.isNotBlank() }.distinct().take(MAX_PLAYERS)
        wanted = list.toSet()
        entries.values.filter { !it.inUse && it.url !in wanted }.forEach { entries.remove(it.url); it.player.release() }
        for (url in list) {
            if (entries.containsKey(url)) continue
            if (entries.size >= MAX_PLAYERS) break
            entries[url] = Entry(url, WebVideoPlayer(url))
        }
    }

    actual fun releaseIdle() {
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

@Composable
actual fun FeedVideoView(player: FeedVideoPlayer, modifier: Modifier, fill: Boolean, cornerPercent: Int) {
    val key = androidx.compose.runtime.remember { Any() }
    val web = player as WebVideoPlayer
    androidx.compose.runtime.SideEffect { web.setLook(fill, cornerPercent) }
    // (The page cuts a hole for the video; clipping the hole rounds it.)
    val m = if (cornerPercent > 0) modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(cornerPercent)) else modifier
    WebVideoSurface(web, m, backdropKey = key)
}
