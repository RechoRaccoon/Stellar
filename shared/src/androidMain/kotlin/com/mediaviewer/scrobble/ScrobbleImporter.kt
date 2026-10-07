package com.mediaviewer.scrobble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.OpenableColumns
import android.util.JsonReader
import android.util.JsonToken
import com.mediaviewer.util.RockskyScrobbler
import com.mediaviewer.util.ScrobbleImport
import com.mediaviewer.util.ScrobbleTrack
import com.mediaviewer.util.ScrobbleUploadOutcome
import kotlinx.coroutines.runBlocking
import java.io.BufferedInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipInputStream

/**
 * "Import YouTube/Spotify music history to Rocksky" (a supporter feature):
 * reads the history file a person asked Spotify or Google for, and writes
 * each listen in it to their repo as a scrobble dated when it was played —
 * what Rocksky's own `rocksky import` command does (apps/cli/src/cmd/
 * import.ts), built in.
 *
 * It's slow on purpose. Bluesky's servers only let an account write so
 * much an hour, so a long history takes hours or days. Everything is
 * therefore kept in the scrobbler's database: when a file is picked, every
 * listen in it is copied in straight away (the file itself is never needed
 * again), and each one is ticked off as it's sent. Closing Stellar, a
 * crash, or the phone restarting loses nothing — the next run carries on
 * with the first listen not yet ticked off. More files simply queue up
 * behind the first.
 *
 * What's read:
 *  - Spotify's "Extended streaming history" (Streaming_History_Audio_*.json)
 *    and the shorter "Account data" history (StreamingHistory_music_*.json)
 *    — the .zip Spotify emails, or any one of the files in it. Podcasts and
 *    anything played for under thirty seconds are left out, as Rocksky does.
 *  - Google Takeout's YouTube history (watch-history.json) — the .zip or
 *    the file. Only what was played in YouTube Music is taken; ordinary
 *    YouTube videos aren't songs.
 */
object ScrobbleImporter {
    /** "Work in Background" is switched on. */
    const val KEY_BACKGROUND = "importBackground"
    /** What's holding the import up, or why the last file was refused. */
    const val KEY_MESSAGE = "importMessage"

    /** A picked file is being read right now. */
    @Volatile var reading = false
        private set

    private val running = AtomicBoolean(false)

    private class Play(val title: String, val artist: String, val album: String, val ts: Long)
    private class Waiting(val id: Long, val title: String, val artist: String, val album: String, val ts: Long, val tries: Int, val did: String)

    private fun say(c: Context, message: String?) {
        val prefs = ScrobbleStore.prefs(c)
        if (prefs.getString(KEY_MESSAGE, null) == message) return
        prefs.edit().apply { if (message == null) remove(KEY_MESSAGE) else putString(KEY_MESSAGE, message) }.apply()
    }

    // ── Reading a picked file ────────────────────────────────────────────

    /** Reads the file at [uri] and queues its listens for [did]. Returns at
     *  once; the reading happens on a thread of its own. */
    fun add(context: Context, uri: Uri, did: String) {
        val c = context.applicationContext
        if (reading) return
        reading = true
        say(c, null)
        Thread({
            try {
                read(c, uri, did)
            } catch (_: OutOfMemoryError) {
                say(c, "That file is too big to read on this phone.")
            } catch (_: Exception) {
                say(c, "That file couldn't be read. Pick the .zip or .json file Spotify or Google sent you.")
            } finally {
                reading = false
                resume(c)
            }
        }, "stellar-import-read").start()
    }

    private fun read(c: Context, uri: Uri, did: String) {
        val shown: String? = try {
            c.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        } catch (_: Exception) { null }
        val name = shown ?: uri.lastPathSegment ?: "Music history"
        val plays = ArrayList<Play>()
        var sawHtmlHistory = false
        val input = c.contentResolver.openInputStream(uri) ?: throw java.io.IOException("can't open the file")
        BufferedInputStream(input, 1 shl 16).use { stream ->
            stream.mark(4)
            val isZip = stream.read() == 'P'.code && stream.read() == 'K'.code
            stream.reset()
            if (isZip) {
                val zip = ZipInputStream(stream)
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val entryName = entry.name.substringAfterLast('/')
                    if (entry.isDirectory) continue
                    if (entryName.endsWith(".html", true) && entry.name.contains("history", true)) sawHtmlHistory = true
                    // (Spotify's video history isn't music listening.)
                    if (!entryName.endsWith(".json", true) || entryName.startsWith("Streaming_History_Video", true)) continue
                    // The reader mustn't close the zip when it's done with one file.
                    try { readHistory(object : FilterInputStream(zip) { override fun close() {} }, plays) } catch (_: Exception) {}
                }
            } else {
                readHistory(stream, plays)
            }
        }
        if (plays.isEmpty()) {
            say(c, if (sawHtmlHistory) "That Takeout has its history as web pages. Ask Google for it again with History set to JSON."
            else "No music listens were found in that file.")
            return
        }
        // Oldest first, and the same song at the same second only once
        // (as `rocksky import` does).
        plays.sortBy { it.ts }
        val seen = HashSet<String>()
        val unique = plays.filter { seen.add("${it.ts}|${it.title.lowercase()}|${it.artist.lowercase()}") }
        val db = ScrobbleStore.db(c)
        db.beginTransaction()
        try {
            val id = db.insertOrThrow("imports", null, ContentValues().apply {
                put("did", did); put("name", name); put("total", unique.size); put("created", System.currentTimeMillis())
            })
            val row = ContentValues()
            unique.forEach { p ->
                row.clear()
                row.put("import_id", id); row.put("title", p.title); row.put("artist", p.artist); row.put("album", p.album); row.put("ts", p.ts)
                db.insertOrThrow("import_plays", null, row)
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    /**
     * Reads one history file, a listen at a time so even a very large one
     * fits in memory. The three formats are told apart by the names of the
     * fields each listen has; a file that is none of them adds nothing.
     */
    private fun readHistory(stream: InputStream, into: MutableList<Play>) {
        val reader = JsonReader(InputStreamReader(stream, Charsets.UTF_8))
        reader.isLenient = true
        if (reader.peek() != JsonToken.BEGIN_ARRAY) return
        reader.beginArray()
        while (reader.hasNext()) {
            if (reader.peek() != JsonToken.BEGIN_OBJECT) { reader.skipValue(); continue }
            var track: String? = null; var artist: String? = null; var album: String? = null
            var time: String? = null; var endTime: String? = null; var played = -1L
            var header: String? = null; var videoTitle: String? = null; var channel: String? = null
            fun text(): String? = if (reader.peek() == JsonToken.NULL) { reader.nextNull(); null } else reader.nextString()
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    // Spotify, extended history
                    "ts" -> time = text()
                    "ms_played", "msPlayed" -> if (reader.peek() == JsonToken.NUMBER) played = reader.nextLong() else reader.skipValue()
                    "master_metadata_track_name", "trackName" -> track = text()
                    "master_metadata_album_artist_name", "artistName" -> artist = text()
                    "master_metadata_album_album_name" -> album = text()
                    // Spotify, account data
                    "endTime" -> endTime = text()
                    // Google Takeout
                    "header" -> header = text()
                    "title" -> videoTitle = text()
                    "time" -> time = text()
                    "subtitles" -> if (reader.peek() == JsonToken.BEGIN_ARRAY) {
                        reader.beginArray()
                        while (reader.hasNext()) {
                            if (reader.peek() != JsonToken.BEGIN_OBJECT) { reader.skipValue(); continue }
                            reader.beginObject()
                            while (reader.hasNext()) {
                                if (reader.nextName() == "name" && channel == null) channel = text() else reader.skipValue()
                            }
                            reader.endObject()
                        }
                        reader.endArray()
                    } else reader.skipValue()
                    else -> reader.skipValue()
                }
            }
            reader.endObject()

            if (header != null) {
                // Takeout. Only YouTube Music; a video that has since been
                // removed has no channel and is passed over.
                if (header != "YouTube Music") continue
                val title = RockskyScrobbler.cleanVideoTitle(videoTitle.orEmpty().removePrefix("Watched ").trim())
                val by = RockskyScrobbler.cleanChannelName(channel.orEmpty())
                val ts = seconds(time) ?: continue
                if (title.isNotEmpty() && by.isNotEmpty()) into += Play(title, by, "", ts)
            } else if (!track.isNullOrBlank() && !artist.isNullOrBlank()) {
                // Spotify. (A podcast or audiobook has no track name.)
                if (played in 0L until 30_000L) continue
                val ts = seconds(time) ?: spotifyMinute(endTime) ?: continue
                into += Play(track.trim(), artist.trim(), album.orEmpty().trim(), ts)
            }
        }
        reader.endArray()
    }

    /** "2024-05-01T18:22:07Z" (with or without fractions or an offset) → seconds since 1970. */
    private fun seconds(iso: String?): Long? {
        if (iso.isNullOrBlank()) return null
        return try { java.time.Instant.parse(iso).epochSecond } catch (_: Exception) {
            try { java.time.OffsetDateTime.parse(iso).toEpochSecond() } catch (_: Exception) { null }
        }?.takeIf { it > 0 }
    }

    /** Spotify's shorter history dates a listen "2024-05-01 18:22" (UTC). */
    private fun spotifyMinute(text: String?): Long? {
        if (text.isNullOrBlank()) return null
        return try {
            java.time.LocalDateTime.parse(text.trim(), java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                .toEpochSecond(java.time.ZoneOffset.UTC)
        } catch (_: Exception) { null }
    }

    // ── The list in Settings ─────────────────────────────────────────────

    /** [did]'s imported files, in the order they're worked on. */
    fun list(c: Context, did: String): List<ScrobbleImport> {
        if (did.isBlank()) return emptyList()
        val out = ArrayList<ScrobbleImport>()
        ScrobbleStore.db(c).rawQuery(
            "SELECT i.id, i.name, i.total, " +
                "(SELECT COUNT(*) FROM import_plays p WHERE p.import_id=i.id AND p.state=1), " +
                "(SELECT COUNT(*) FROM import_plays p WHERE p.import_id=i.id AND p.state=2) " +
                "FROM imports i WHERE i.did=? ORDER BY i.id", arrayOf(did)
        ).use {
            while (it.moveToNext()) out += ScrobbleImport(it.getLong(0), it.getString(1), it.getInt(3), it.getInt(2), it.getInt(4))
        }
        return out
    }

    /** Takes a file off the list. Listens already sent stay on Rocksky;
     *  the rest of the file is simply not sent. */
    fun cancel(c: Context, id: Long) {
        val db = ScrobbleStore.db(c)
        db.beginTransaction()
        try {
            db.delete("import_plays", "import_id=?", arrayOf(id.toString()))
            db.delete("imports", "id=?", arrayOf(id.toString()))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    /** How many listens are still to be sent, over every file, and how many there were. */
    fun progress(c: Context): Pair<Int, Int> =
        ScrobbleStore.db(c).rawQuery("SELECT COALESCE(SUM(state=0),0), COUNT(*) FROM import_plays", null).use {
            it.moveToFirst(); it.getInt(0) to it.getInt(1)
        }

    // ── Sending ──────────────────────────────────────────────────────────

    /**
     * Carries on with whatever is still to be sent, if anything is. Safe to
     * call at any time and as often as you like (Stellar opening, the phone
     * restarting, a file being added, the toggle changing): one sender runs
     * at most. With "Work in Background" on it also puts up the quiet
     * notification that lets Android keep the sender going while Stellar
     * is closed.
     */
    fun resume(context: Context) {
        val c = context.applicationContext
        val waiting = try { progress(c).first > 0 } catch (_: Exception) { false }
        if (!waiting) return
        if (ScrobbleStore.prefs(c).getBoolean(KEY_BACKGROUND, false)) {
            try {
                val intent = Intent(c, ScrobbleImportService::class.java)
                if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(intent) else c.startService(intent)
            } catch (_: Exception) {
                // Android wouldn't allow it from the background just now;
                // it's started the next time Stellar is opened.
            }
        }
        if (!running.compareAndSet(false, true)) return
        Thread({
            try { send(c) } catch (_: Exception) {} finally { running.set(false) }
        }, "stellar-import-send").start()
    }

    private fun online(c: Context): Boolean = try {
        c.getSystemService(ConnectivityManager::class.java)?.activeNetwork != null
    } catch (_: Exception) { true }

    private fun send(c: Context) {
        val index = ScrobbleStore.index(c)
        val db = ScrobbleStore.db(c)
        val synced = HashSet<String>()
        var waits = 0
        /** Waits longer each time in a row: 5 s, 10 s, 20 s … up to [most] seconds. */
        fun pause(most: Long) {
            Thread.sleep(minOf(most, 5L shl minOf(waits, 12)) * 1000)
            waits++
        }
        while (true) {
            // (A supporter feature: it rests if the support has lapsed.)
            if (!com.mediaviewer.widget.StellarWidgets.isSupporter(c)) return
            // The next listen: the oldest file first, each from its start.
            val next = db.rawQuery(
                "SELECT p.id, p.title, p.artist, p.album, p.ts, p.tries, i.did FROM import_plays p JOIN imports i ON i.id=p.import_id " +
                    "WHERE p.state=0 ORDER BY p.import_id, p.id LIMIT 1", null
            ).use {
                if (!it.moveToFirst()) null
                else Waiting(it.getLong(0), it.getString(1), it.getString(2), it.getString(3), it.getLong(4), it.getInt(5), it.getString(6))
            } ?: break
            val id = next.id
            val did = next.did

            // Once per run and account: catch up on what the repo already
            // holds, so nothing that's there is written again — including a
            // listen that was sent just as Stellar was closed last time.
            if (did !in synced) {
                if (!runBlocking { RockskyScrobbler.syncIndex(did, index) }) {
                    say(c, "Waiting for a connection. The import carries on by itself.")
                    pause(300)
                    continue
                }
                synced += did
            }

            val track = ScrobbleTrack(
                title = next.title, artist = next.artist, album = next.album,
                albumArtist = "", durationMs = 0, timestampSeconds = next.ts
            )
            when (runBlocking { RockskyScrobbler.upload(c, did, track, index, history = true) }) {
                ScrobbleUploadOutcome.DONE -> {
                    db.execSQL("UPDATE import_plays SET state=1 WHERE id=?", arrayOf<Any>(id))
                    waits = 0
                    say(c, null)
                }
                ScrobbleUploadOutcome.REJECTED -> db.execSQL("UPDATE import_plays SET state=2 WHERE id=?", arrayOf<Any>(id))
                ScrobbleUploadOutcome.AUTH -> {
                    say(c, "Sign in to the Bluesky account this history was imported for, and the import carries on.")
                    pause(120)
                }
                ScrobbleUploadOutcome.LIMITED -> {
                    say(c, "Bluesky asked Stellar to slow down. The import carries on shortly.")
                    waits = maxOf(waits, 4)
                    pause(1800)
                }
                ScrobbleUploadOutcome.RETRY -> {
                    // With a connection, a listen that fails eight times over
                    // is set aside so the rest aren't stuck behind it.
                    if (online(c)) {
                        if (next.tries >= 7) db.execSQL("UPDATE import_plays SET state=2 WHERE id=?", arrayOf<Any>(id))
                        else db.execSQL("UPDATE import_plays SET tries=tries+1 WHERE id=?", arrayOf<Any>(id))
                    }
                    say(c, "Waiting for a connection. The import carries on by itself.")
                    pause(300)
                }
            }
        }
        say(c, null)
    }
}

/**
 * "Work in Background": while a history import has listens left to send,
 * this shows one quiet notification with how far along it is — which is
 * what lets Android keep the sending going with Stellar closed and the
 * screen off — and goes away by itself when the import is finished or the
 * toggle is switched off.
 */
class ScrobbleImportService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private val tick = object : Runnable {
        override fun run() {
            val (left, total) = try { ScrobbleImporter.progress(this@ScrobbleImportService) } catch (_: Exception) { 0 to 0 }
            val on = ScrobbleStore.prefs(this@ScrobbleImportService).getBoolean(ScrobbleImporter.KEY_BACKGROUND, false)
            if (left == 0 || !on) { stopSelf(); return }
            try {
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(total - left, total))
            } catch (_: Exception) {}
            // The phone is kept awake in ten-minute stretches, renewed here,
            // so a stuck service can never hold it awake for good.
            try {
                wakeLock?.let { if (it.isHeld) it.release() }
                wakeLock = getSystemService(PowerManager::class.java)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Stellar:HistoryImport").apply { acquire(10 * 60 * 1000L) }
            } catch (_: Exception) {}
            ScrobbleImporter.resume(this@ScrobbleImportService)
            handler.postDelayed(this, 30_000)
        }
    }

    private fun notification(done: Int, total: Int): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        // (Low importance: listed in the shade, never a sound or a pop-up.)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Music history import", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shown while Stellar is importing your music history to Rocksky."
            setShowBadge(false)
        })
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: Intent()
        val pending = PendingIntent.getActivity(this, NOTIFICATION_ID, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(com.mediaviewer.R.drawable.stellar_logo_vector)
            .setContentTitle("Importing music history to Rocksky")
            .setContentText("$done of $total listens")
            .setProgress(total, done, false)
            .setContentIntent(pending).setOngoing(true).setOnlyAlertOnce(true).build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val (left, total) = try { ScrobbleImporter.progress(this) } catch (_: Exception) { 0 to 0 }
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, notification(total - left, total), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(NOTIFICATION_ID, notification(total - left, total))
        } catch (_: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        handler.removeCallbacks(tick)
        handler.post(tick)
        // (Sticky: if Android has to close Stellar, it starts this again.)
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "stellar_scrobble_import"
        private const val NOTIFICATION_ID = 7344
    }
}
