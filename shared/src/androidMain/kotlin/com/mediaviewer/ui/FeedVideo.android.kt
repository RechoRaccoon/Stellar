package com.mediaviewer.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.mediaviewer.platform.PlatformContext
import com.mediaviewer.util.FeedVideoPool

/** An ExoPlayer from [FeedVideoPool], seen through [FeedVideoPlayer]. */
internal class ExoFeedVideoPlayer(val exo: ExoPlayer) : FeedVideoPlayer {
    override val isPlaying: Boolean get() = exo.isPlaying
    override val currentPosition: Long get() = exo.currentPosition
    override val duration: Long get() = exo.duration
    override fun play() = exo.play()
    override fun pause() = exo.pause()
    override fun seekTo(positionMs: Long) = exo.seekTo(positionMs)

    override val aspectRatio: Float
        get() = exo.videoSize.let { v -> if (v.width > 0 && v.height > 0) (v.width * v.pixelWidthHeightRatio) / v.height else 0f }

    override val isBuffering: Boolean
        get() = exo.playbackState == Player.STATE_BUFFERING || exo.playbackState == Player.STATE_IDLE

    private val wrapped = HashMap<FeedVideoListener, Player.Listener>()

    override fun addListener(listener: FeedVideoListener) {
        val l = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    listener.onAspectRatio((videoSize.width * videoSize.pixelWidthHeightRatio) / videoSize.height)
                }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) = listener.onIsPlayingChanged(isPlaying)
            override fun onRenderedFirstFrame() = listener.onFirstFrame()
            override fun onPlaybackStateChanged(playbackState: Int) = listener.onBuffering(playbackState == Player.STATE_BUFFERING)
        }
        wrapped[listener] = l
        exo.addListener(l)
    }

    override fun removeListener(listener: FeedVideoListener) {
        wrapped.remove(listener)?.let { exo.removeListener(it) }
    }

    private var muted = false
    private var level = VideoVolume.level
    override fun setVolumeMuted(muted: Boolean) { this.muted = muted; exo.volume = if (muted) 0f else level }
    override fun setVolume(level: Float) { this.level = level; if (!muted) exo.volume = level }
}

actual object FeedVideos {
    actual fun acquire(context: PlatformContext, url: String): FeedVideoPlayer =
        ExoFeedVideoPlayer(FeedVideoPool.acquire(context, url))

    actual fun recycle(url: String, player: FeedVideoPlayer) {
        FeedVideoPool.recycle(url, (player as ExoFeedVideoPlayer).exo)
    }

    actual fun preload(context: PlatformContext, urls: List<String>) = FeedVideoPool.preload(context, urls)
    actual fun releaseIdle() = FeedVideoPool.releaseIdle()
}

/**
 * Every view showing each player, newest last. A player draws into one view
 * at a time — the newest takes it — so when that view goes (a profile's
 * picture drawn twice for a moment, say), the one before it takes the
 * player back instead of being left frozen on its last frame.
 */
private val viewsOf = HashMap<ExoPlayer, LinkedHashSet<PlayerView>>()

@Composable
actual fun FeedVideoView(player: FeedVideoPlayer, modifier: Modifier, fill: Boolean, cornerPercent: Int) {
    val exo = (player as ExoFeedVideoPlayer).exo
    AndroidView(
        factory = { ctx ->
            // Item 4: inflated from XML (see res/layout/player_view_texture.xml)
            // because PlayerView's surface type can only be set via XML/AttributeSet
            // at construction — there's no runtime setter for it — and it must be
            // TextureView (not the default SurfaceView) for the video to actually
            // show up in the live backdrop-blur capture.
            (android.view.LayoutInflater.from(ctx).inflate(com.mediaviewer.R.layout.player_view_texture, null) as PlayerView).apply {
                // Controller off BEFORE the player is attached: attaching
                // with it on pops the system controls up for a moment.
                useController = false
                this.player = exo
                viewsOf.getOrPut(exo) { LinkedHashSet() }.add(this)
                // The pooled player outlives this view; keep the
                // last frame instead of flashing black on reattach.
                setKeepContentOnPlayerReset(true)
                // Item 10: only the Compose-drawn buffering spinner shows.
                setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
            }
        },
        modifier = modifier,
        update = { view ->
            view.resizeMode = if (fill) androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                else androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
            if (cornerPercent > 0) {
                view.outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(v: android.view.View, outline: android.graphics.Outline) {
                        outline.setRoundRect(0, 0, v.width, v.height, minOf(v.width, v.height) * cornerPercent / 100f)
                    }
                }
                view.clipToOutline = true
            } else view.clipToOutline = false
        },
        // Detach from the pooled player when this view goes away, so
        // it can be handed to the next view cleanly.
        onRelease = { view ->
            val others = viewsOf[exo]
            // (Only the newest view is the one the player is drawing into.)
            val wasShowing = others?.lastOrNull() === view
            others?.remove(view)
            view.player = null
            if (others.isNullOrEmpty()) viewsOf.remove(exo)
            else if (wasShowing) others.last().let { back -> back.player = null; back.player = exo }
        }
    )
}
