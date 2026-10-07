// check:jvm
package com.mediaviewer.platform

import kotlinx.coroutines.flow.first
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.webkit.MimeTypeMap
import java.io.File
import java.util.UUID

actual object LocalPlatform {
    private var player: MediaPlayer? = null

    private fun activityOf(context: Context): Activity? {
        var c: Context? = context
        while (c is ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return null
    }

    actual fun importMedia(context: PlatformContext, uri: PlatformUri, folder: String): PlatformUri? = try {
        val app = context.applicationContext
        val dir = File(File(app.filesDir, "local_media"), folder).apply { mkdirs() }
        // Already one of ours: nothing to copy.
        val ownPath = uri.path
        if (uri.scheme == "file" && ownPath != null && ownPath.startsWith(File(app.filesDir, "local_media").absolutePath)) {
            uri
        } else {
            val type = app.contentResolver.getType(uri)
            val ext = type?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
                ?: uri.lastPathSegment?.substringAfterLast('.', "")?.takeIf { it.length in 2..5 }
                ?: "bin"
            val out = File(dir, UUID.randomUUID().toString() + "." + ext)
            val input = app.contentResolver.openInputStream(uri)
            if (input == null) null else {
                input.use { ins -> out.outputStream().use { ins.copyTo(it) } }
                Uri.fromFile(out)
            }
        }
    } catch (_: Exception) {
        null
    }

    actual fun parseUri(text: String): PlatformUri = Uri.parse(text)

    actual fun deleteMedia(context: PlatformContext, uri: String) {
        try {
            val parsed = Uri.parse(uri)
            val path = parsed.path ?: return
            val root = File(context.applicationContext.filesDir, "local_media").absolutePath
            if (parsed.scheme == "file" && path.startsWith(root)) File(path).delete()
        } catch (_: Exception) {
        }
    }

    actual fun setBatterySaver(context: PlatformContext, on: Boolean) {
        val activity = activityOf(context) ?: return
        try {
            val attrs = activity.window.attributes
            // 0 = "no preference" (the display's own choice).
            attrs.preferredRefreshRate = if (on) 60f else 0f
            activity.window.attributes = attrs
        } catch (_: Exception) {
        }
    }

    actual fun playLoopingWav(context: PlatformContext, wav: ByteArray) {
        stopSound()
        try {
            val file = File(context.applicationContext.cacheDir, "stellar_timer_alarm.wav")
            file.writeBytes(wav)
            val p = MediaPlayer()
            p.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            p.setDataSource(file.absolutePath)
            p.isLooping = true
            p.prepare()
            p.start()
            player = p
        } catch (_: Exception) {
            player = null
        }
    }

    actual fun stopSound() {
        val p = player ?: return
        player = null
        try { p.stop() } catch (_: Exception) {}
        try { p.release() } catch (_: Exception) {}
    }

    actual fun syncNotifications(context: PlatformContext, requestPermission: Boolean) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(com.mediaviewer.util.LocalData.PREFS, Context.MODE_PRIVATE)
        val notifications = prefs.getBoolean(com.mediaviewer.util.LocalData.KEY_NOTIFY_DMS, false) ||
            prefs.getBoolean(com.mediaviewer.util.LocalData.KEY_NOTIFY_INBOX, false)
        if (notifications) {
            val activity = activityOf(context)
            val needsPermission = Build.VERSION.SDK_INT >= 33 && activity != null &&
                activity.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
            if (needsPermission && requestPermission) {
                try { activity!!.requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 7341) } catch (_: Exception) {}
            } else if (!needsPermission && activity != null) {
                askToRunInBackground(activity, prefs)
            }
        }
        // (A DMs widget on the home screen needs the check too.)
        if (com.mediaviewer.worker.StellarNotificationScheduler.wanted(app)) {
            com.mediaviewer.worker.StellarNotificationScheduler.schedule(app)
        } else {
            com.mediaviewer.worker.StellarNotificationScheduler.cancel(app)
        }
    }

    /**
     * Asked once, after notifications are switched on: Android's own "let
     * this app run in the background?" prompt. Without it the phone's
     * battery saver holds the check back for hours at a time on most
     * phones, which is exactly how notifications went missing.
     */
    private fun askToRunInBackground(activity: Activity, prefs: android.content.SharedPreferences) {
        if (Build.VERSION.SDK_INT < 23 || prefs.getBoolean("battery_prompted", false)) return
        try {
            val pm = activity.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            if (pm.isIgnoringBatteryOptimizations(activity.packageName)) return
            prefs.edit().putBoolean("battery_prompted", true).apply()
            activity.startActivity(
                android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + activity.packageName))
            )
        } catch (_: Exception) {
        }
    }

    actual fun updateWidgets(context: PlatformContext, dms: List<WidgetChat>?) {
        val app = context.applicationContext
        try {
            if (dms != null) com.mediaviewer.widget.StellarWidgets.saveChats(app, dms)
            else com.mediaviewer.widget.StellarWidgets.refreshAll(app)
        } catch (_: Exception) {
        }
    }

    // ── "Scrobble Music to Rocksky" (see com.mediaviewer.scrobble) ──

    private fun scrobbleListener(c: Context) =
        android.content.ComponentName(c, com.mediaviewer.scrobble.StellarScrobbleListener::class.java)

    actual fun scrobblerStatus(context: PlatformContext): com.mediaviewer.util.ScrobblerStatus {
        val app = context.applicationContext
        return try {
            val store = com.mediaviewer.scrobble.ScrobbleStore
            val p = store.prefs(app)
            val enabled = p.getBoolean(store.KEY_ENABLED, false)
            val did = p.getString(store.KEY_DID, null).orEmpty()
            // Android keeps the list of apps that have "notification
            // access" as text; Stellar is allowed if its listener is on it.
            val component = scrobbleListener(app)
            val access = android.provider.Settings.Secure.getString(app.contentResolver, "enabled_notification_listeners")
                ?.split(':')?.any { android.content.ComponentName.unflattenFromString(it) == component } == true
            val needsPermission = Build.VERSION.SDK_INT >= 33 &&
                app.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
            val chosen = p.getStringSet(store.KEY_APPS, emptySet())!!.toSet()
            // The built-in list, then any other player the listener has seen.
            val known = com.mediaviewer.util.RockskyScrobbler.KNOWN_APPS
            val seen = org.json.JSONObject(p.getString(store.KEY_SEEN, "{}")!!)
            // (Patched builds of YouTube / YouTube Music go with those two
            // rows rather than getting rows of their own.)
            val aliases = com.mediaviewer.util.RockskyScrobbler.APP_ALIASES.values.flatten().toSet()
            val others = seen.keys().asSequence().toList()
                .filter { pkg -> pkg !in aliases && known.none { it.first == pkg } }
                .map { pkg -> com.mediaviewer.util.ScrobbleApp(pkg, seen.optString(pkg, pkg), pkg in chosen) }
                .sortedBy { it.label.lowercase() }
            com.mediaviewer.util.ScrobblerStatus(
                supported = true, enabled = enabled, access = access,
                connected = com.mediaviewer.scrobble.StellarScrobbleListener.connected,
                needsNotificationPermission = needsPermission,
                queued = if (enabled) store.counts(app, did).first else 0,
                error = p.getString("authError", null) ?: p.getString("serviceError", null),
                apps = known.map { com.mediaviewer.util.ScrobbleApp(it.first, it.second, it.first in chosen) } + others,
                percent = p.getInt(store.KEY_PERCENT, com.mediaviewer.util.RockskyScrobbler.DEFAULT_PERCENT),
                seconds = p.getInt(store.KEY_SECONDS, com.mediaviewer.util.RockskyScrobbler.DEFAULT_SECONDS),
                imports = com.mediaviewer.scrobble.ScrobbleImporter.list(app, signedInDid(app)),
                importReading = com.mediaviewer.scrobble.ScrobbleImporter.reading,
                importMessage = p.getString(com.mediaviewer.scrobble.ScrobbleImporter.KEY_MESSAGE, null),
                importBackground = p.getBoolean(com.mediaviewer.scrobble.ScrobbleImporter.KEY_BACKGROUND, false),
                importBatteryRestricted = app.getSystemService(android.os.PowerManager::class.java)
                    ?.isIgnoringBatteryOptimizations(app.packageName) == false
            )
        } catch (_: Exception) {
            com.mediaviewer.util.ScrobblerStatus(supported = true)
        }
    }

    actual fun setScrobblerEnabled(context: PlatformContext, on: Boolean, did: String) {
        val app = context.applicationContext
        try {
            val store = com.mediaviewer.scrobble.ScrobbleStore
            val edit = store.prefs(app).edit().putBoolean(store.KEY_ENABLED, on)
            if (on) edit.putString(store.KEY_DID, did)
            edit.remove("authError").commit()
            if (on) {
                // (Asks Android to connect the listener now, if it's allowed to.)
                try { android.service.notification.NotificationListenerService.requestRebind(scrobbleListener(app)) } catch (_: Exception) {}
                com.mediaviewer.scrobble.ScrobbleUploadJob.schedule(app)
            } else {
                com.mediaviewer.scrobble.ScrobbleUploadJob.cancel(app)
            }
        } catch (_: Exception) {
        }
    }

    actual fun setScrobblerApp(context: PlatformContext, packageName: String, on: Boolean) {
        try {
            val store = com.mediaviewer.scrobble.ScrobbleStore
            val p = store.prefs(context.applicationContext)
            val chosen = p.getStringSet(store.KEY_APPS, emptySet())!!.toSet()
            p.edit().putStringSet(store.KEY_APPS, if (on) chosen + packageName else chosen - packageName).apply()
        } catch (_: Exception) {
        }
    }

    /** The account Stellar is signed in as right now ("" when signed out). */
    private fun signedInDid(app: android.content.Context): String = try {
        kotlinx.coroutines.runBlocking { com.mediaviewer.util.PreferencesManager(app).bskyDid.first() }.orEmpty()
    } catch (_: Exception) { "" }

    actual fun setScrobblerThreshold(context: PlatformContext, percent: Int, seconds: Int) {
        try {
            val store = com.mediaviewer.scrobble.ScrobbleStore
            store.prefs(context.applicationContext).edit()
                .putInt(store.KEY_PERCENT, percent.coerceIn(1, 100)).putInt(store.KEY_SECONDS, seconds.coerceIn(1, 3600)).apply()
        } catch (_: Exception) {
        }
    }

    actual fun importScrobbleHistory(context: PlatformContext, uri: PlatformUri, did: String) {
        com.mediaviewer.scrobble.ScrobbleImporter.add(context.applicationContext, uri, did)
    }

    actual fun cancelScrobbleImport(context: PlatformContext, id: Long) {
        try { com.mediaviewer.scrobble.ScrobbleImporter.cancel(context.applicationContext, id) } catch (_: Exception) {}
    }

    actual fun setScrobbleImportBackground(context: PlatformContext, on: Boolean) {
        val app = context.applicationContext
        try {
            com.mediaviewer.scrobble.ScrobbleStore.prefs(app).edit()
                .putBoolean(com.mediaviewer.scrobble.ScrobbleImporter.KEY_BACKGROUND, on).commit()
            // (Switched off, the notification notices and goes away by itself.)
            if (on) com.mediaviewer.scrobble.ScrobbleImporter.resume(app)
        } catch (_: Exception) {
        }
    }

    @android.annotation.SuppressLint("BatteryLife")
    actual fun requestScrobbleBatteryExemption(context: PlatformContext) {
        val app = context.applicationContext
        // Android's own "Let Stellar always run in the background?" prompt;
        // on a phone that doesn't have it, the list where it's chosen by hand.
        val asked = try {
            (activityOf(context) ?: app).startActivity(
                android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, android.net.Uri.parse("package:" + app.packageName))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        } catch (_: Exception) {
            false
        }
        if (!asked) try {
            (activityOf(context) ?: app).startActivity(
                android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {
        }
    }

    actual fun openScrobblerAccessSettings(context: PlatformContext) {
        val app = context.applicationContext
        // Android 11 and later can open Stellar's own switch directly;
        // before that (or if a phone's Settings app doesn't have that
        // page) it's the list of every app with notification access.
        val direct = if (Build.VERSION.SDK_INT >= 30) {
            try {
                (activityOf(context) ?: app).startActivity(
                    android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                        .putExtra(android.provider.Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, scrobbleListener(app).flattenToString())
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                true
            } catch (_: Exception) {
                false
            }
        } else false
        if (!direct) try {
            (activityOf(context) ?: app).startActivity(
                android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {
        }
    }
}
