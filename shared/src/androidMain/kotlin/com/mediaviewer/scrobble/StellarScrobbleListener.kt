// This file is adapted from Rocksky (https://github.com/tsirysndr/rocksky),
// apps/app/modules/rocksky-scrobbler — ScrobbleListener.kt — Copyright (c)
// 2025 Tsiry Sandratraina, used under the Mozilla Public License 2.0
// (https://mozilla.org/MPL/2.0/). This file stays available under those terms.
package com.mediaviewer.scrobble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.ContentValues
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.database.sqlite.SQLiteDatabase
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * "Scrobble Music to Rocksky": the part that notices what you're listening
 * to. It works the way Rocksky's own Android app does.
 *
 * Android lets an app that has been given "notification access" see the
 * phone's media sessions — the same information the lock screen's player
 * shows: which app, the song's title, artist and album, how long it is,
 * where it's up to, and whether it's playing. Nothing is read from the
 * sound itself. This service watches those sessions for the apps switched
 * on under Settings › Integrations › Apps (none, to begin with), times how
 * long each song has actually been heard, and once it has been heard for
 * long enough — half of it, or four minutes — saves the listen for
 * [ScrobbleUploadJob] to send.
 *
 * While it's switched on it shows one quiet, permanent notification (no
 * sound, no pop-up), which is what lets Android keep it running with the
 * screen off.
 *
 * It also reads the "here's what's playing" notifications of the song-
 * recognition apps in the list (see [RecognitionParser]).
 */
class StellarScrobbleListener : NotificationListenerService() {
    private val handler = Handler(Looper.getMainLooper())
    private val controllers = mutableMapOf<MediaSession.Token, Pair<MediaController, MediaController.Callback>>()
    /** The song each app is on, by the app's package name. */
    private val tracks = mutableMapOf<String, Listen>()
    private val pendingRecognition = mutableMapOf<String, Runnable>()
    private var account: String? = null
    private var foreground = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var checkpoint = 0L
    private val manager by lazy { getSystemService(MediaSessionManager::class.java) }
    private val sessions = MediaSessionManager.OnActiveSessionsChangedListener { attach(it.orEmpty()) }
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == ScrobbleStore.KEY_ENABLED || key == ScrobbleStore.KEY_DID || key == ScrobbleStore.KEY_APPS) {
            handler.post { refresh() }
        }
    }
    private val tick = object : Runnable {
        override fun run() {
            try { sample() } catch (e: Exception) { android.util.Log.e(TAG, "sampling failed", e) }
            handler.postDelayed(this, 1000)
        }
    }

    private class Listen(
        val id: String, val key: String, val source: String, val started: Long,
        var metadata: JSONObject, val clock: ListeningClock = ListeningClock()
    ) {
        var submitted = false
        var position = 0L
        var playing = false
    }

    override fun onListenerConnected() {
        connected = true
        ScrobbleStore.prefs(this).registerOnSharedPreferenceChangeListener(prefsListener)
        try {
            manager.removeOnActiveSessionsChangedListener(sessions)
            manager.addOnActiveSessionsChangedListener(sessions, ComponentName(this, StellarScrobbleListener::class.java), handler)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "couldn't watch media sessions", e)
        }
        refresh()
        handler.removeCallbacks(tick); handler.post(tick)
        ScrobbleUploadJob.schedule(this)
    }

    /** Settings changed (or the listener just connected): start, stop, or
     *  switch account to match. */
    private fun refresh() {
        val settings = ScrobbleStore.settings(this)
        val next = settings.did.takeIf { it.isNotBlank() }
        if (next != account || !settings.enabled) {
            tracks.clear(); current = null
            pendingRecognition.values.forEach { handler.removeCallbacks(it) }; pendingRecognition.clear()
            // A half-heard song must never carry over to another account.
            if (account != null || !settings.enabled) ScrobbleStore.db(this).delete("playback", null, null)
            account = next
        }
        if (!settings.enabled || account == null) {
            stopForeground(STOP_FOREGROUND_REMOVE); foreground = false
            releaseWakeLock()
            attach(emptyList())
            return
        }
        if (!foreground) startOngoingNotification()
        try { attach(manager.getActiveSessions(ComponentName(this, StellarScrobbleListener::class.java))) }
        catch (_: SecurityException) { connected = false }
        sample()
    }

    /** The quiet, permanent "Stellar is scrobbling" notification. */
    private fun startOngoingNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        // (Low importance: listed in the shade, never a sound or a pop-up.)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Scrobbling", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shown while Stellar is scrobbling your music to Rocksky."
            setShowBadge(false)
        })
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: Intent()
        val pending = PendingIntent.getActivity(this, NOTIFICATION_ID, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(com.mediaviewer.R.drawable.stellar_logo_vector)
            .setContentTitle("Stellar scrobbling")
            .setContentText("Listening for music in your enabled apps")
            .setContentIntent(pending).setOngoing(true).setOnlyAlertOnce(true).build()
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(NOTIFICATION_ID, notification)
            foreground = true
            ScrobbleStore.prefs(this).edit().remove("serviceError").apply()
        } catch (_: Exception) {
            // Some phones refuse this from the background. Listening still
            // works (Android itself holds the listener); it just isn't
            // protected from the phone's battery saver. Settings says so.
            ScrobbleStore.prefs(this).edit().putString("serviceError", "Your phone may pause scrobbling in the background. Allow Stellar unrestricted battery use to prevent that.").apply()
        }
    }

    /** Follows the media sessions that are active right now. */
    private fun attach(active: List<MediaController>) {
        if (!ScrobbleStore.settings(this).enabled || account == null) {
            controllers.values.forEach { it.first.unregisterCallback(it.second) }
            controllers.clear()
            return
        }
        val tokens = active.map { it.sessionToken }.toSet()
        controllers.keys.filter { it !in tokens }.forEach { token ->
            controllers.remove(token)?.let { it.first.unregisterCallback(it.second) }
        }
        active.filter { it.packageName != packageName }.forEach { controller ->
            rememberApp(controller.packageName)
            if (controller.sessionToken !in controllers) {
                val callback = object : MediaController.Callback() {
                    override fun onMetadataChanged(metadata: MediaMetadata?) { sample() }
                    override fun onPlaybackStateChanged(state: PlaybackState?) { sample() }
                    override fun onSessionDestroyed() {
                        controllers.remove(controller.sessionToken)?.let { it.first.unregisterCallback(it.second) }
                        sample()
                    }
                }
                controllers[controller.sessionToken] = controller to callback
                controller.registerCallback(callback, handler)
            }
        }
        sample()
    }

    /** Any app seen playing something is added to the "Apps" list in
     *  Settings (switched off, like the rest), so players that aren't on
     *  the built-in list can be chosen too. */
    private fun rememberApp(pkg: String) {
        try {
            val prefs = ScrobbleStore.prefs(this)
            val seen = JSONObject(prefs.getString(ScrobbleStore.KEY_SEEN, "{}")!!)
            if (seen.has(pkg)) return
            val label = try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() } catch (_: Exception) { pkg }
            seen.put(pkg, label)
            prefs.edit().putString(ScrobbleStore.KEY_SEEN, seen.toString()).apply()
        } catch (_: Exception) {
        }
    }

    private fun text(m: MediaMetadata, key: String) = m.getText(key)?.toString()?.trim().orEmpty()

    /** The song a session is showing; null without both a title and an artist. */
    private fun metadata(c: MediaController): JSONObject? {
        val m = c.metadata ?: return null
        val title = text(m, MediaMetadata.METADATA_KEY_TITLE).ifBlank { text(m, MediaMetadata.METADATA_KEY_DISPLAY_TITLE) }
        val artist = text(m, MediaMetadata.METADATA_KEY_ARTIST)
            .ifBlank { text(m, MediaMetadata.METADATA_KEY_ALBUM_ARTIST) }
            .ifBlank { text(m, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) }
        if (title.isBlank() || artist.isBlank()) return null
        return JSONObject().put("title", title).put("artist", artist)
            .put("album", text(m, MediaMetadata.METADATA_KEY_ALBUM))
            .put("albumArtist", text(m, MediaMetadata.METADATA_KEY_ALBUM_ARTIST).ifBlank { artist })
            .put("duration", m.getLong(MediaMetadata.METADATA_KEY_DURATION).coerceAtLeast(0))
    }

    /** One look at every chosen app: what's it playing, how far in, and has
     *  the current song been heard for long enough to count? Runs once a
     *  second and whenever a player reports a change. */
    private fun sample() {
        val settings = ScrobbleStore.settings(this)
        val did = account
        if (!settings.enabled || did == null) return
        val now = SystemClock.elapsedRealtime()
        val sources = mutableSetOf<String>()
        // An app with several sessions is counted once (its playing one).
        val selected = controllers.values.map { it.first }.groupBy { it.packageName }.values.map { group ->
            group.maxBy { if (it.playbackState?.state == PlaybackState.STATE_PLAYING) 1 else 0 }
        }
        var anyPlaying = false
        current = null
        selected.filter { it.packageName in settings.apps }.forEach { c ->
            val meta = metadata(c) ?: return@forEach
            val state = c.playbackState ?: return@forEach
            val source = c.packageName
            sources.add(source)
            val playing = state.state == PlaybackState.STATE_PLAYING && state.playbackSpeed > 0
            var position = state.position.coerceAtLeast(0)
            if (playing && state.lastPositionUpdateTime > 0) position += ((now - state.lastPositionUpdateTime).coerceAtLeast(0) * state.playbackSpeed).toLong()
            val duration = meta.optLong("duration")
            if (duration > 0) position = position.coerceAtMost(duration)
            // A song is its title and artist: an album name that arrives a
            // moment later, or a tidied-up title, isn't a new play.
            val key = ScrobbleIdentity.key("", meta.getString("title"), meta.getString("artist"), 0)
            var listen = tracks[source]
            // The same song starting over (on repeat) is a new play.
            val repeated = listen != null && listen.key == key && duration > 0 && listen.position >= duration - 5000 &&
                position < 5000 && playing && listen.playing
            if (listen == null || listen.key != key || repeated) {
                listen?.let { finish(it, now, settings, did) }
                listen = if (listen == null) restore(source, key, did, position) else null
                if (listen == null) listen = Listen(UUID.randomUUID().toString(), key, source, System.currentTimeMillis() / 1000, meta)
                tracks[source] = listen
            }
            if (listen == null) return@forEach
            listen.metadata = meta
            listen.clock.update(now, playing && (duration == 0L || position < duration))
            listen.position = position
            listen.playing = playing
            qualify(listen, settings, did)
            if (playing) {
                anyPlaying = true
                current = meta.optString("title") + " — " + meta.optString("artist")
            }
        }
        tracks.keys.filter { it !in sources }.forEach { source ->
            tracks.remove(source)?.let {
                if (source in settings.apps) finish(it, now, settings, did)
                ScrobbleStore.db(this).delete("playback", "id=?", arrayOf(source))
            }
        }
        // Every few seconds, where each song is up to is written down, so a
        // listen survives Android restarting the service part-way through.
        if (now - checkpoint > 5000) { tracks.values.forEach { save(it, did) }; checkpoint = now }
        if (anyPlaying) {
            if (wakeLock?.isHeld != true) {
                wakeLock = getSystemService(PowerManager::class.java)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Stellar:Scrobbling").apply { acquire(10 * 60 * 1000L) }
            }
        } else releaseWakeLock()
    }

    private fun finish(listen: Listen, now: Long, settings: ScrobbleSettings, did: String) {
        listen.clock.update(now, false)
        qualify(listen, settings, did)
    }

    private fun qualify(listen: Listen, settings: ScrobbleSettings, did: String) {
        if (!listen.submitted && listen.clock.qualifies(
                listen.metadata.optLong("duration"), listen.position,
                settings.mode, settings.seconds, settings.percent, settings.minimum
            )
        ) {
            val payload = payload(listen.metadata, listen.started)
            // Saved and marked as saved in one step, so a crash in between
            // can't send the same listen twice.
            val db = ScrobbleStore.db(this)
            db.beginTransaction()
            try {
                ScrobbleStore.enqueue(this, did, listen.source, payload)
                listen.submitted = true
                save(listen, did)
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
            ScrobbleUploadJob.schedule(this)
        }
    }

    private fun payload(meta: JSONObject, timestamp: Long): JSONObject = JSONObject(meta.toString()).apply {
        put("timestamp", timestamp)
        // Rocksky's records need an album and an album artist.
        if (optString("album").isBlank()) put("album", getString("title"))
        if (optString("albumArtist").isBlank()) put("albumArtist", getString("artist"))
    }

    private fun save(listen: Listen, did: String) {
        val json = JSONObject().put("did", did).put("key", listen.key).put("id", listen.id)
            .put("started", listen.started).put("metadata", listen.metadata).put("submitted", listen.submitted)
            .put("listened", listen.clock.listenedMs).put("position", listen.position).put("saved", System.currentTimeMillis())
        ScrobbleStore.db(this).insertWithOnConflict("playback", null, ContentValues().apply {
            put("id", listen.source); put("state", json.toString())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** Picks a half-heard song back up after the service was restarted —
     *  if it's the same song, for the same account, within a day, and the
     *  player hasn't gone back to the start. */
    private fun restore(source: String, key: String, did: String, position: Long): Listen? {
        val raw = ScrobbleStore.db(this).rawQuery("SELECT state FROM playback WHERE id=?", arrayOf(source)).use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: return null
        val j = JSONObject(raw)
        if (j.optString("did") != did || j.optString("key") != key ||
            System.currentTimeMillis() - j.optLong("saved") > 86400000 || position + 3000 < j.optLong("position")
        ) return null
        return Listen(j.getString("id"), key, source, j.getLong("started"), j.getJSONObject("metadata")).apply {
            clock.restore(j.optLong("listened")); submitted = j.optBoolean("submitted"); this.position = position
        }
    }

    /** A song-recognition app announced a match. */
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        try {
            val settings = ScrobbleStore.settings(this)
            val did = account ?: return
            if (!settings.enabled || sbn.packageName !in settings.apps) return
            val recognition = RecognitionParser.parse(sbn) ?: return
            // (Not when one of your players is already playing that very song.)
            if (tracks.values.any {
                    it.playing && it.metadata.optString("title").equals(recognition.optString("title"), true) &&
                        it.metadata.optString("artist").equals(recognition.optString("artist"), true)
                }
            ) return
            pendingRecognition.remove(sbn.key)?.let { handler.removeCallbacks(it) }
            val ambient = sbn.packageName in RecognitionParser.ambientPackages
            val task = Runnable {
                pendingRecognition.remove(sbn.key)
                val config = ScrobbleStore.settings(this)
                if (!config.enabled || account != did || sbn.packageName !in config.apps) return@Runnable
                val now = System.currentTimeMillis()
                val identity = JSONArray(listOf(did, sbn.packageName, recognition.getString("title"), recognition.getString("artist"))).toString()
                val db = ScrobbleStore.db(this)
                // The same song recognised again within five minutes is the
                // same listen.
                val seen = db.rawQuery("SELECT seen FROM recognitions WHERE id=?", arrayOf(identity)).use { if (it.moveToFirst()) it.getLong(0) else 0L }
                if (now - seen < 5 * 60 * 1000) return@Runnable
                db.beginTransaction()
                try {
                    ScrobbleStore.enqueue(this, did, sbn.packageName, payload(recognition, sbn.postTime / 1000))
                    db.insertWithOnConflict("recognitions", null, ContentValues().apply { put("id", identity); put("seen", now) }, SQLiteDatabase.CONFLICT_REPLACE)
                    db.delete("recognitions", "seen<?", arrayOf((now - 86400000).toString()))
                    db.setTransactionSuccessful()
                } finally { db.endTransaction() }
                ScrobbleUploadJob.schedule(this)
            }
            pendingRecognition[sbn.key] = task
            // (The Pixel's ambient matches wait a moment: a song that's gone
            // again within fifteen seconds was only passing by.)
            handler.postDelayed(task, if (ambient) 15000 else 0)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "reading a recognition failed", e)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn?.key?.let { pendingRecognition.remove(it)?.let { handler.removeCallbacks(it) } }
    }

    private fun releaseWakeLock() { if (wakeLock?.isHeld == true) wakeLock?.release(); wakeLock = null }

    private fun disconnect() {
        connected = false; current = null
        handler.removeCallbacksAndMessages(null)
        pendingRecognition.clear()
        ScrobbleStore.prefs(this).unregisterOnSharedPreferenceChangeListener(prefsListener)
        try { manager.removeOnActiveSessionsChangedListener(sessions) } catch (_: Exception) {}
        controllers.values.forEach { it.first.unregisterCallback(it.second) }; controllers.clear()
        try { account?.let { did -> tracks.values.forEach { save(it, did) } } } catch (_: Exception) {}
        tracks.clear(); releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE); foreground = false
    }

    override fun onListenerDisconnected() {
        disconnect()
        if (ScrobbleStore.settings(this).enabled) requestRebind(ComponentName(this, StellarScrobbleListener::class.java))
    }

    override fun onDestroy() { disconnect(); super.onDestroy() }

    companion object {
        private const val TAG = "StellarScrobbler"
        private const val CHANNEL = "stellar_scrobbling"
        private const val NOTIFICATION_ID = 7343
        /** Android has the listener connected right now. */
        @Volatile var connected = false
        /** "Title — Artist" of what's playing in a chosen app, for Settings. */
        @Volatile var current: String? = null
    }
}
