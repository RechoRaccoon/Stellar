package com.mediaviewer.util

import com.mediaviewer.platform.PlatformContext

/**
 * The feed's audio visualizer source: bar levels (0..1) that move to
 * whatever music is playing. Android listens to the audio output; the web
 * hears the sound shared with it (a tab, or the whole screen's sound,
 * through the browser's own share prompt); a computer listens to a
 * "loopback" input (Stereo Mix on Windows, BlackHole on a Mac, a monitor
 * on Linux). iOS can't hear other apps' audio at all, so there it stays
 * flat (and the setting is shown as not available).
 */
expect object AudioVisualizerEngine {
    /** Current bar heights, 0..1 — Compose state (read it while drawing). */
    val levels: FloatArray

    /** Plain-language state for the Settings row ("" = never ran). */
    val status: String

    fun hasPermission(context: PlatformContext): Boolean

    /** Starts noting music apps' audio sessions (once permission is granted). */
    fun watchPlayerSessions(context: PlatformContext)

    /** Main thread. Starts listening while at least one caller holds it. */
    fun acquire(context: PlatformContext)

    /** Main thread. */
    fun release()

    /** The web: asks the browser for audio to listen to (it must come
     *  from a tap). A computer: looks for a loopback input again.
     *  Elsewhere nothing. */
    fun startListening(context: PlatformContext)

    /** True while there's a sound source (Android/iOS: always true). */
    val listening: Boolean
}
