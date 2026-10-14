package com.mediaviewer.util

import com.mediaviewer.platform.PlatformContext

/** The web version is always the newest one: nothing to check. */
actual object PlatformUpdater {
    actual val checks: Boolean = false
    actual val canInstall: Boolean = false
    actual fun pickAsset(names: List<String>): String? = null
    actual suspend fun downloadAndInstall(context: PlatformContext, url: String, name: String, version: String, onProgress: (Float) -> Unit) {
        throw UnsupportedOperationException("Not on the web")
    }
}
