package com.mediaviewer.util

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mediaviewer.model.MediaItem
import com.mediaviewer.platform.PlatformContext
import com.mediaviewer.platform.SharedPreferences
import com.mediaviewer.platform.sharedPreferences

/**
 * Adult content follows the Bluesky account's own moderation settings
 * (bsky.app → Settings → Moderation), on every platform:
 *
 *  - "Enable adult content" off → every post labeled porn / sexual /
 *    nudity is left out.
 *  - On → each of those three labels does what the account set it to:
 *    Show (shown), Warn (blurred, tap to reveal) or Hide (left out).
 *
 * Only these adult labels are followed. Every other label (graphic media,
 * self-harm, extremism, …) is ignored here, and those posts show normally.
 * "I Hate Fun" (Settings) additionally blurs every adult-labeled post that
 * is shown.
 */
object AdultContentPolicy {
    private const val PREFS = "content_policy"
    private const val KEY_ALLOWED = "bsky_adult_content_enabled"
    private const val KEY_LABEL_PREFIX = "bsky_label_"

    enum class Visibility { SHOW, WARN, HIDE }

    /** The adult labels Stellar follows, with Bluesky's defaults. */
    private val DEFAULTS = linkedMapOf(
        "porn" to Visibility.HIDE,
        "sexual" to Visibility.WARN,
        "nudity" to Visibility.SHOW
    )

    /** The account's "Enable adult content", as last read from Bluesky
     *  (remembered, so the first feed after launch already follows it). */
    var accountAllowsAdult by mutableStateOf(false)
        private set

    /** Each adult label's setting on the account. Compose state. */
    var labelVisibility by mutableStateOf<Map<String, Visibility>>(DEFAULTS)
        private set

    private var prefs: SharedPreferences? = null

    fun init(context: PlatformContext) {
        if (prefs != null) return
        val p = context.sharedPreferences(PREFS)
        prefs = p
        accountAllowsAdult = p.getBoolean(KEY_ALLOWED, false)
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
    fun update(allowed: Boolean, labels: Map<String, String>) {
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
        accountAllowsAdult = allowed
        labelVisibility = next
        prefs?.edit()?.apply {
            putBoolean(KEY_ALLOWED, allowed)
            next.forEach { (label, v) -> putString(KEY_LABEL_PREFIX + label, v.name) }
            apply()
        }
    }

    /** The strictest setting among [item]'s adult labels (null: none). */
    private fun strictest(item: MediaItem): Visibility? {
        val adult = item.labels?.mapNotNull { normalize(it) }?.takeIf { it.isNotEmpty() } ?: return null
        if (!accountAllowsAdult) return Visibility.HIDE
        return adult.maxOf { labelVisibility[it] ?: Visibility.SHOW }
    }

    private fun normalize(label: String): String? = when (label) {
        "porn", "nsfw" -> "porn"
        "sexual", "suggestive" -> "sexual"
        "nudity" -> "nudity"
        else -> null
    }

    /** True when [item] is left out everywhere. */
    fun hides(item: MediaItem): Boolean = strictest(item) == Visibility.HIDE

    /** True when [item] is shown blurred (tap to reveal). */
    fun warns(item: MediaItem): Boolean = strictest(item) == Visibility.WARN
}
