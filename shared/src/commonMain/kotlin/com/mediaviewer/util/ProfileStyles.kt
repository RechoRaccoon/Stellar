package com.mediaviewer.util

import androidx.compose.runtime.mutableStateMapOf
import com.mediaviewer.json.StellarJson
import com.mediaviewer.platform.PlatformContext
import com.mediaviewer.platform.SharedPreferences
import com.mediaviewer.platform.sharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer

/**
 * A supporter's profile customizations: icon shape, the effect their
 * profile opens with, and their two profile colors. All of it lives in ONE
 * small record on their own PDS ([ProfileStyles.COLLECTION], key "self"), so
 * everyone using Stellar sees it.
 */
@Serializable
data class ProfileStyle(
    /** A slightly rounded square profile icon instead of the round one. */
    val squareIcon: Boolean = false,
    /** "none" (the default) or another id from [ProfileStyles.EFFECTS]. */
    val effect: String = "none",
    /** The two profile colors (ARGB), or null to take them from the
     *  banner and profile picture as usual. */
    val colorA: Int? = null,
    val colorB: Int? = null
) {
    val isDefault: Boolean get() = !squareIcon && effect == "none" && colorA == null && colorB == null
}

/**
 * Everyone's profile customizations, as far as they've been needed.
 *
 * Kept deliberately light on the network (see [refresh]):
 *  - a record is only ever read when someone's PROFILE is opened — never
 *    for authors scrolling past in a feed (hundreds of reads would hit PDS
 *    rate limits); the square icon is only shown on profile pages anyway;
 *  - each account is read at most ONCE per session — one unauthenticated
 *    read of one record straight from that account's PDS (it never touches
 *    your own account's rate limits) — and the answer, including "they have
 *    none", is reused for every later open until the app is restarted;
 *  - a restart reads it again on the next open, which is how changes made
 *    on another device (or by the other person) show up. Until then the
 *    copy saved on this phone is shown.
 * Your own style is also kept on this device, so your colors and icon are
 * right from the first frame.
 */
object ProfileStyles {
    const val COLLECTION = "com.rechoraccoon.stellar.profile"
    const val RKEY = "self"
    private const val KEY_EFFECT_RESET = "effects_reset_v2"

    /** Effect id → the name shown in the picker. */
    val EFFECTS = listOf(
        "none" to "None", "confetti" to "Confetti", "balloons" to "Balloons", "snow" to "Snow", "fireworks" to "Fireworks",
        "bats" to "Bats", "hearts" to "Hearts", "rain" to "Rain", "bubbles" to "Bubbles"
    )

    /** The animation an effect id stands for (null for "none" / unknown). */
    fun effectOf(id: String?): com.mediaviewer.ui.DmEffect? = when (id) {
        "confetti" -> com.mediaviewer.ui.DmEffect.CONFETTI
        "balloons" -> com.mediaviewer.ui.DmEffect.BIRTHDAY
        "snow" -> com.mediaviewer.ui.DmEffect.SNOW
        "fireworks" -> com.mediaviewer.ui.DmEffect.FIREWORKS
        "bats" -> com.mediaviewer.ui.DmEffect.BATS
        "hearts" -> com.mediaviewer.ui.DmEffect.HEARTS
        "rain" -> com.mediaviewer.ui.DmEffect.RAIN
        "bubbles" -> com.mediaviewer.ui.DmEffect.BUBBLES
        else -> null
    }
    fun effectLabel(id: String): String = EFFECTS.firstOrNull { it.first == id }?.second ?: "None"

    /** Customizations used to be a supporter benefit; now everyone can have
     *  them, so every account may be looked up (see [request]'s limits). */
    private fun lookupAllowed(did: String): Boolean =
        FeatureFlags.ALL_FEATURES_FREE || StellarSupporters.isSupporter(did)

    private val styles = mutableStateMapOf<String, ProfileStyle>()
    /** Looked up already this session (whatever the answer was). */
    private val asked = HashSet<String>()
    private var prefs: SharedPreferences? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Reads one account's style record (null = it has none). Set by the ViewModel. */
    @kotlin.concurrent.Volatile var fetcher: (suspend (did: String) -> ProfileStyle?)? = null
    /** Writes your own. Set by the ViewModel; the callback gets an error or null. */
    var saver: ((style: ProfileStyle, onDone: (String?) -> Unit) -> Unit)? = null

    fun init(context: PlatformContext) {
        if (prefs != null) return
        val p = context.sharedPreferences("profile_style")
        prefs = p
        runCatching { loadOthers(context) }
        val did = p.getString("did", null)?.takeIf { it.isNotBlank() } ?: return
        var saved = p.getString("style", null)?.let {
            runCatching { StellarJson.default.decodeFromString(ProfileStyle.serializer(), it) }.getOrNull()
        } ?: return
        // (The old default, confetti, kept on this device from before
        // "None" existed, becomes "none" too — once.)
        if (!p.getBoolean(KEY_EFFECT_RESET, false)) {
            if (saved.effect == "confetti") saved = saved.copy(effect = "none")
            p.edit().putBoolean(KEY_EFFECT_RESET, true)
                .putString("style", StellarJson.default.encodeToString(ProfileStyle.serializer(), saved)).apply()
        }
        styles[did] = saved
    }

    /** What's known right now, without asking (safe from any thread). */
    fun peek(did: String?): ProfileStyle? =
        if (did.isNullOrBlank() || !lookupAllowed(did)) null else styles[did]

    /**
     * [did]'s style, for drawing — only what's already known (Compose state,
     * so whatever shows it redraws when a lookup lands). Never looks
     * anything up: scrolling a feed must not fire a request per author
     * (that hits PDS rate limits). Lookups happen only in [refresh], when
     * a profile is opened.
     */
    fun of(did: String?): ProfileStyle? {
        if (did.isNullOrBlank() || !lookupAllowed(did)) return null
        return styles[did]
    }

    /**
     * Opening a profile: [did]'s style record is read ONCE per session (the
     * first time their profile is opened since the app started) and the
     * answer is reused for every later open. Restarting the app reads it
     * again on the next open. A failed read (offline) is tried again on the
     * next open, never on its own.
     */
    fun refresh(did: String?) {
        if (did.isNullOrBlank() || !lookupAllowed(did)) return
        val fetch = fetcher ?: return
        if (!synchronizedAdd(did)) return
        scope.launch {
            val result = runCatching { fetch(did) }
            withContext(Dispatchers.Main) { deliver(did, result) }
        }
    }

    private fun deliver(did: String, result: Result<ProfileStyle?>) {
        result.onSuccess { style ->
            val own = did == Supporter.selfDid
            val kept = styles[did]
            if (style != null && !style.isDefault) {
                styles[did] = style
                if (own) persistOwn(did, style) else cacheOther(did, style)
            } else if (own && kept != null && !kept.isDefault) {
                // Yours is saved on this device but the server has
                // none: the device's copy stays, and is sent again.
                saver?.invoke(kept) { }
            } else {
                styles.remove(did)
                if (!own) cacheOther(did, null)
            }
        }.onFailure {
            // Offline or the PDS didn't answer: asked again the next time
            // this profile is opened. What was saved stays meanwhile.
            synchronizedRemove(did)
        }
    }

    // Other people's styles, kept on the device (up to 400) so a profile
    // opens with its icon shape and colors at once; each is still read
    // again from the network once a session.
    private var otherCache: SharedPreferences? = null
    private val cachedOrder = ArrayList<String>()

    private fun loadOthers(context: PlatformContext) {
        val c = context.sharedPreferences("profile_style_others")
        otherCache = c
        val raw = c.getString("styles", null) ?: return
        runCatching {
            val map = StellarJson.default.decodeFromString(
                kotlinx.serialization.builtins.MapSerializer(String.serializer(), ProfileStyle.serializer()), raw
            )
            map.forEach { (d, st) -> if (!styles.containsKey(d)) styles[d] = st; cachedOrder += d }
        }
    }

    private fun cacheOther(did: String, style: ProfileStyle?) {
        val c = otherCache ?: return
        cachedOrder.remove(did)
        if (style != null) cachedOrder += did
        while (cachedOrder.size > 400) cachedOrder.removeAt(0)
        val map = LinkedHashMap<String, ProfileStyle>()
        for (d in cachedOrder) styles[d]?.takeIf { d != Supporter.selfDid }?.let { map[d] = it }
        val text = StellarJson.default.encodeToString(
            kotlinx.serialization.builtins.MapSerializer(String.serializer(), ProfileStyle.serializer()), map
        )
        c.edit().putString("styles", text).apply()
    }

    private fun synchronizedAdd(did: String): Boolean = com.mediaviewer.platform.synchronizedCompat(asked) { asked.add(did) }
    private fun synchronizedRemove(did: String) { com.mediaviewer.platform.synchronizedCompat(asked) { asked.remove(did) } }

    /** Your own style, just saved (or just edited): shown straight away. */
    fun setOwn(did: String, style: ProfileStyle) {
        if (did.isBlank()) return
        synchronizedAdd(did)
        if (style.isDefault) styles.remove(did) else styles[did] = style
        persistOwn(did, style)
    }

    private fun persistOwn(did: String, style: ProfileStyle?) {
        val p = prefs ?: return
        if (style == null || style.isDefault) p.edit().remove("did").remove("style").apply()
        else p.edit().putString("did", did).putString("style", StellarJson.default.encodeToString(ProfileStyle.serializer(), style)).apply()
    }
}
