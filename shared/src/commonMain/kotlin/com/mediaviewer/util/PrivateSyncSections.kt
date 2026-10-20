package com.mediaviewer.util

import com.mediaviewer.platform.PlatformContext
import com.mediaviewer.platform.PrivateFiles
import com.mediaviewer.platform.sharedPreferences
import com.mediaviewer.util.settings.PreferenceType
import com.mediaviewer.util.settings.booleanPreferencesKey
import com.mediaviewer.util.settings.doublePreferencesKey
import com.mediaviewer.util.settings.floatPreferencesKey
import com.mediaviewer.util.settings.intPreferencesKey
import com.mediaviewer.util.settings.longPreferencesKey
import com.mediaviewer.util.settings.stringPreferencesKey
import com.mediaviewer.util.settings.stringSetPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * What Private Sync carries, one named section per store. Each saved value
 * becomes {"type": value}: "s" text, "j" text that is itself JSON (so
 * lists of drafts, notes and the like merge entry by entry), "b", "i",
 * "l", "f", "ss" (a set of texts).
 *
 * Paths to this device's own files are written as stellar-sync://private/…
 * and stellar-sync://media/… so they mean the same file on every device.
 *
 * Left out on purpose: sign-ins and tokens, caches, where you last were in
 * the app, viewing history, and settings that belong to one device (its
 * notifications, Battery Saver, loading animation, tagging, Download When
 * Liked, the custom font and VRM files). The tag database itself (tagged
 * posts, datasets) is a separate on-device database that's never read here.
 */
internal object Sections {
    const val PRIVATE_TOKEN = "stellar-sync://private"
    const val MEDIA_TOKEN = "stellar-sync://media"

    /** App settings that stay on their device. */
    private val SETTINGS_EXCLUDED = setOf(
        "bsky_access_jwt", "bsky_refresh_jwt", "bsky_did", "bsky_handle", "bsky_service_url",
        "e621_username", "e621_api_key", "bsky_other_accounts_json",
        "hub_reviews_cache_json", "hub_blogs_cache_json", "hub_cache_hydrated_at", "hub_mutuals_cache_json",
        "profile_tab_cache_json_v2", "self_avatar_url_cache", "history_json",
        "follower_scan_completed", "follower_scan_last_run_ms", "follower_scan_cursor",
        "live_active_platform", "live_expires_at_ms", "vrm_avatar_uri", "custom_font_path", "custom_font_name",
        "last_mode", "last_feed_uri", "last_e621_tags", "last_picker_tab"
    )

    /**
     * What you've looked at never leaves this device: no viewing history,
     * recently viewed or recent searches, whatever store it's in. A second
     * line of defence behind the lists above, so a new setting named like
     * one can't slip into the vault. (Status history is the one "history"
     * that syncs: the statuses you posted, listed by its exact key.)
     */
    private fun isViewingData(name: String): Boolean {
        val n = name.lowercase()
        return "history" in n || "viewed" in n || "recent" in n || "seen" in n
    }

    /** Settings each device keeps for itself: its loading animation,
     *  everything about tagging (the tagger runs on this device's own model
     *  and dataset) and Download When Liked. */
    private fun deviceOnly(name: String): Boolean {
        val n = name.lowercase()
        return n == "loading_animation" || n == "download_on_like" || "tag" in n
    }

    private fun settingSynced(name: String) =
        name !in SETTINGS_EXCLUDED && !name.startsWith(PrefKeys.BSKY_ACCOUNT_STATE_PREFIX) && !name.contains("cache") &&
            !isViewingData(name) && !deviceOnly(name)

    private class Prefs(val section: String, val file: String, private val rule: (key: String, did: String) -> Boolean) {
        fun keep(key: String, did: String) = rule(key, did) && (section == "status" || (!isViewingData(key) && !deviceOnly(key)))
    }

    private val PREFS = listOf(
        Prefs("ui", "ui_toggles") { _, _ -> true },
        // The Hub's layout is kept per account; only this one's travels.
        Prefs("hub", "hub_layout") { key, did -> key != "active_did" && key != "legacy_claimed" && (!key.contains('@') || key.contains("@$did")) },
        Prefs("local", LocalData.PREFS) { key, _ ->
            key !in setOf("browser_last_url", "battery_saver", "notify_dms", "notify_inbox", "dm_effects_played")
        },
        Prefs("status", "status_history") { key, did -> key == "entries_$did" },
        Prefs("emojiAccounts", "emoji_accounts") { key, did -> key == "saved_$did" }
    )

    suspend fun collect(ctx: PlatformContext, did: String): JsonObject {
        val roots = roots(ctx)
        val out = linkedMapOf<String, JsonElement>()
        // App settings (Blogs/Reviews lists among them).
        val settings = linkedMapOf<String, JsonElement>()
        for ((key, value) in ctx.appSettings.data.first().asMap()) {
            val name = key.name
            if (!settingSynced(name)) continue
            encode(value, roots)?.let { settings[name] = it }
        }
        out["settings"] = JsonObject(settings)
        for (p in PREFS) {
            val values = linkedMapOf<String, JsonElement>()
            for ((k, v) in ctx.sharedPreferences(p.file).getAll()) {
                if (v == null || !p.keep(k, did)) continue
                encode(v, roots)?.let { values[k] = it }
            }
            out[p.section] = JsonObject(values)
        }
        // Archived posts: only this account's.
        PostArchive.init(ctx)
        val archived = ctx.sharedPreferences("post_archive").getString("posts", null)
            ?.let { runCatching { Json.parseToJsonElement(it) as? JsonArray }.getOrNull() }.orEmpty()
            .filter { ((it as? JsonObject)?.get("did") as? JsonPrimitive)?.contentOrNull == did }
        out["archive"] = tokenize(JsonArray(archived), roots)
        return JsonObject(out)
    }

    /** Writes [changed] sections into their stores and has them read it.
     *  Called on the main thread. */
    suspend fun apply(ctx: PlatformContext, did: String, changed: Map<String, JsonElement>) {
        val roots = roots(ctx)
        (changed["settings"] as? JsonObject)?.let { incoming ->
            ctx.appSettings.edit { prefs ->
                // (A setting dropped elsewhere goes back to its default here.)
                for (key in prefs.asMap().keys.toList()) {
                    val name = key.name
                    if (!settingSynced(name)) continue
                    if (name !in incoming) prefs.remove(key)
                }
                for ((name, el) in incoming) {
                    if (!settingSynced(name)) continue
                    val o = el as? JsonObject ?: continue
                    val (t, v) = o.entries.firstOrNull() ?: continue
                    runCatching {
                        when (t) {
                            "s" -> prefs[stringPreferencesKey(name)] = untokenize(v.jsonPrimitive.content, roots)
                            "j" -> prefs[stringPreferencesKey(name)] = untokenize(v, roots).toString()
                            "b" -> prefs[booleanPreferencesKey(name)] = v.jsonPrimitive.booleanOrNull ?: return@runCatching
                            "i" -> prefs[intPreferencesKey(name)] = v.jsonPrimitive.intOrNull ?: return@runCatching
                            "l" -> prefs[longPreferencesKey(name)] = v.jsonPrimitive.longOrNull ?: return@runCatching
                            "f" -> prefs[floatPreferencesKey(name)] = v.jsonPrimitive.doubleOrNull?.toFloat() ?: return@runCatching
                            "d" -> prefs[doublePreferencesKey(name)] = v.jsonPrimitive.doubleOrNull ?: return@runCatching
                            "ss" -> prefs[stringSetPreferencesKey(name)] = (v as JsonArray).map { untokenize(it.jsonPrimitive.content, roots) }.toSet()
                        }
                    }
                }
            }
        }
        for (p in PREFS) {
            val incoming = changed[p.section] as? JsonObject ?: continue
            val sp = ctx.sharedPreferences(p.file)
            val edit = sp.edit()
            for (k in sp.getAll().keys) if (p.keep(k, did) && k !in incoming) edit.remove(k)
            for ((k, el) in incoming) {
                if (!p.keep(k, did)) continue
                val o = el as? JsonObject ?: continue
                val (t, v) = o.entries.firstOrNull() ?: continue
                runCatching {
                    when (t) {
                        "s" -> edit.putString(k, untokenize(v.jsonPrimitive.content, roots))
                        "j" -> edit.putString(k, untokenize(v, roots).toString())
                        "b" -> edit.putBoolean(k, v.jsonPrimitive.booleanOrNull ?: return@runCatching)
                        "i" -> edit.putInt(k, v.jsonPrimitive.intOrNull ?: return@runCatching)
                        "l" -> edit.putLong(k, v.jsonPrimitive.longOrNull ?: return@runCatching)
                        "f", "d" -> edit.putFloat(k, v.jsonPrimitive.doubleOrNull?.toFloat() ?: return@runCatching)
                        "ss" -> edit.putStringSet(k, (v as JsonArray).map { untokenize(it.jsonPrimitive.content, roots) }.toSet())
                    }
                }
            }
            edit.commit()
            when (p.section) {
                "ui" -> UiToggles.reload(ctx)
                "hub" -> HubLayout.reload(ctx)
                "local" -> LocalData.reload(ctx)
                "status" -> StatusHistory.reload(did)
                "emojiAccounts" -> EmojiAccounts.reload()
            }
        }
        (changed["archive"] as? JsonArray)?.let { incoming ->
            val sp = ctx.sharedPreferences("post_archive")
            val all = sp.getString("posts", null)?.let { runCatching { Json.parseToJsonElement(it) as? JsonArray }.getOrNull() }.orEmpty()
            val others = all.filter { ((it as? JsonObject)?.get("did") as? JsonPrimitive)?.contentOrNull != did }
            val mine = (untokenize(incoming, roots) as? JsonArray).orEmpty()
            val merged = (mine + others).sortedByDescending { ((it as? JsonObject)?.get("archivedAt") as? JsonPrimitive)?.longOrNull ?: 0L }
            sp.edit().putString("posts", JsonArray(merged).toString()).commit()
            PostArchive.reload(ctx)
        }
    }

    // ── Values ─────────────────────────────────────────────────────────

    private fun encode(v: Any, roots: List<Pair<String, String>>): JsonElement? = when (v) {
        is String -> {
            val t = v.trimStart()
            val asJson = if (t.startsWith("{") || t.startsWith("[")) runCatching { Json.parseToJsonElement(v) }.getOrNull() else null
            if (asJson != null && (asJson is JsonObject || asJson is JsonArray)) JsonObject(mapOf("j" to tokenize(asJson, roots)))
            else JsonObject(mapOf("s" to JsonPrimitive(tokenize(v, roots))))
        }
        is Boolean -> JsonObject(mapOf("b" to JsonPrimitive(v)))
        is Int -> JsonObject(mapOf("i" to JsonPrimitive(v)))
        is Long -> JsonObject(mapOf("l" to JsonPrimitive(v)))
        is Float -> JsonObject(mapOf("f" to JsonPrimitive(v)))
        is Double -> JsonObject(mapOf("d" to JsonPrimitive(v)))
        is Set<*> -> JsonObject(mapOf("ss" to JsonArray(v.map { JsonPrimitive(tokenize(it.toString(), roots)) }.sortedBy { it.content })))
        else -> null
    }

    /** (local prefix, token) for this device's two file folders. */
    private fun roots(ctx: PlatformContext): List<Pair<String, String>> = listOfNotNull(
        runCatching { PrivateFiles.rootUri(ctx) }.getOrNull()?.trimEnd('/')?.takeIf { it.length > 8 }?.let { it to PRIVATE_TOKEN },
        runCatching { PrivateFiles.mediaRootUri(ctx) }.getOrNull()?.trimEnd('/')?.takeIf { it.length > 8 }?.let { it to MEDIA_TOKEN }
    )

    private fun tokenize(s: String, roots: List<Pair<String, String>>): String {
        var out = s
        for ((local, token) in roots) if (out.contains(local)) out = out.replace("$local/", "$token/")
        return out
    }

    private fun untokenize(s: String, roots: List<Pair<String, String>>): String {
        var out = s
        for ((local, token) in roots) if (out.contains(token)) out = out.replace("$token/", "$local/")
        return out
    }

    private fun tokenize(e: JsonElement, roots: List<Pair<String, String>>): JsonElement = mapStrings(e) { tokenize(it, roots) }
    private fun untokenize(e: JsonElement, roots: List<Pair<String, String>>): JsonElement = mapStrings(e) { untokenize(it, roots) }

    private fun mapStrings(e: JsonElement, f: (String) -> String): JsonElement = when (e) {
        is JsonObject -> JsonObject(e.mapValues { mapStrings(it.value, f) })
        is JsonArray -> JsonArray(e.map { mapStrings(it, f) })
        is JsonPrimitive -> if (e.isString) JsonPrimitive(f(e.content)) else e
        else -> e
    }
}
