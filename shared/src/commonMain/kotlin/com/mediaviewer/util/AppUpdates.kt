package com.mediaviewer.util

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mediaviewer.json.StellarJson
import com.mediaviewer.network.PlainHttp
import com.mediaviewer.network.bodyString
import com.mediaviewer.network.isSuccessful
import com.mediaviewer.platform.PlatformContext
import com.mediaviewer.platform.SharedPreferences
import com.mediaviewer.platform.sharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Settings → Check for Updates: the newest release on GitHub
 * (RechoRaccoon/Stellar), compared with [com.mediaviewer.AppVersion]. When
 * there's a newer one, a popup offers it — Android downloads the APK and
 * opens the system installer, Windows and macOS the installer for this
 * computer; iOS can only open the release page (a sideloaded app can't
 * update itself); the web version is always the newest already.
 */
object AppUpdates {
    const val REPO = "RechoRaccoon/Stellar"
    /** The release the shared tag dataset's exports are attached to (never an app release). */
    const val TAG_DATASETS_RELEASE = "tag-datasets"
    private const val CHECK_EVERY_MS = 12 * 60 * 60 * 1000L

    data class Release(val version: String, val title: String, val notes: String, val pageUrl: String, val assets: Map<String, String>)

    /** A newer release than this one (shown as a popup), or null. */
    var available by mutableStateOf<Release?>(null)
        private set
    /** Downloading: 0 to 1 (or -1 while the size isn't known); null when not. */
    var progress by mutableStateOf<Float?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    /** Settings → Check for Updates. */
    var enabled by mutableStateOf(true)
        private set

    private var prefs: SharedPreferences? = null
    private val scope = CoroutineScope(SupervisorJob() + com.mediaviewer.platform.ioDispatcher)

    fun init(context: PlatformContext) {
        if (prefs != null) return
        val p = context.sharedPreferences("app_updates")
        prefs = p
        enabled = p.getBoolean("enabled", true)
    }

    fun updateEnabled(on: Boolean) {
        enabled = on
        prefs?.edit()?.putBoolean("enabled", on)?.apply()
    }

    /** At most twice a day on its own; [force] (Check Now) always asks.
     *  [onDone] gets whether an update was found, or an error. */
    fun check(context: PlatformContext, force: Boolean = false, onDone: (Boolean, String?) -> Unit = { _, _ -> }) {
        init(context)
        if (!PlatformUpdater.checks) return
        val p = prefs ?: return
        val now = com.mediaviewer.platform.currentTimeMillis()
        if (!force && (!enabled || now - p.getLong("last_check", 0L) < CHECK_EVERY_MS)) return
        scope.launch {
            val result = runCatching { latest() }
            withContext(Dispatchers.Main) {
                result.onSuccess { release ->
                    p.edit().putLong("last_check", now).apply()
                    val newer = release != null && isNewer(release.version, com.mediaviewer.AppVersion.NAME)
                    val skipped = release != null && !force && p.getString("skipped", null) == release.version
                    if (newer && !skipped) { available = release; error = null }
                    onDone(newer, null)
                }.onFailure { onDone(false, it.message ?: "Couldn't check for updates") }
            }
        }
    }

    /** "Later": hidden until the next check. */
    fun later() { available = null; error = null }

    /** "Skip This Version": not offered again (until a newer one). */
    fun skip(release: Release) {
        prefs?.edit()?.putString("skipped", release.version)?.apply()
        later()
    }

    /** "Update": downloads and installs (or opens the release page). */
    fun install(context: PlatformContext, release: Release) {
        if (progress != null) return
        val name = PlatformUpdater.pickAsset(release.assets.keys.toList())
        val url = name?.let { release.assets[it] }
        if (!PlatformUpdater.canInstall || name == null || url == null) {
            com.mediaviewer.ui.compat.openUrl(context, release.pageUrl)
            return
        }
        error = null
        progress = -1f
        scope.launch {
            val result = runCatching {
                PlatformUpdater.downloadAndInstall(context, url, name, release.version) { f ->
                    scope.launch(Dispatchers.Main) { progress = f }
                }
            }
            withContext(Dispatchers.Main) {
                progress = null
                result.onFailure { error = it.message ?: "The update couldn't be installed" }
            }
        }
    }

    private suspend fun latest(): Release? {
        // The newest published app release. (Not /releases/latest: the
        // shared tag dataset's "tag-datasets" release could be that one.)
        val resp = PlainHttp.get("https://api.github.com/repos/$REPO/releases?per_page=15", listOf("Accept" to "application/vnd.github+json"))
        if (!resp.isSuccessful) error("GitHub answered ${resp.code}")
        val list = StellarJson.default.parseToJsonElement(resp.bodyString()) as? JsonArray ?: return null
        val o = list.firstOrNull { r ->
            val ro = r as? JsonObject ?: return@firstOrNull false
            fun flag(k: String) = (ro[k] as? JsonPrimitive)?.content == "true"
            val t = (ro["tag_name"] as? JsonPrimitive)?.content.orEmpty()
            !flag("draft") && !flag("prerelease") && t != TAG_DATASETS_RELEASE
        } as? JsonObject ?: return null
        fun text(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val tag = text("tag_name") ?: return null
        val assets = (o["assets"] as? JsonArray).orEmpty().mapNotNull { a ->
            val ao = a as? JsonObject ?: return@mapNotNull null
            val n = (ao["name"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            val u = (ao["browser_download_url"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            n to u
        }.toMap()
        return Release(
            version = tag.trim().removePrefix("v").removePrefix("V"),
            title = text("name") ?: "Stellar $tag",
            notes = text("body").orEmpty().replace("\r\n", "\n").trim(),
            pageUrl = text("html_url") ?: "https://github.com/$REPO/releases/latest",
            assets = assets
        )
    }

    /** "2.10" is newer than "2.9"; missing parts count as 0. */
    fun isNewer(candidate: String, current: String): Boolean {
        val a = candidate.split('.', '-').map { it.toIntOrNull() ?: 0 }
        val b = current.split('.', '-').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}

/** Each platform's part of [AppUpdates]. */
expect object PlatformUpdater {
    /** Whether this version checks for updates at all (not the web). */
    val checks: Boolean
    /** Whether it can download and install one itself (Android, desktop). */
    val canInstall: Boolean
    /** Which of a release's files is for this device, or null. */
    fun pickAsset(names: List<String>): String?
    /** Downloads [url] and hands it to the system installer. Throws with a
     *  readable message if it can't. */
    suspend fun downloadAndInstall(context: PlatformContext, url: String, name: String, version: String, onProgress: (Float) -> Unit)
}
