package com.mediaviewer.util

import com.mediaviewer.platform.PlatformContext

/** iOS: a sideloaded app can't install its own update, so "Update" opens
 *  the release page (for AltStore / SideStore or a computer). */
actual object PlatformUpdater {
    actual val checks: Boolean = true
    actual val canInstall: Boolean = false
    actual fun pickAsset(names: List<String>): String? = names.firstOrNull { it.endsWith(".ipa", ignoreCase = true) }
    actual suspend fun downloadAndInstall(context: PlatformContext, url: String, name: String, version: String, onProgress: (Float) -> Unit) {
        throw UnsupportedOperationException("Open the release page to update")
    }
}
