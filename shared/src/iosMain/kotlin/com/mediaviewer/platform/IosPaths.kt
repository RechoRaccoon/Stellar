package com.mediaviewer.platform

import platform.Foundation.*
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.convert
import platform.posix.mkdir

/** Where Stellar keeps its files on iOS (inside the app's sandbox). */
@OptIn(ExperimentalForeignApi::class)
object IosPaths {
    private fun ensure(dir: String): String { mkdir(dir, 0x1ED.convert()); return dir }

    /** Library/Application Support/Stellar — app data (like Android's filesDir). */
    fun filesDir(): String {
        val lib = ensure(NSHomeDirectory() + "/Library")
        val support = ensure("$lib/Application Support")
        return ensure("$support/Stellar")
    }

    /** Library/Caches/Stellar — like Android's cacheDir. */
    fun cacheDir(): String {
        val lib = ensure(NSHomeDirectory() + "/Library")
        val caches = ensure("$lib/Caches")
        return ensure("$caches/Stellar")
    }

    // ── The app's folder moves ──
    // iOS gives the app's data folder a new name (…/Application/<UUID>)
    // when the app is updated or re-installed over itself — which an
    // AltStore refresh is. The files move with it, but anything that
    // remembered one by its full path (a draft's picture, a note's video,
    // an archived post's media, a folder cover) then points at a folder
    // that's gone. Saved text is passed through [rehome] as it's read, so
    // those paths always name the folder as it is now.
    private val containerPath = Regex(
        "(Application\\\\*/)([0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12})"
    )
    private val uuidShape = Regex("[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}")
    private val currentContainer: String? by lazy {
        NSHomeDirectory().trimEnd('/').substringAfterLast('/').takeIf { uuidShape.matches(it) }
    }

    /** [text] with every path into an older copy of the app's folder
     *  pointed at the current one. */
    fun rehome(text: String): String {
        val now = currentContainer ?: return text
        if (!text.contains("Application")) return text
        return containerPath.replace(text) { m ->
            if (m.groupValues[2].equals(now, ignoreCase = true)) m.value else m.groupValues[1] + now
        }
    }

    /** SharedPreferences files. */
    fun preferencesDir(): String = ensure(filesDir() + "/prefs")
}
