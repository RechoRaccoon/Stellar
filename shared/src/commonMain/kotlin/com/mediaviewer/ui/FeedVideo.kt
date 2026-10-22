package com.mediaviewer.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.mediaviewer.platform.PlatformContext
import com.mediaviewer.platform.sharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/** The transport controls the feed's own (Compose-drawn) video UI needs. */
interface VideoController {
    val isPlaying: Boolean
    /** Milliseconds. */
    val currentPosition: Long
    /** Milliseconds; 0 or less while unknown. */
    val duration: Long
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
}

/** What a feed video reports back while it plays. */
interface FeedVideoListener {
    /** Width / height of the picture (pixel aspect already applied). */
    fun onAspectRatio(ratio: Float) {}
    fun onIsPlayingChanged(playing: Boolean) {}
    fun onFirstFrame() {}
    fun onBuffering(buffering: Boolean) {}
}

/** A pooled feed video player (ExoPlayer on Android, AVPlayer on iOS). */
interface FeedVideoPlayer : VideoController {
    /** Current picture aspect ratio, or 0 while unknown. */
    val aspectRatio: Float
    /** Still loading (buffering or not yet prepared). */
    val isBuffering: Boolean
    fun addListener(listener: FeedVideoListener)
    fun removeListener(listener: FeedVideoListener)
    /** Silences (or un-silences) the sound — a profile's video banner
     *  always plays muted. */
    fun setVolumeMuted(muted: Boolean) {}
    /** 0–1: how loud it plays while not muted (see [VideoVolume]). */
    fun setVolume(level: Float) {}
    /** Plays again after stopping for any reason — a load that failed
     *  included (that one is loaded afresh). Still loading: left to it. */
    fun restart() { if (!isBuffering) play() }
}

/** The video player's volume slider: one level for every video, half
 *  to begin with, remembered between launches. */
object VideoVolume {
    var level by androidx.compose.runtime.mutableStateOf(0.5f)
        private set
    private var prefs: com.mediaviewer.platform.SharedPreferences? = null

    fun init(context: PlatformContext) {
        if (prefs != null) return
        prefs = context.sharedPreferences("video_volume").also { level = it.getFloat("level", 0.5f).coerceIn(0f, 1f) }
    }

    fun set(value: Float) {
        level = value.coerceIn(0f, 1f)
        prefs?.edit()?.putFloat("level", level)?.apply()
    }
}

/**
 * Feed video players, pooled and pre-prepared: the next posts' videos are
 * already loading while the current one plays, so swiping to them starts
 * instantly.
 */
expect object FeedVideos {
    fun acquire(context: PlatformContext, url: String): FeedVideoPlayer
    fun recycle(url: String, player: FeedVideoPlayer)
    fun preload(context: PlatformContext, urls: List<String>)
    fun releaseIdle()
}

/**
 * Draws [player]'s picture (no native controls), filling [modifier].
 * [fill]: crop the picture to fill the view instead of letterboxing it
 * (profile video banners and icons). [cornerPercent]: round the view's
 * corners by this percent of its shorter side (50 = a circle) — native
 * video views don't follow Compose's own clipping everywhere.
 */
@Composable
expect fun FeedVideoView(player: FeedVideoPlayer, modifier: Modifier, fill: Boolean = false, cornerPercent: Int = 0)
