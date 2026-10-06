package com.mediaviewer.util

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.mediaviewer.platform.IosPaths
import com.mediaviewer.platform.PlatformContext
import com.mediaviewer.platform.PlatformUri
import com.mediaviewer.platform.currentTimeMillis
import com.mediaviewer.platform.readLocalFile
import com.mediaviewer.platform.sharedPreferences
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import platform.Foundation.NSFileManager
import platform.posix.FILE
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite
import platform.posix.mkdir

/**
 * Settings → Export / Import App Data on iOS. The file is the same
 * "stellar-backup" JSON the Android app writes, so a backup made on either
 * platform can be imported on the other:
 *  - the app's settings (DataStore), including Blog/Review subscriptions;
 *  - UI toggles, Customize Hub and Add To's recent order (preference files).
 *
 * Images and videos attached to drafts, notes and bookmark-folder covers
 * travel in the same file, as a trailing "localMedia" section of base64
 * chunks. Backups without that section import as before.
 *
 * Archived posts (supporters) travel too, like on Android: the list as
 * "archivedPosts" and their files as a second trailing section,
 * "archiveMedia". On import they're added to what's already archived here.
 *
 * Left out, like on Android: sign-in tokens and per-account caches. The
 * Android-only parts of a backup (the custom font, VRM settings' avatar and
 * the AI-tagged dataset) are skipped on import here, and an iOS export
 * carries an empty tagged dataset.
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalEncodingApi::class)
actual object AppBackup {
    private const val FORMAT = "stellar-backup"
    private const val VERSION = 1
    private val SHARED_PREFS = listOf("ui_toggles", "vrm_settings", "hub_layout", "list_recency", "supporter_local")

    private val EXCLUDED = setOf(
        "bsky_access_jwt", "bsky_refresh_jwt", "bsky_did", "bsky_handle", "bsky_service_url",
        "e621_username", "e621_api_key", "bsky_other_accounts_json",
        "hub_reviews_cache_json", "hub_blogs_cache_json", "hub_cache_hydrated_at", "hub_mutuals_cache_json",
        "profile_tab_cache_json_v2", "self_avatar_url_cache", "history_json",
        "follower_scan_completed", "follower_scan_last_run_ms", "follower_scan_cursor",
        "live_active_platform", "live_expires_at_ms", "vrm_avatar_uri", "custom_font_path"
    )
    private fun excluded(name: String) = name in EXCLUDED || name.startsWith(PrefKeys.BSKY_ACCOUNT_STATE_PREFIX)

    private fun pathOf(uri: PlatformUri) = uri.toString().removePrefix("file://")

    actual suspend fun export(context: PlatformContext, uri: PlatformUri): String = withContext(Dispatchers.IO) {
        var settingsCount = 0
        val prefs = context.dataStore.data.first()
        val archivedJson = PostArchive.exportJson(context)
        val archivedCount = if (archivedJson != null) PostArchive.posts.size else 0
        val root = buildJsonObject {
            put("format", FORMAT)
            put("version", VERSION)
            put("exportedAt", currentTimeMillis())
            put("dataStore", buildJsonObject {
                for ((key, value) in prefs.asMap()) {
                    if (excluded(key.name)) continue
                    typed(value)?.let { put(key.name, it); settingsCount++ }
                }
            })
            put("sharedPrefs", buildJsonObject {
                for (name in SHARED_PREFS) {
                    put(name, buildJsonObject {
                        for ((k, v) in context.sharedPreferences(name).getAll()) {
                            if (v != null) typed(v)?.let { put(k, it) }
                        }
                    })
                }
            })
            put("taggedPosts", JsonArray(emptyList()))
            if (archivedJson != null) put("archivedPosts", archivedJson)
        }
        // Everything but the closing brace, then the local media, streamed.
        val f = fopen(pathOf(uri), "wb") ?: error("Couldn't open the chosen file for writing")
        var mediaCount = 0
        var ok = true
        try {
            fun w(s: String) { if (ok) ok = put(f, s.encodeToByteArray()) }
            w(root.toString().dropLast(1))
            /** One "files" array: each file's path under [dir] and its bytes. */
            fun files(dir: String, rels: List<String>): Int {
                var count = 0
                for (rel in rels) {
                    val bytes = readLocalFile("$dir/$rel") ?: continue
                    if (count > 0) w(",")
                    w("{\"p\":" + JsonPrimitive(rel).toString() + ",\"d\":[")
                    var at = 0
                    while (at < bytes.size) {
                        val end = minOf(bytes.size, at + CHUNK)
                        w((if (at > 0) ",\"" else "\"") + Base64.encode(bytes, at, end) + "\"")
                        at = end
                    }
                    w("]}")
                    count++
                }
                return count
            }
            w(",\"localMedia\":{\"root\":" + JsonPrimitive(mediaRoot()).toString() + ",\"files\":[")
            mediaCount = files(mediaDir(), filesUnder(mediaDir(), 2))
            w("]}")
            w(ARCHIVE_MARKER + "\"files\":[")
            if (archivedJson != null) files(archiveDir(context), filesUnder(archiveDir(context), 4))
            w("]}}")
        } finally {
            fclose(f)
        }
        if (!ok) error("Couldn't write the backup file")
        "Exported $settingsCount settings, $mediaCount media files and $archivedCount archived posts"
    }

    actual suspend fun import(context: PlatformContext, uri: PlatformUri): String = withContext(Dispatchers.IO) {
        val data = readLocalFile(pathOf(uri)) ?: error("Couldn't open the chosen file")
        // The media section, when there is one, is the tail of the file; the
        // part before it is the plain settings JSON older backups consist of.
        val mediaAt = indexOf(data, MEDIA_MARKER.encodeToByteArray(), 0)
        val text = if (mediaAt >= 0) data.decodeToString(0, mediaAt) + "}" else data.decodeToString()
        val root = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: error("That isn't a Stellar backup file")
        if (runCatching { root["format"]?.jsonPrimitive?.content }.getOrNull() != FORMAT) error("That isn't a Stellar backup file")

        var oldMediaRoot: String? = null
        var mediaCount = 0
        // (The archived posts' files, when the backup has them, follow the
        // local media.)
        val archiveAt = if (mediaAt >= 0) indexOf(data, ARCHIVE_MARKER.encodeToByteArray(), mediaAt) else -1
        if (mediaAt >= 0) runCatching {
            val (r, n) = readMedia(data, mediaAt, if (archiveAt >= 0) archiveAt else data.size, mediaDir(), findRoot = true)
            oldMediaRoot = r; mediaCount = n
        }
        if (archiveAt >= 0) runCatching { readMedia(data, archiveAt, data.size, archiveDir(context), findRoot = false) }
        // Drafts, notes and covers point at their files by full path; aim
        // those at this install's copy of the files.
        val newMediaRoot = mediaRoot()
        fun remap(name: String, s: String): String {
            val old = oldMediaRoot
            if (name != "supporter_local" || old.isNullOrEmpty() || old == newMediaRoot) return s
            return s.replace(old, newMediaRoot).replace(old.replace("/", "\\/"), newMediaRoot.replace("/", "\\/"))
        }

        var settingsCount = 0
        val ds = root["dataStore"] as? JsonObject
        context.dataStore.edit { prefs ->
            ds?.forEach { (name, el) ->
                if (excluded(name) || el !is JsonObject) return@forEach
                if (applyTyped(prefs, name, el)) settingsCount++
            }
        }

        (root["sharedPrefs"] as? JsonObject)?.forEach { (name, el) ->
            if (name !in SHARED_PREFS || el !is JsonObject) return@forEach
            val editor = context.sharedPreferences(name).edit()
            for ((k, v) in el) {
                val o = v as? JsonObject ?: continue
                val t = runCatching { o["t"]?.jsonPrimitive?.content }.getOrNull() ?: continue
                val value = o["v"] ?: continue
                runCatching {
                    when (t) {
                        "bool" -> editor.putBoolean(k, value.jsonPrimitive.boolean)
                        "int" -> editor.putInt(k, value.jsonPrimitive.int)
                        "long" -> editor.putLong(k, value.jsonPrimitive.long)
                        "float", "double" -> editor.putFloat(k, value.jsonPrimitive.float)
                        "string" -> editor.putString(k, remap(name, value.jsonPrimitive.content))
                        "set" -> editor.putStringSet(k, value.jsonArray.map { it.jsonPrimitive.content }.toSet())
                        else -> return@runCatching
                    }
                    settingsCount++
                }
            }
            editor.commit()
        }
        // iOS rebuilds the app in place after an import (there's no
        // process restart), so the stores read the new files now.
        runCatching { com.mediaviewer.ui.SharedAppStartup.reloadAfterImport(context) }
        val archivedCount = runCatching {
            (root["archivedPosts"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { PostArchive.importJson(context, it) }
        }.getOrNull() ?: 0
        "Imported $settingsCount settings, $mediaCount media files and $archivedCount archived posts"
    }

    private const val CHUNK = 180_000 // bytes per base64 chunk (a multiple of 3)
    private const val MEDIA_MARKER = ",\"localMedia\":{"
    private const val ARCHIVE_MARKER = ",\"archiveMedia\":{"

    private fun mediaDir(): String = IosPaths.filesDir() + "/local_media"

    /** The prefix every stored draft/note/cover file URI on this device starts with. */
    private fun mediaRoot(): String = "file://" + mediaDir()

    private fun list(dir: String): List<String>? =
        NSFileManager.defaultManager.contentsOfDirectoryAtPath(dir, null)?.map { it.toString() }

    /** Where archived posts keep their files (PrivateFiles' "archive" folder). */
    private fun archiveDir(context: PlatformContext): String =
        com.mediaviewer.platform.PrivateFiles.rootUri(context).removePrefix("file://") + "/" + PostArchive.FOLDER

    /** Every file under [dir], as its path below it ("folder/name"), looking
     *  at most [depth] folders deep. */
    private fun filesUnder(dir: String, depth: Int, prefix: String = ""): List<String> {
        val out = ArrayList<String>()
        for (name in list(dir) ?: emptyList()) {
            val children = if (depth > 1) list("$dir/$name") else null
            if (children == null) out.add(prefix + name)
            else out.addAll(filesUnder("$dir/$name", depth - 1, "$prefix$name/"))
        }
        return out
    }

    /** mkdir -p for a path made of [dir] plus the folders in [parts]. */
    private fun makeDirs(dir: String, parts: List<String>) {
        var d = dir
        for (part in parts) {
            d = "$d/$part"
            mkdir(d, 0x1ED.convert())
        }
    }

    private fun put(f: CPointer<FILE>, bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return true
        var written = 0
        bytes.usePinned { pinned ->
            while (written < bytes.size) {
                val n = fwrite(pinned.addressOf(written), 1u, (bytes.size - written).toULong(), f).toInt()
                if (n <= 0) break
                written += n
            }
        }
        return written == bytes.size
    }

    private fun indexOf(data: ByteArray, pattern: ByteArray, from: Int, until: Int = data.size): Int {
        val first = pattern[0]
        var i = from
        val last = minOf(until, data.size) - pattern.size
        while (i <= last) {
            if (data[i] == first) {
                var j = 1
                while (j < pattern.size && data[i + j] == pattern[j]) j++
                if (j == pattern.size) return i
            }
            i++
        }
        return -1
    }

    /** Index of the quote closing the JSON string whose first character is at [from]. */
    private fun stringEnd(data: ByteArray, from: Int): Int {
        var i = from
        while (i < data.size) {
            val c = data[i].toInt()
            if (c == '\\'.code) i++ else if (c == '"'.code) return i
            i++
        }
        return -1
    }

    private fun jsonString(data: ByteArray, openQuote: Int, closeQuote: Int): String =
        Json.parseToJsonElement(data.decodeToString(openQuote, closeQuote + 1)).jsonPrimitive.content

    /** Walks one media section ([start] until [end]), writing each file
     *  into [dir]. Returns the exporting device's media root (looked for
     *  only when [findRoot]) and how many files were restored. */
    private fun readMedia(data: ByteArray, start: Int, end: Int, dir: String, findRoot: Boolean): Pair<String?, Int> {
        var root: String? = null
        val rootKey = "\"root\":\"".encodeToByteArray()
        val fileKey = "{\"p\":\"".encodeToByteArray()
        val dataKey = "\"d\":[".encodeToByteArray()
        val quote = byteArrayOf('"'.code.toByte())
        var pos = start
        if (findRoot) {
            val firstFile = indexOf(data, fileKey, pos, end)
            val r = indexOf(data, rootKey, pos, end)
            if (r >= 0 && (firstFile < 0 || r < firstFile)) {
                val open = r + rootKey.size - 1
                val close = stringEnd(data, open + 1)
                if (close > 0) root = jsonString(data, open, close)
            }
        }
        makeDirs(dir.substringBeforeLast('/'), listOf(dir.substringAfterLast('/')))
        var count = 0
        while (true) {
            val p = indexOf(data, fileKey, pos, end)
            if (p < 0) break
            val open = p + fileKey.size - 1
            val close = stringEnd(data, open + 1)
            if (close < 0 || close >= end) break
            val rel = jsonString(data, open, close)
            val d = indexOf(data, dataKey, close, end)
            if (d < 0) break
            pos = d + dataKey.size
            val parts = rel.split('/')
            val safe = rel.isNotBlank() && parts.size <= 4 && parts.none { it.isEmpty() || it == "." || it == ".." }
            if (safe && parts.size > 1) makeDirs(dir, parts.dropLast(1))
            val f = if (safe) fopen("$dir/$rel", "wb") else null
            var ok = f != null
            try {
                while (pos < end) {
                    val c = data[pos].toInt()
                    if (c == '"'.code) {
                        val e = indexOf(data, quote, pos + 1, end)
                        if (e < 0) { pos = end; break }
                        if (f != null && ok) ok = put(f, Base64.decode(data, pos + 1, e))
                        pos = e + 1
                    } else if (c == ']'.code) {
                        pos++; break
                    } else {
                        pos++
                    }
                }
            } finally {
                if (f != null) fclose(f)
            }
            if (ok) count++
        }
        return root to count
    }

    private fun typed(value: Any): JsonObject? = when (value) {
        is Boolean -> buildJsonObject { put("t", "bool"); put("v", value) }
        is Int -> buildJsonObject { put("t", "int"); put("v", value) }
        is Long -> buildJsonObject { put("t", "long"); put("v", value) }
        is Float -> buildJsonObject { put("t", "float"); put("v", value) }
        is Double -> buildJsonObject { put("t", "double"); put("v", value) }
        is String -> buildJsonObject { put("t", "string"); put("v", value) }
        is Set<*> -> buildJsonObject {
            put("t", "set")
            put("v", JsonArray(value.filterIsInstance<String>().map { JsonPrimitive(it) }))
        }
        else -> null
    }

    private fun applyTyped(prefs: MutablePreferences, name: String, o: JsonObject): Boolean {
        val t = runCatching { o["t"]?.jsonPrimitive?.content }.getOrNull() ?: return false
        val v: JsonElement = o["v"] ?: return false
        return runCatching {
            when (t) {
                "bool" -> prefs[booleanPreferencesKey(name)] = v.jsonPrimitive.boolean
                "int" -> prefs[intPreferencesKey(name)] = v.jsonPrimitive.int
                "long" -> prefs[longPreferencesKey(name)] = v.jsonPrimitive.long
                "float" -> prefs[floatPreferencesKey(name)] = v.jsonPrimitive.float
                "double" -> prefs[doublePreferencesKey(name)] = v.jsonPrimitive.double
                "string" -> prefs[stringPreferencesKey(name)] = v.jsonPrimitive.content
                "set" -> prefs[stringSetPreferencesKey(name)] = v.jsonArray.map { it.jsonPrimitive.content }.toSet()
                else -> return@runCatching false
            }
            true
        }.getOrDefault(false)
    }
}
