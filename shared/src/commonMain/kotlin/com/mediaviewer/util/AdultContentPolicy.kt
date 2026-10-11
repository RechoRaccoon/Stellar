package com.mediaviewer.util

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mediaviewer.model.MediaItem
import com.mediaviewer.platform.PlatformContext
import com.mediaviewer.platform.SharedPreferences
import com.mediaviewer.platform.sharedPreferences

/**
 * NSFW posts (labeled porn / sexual / nudity — only those labels; graphic
 * media, self-harm etc. are ignored and show normally).
 *
 * Android: one Stellar setting, "NSFW Posts": Show (default), Blur (tap
 * Show Post to see one) or Hide (left out everywhere). Bluesky's own label
 * settings aren't used at all.
 *
 * iOS: the Bluesky account's moderation settings (bsky.app → Settings →
 * Moderation), as the App Store requires:
 *  - NSFW content not enabled → shown blurred and locked; opened, it says
 *    how to enable NSFW content on Bluesky, with a button to its settings.
 *  - Enabled → each label does what the account set: Show (shown), Warn
 *    (blurred, Show Post) or Hide (left out everywhere).
 */
object AdultContentPolicy {
    private const val PREFS = "content_policy"
    private const val KEY_ALLOWED = "bsky_adult_content_enabled"
    private const val KEY_LABEL_PREFIX = "bsky_label_"
    private const val KEY_ANDROID_MODE = "nsfw_posts_mode"

    enum class Visibility { SHOW, WARN, HIDE }

    /** True on iOS: the Bluesky account's settings decide. */
    val followsBluesky: Boolean get() = com.mediaviewer.platform.currentPlatform == com.mediaviewer.platform.PlatformKind.IOS

    /** The adult labels Stellar follows, with Bluesky's defaults. */
    private val DEFAULTS = linkedMapOf(
        "porn" to Visibility.HIDE,
        "sexual" to Visibility.WARN,
        "nudity" to Visibility.SHOW
    )

    /** iOS: the account's "Enable adult content", as last read from
     *  Bluesky (remembered, so the first feed after launch follows it). */
    var accountAllowsAdult by mutableStateOf(false)
        private set

    /** iOS: each NSFW label's setting on the account. Compose state. */
    var labelVisibility by mutableStateOf<Map<String, Visibility>>(DEFAULTS)
        private set

    /** Android: Settings › NSFW Posts (Show / Blur / Hide). */
    var androidMode by mutableStateOf(Visibility.SHOW)
        private set

    fun updateAndroidMode(mode: Visibility) {
        androidMode = mode
        prefs?.edit()?.putString(KEY_ANDROID_MODE, mode.name)?.apply()
    }

    private var prefs: SharedPreferences? = null

    fun init(context: PlatformContext) {
        if (prefs != null) return
        val p = context.sharedPreferences(PREFS)
        prefs = p
        accountAllowsAdult = p.getBoolean(KEY_ALLOWED, false)
        androidMode = p.getString(KEY_ANDROID_MODE, null)?.let { runCatching { Visibility.valueOf(it) }.getOrNull() } ?: Visibility.SHOW
        labelVisibility = DEFAULTS.mapValues { (label, def) ->
            p.getString(KEY_LABEL_PREFIX + label, null)?.let { runCatching { Visibility.valueOf(it) }.getOrNull() } ?: def
        }
    }

    /**
     * Called with what was read from the account's Bluesky preferences:
     * [allowed] = adultContentPref.enabled; [labels] = contentLabelPref
     * values for Bluesky's own labels ("ignore" / "show", "warn", "hide"),
     * keyed by label (older names "nsfw" and "suggestive" mean porn and
     * sexual). Labels not set keep Bluesky's defaults.
     */
    fun update(allowed: Boolean, labels: Map<String, String>): Boolean {
        val next = DEFAULTS.toMutableMap()
        fun read(value: String?): Visibility? = when (value?.lowercase()) {
            "ignore", "show" -> Visibility.SHOW
            "warn" -> Visibility.WARN
            "hide" -> Visibility.HIDE
            else -> null
        }
        read(labels["nsfw"])?.let { next["porn"] = it }
        read(labels["suggestive"])?.let { next["sexual"] = it }
        for (label in DEFAULTS.keys) read(labels[label])?.let { next[label] = it }
        val changed = allowed != accountAllowsAdult || next != labelVisibility
        accountAllowsAdult = allowed
        labelVisibility = next
        prefs?.edit()?.apply {
            putBoolean(KEY_ALLOWED, allowed)
            next.forEach { (label, v) -> putString(KEY_LABEL_PREFIX + label, v.name) }
            apply()
        }
        return changed
    }

    /** What happens to [item]: SHOW, WARN (blurred, Show Post) or HIDE
     *  (left out; on iOS while NSFW isn't enabled, blurred and locked
     *  instead — see [lock]). Null: not an NSFW post. */
    private fun strictest(item: MediaItem): Visibility? {
        val adult = item.labels?.mapNotNull { normalize(it) }?.takeIf { it.isNotEmpty() } ?: return null
        if (!followsBluesky) return androidMode
        if (!accountAllowsAdult) return Visibility.HIDE
        return adult.maxOf { labelVisibility[it] ?: Visibility.SHOW }
    }

    private fun normalize(label: String): String? = when (label) {
        "porn", "nsfw" -> "porn"
        "sexual", "suggestive" -> "sexual"
        "nudity" -> "nudity"
        else -> null
    }

    /** True when [item] is left out of every feed and page. */
    fun filters(item: MediaItem): Boolean =
        strictest(item) == Visibility.HIDE && (!followsBluesky || accountAllowsAdult)

    /** iOS, NSFW content not enabled on Bluesky: [item] is blurred and
     *  can't be revealed here. */
    fun locked(item: MediaItem): Boolean = followsBluesky && !accountAllowsAdult && strictest(item) != null

    /** True when [item] is shown blurred with a Show Post button. */
    fun warns(item: MediaItem): Boolean = strictest(item) == Visibility.WARN

    /** Where NSFW content is managed on Bluesky. */
    const val MANAGE_URL = "https://bsky.app/moderation"
}
