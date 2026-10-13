package com.mediaviewer.platform

import java.io.File

/** Which desktop system Stellar is running on. */
object DesktopOs {
    private val name = System.getProperty("os.name").orEmpty().lowercase()
    val isWindows: Boolean = name.startsWith("windows")
    val isMac: Boolean = name.startsWith("mac") || name.contains("darwin")
}

/**
 * Where Stellar keeps its files on a computer:
 *  - Windows: %APPDATA%\Stellar (data) and %LOCALAPPDATA%\Stellar\Cache;
 *  - macOS: ~/Library/Application Support/Stellar and ~/Library/Caches/Stellar;
 *  - anything else: the XDG folders.
 * (Set STELLAR_HOME to keep everything in one folder instead.)
 */
object DesktopPaths {
    private val home = File(System.getProperty("user.home"))

    private fun ensure(dir: File): File = dir.apply { mkdirs() }

    private val override: File? = System.getenv("STELLAR_HOME")?.takeIf { it.isNotBlank() }?.let { File(it) }

    val filesDir: File by lazy {
        ensure(
            override?.let { File(it, "data") } ?: when {
                DesktopOs.isWindows -> File(System.getenv("APPDATA")?.takeIf { it.isNotBlank() } ?: File(home, "AppData/Roaming").path, "Stellar")
                DesktopOs.isMac -> File(home, "Library/Application Support/Stellar")
                else -> File(System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() } ?: File(home, ".local/share").path, "stellar")
            }
        )
    }

    val cacheDir: File by lazy {
        ensure(
            override?.let { File(it, "cache") } ?: when {
                DesktopOs.isWindows -> File(System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() } ?: File(home, "AppData/Local").path, "Stellar/Cache")
                DesktopOs.isMac -> File(home, "Library/Caches/Stellar")
                else -> File(System.getenv("XDG_CACHE_HOME")?.takeIf { it.isNotBlank() } ?: File(home, ".cache").path, "stellar")
            }
        )
    }

    /** SharedPreferences files. */
    val preferencesDir: File by lazy { ensure(File(filesDir, "prefs")) }

    /** Where "Save" puts downloaded pictures and videos: the user's
     *  Pictures folder, in a "Stellar" folder of its own. */
    val savedMediaDir: File
        get() {
            val pictures = File(home, "Pictures").takeIf { it.isDirectory } ?: File(home, "Downloads").takeIf { it.isDirectory } ?: home
            return ensure(File(pictures, "Stellar"))
        }
}

/** A file as the "file:///…" uri the rest of the app passes around
 *  (spaces and other characters escaped, Windows drive letters kept). */
fun File.toFileUri(): String = absoluteFile.toPath().toUri().toString()

/** The local file a "file:…" uri (or a plain path) names, or null for a
 *  remote one. Lenient: unescaped spaces and "file://C:/…" forms work too. */
fun fileOfUri(uri: String): File? {
    if (uri.isBlank()) return null
    if (uri.startsWith("http://") || uri.startsWith("https://")) return null
    if (!uri.startsWith("file:")) return File(uri)
    runCatching { return java.nio.file.Paths.get(java.net.URI(uri)).toFile() }
    var path = uri.removePrefix("file:").trimStart('/')
    path = runCatching { java.net.URLDecoder.decode(path.replace("+", "%2B"), "UTF-8") }.getOrDefault(path)
    // "C:/…" on Windows; everything else is absolute from "/".
    return if (path.length > 1 && path[1] == ':') File(path) else File("/$path")
}
