package com.mediaviewer.util

import androidx.compose.runtime.mutableStateMapOf
import com.mediaviewer.platform.ioDispatcher
import com.mediaviewer.json.StellarJson
import com.mediaviewer.platform.PlatformContext
import com.mediaviewer.platform.SharedPreferences
import com.mediaviewer.platform.sharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
    val colorB: Int? = null,
    /** A short looping video shown in place of the banner picture: a blob
     *  on the owner's own PDS, referenced from this same record. */
    val bannerVideo: BannerVideo? = null,
    /** A temporary status, shown in a bubble by the profile picture. */
    val status: ProfileStatus? = null,
    /** A short looping video shown in place of the profile picture (on the
     *  profile page), stored the same way as [bannerVideo]. */
    val iconVideo: BannerVideo? = null
) {
    val isDefault: Boolean get() = !squareIcon && effect == "none" && colorA == null && colorB == null &&
        bannerVideo == null && status == null && iconVideo == null
}

/** A profile video's blob (what the record's blob ref carries) — the
 *  video banner and the video icon both. */
@Serializable
data class BannerVideo(val cid: String, val mimeType: String = "video/mp4", val size: Long = 0L)

/** A custom emoji in a status: [at] is its place in the text (the index
 *  of the invisible character it stands on, see EmojiCloud.HIDDEN), the
 *  rest says where its picture is (an emoji record's blob). */
@Serializable
data class StatusEmoji(val at: Int, val did: String, val cid: String, val name: String = "", val animated: Boolean = false)

/**
 * A temporary status. [expiresAt] (ISO 8601) is when it clears itself; null
 * means it stays until it's changed. Every app hides it once that time has
 * passed — whether or not the owner's app has cleared the record yet.
 */
@Serializable
data class ProfileStatus(
    val text: String, val createdAt: String = "", val expiresAt: String? = null,
    /** Custom emoji in [text] (each one stands on an invisible character). */
    val emoji: List<StatusEmoji> = emptyList()
) {
    fun expiresAtMillis(): Long? = expiresAt?.let { runCatching { com.mediaviewer.platform.parseIsoInstantMillis(it) }.getOrNull() }
    fun isActive(nowMillis: Long = com.mediaviewer.platform.currentTimeMillis()): Boolean {
        if (text.isBlank()) return false
        val end = expiresAt ?: return true
        val endMs = expiresAtMillis() ?: return false
        return endMs > nowMillis
    }
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
        "bats" to "Bats", "hearts" to "Hearts", "rain" to "Rain", "bubbles" to "Bubbles",
        "paws" to "Paw Prints", "starshower" to "Shooting Stars"
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
        "paws" -> com.mediaviewer.ui.DmEffect.PAWPRINTS
        "starshower" -> com.mediaviewer.ui.DmEffect.STAR_SHOWER
        else -> null
    }
    fun effectLabel(id: String): String = EFFECTS.firstOrNull { it.first == id }?.second ?: "None"

    /** Customizations used to be a supporter benefit; now everyone can have
     *  them, so every account may be looked up (see [request]'s limits). */
    private fun lookupAllowed(did: String): Boolean =
        FeatureFlags.ALL_FEATURES_FREE || StellarSupporters.isSupporter(did)

    private val styles = mutableStateMapOf<String, ProfileStyle>()
    /** When each account was last looked up (whatever the answer was). */
    private val asked = HashMap<String, Long>()
    /** A record is read again after this long, so a status someone just
     *  set shows up without restarting the app. */
    private const val REREAD_MS = 10 * 60 * 1000L
    private var prefs: SharedPreferences? = null
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    /** Reads one account's style record (null = it has none). Set by the ViewModel. */
    @kotlin.concurrent.Volatile var fetcher: (suspend (did: String) -> ProfileStyle?)? = null
    /** Writes your own. Set by the ViewModel; the callback gets an error or null. */
    var saver: ((style: ProfileStyle, onDone: (String?) -> Unit) -> Unit)? = null

    fun init(context: PlatformContext) {
        if (prefs != null) return
        val p = context.sharedPreferences("profile_style")
        prefs = p
        StatusHistory.init(context)
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
    fun refresh(did: String?, force: Boolean = false) {
        if (did.isNullOrBlank() || !lookupAllowed(did)) return
        val fetch = fetcher ?: return
        // (The profile's own Refresh button reads it again now.)
        if (force) synchronizedRemove(did)
        if (!synchronizedAdd(did)) return
        com.mediaviewer.platform.synchronizedCompat(inFlight) { inFlight.add(did) }
        scope.launch {
            try {
                val result = runCatching { fetch(did) }
                withContext(Dispatchers.Main) { deliver(did, result) }
            } finally {
                com.mediaviewer.platform.synchronizedCompat(inFlight) { inFlight.remove(did) }
            }
        }
    }

    /** [did]'s style is being read right now (a profile's loading screen
     *  waits for it, so its colors and videos don't pop in after). */
    fun isLoading(did: String): Boolean = com.mediaviewer.platform.synchronizedCompat(inFlight) { did in inFlight }
    private val inFlight = HashSet<String>()

    private fun deliver(did: String, result: Result<ProfileStyle?>) {
        result.onSuccess { style ->
            val own = did == Supporter.selfDid
            val kept = styles[did]
            if (style != null && !style.isDefault) {
                styles[did] = style
                if (own) { persistOwn(did, style); scheduleStatusClear(did, style) } else cacheOther(did, style)
            } else {
                // (Yours too: this device only keeps what was last saved
                // successfully, so a record that's gone was changed on
                // another device — the server's answer wins, and a stale
                // copy here is never sent back over it.)
                styles.remove(did)
                if (own) { clearJob?.cancel(); persistOwn(did, null) } else cacheOther(did, null)
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

    private fun synchronizedAdd(did: String): Boolean = com.mediaviewer.platform.synchronizedCompat(asked) {
        val now = com.mediaviewer.platform.currentTimeMillis()
        val last = asked[did]
        if (last != null && now - last < REREAD_MS) false else { asked[did] = now; true }
    }
    private fun synchronizedRemove(did: String) { com.mediaviewer.platform.synchronizedCompat(asked) { asked.remove(did) } }

    /** Your own style, just saved (or just edited): shown straight away. */
    fun setOwn(did: String, style: ProfileStyle) {
        if (did.isBlank()) return
        synchronizedAdd(did)
        if (style.isDefault) styles.remove(did) else styles[did] = style
        persistOwn(did, style)
        scheduleStatusClear(did, style)
    }

    /** Uploads a video banner to your PDS. Set by the ViewModel; the
     *  callback gets the blob or an error. */
    var bannerUploader: ((uri: com.mediaviewer.platform.PlatformUri, onDone: (BannerVideo?, String?) -> Unit) -> Unit)? = null

    /** [did]'s status, if it hasn't run out (Compose state, like [of]). */
    fun statusOf(did: String?): ProfileStatus? = of(did)?.status?.takeIf { it.isActive() }

    /**
     * Your status runs out: while the app is open, the record is rewritten
     * without it the moment it does (and on the next start if it ran out
     * while the app was closed — see [deliver]). Nothing runs in the
     * background for it: every app already hides a status whose time has
     * passed, so the record only needs tidying, never racing.
     */
    private var clearJob: kotlinx.coroutines.Job? = null
    private fun scheduleStatusClear(did: String, style: ProfileStyle) {
        clearJob?.cancel()
        val status = style.status ?: return
        val end = status.expiresAtMillis() ?: return
        clearJob = scope.launch {
            val wait = end - com.mediaviewer.platform.currentTimeMillis()
            if (wait > 0) kotlinx.coroutines.delay(wait)
            // Tried again (a minute apart) while it fails — offline, not
            // signed in yet at startup — so the record never keeps it.
            repeat(30) {
                // What the server holds now: another of your devices may
                // have set a newer status since this one last looked. Only
                // the very status that ran out is cleared, and from the
                // server's copy — never by writing this device's older one.
                val server = fetcher?.let { f -> runCatching { f(did) } }
                var replaced: ProfileStyle? = null
                val error = withContext(Dispatchers.Main) {
                    val now = styles[did] ?: return@withContext null
                    if (now.status != status) return@withContext null
                    val remote = server?.getOrNull()
                    if (server != null && server.isSuccess && remote?.status != status) {
                        // Changed elsewhere: take the server's copy instead.
                        if (remote != null && !remote.isDefault) { styles[did] = remote; persistOwn(did, remote); replaced = remote }
                        else { styles.remove(did); persistOwn(did, null) }
                        return@withContext null
                    }
                    val base = if (server != null && server.isSuccess && remote != null) remote else now
                    val save = saver ?: return@withContext "No saver yet"
                    kotlinx.coroutines.suspendCancellableCoroutine<String?> { cont ->
                        save(base.copy(status = null)) { err -> if (cont.isActive) cont.resumeWith(Result.success(err)) }
                    }
                }
                if (error == null) {
                    // (A newer status from elsewhere gets its own clear-up.)
                    replaced?.let { r -> withContext(Dispatchers.Main) { scheduleStatusClear(did, r) } }
                    return@launch
                }
                kotlinx.coroutines.delay(if (error == "No saver yet") 5_000L else 60_000L)
            }
        }
    }

    /** At startup: your saved status gets its clear-up scheduled at once
     *  (cleared straight away if it ran out while the app was closed),
     *  without waiting for your own profile to be opened. */
    fun resumeOwnStatusClear() {
        val did = Supporter.selfDid.takeIf { it.isNotBlank() } ?: prefs?.getString("did", null) ?: return
        val style = styles[did] ?: return
        scheduleStatusClear(did, style)
    }

    private fun persistOwn(did: String, style: ProfileStyle?) {
        val p = prefs ?: return
        if (style == null || style.isDefault) p.edit().remove("did").remove("style").apply()
        else p.edit().putString("did", did).putString("style", StellarJson.default.encodeToString(ProfileStyle.serializer(), style)).apply()
    }
}
