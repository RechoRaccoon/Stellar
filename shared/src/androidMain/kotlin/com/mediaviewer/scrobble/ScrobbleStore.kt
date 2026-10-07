// This file is adapted from Rocksky (https://github.com/tsirysndr/rocksky),
// apps/app/modules/rocksky-scrobbler — ScrobbleStore.kt, ListeningClock.kt
// and ScrobbleIdentity.kt — Copyright (c) 2025 Tsiry Sandratraina, used
// under the Mozilla Public License 2.0 (https://mozilla.org/MPL/2.0/).
// This file stays available under those terms.
package com.mediaviewer.scrobble

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import kotlin.math.min

/**
 * What "Scrobble Music to Rocksky" is set to. The timing rules start out
 * as Rocksky's own: a listen counts once you've heard half of the song or
 * four minutes of it, whichever comes first (both can be changed under
 * Scrobble Settings), and songs under thirty seconds never count.
 */
class ScrobbleSettings(
    /** Switched on in Settings, signed in, and a supporter. */
    val enabled: Boolean,
    /** The account the listens belong to. */
    val did: String,
    /** The apps chosen under Scrobble Settings (package names), together
     *  with the other builds of them (ReVanced and the like). None by default. */
    val apps: Set<String>,
    val mode: String = "listened",
    val seconds: Int = 240,
    val percent: Int = 50,
    val minimum: Int = 30
)

/**
 * The scrobbler's own storage: its settings, and a small database that
 * holds listens until they've been sent — so nothing is lost offline, when
 * Stellar is closed, or across a restart of the phone.
 */
object ScrobbleStore {
    const val PREFS = "stellar_scrobbler"
    const val KEY_ENABLED = "enabled"
    const val KEY_DID = "did"
    const val KEY_APPS = "apps"
    /** Apps the listener has seen playing something: package → name. */
    const val KEY_SEEN = "seen"
    /** "Scrobble after …% or … seconds of the track". */
    const val KEY_PERCENT = "percent"
    const val KEY_SECONDS = "seconds"

    fun prefs(c: Context): SharedPreferences = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun settings(c: Context): ScrobbleSettings {
        val p = prefs(c)
        val did = p.getString(KEY_DID, null).orEmpty()
        val on = p.getBoolean(KEY_ENABLED, false) && did.isNotBlank() &&
            // (A supporter feature: it rests if the support has lapsed.)
            com.mediaviewer.widget.StellarWidgets.isSupporter(c)
        val chosen = p.getStringSet(KEY_APPS, emptySet())!!.toSet()
        // "YouTube" and "YouTube Music" also mean their patched builds.
        val apps = chosen + chosen.flatMap { com.mediaviewer.util.RockskyScrobbler.APP_ALIASES[it].orEmpty() }
        return ScrobbleSettings(
            on, did, apps,
            seconds = p.getInt(KEY_SECONDS, com.mediaviewer.util.RockskyScrobbler.DEFAULT_SECONDS).coerceIn(1, 3600),
            percent = p.getInt(KEY_PERCENT, com.mediaviewer.util.RockskyScrobbler.DEFAULT_PERCENT).coerceIn(1, 100)
        )
    }

    /**
     * What each account's repo already holds (see [com.mediaviewer.util.ScrobbleIndex]),
     * kept in the same database so it survives restarts — an import of
     * tens of thousands of listens leans on it to never write one twice.
     */
    fun index(c: Context): com.mediaviewer.util.ScrobbleIndex {
        val app = c.applicationContext
        return object : com.mediaviewer.util.ScrobbleIndex {
            override fun has(key: String): Boolean =
                db(app).rawQuery("SELECT 1 FROM known WHERE id=?", arrayOf(key)).use { it.moveToFirst() }

            override fun addAll(keys: List<String>) {
                if (keys.isEmpty()) return
                val db = db(app)
                db.beginTransaction()
                try {
                    keys.forEach { key ->
                        db.insertWithOnConflict("known", null, ContentValues().apply { put("id", key) }, SQLiteDatabase.CONFLICT_IGNORE)
                    }
                    db.setTransactionSuccessful()
                } finally { db.endTransaction() }
            }
        }
    }

    @Volatile private var helper: QueueDb? = null
    fun db(c: Context): SQLiteDatabase = (helper ?: synchronized(this) {
        helper ?: QueueDb(c.applicationContext).also { helper = it }
    }).writableDatabase

    /** Saves one listen for sending. A listen already saved (the same
     *  song at the same moment) is not saved twice. */
    fun enqueue(c: Context, did: String, source: String, track: JSONObject) {
        val identity = ScrobbleIdentity.key(did, track.getString("title"), track.getString("artist"), track.getLong("timestamp"))
        val db = db(c)
        db.beginTransaction()
        try {
            // Receipts are kept after a listen is sent: deleting it from the
            // queue must not let the same listen be saved again.
            val inserted = db.insertWithOnConflict("receipts", null, ContentValues().apply {
                put("id", identity)
            }, SQLiteDatabase.CONFLICT_IGNORE)
            if (inserted != -1L) db.insertOrThrow("queue", null, ContentValues().apply {
                put("id", identity); put("did", did); put("source", source); put("payload", track.toString())
                put("created", System.currentTimeMillis())
            })
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    /** How many listens are waiting, and how many of those were refused. */
    fun counts(c: Context, did: String): Pair<Int, Int> {
        if (did.isBlank()) return 0 to 0
        return db(c).rawQuery("SELECT COUNT(*), COALESCE(SUM(failed),0) FROM queue WHERE did=?", arrayOf(did)).use {
            it.moveToFirst(); it.getInt(0) to it.getInt(1)
        }
    }

    private class QueueDb(c: Context) : SQLiteOpenHelper(
        c, java.io.File(c.noBackupFilesDir, "stellar_scrobbles.db").absolutePath, null, 2
    ) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE queue (id TEXT PRIMARY KEY, did TEXT NOT NULL, source TEXT NOT NULL, payload TEXT NOT NULL, created INTEGER NOT NULL, failed INTEGER NOT NULL DEFAULT 0)")
            db.execSQL("CREATE INDEX queue_account ON queue(did,failed,created)")
            db.execSQL("CREATE TABLE playback (id TEXT PRIMARY KEY, state TEXT NOT NULL)")
            db.execSQL("CREATE TABLE recognitions (id TEXT PRIMARY KEY, seen INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE receipts (id TEXT PRIMARY KEY)")
            createVersion2(db)
        }

        /** Added with history importing: what's known to be in each repo,
         *  the imported files, and every listen in them with whether it has
         *  been sent (state 0 = waiting, 1 = done, 2 = couldn't be sent). */
        private fun createVersion2(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS known (id TEXT PRIMARY KEY)")
            db.execSQL("CREATE TABLE IF NOT EXISTS imports (id INTEGER PRIMARY KEY AUTOINCREMENT, did TEXT NOT NULL, name TEXT NOT NULL, total INTEGER NOT NULL, created INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS import_plays (id INTEGER PRIMARY KEY AUTOINCREMENT, import_id INTEGER NOT NULL, title TEXT NOT NULL, artist TEXT NOT NULL, album TEXT NOT NULL, ts INTEGER NOT NULL, state INTEGER NOT NULL DEFAULT 0, tries INTEGER NOT NULL DEFAULT 0)")
            db.execSQL("CREATE INDEX IF NOT EXISTS import_plays_next ON import_plays(import_id,state,id)")
        }

        override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
            if (old < 2) createVersion2(db)
        }
    }
}

/** Listening time that only ever counts forward: skipping ahead in a song
 *  adds nothing. */
class ListeningClock {
    var listenedMs = 0L
        private set
    private var lastTime: Long? = null
    private var wasPlaying = false

    fun update(now: Long, playing: Boolean) {
        lastTime?.let { if (wasPlaying) listenedMs += (now - it).coerceAtLeast(0) }
        lastTime = now
        wasPlaying = playing
    }

    fun restore(ms: Long) { listenedMs = ms.coerceAtLeast(0) }

    fun qualifies(duration: Long, position: Long, mode: String, seconds: Int, percent: Int, minimum: Int): Boolean {
        if (duration > 0 && duration < minimum * 1000L) return false
        val threshold = if (mode == "position") seconds * 1000L else
            if (duration > 0) min(seconds * 1000L, duration * percent / 100) else seconds * 1000L
        return if (mode == "position") position >= threshold && listenedMs > 0 else listenedMs >= threshold
    }
}

/** What makes two reports "the same listen": the account, the song's title
 *  and artist (tidied), and when it began. The app and the album are left
 *  out on purpose — two players can report one listen, one with a fuller
 *  album name than the other. */
object ScrobbleIdentity {
    private fun normalize(text: String) = Normalizer.normalize(text, Normalizer.Form.NFKC)
        .replace('’', '\'').trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)

    fun key(did: String, title: String, artist: String, timestamp: Long): String {
        val parts = listOf(did, normalize(title), normalize(artist), timestamp.toString())
        val canonical = parts.joinToString("") { "${it.length}:$it" }
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
