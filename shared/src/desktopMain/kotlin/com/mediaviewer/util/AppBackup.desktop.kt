package com.mediaviewer.util


import com.mediaviewer.platform.DesktopPaths
import com.mediaviewer.platform.PlatformContext
import com.mediaviewer.platform.PlatformUri
import com.mediaviewer.platform.file
import com.mediaviewer.platform.sharedPreferences
import com.mediaviewer.platform.toFileUri
import java.util.Base64
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.google.gson.stream.JsonReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Settings → Data → Export / Import on the desktop app: the same
 * "stellar-backup" file as Android's (this is Android's code, moved onto
 * plain files), so a backup made on a phone imports on a computer and
 * back. The Android-only parts — the AI-tagged dataset and the custom font
 * — aren't written here and are skipped on import (an exported file
 * carries an empty tagged dataset).
 *
 * Android's notes:
 *
 * Item 7: Settings → Data → Export / Import — every local setting in ONE
 * file, so moving to a build with a different signing key (a fresh install)
 * doesn't lose anything:
 *  - the app's settings (DataStore), including the Blog/Review
 *    subscription lists;
 *  - UI toggles and every VRM-mode / Live setting (SharedPreferences);
 *  - the custom app font, embedded;
 *  - the main (on-device) AI-tagged post dataset. On import it REPLACES
 *    the main dataset — it isn't added as an extra imported one.
 *
 * Images and videos attached to drafts, notes and bookmark-folder covers
 * (the files under local_media) travel in the same file too, as a trailing
 * "localMedia" section. It is written and read as a stream, in small
 * base64 chunks, so a large video never has to fit in memory. Backups made
 * before this section existed simply don't have it and import as before.
 *
 * Archived posts (supporters) travel too: the list as "archivedPosts" and
 * their pictures and videos as a second streamed section, "archiveMedia".
 * On import they're added to whatever is already archived on the device.
 * (They're private to the app until exported — the backup file is an
 * ordinary file, so it's as private as wherever it's kept.)
 *
 * Left out on purpose: sign-in tokens/passwords (you sign in again, which
 * is safer than a file carrying live credentials), per-account caches,
 * and the VRM avatar file pointer (the file itself isn't in the backup).
 */
actual object AppBackup {
    private const val FORMAT = "stellar-backup"
    private const val VERSION = 1
    // "hub_layout": Settings → Customize Hub (row order/toggles + Hub lists).
    // "list_recency": Add To's most-recently-added-to order.
    private val SHARED_PREFS = listOf("ui_toggles", "vrm_settings", "hub_layout", "list_recency", "supporter_local", "status_history")

    private val EXCLUDED = setOf(
        "bsky_access_jwt", "bsky_refresh_jwt", "bsky_did", "bsky_handle", "bsky_service_url",
        "e621_username", "e621_api_key", "bsky_other_accounts_json",
        "hub_reviews_cache_json", "hub_blogs_cache_json", "hub_cache_hydrated_at", "hub_mutuals_cache_json",
        "profile_tab_cache_json_v2", "self_avatar_url_cache", "history_json",
        "follower_scan_completed", "follower_scan_last_run_ms", "follower_scan_cursor",
        "live_active_platform", "live_expires_at_ms", "vrm_avatar_uri", "custom_font_path"
    )
    private fun excluded(name: String) = name in EXCLUDED || name.startsWith(PrefKeys.BSKY_ACCOUNT_STATE_PREFIX)

    /** Writes the backup to [uri]. Returns a short summary. */
    actual suspend fun export(context: PlatformContext, uri: PlatformUri): String = withContext(Dispatchers.IO) {
        val root = JsonObject()
        root.addProperty("format", FORMAT)
        root.addProperty("version", VERSION)
        root.addProperty("exportedAt", System.currentTimeMillis())

        // App settings.
        val ds = JsonObject()
        val prefs = context.dataStore.data.first()
        for ((key, value) in prefs.asMap()) {
            if (excluded(key.name)) continue
            typed(value)?.let { ds.add(key.name, it) }
        }
        root.add("dataStore", ds)

        // UI toggles, VRM and Live settings.
        val sp = JsonObject()
        for (name in SHARED_PREFS) {
            val obj = JsonObject()
            for ((k, v) in context.sharedPreferences(name).getAll()) {
                if (v != null) typed(v)?.let { obj.add(k, it) }
            }
            sp.add(name, obj)
        }
        root.add("sharedPrefs", sp)

        // (No AI-tagged dataset on the desktop app.)
        root.add("taggedPosts", JsonArray())

        // Archived posts: the list here, their files streamed at the end.
        val archivedJson = PostArchive.exportJson(context)
        val archivedCount = if (archivedJson != null) PostArchive.posts.size else 0
        if (archivedJson != null) root.addProperty("archivedPosts", archivedJson)

        // Local media (drafts, notes, folder covers), streamed after the rest.
        val mediaDir = mediaDir(context)
        val mediaFiles = mediaDir.walkTopDown().filter { it.isFile }.toList()
        val archiveDir = archiveDir(context)
        val archiveFiles = if (archivedJson != null) archiveDir.walkTopDown().filter { it.isFile }.toList() else emptyList()
        var mediaCount = 0
        (uri.file ?: error("Couldn't open the chosen file for writing")).outputStream().use { out ->
            out.bufferedWriter(Charsets.UTF_8).use { w ->
                val json = root.toString()
                w.write(json, 0, json.length - 1) // everything but the closing brace
                w.write(",\"localMedia\":{\"root\":" + JsonPrimitive(mediaRoot(context)).toString() + ",\"files\":[")
                val buf = ByteArray(CHUNK)
                mediaCount = writeFiles(w, buf, mediaDir, mediaFiles)
                w.write("]}")
                w.write(",\"archiveMedia\":{\"files\":[")
                writeFiles(w, buf, archiveDir, archiveFiles)
                w.write("]}}")
            }
        }
        "Exported ${ds.size()} settings, $mediaCount media files and $archivedCount archived posts"
    }

    /** Writes [files] (all under [dir]) as the entries of a "files" array:
     *  each one its path under [dir] and its bytes in base64 chunks.
     *  Returns how many were written. */
    private fun writeFiles(w: java.io.Writer, buf: ByteArray, dir: File, files: List<File>): Int {
        var count = 0
        for (f in files) {
            val rel = f.relativeTo(dir).path.replace(File.separatorChar, '/')
            runCatching { f.inputStream() }.getOrNull()?.use { input ->
                if (count > 0) w.write(",")
                w.write("{\"p\":" + JsonPrimitive(rel).toString() + ",\"d\":[")
                var first = true
                while (true) {
                    var n = 0
                    while (n < buf.size) {
                        val r = input.read(buf, n, buf.size - n)
                        if (r < 0) break
                        n += r
                    }
                    if (n <= 0) break
                    if (!first) w.write(",")
                    first = false
                    w.write("\"")
                    w.write(Base64.getEncoder().encodeToString(buf.copyOf(n)))
                    w.write("\"")
                    if (n < buf.size) break
                }
                w.write("]}")
                count++
            }
        }
        return count
    }

    /** Reads a backup from [uri] and applies it. Throws on a bad file. The
     *  app should be restarted afterwards so every screen reloads. */
    actual suspend fun import(context: PlatformContext, uri: PlatformUri): String = withContext(Dispatchers.IO) {
        // Read as a stream: everything is parsed into [root] as before, except
        // the optional "localMedia" section, whose files go straight to disk.
        var oldMediaRoot: String? = null
        var mediaCount = 0
        val input = uri.file?.takeIf { it.isFile }?.inputStream() ?: error("Couldn't open the chosen file")
        val root = runCatching {
            input.use { stream ->
                val reader = JsonReader(stream.bufferedReader(Charsets.UTF_8))
                val obj = JsonObject()
                reader.beginObject()
                while (reader.hasNext()) {
                    val name = reader.nextName()
                    if (name == "localMedia") {
                        reader.beginObject()
                        while (reader.hasNext()) {
                            when (reader.nextName()) {
                                "root" -> oldMediaRoot = reader.nextString()
                                "files" -> mediaCount += readMediaFiles(mediaDir(context), reader)
                                else -> reader.skipValue()
                            }
                        }
                        reader.endObject()
                    } else if (name == "archiveMedia") {
                        // Archived posts' pictures and videos.
                        reader.beginObject()
                        while (reader.hasNext()) {
                            when (reader.nextName()) {
                                "files" -> readMediaFiles(archiveDir(context), reader)
                                else -> reader.skipValue()
                            }
                        }
                        reader.endObject()
                    } else {
                        obj.add(name, JsonParser.parseReader(reader))
                    }
                }
                reader.endObject()
                obj
            }
        }.getOrNull() ?: error("That isn't a Stellar backup file")
        if (root.get("format")?.asString != FORMAT) error("That isn't a Stellar backup file")
        // Drafts, notes and covers point at their files by full path; aim
        // those at this install's copy of the files.
        val newMediaRoot = mediaRoot(context)
        fun remap(name: String, s: String): String {
            val old = oldMediaRoot
            if (name != "supporter_local" || old.isNullOrEmpty() || old == newMediaRoot) return s
            return s.replace(old, newMediaRoot).replace(old.replace("/", "\\/"), newMediaRoot.replace("/", "\\/"))
        }

        var settingsCount = 0
        val ds = root.getAsJsonObject("dataStore")
        context.dataStore.edit { prefs ->
            ds?.entrySet()?.forEach { (name, el) ->
                if (excluded(name) || !el.isJsonObject) return@forEach
                if (applyTyped(prefs, name, el.asJsonObject)) settingsCount++
            }
        }

        root.getAsJsonObject("sharedPrefs")?.entrySet()?.forEach { (name, el) ->
            if (name !in SHARED_PREFS || !el.isJsonObject) return@forEach
            val editor = context.sharedPreferences(name).edit()
            for ((k, v) in el.asJsonObject.entrySet()) {
                if (!v.isJsonObject) continue
                val o = v.asJsonObject
                val t = o.get("t")?.asString ?: continue
                val value = o.get("v") ?: continue
                runCatching {
                    when (t) {
                        "bool" -> editor.putBoolean(k, value.asBoolean)
                        "int" -> editor.putInt(k, value.asInt)
                        "long" -> editor.putLong(k, value.asLong)
                        "float", "double" -> editor.putFloat(k, value.asFloat)
                        "string" -> editor.putString(k, remap(name, value.asString))
                        "set" -> editor.putStringSet(k, value.asJsonArray.map { it.asString }.toSet())
                    }
                    settingsCount++
                }
            }
            editor.commit()
        }

        // Archived posts (their files were written above, as the file was read).
        val archivedCount = runCatching {
            root.get("archivedPosts")?.takeIf { it.isJsonPrimitive }?.asString?.let { PostArchive.importJson(context, it) }
        }.getOrNull() ?: 0
        "Imported $settingsCount settings, $mediaCount media files and $archivedCount archived posts"
    }

    private const val CHUNK = 180_000 // bytes per base64 chunk (a multiple of 3)

    private fun mediaDir(context: PlatformContext) = File(DesktopPaths.filesDir, "local_media")

    /** Where archived posts keep their files (PrivateFiles' "archive" folder). */
    private fun archiveDir(context: PlatformContext) = File(File(DesktopPaths.filesDir, "private"), PostArchive.FOLDER)

    /** The prefix every stored draft/note/cover file URI on this device starts with. */
    private fun mediaRoot(context: PlatformContext) = mediaDir(context).toFileUri().trimEnd('/')

    /** Reads the "files" array of a media section, writing each file into
     *  [dir]. Returns how many were restored. */
    private fun readMediaFiles(dir: File, reader: JsonReader): Int {
        var count = 0
        reader.beginArray()
        while (reader.hasNext()) {
            var target: File? = null
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "p" -> {
                        val rel = reader.nextString()
                        val parts = rel.split('/')
                        target = if (rel.isBlank() || parts.any { it.isEmpty() || it == "." || it == ".." }) null else File(dir, rel)
                    }
                    "d" -> {
                        val f = target
                        if (f == null) { reader.skipValue() } else {
                            f.parentFile?.mkdirs()
                            f.outputStream().use { out ->
                                reader.beginArray()
                                while (reader.hasNext()) out.write(Base64.getMimeDecoder().decode(reader.nextString()))
                                reader.endArray()
                            }
                            count++
                        }
                    }
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
        }
        reader.endArray()
        return count
    }

    private fun typed(value: Any): JsonObject? {
        val o = JsonObject()
        when (value) {
            is Boolean -> { o.addProperty("t", "bool"); o.addProperty("v", value) }
            is Int -> { o.addProperty("t", "int"); o.addProperty("v", value) }
            is Long -> { o.addProperty("t", "long"); o.addProperty("v", value) }
            is Float -> { o.addProperty("t", "float"); o.addProperty("v", value) }
            is Double -> { o.addProperty("t", "double"); o.addProperty("v", value) }
            is String -> { o.addProperty("t", "string"); o.addProperty("v", value) }
            is Set<*> -> {
                o.addProperty("t", "set")
                val arr = JsonArray()
                value.filterIsInstance<String>().forEach { arr.add(it) }
                o.add("v", arr)
            }
            else -> return null
        }
        return o
    }

    private fun applyTyped(prefs: androidx.datastore.preferences.core.MutablePreferences, name: String, o: JsonObject): Boolean {
        val t = o.get("t")?.asString ?: return false
        val v = o.get("v") ?: return false
        return runCatching {
            when (t) {
                "bool" -> prefs[booleanPreferencesKey(name)] = v.asBoolean
                "int" -> prefs[intPreferencesKey(name)] = v.asInt
                "long" -> prefs[longPreferencesKey(name)] = v.asLong
                "float" -> prefs[floatPreferencesKey(name)] = v.asFloat
                "double" -> prefs[doublePreferencesKey(name)] = v.asDouble
                "string" -> prefs[stringPreferencesKey(name)] = v.asString
                "set" -> prefs[stringSetPreferencesKey(name)] = v.asJsonArray.map { it.asString }.toSet()
                else -> return@runCatching false
            }
            true
        }.getOrDefault(false)
    }
}
