package com.mediaviewer.util

import com.mediaviewer.platform.PlatformContext

/** A web page can't hear other apps' audio: the bars stay flat. */
actual object AudioVisualizerEngine {
    private val flat = FloatArray(28)
    actual val levels: FloatArray get() = flat
    actual val status: String get() = "Not available on the web"
    actual fun hasPermission(context: PlatformContext): Boolean = false
    actual fun watchPlayerSessions(context: PlatformContext) {}
    actual fun acquire(context: PlatformContext) {}
    actual fun release() {}
}
