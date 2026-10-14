package com.mediaviewer.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import com.mediaviewer.platform.WebBlobs
import com.mediaviewer.platform.webPathOf

/**
 * Video on the web. Compose draws the whole app on one <canvas>, and a
 * canvas can't hold a playing video, so each video is the browser's own
 * <video> element (hls.js plays Bluesky's HLS streams where the browser
 * can't by itself), placed BEHIND the canvas exactly where the post shows
 * it; the post cuts a see-through hole in the canvas there. Everything
 * Compose draws on top (menus, sheets, controls) still covers the video,
 * as on the phone.
 */
class WebVideoPlayer(val url: String, muted: Boolean = false) : FeedVideoPlayer {
    private val element: JsAny = createVideo(playableUrl(url), muted)
    private var released = false
    private val listeners = ArrayList<FeedVideoListener>()

    private var playingState by mutableStateOf(false)
    private var bufferingState by mutableStateOf(true)
    private var ratioState by mutableFloatStateOf(0f)
    private var durationState by mutableLongStateOf(0L)
    private var positionState by mutableLongStateOf(0L)
    private var firstFrameSeen = false

    init {
        listen(element) { event ->
            when (event) {
                "loadedmetadata" -> {
                    val w = videoWidth(element)
                    val h = videoHeight(element)
                    durationState = (mediaDuration(element) * 1000.0).toLong().coerceAtLeast(0L)
                    if (w > 0 && h > 0) {
                        ratioState = w.toFloat() / h
                        listeners.toList().forEach { it.onAspectRatio(ratioState) }
                    }
                }
                "durationchange" -> durationState = (mediaDuration(element) * 1000.0).toLong().coerceAtLeast(0L)
                "loadeddata" -> if (!firstFrameSeen) {
                    firstFrameSeen = true
                    setBuffering(false)
                    listeners.toList().forEach { it.onFirstFrame() }
                }
                "playing" -> { setBuffering(false); setPlaying(true) }
                "pause", "ended" -> setPlaying(false)
                "waiting" -> setBuffering(true)
                "canplay" -> setBuffering(false)
                "timeupdate" -> positionState = (mediaTime(element) * 1000.0).toLong()
            }
        }
    }

    private fun setPlaying(p: Boolean) {
        if (playingState == p) return
        playingState = p
        listeners.toList().forEach { it.onIsPlayingChanged(p) }
    }

    private fun setBuffering(b: Boolean) {
        if (bufferingState == b) return
        bufferingState = b
        listeners.toList().forEach { it.onBuffering(b) }
    }

    override val isPlaying: Boolean get() = playingState
    override val currentPosition: Long get() = if (released) 0L else (mediaTime(element) * 1000.0).toLong().also { positionState = it }
    override val duration: Long get() = durationState
    override val aspectRatio: Float get() = ratioState
    override val isBuffering: Boolean get() = bufferingState

    override fun play() { if (!released) playVideo(element) }
    override fun pause() { if (!released) pauseVideo(element) }
    override fun seekTo(positionMs: Long) { if (!released) seekVideo(element, positionMs / 1000.0) }

    fun setMuted(muted: Boolean) { if (!released) setVideoMuted(element, muted) }

    override fun addListener(listener: FeedVideoListener) { listeners += listener }
    override fun removeListener(listener: FeedVideoListener) { listeners -= listener }

    /** Where the video shows, in the page's CSS pixels; null hides it. */
    fun place(x: Double, y: Double, w: Double, h: Double) { if (!released) placeVideo(element, x, y, w, h) }
    fun hide() { if (!released) hideVideo(element) }

    fun release() {
        if (released) return
        released = true
        listeners.clear()
        destroyVideo(element)
    }

    private companion object {
        /** Stellar's own files play from a blob: URL. */
        fun playableUrl(url: String): String = webPathOf(url)?.let { WebBlobs.objectUrl(it) } ?: url
    }
}

/**
 * Shows [player]'s picture, filling [modifier] (letterboxed): the canvas
 * is cleared to see-through here, and the <video> behind it is moved to
 * match, every time this moves.
 */
@Composable
internal fun WebVideoSurface(player: WebVideoPlayer, modifier: Modifier) {
    val density = LocalDensity.current.density
    DisposableEffect(player) {
        onDispose { player.hide() }
    }
    Box(
        modifier
            .onGloballyPositioned { c ->
                val p = c.positionInWindow()
                if (!c.isAttached || c.size.width == 0 || c.size.height == 0) player.hide()
                else player.place(
                    p.x / density.toDouble(), p.y / density.toDouble(),
                    c.size.width / density.toDouble(), c.size.height / density.toDouble()
                )
            }
            .drawBehind { drawRect(Color.Black, blendMode = BlendMode.Clear) }
    )
}

// ── The <video> elements ──────────────────────────────────────────────────

private fun createVideo(url: String, muted: Boolean): JsAny = js("""(() => {
    let layer = document.getElementById('stellar-video-layer');
    if (!layer) {
        layer = document.createElement('div');
        layer.id = 'stellar-video-layer';
        layer.style.cssText = 'position:fixed;inset:0;overflow:hidden;z-index:0;pointer-events:none;background:#000';
        document.body.insertBefore(layer, document.body.firstChild);
    }
    const v = document.createElement('video');
    v.playsInline = true;
    v.setAttribute('playsinline', '');
    v.muted = muted;
    v.loop = true;
    v.preload = 'auto';
    v.style.cssText = 'position:absolute;left:0;top:0;width:0;height:0;object-fit:contain;background:#000;display:none';
    const isHls = /\.m3u8(\?|$)/i.test(url);
    if (isHls && !v.canPlayType('application/vnd.apple.mpegurl') && globalThis.Hls && globalThis.Hls.isSupported()) {
        const hls = new globalThis.Hls({ capLevelToPlayerSize: true, maxBufferLength: 20 });
        hls.loadSource(url);
        hls.attachMedia(v);
        v.__hls = hls;
    } else {
        v.src = url;
    }
    layer.appendChild(v);
    return v;
})()""")

private fun listen(v: JsAny, onEvent: (String) -> Unit): Unit = js("""{
    for (const name of ['loadedmetadata', 'durationchange', 'loadeddata', 'playing', 'pause', 'ended', 'waiting', 'canplay', 'timeupdate']) {
        v.addEventListener(name, () => onEvent(name));
    }
}""")

private fun videoWidth(v: JsAny): Int = js("v.videoWidth | 0")
private fun videoHeight(v: JsAny): Int = js("v.videoHeight | 0")
private fun mediaDuration(v: JsAny): Double = js("(isFinite(v.duration) ? v.duration : 0)")
private fun mediaTime(v: JsAny): Double = js("(v.currentTime || 0)")

/** Plays; if the browser won't play sound before the first click, plays muted. */
private fun playVideo(v: JsAny): Unit = js("""{
    const p = v.play();
    if (p && p.catch) p.catch((e) => {
        if (e && e.name === 'NotAllowedError' && !v.muted) { v.muted = true; v.play().catch(() => {}); }
    });
}""")
private fun pauseVideo(v: JsAny): Unit = js("v.pause()")
private fun seekVideo(v: JsAny, seconds: Double): Unit = js("{ try { v.currentTime = seconds; } catch (e) {} }")
private fun setVideoMuted(v: JsAny, muted: Boolean): Unit = js("{ v.muted = muted; }")

private fun placeVideo(v: JsAny, x: Double, y: Double, w: Double, h: Double): Unit = js("""{
    const s = v.style;
    s.left = x + 'px'; s.top = y + 'px'; s.width = w + 'px'; s.height = h + 'px';
    s.display = 'block';
}""")
private fun hideVideo(v: JsAny): Unit = js("{ v.style.display = 'none'; }")

private fun destroyVideo(v: JsAny): Unit = js("""{
    try { v.pause(); } catch (e) {}
    if (v.__hls) { try { v.__hls.destroy(); } catch (e) {} v.__hls = null; }
    v.removeAttribute('src');
    try { v.load(); } catch (e) {}
    v.remove();
}""")
