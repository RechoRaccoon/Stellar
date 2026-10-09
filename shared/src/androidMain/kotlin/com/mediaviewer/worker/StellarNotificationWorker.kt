// check:jvm
package com.mediaviewer.worker

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.mediaviewer.platform.WidgetChat
import com.mediaviewer.repository.BlueskyRepository
import com.mediaviewer.util.AppLinks
import com.mediaviewer.util.BskyServices
import com.mediaviewer.util.LocalData
import com.mediaviewer.util.PreferencesManager
import com.mediaviewer.widget.StellarWidgets
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Supporter Settings → Notifications (Android), and the DMs home-screen
 * widget: checks for unread DMs and new Inbox activity and posts ordinary
 * device notifications for anything new. Entirely local — no push server.
 * Both checks go straight to Bluesky's chat service / AppView with
 * service-auth tokens (see BlueskyRepository.viaService), so they cost the
 * PDS at most one token request an hour and can't get the account
 * rate-limited.
 *
 * How it's kept running (see [StellarNotificationScheduler]): a WorkManager
 * job every 15 minutes was the only trigger before, and Android postpones
 * those for hours once the phone is idle or the app hasn't been opened for
 * a while — which is why notifications all but never arrived. Now an alarm
 * that Android still delivers while the phone is idle wakes this check
 * every few minutes and runs it straight away as "expedited" work; the
 * 15-minute job stays as a backstop.
 */
class StellarNotificationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext
        return try {
            check(app)
            Result.success()
        } catch (_: Exception) {
            // Offline, signed out, …: just try again next time.
            Result.success()
        }
    }

    /** Android 11 and older run an expedited check as a short foreground
     *  service, which has to show something while it does. */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val app = applicationContext
        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(StellarNotificationScheduler.CHANNEL_CHECK) == null) {
            nm.createNotificationChannel(
                NotificationChannel(StellarNotificationScheduler.CHANNEL_CHECK, "Checking for notifications", NotificationManager.IMPORTANCE_MIN)
            )
        }
        val n = NotificationCompat.Builder(app, StellarNotificationScheduler.CHANNEL_CHECK)
            .setSmallIcon(com.mediaviewer.R.drawable.stellar_logo_vector)
            .setContentTitle("Checking for new messages")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()
        return ForegroundInfo(73400, n)
    }

    private suspend fun check(app: Context) {
        val local = app.getSharedPreferences(LocalData.PREFS, Context.MODE_PRIVATE)
        val wantDms = local.getBoolean(LocalData.KEY_NOTIFY_DMS, false)
        val wantInbox = local.getBoolean(LocalData.KEY_NOTIFY_INBOX, false)
        val dmWidget = StellarWidgets.hasDmWidget(app)
        if (!wantDms && !wantInbox && !dmWidget) return
        // While Stellar is open it shows all of this itself (its badges,
        // the in-app banner, and it keeps the widgets current).
        if (StellarNotificationScheduler.appInForeground) return

        val p = PreferencesManager(app)
        var token = p.bskyAccessJwt.first().orEmpty()
        var did = p.bskyDid.first().orEmpty()
        if (token.isBlank() || did.isBlank()) return
        // Supporters only.
        val supporters = app.getSharedPreferences("stellar_supporters", Context.MODE_PRIVATE)
            .getString("dids", null)?.split(',')?.toSet() ?: emptySet()
        if (!com.mediaviewer.util.FeatureFlags.ALL_FEATURES_FREE && did !in supporters) return

        // (An account on its own PDS is checked there.)
        BskyServices.init(app)
        val repo = BlueskyRepository().apply { updateServiceUrl(BskyServices.urlFor(did)) }
        val state = app.getSharedPreferences("stellar_notification_state", Context.MODE_PRIVATE)

        suspend fun refresh(): Boolean {
            val refreshJwt = p.bskyRefreshJwt.first().orEmpty()
            if (refreshJwt.isBlank()) return false
            val r = repo.refreshToken(refreshJwt).getOrNull() ?: return false
            p.saveBskySession(r.accessJwt, r.refreshJwt, r.did, r.handle)
            token = r.accessJwt
            did = r.did
            return true
        }
        fun authError(t: Throwable?): Boolean {
            val m = t?.message ?: return false
            return m.contains("401") || m.contains("400") || m.contains("ExpiredToken", true) || m.contains("InvalidToken", true)
        }

        if (wantDms || dmWidget) {
            // (The 50 most recently active chats: anything unread is among
            // them — one request instead of three, every few minutes.)
            var convos = repo.listConvos(token, did, maxPages = 1)
            if (convos.isFailure && authError(convos.exceptionOrNull()) && refresh()) convos = repo.listConvos(token, did, maxPages = 1)
            val list = convos.getOrNull()
            if (list != null) {
                // The DMs widget shows exactly what was just read.
                if (dmWidget) {
                    // (Streaks come from the app's own saved data.)
                    runCatching { com.mediaviewer.util.LocalData.init(app) }
                    StellarWidgets.saveChats(app, com.mediaviewer.platform.widgetChats(list))
                }
                if (wantDms) {
                    val editor = state.edit()
                    val firstRun = !state.contains("dm_seeded")
                    for (c in list) {
                        if (c.unreadCount <= 0) continue
                        val key = "dm_" + c.convoId
                        // What "already told you about this" is keyed on: the
                        // chat's newest activity, plus how many are unread
                        // (so a chat whose time doesn't move still counts).
                        val stamp = c.lastActivityAt + "|" + c.unreadCount
                        if (state.getString(key, null) == stamp) continue
                        editor.putString(key, stamp)
                        // The very first check only records what's already there.
                        if (firstRun) continue
                        val name = c.member.displayName.ifBlank { c.member.handle }.ifBlank { "New message" }
                        val text = c.lastMessageText.ifBlank {
                            if (c.unreadCount == 1) "Sent you a message" else "${c.unreadCount} new messages"
                        }
                        post(app, StellarNotificationScheduler.CHANNEL_DMS, "Messages", c.convoId.hashCode(), name, text, "dm:" + c.convoId)
                    }
                    editor.putBoolean("dm_seeded", true).apply()
                }
            }
        }

        if (wantInbox) {
            var resp = repo.listNotifications(token, did, limit = 25)
            if (resp.isFailure && authError(resp.exceptionOrNull()) && refresh()) resp = repo.listNotifications(token, did, limit = 25)
            val all = resp.getOrNull()?.notifications.orEmpty()
            val last = state.getString("inbox_last", null)
            val newest = all.maxOfOrNull { it.indexedAt }
            if (last != null) {
                val fresh = all.filter { !it.isRead && it.indexedAt > last }.sortedByDescending { it.indexedAt }
                if (fresh.isNotEmpty()) {
                    val first = fresh.first()
                    val who = first.author.displayName?.ifBlank { null } ?: first.author.handle
                    val what = when (first.reason) {
                        "like", "like-via-repost" -> "liked your post"
                        "repost", "repost-via-repost" -> "reposted your post"
                        "follow" -> "followed you"
                        "mention" -> "mentioned you"
                        "reply" -> "replied to you"
                        "quote" -> "quoted your post"
                        else -> "sent you a notification"
                    }
                    val title = if (fresh.size == 1) "Stellar" else "${fresh.size} new notifications"
                    val text = if (fresh.size == 1) "$who $what" else "$who $what, and ${fresh.size - 1} more"
                    post(app, StellarNotificationScheduler.CHANNEL_INBOX, "Inbox", 73410, title, text, "inbox")
                }
            }
            if (newest != null && newest != last) state.edit().putString("inbox_last", newest).apply()
        }
    }

    private fun post(app: Context, channelId: String, channelName: String, id: Int, title: String, text: String, link: String) {
        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(channelId) == null) {
            nm.createNotificationChannel(NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_HIGH))
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            app.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        // Tapping it opens Stellar on that chat / the Inbox.
        val launch = app.packageManager.getLaunchIntentForPackage(app.packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(AppLinks.EXTRA, link)
        }
        val pending = launch?.let {
            PendingIntent.getActivity(app, id, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        val n = NotificationCompat.Builder(app, channelId)
            .setSmallIcon(com.mediaviewer.R.drawable.stellar_logo_vector)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(if (channelId == StellarNotificationScheduler.CHANNEL_DMS) NotificationCompat.CATEGORY_MESSAGE else NotificationCompat.CATEGORY_SOCIAL)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        try { nm.notify(id, n) } catch (_: Exception) {}
    }
}

/**
 * Wakes the notification check. Three triggers: the repeating alarm
 * ([StellarNotificationScheduler]), the phone finishing booting, and
 * Stellar being updated — the last two only put the schedule back, since
 * Android forgets alarms on both.
 */
class StellarNotificationAlarm : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        if (!StellarNotificationScheduler.wanted(app)) {
            StellarNotificationScheduler.cancel(app)
            return
        }
        if (intent.action == StellarNotificationScheduler.ACTION_CHECK) StellarNotificationScheduler.checkNow(app)
        StellarNotificationScheduler.schedule(app)
    }
}

object StellarNotificationScheduler {
    private const val UNIQUE_WORK_NAME = "stellar_notifications"
    private const val UNIQUE_NOW_NAME = "stellar_notifications_now"
    const val ACTION_CHECK = "com.mediaviewer.worker.ACTION_CHECK_NOTIFICATIONS"
    const val CHANNEL_DMS = "stellar_dms_v2"
    const val CHANNEL_INBOX = "stellar_inbox_v2"
    const val CHANNEL_CHECK = "stellar_check"
    /** How often the alarm asks for a check. (While the phone is in deep
     *  idle Android itself stretches this to about one every 9 minutes.) */
    private const val ALARM_INTERVAL_MS = 5 * 60 * 1000L

    /** Set by the app as it comes to the front / goes to the background. */
    @Volatile var appInForeground: Boolean = false

    /** True while anything needs the background check: either toggle in
     *  Supporter Settings, or a DMs widget on the home screen. */
    fun wanted(context: Context): Boolean {
        val prefs = context.getSharedPreferences(LocalData.PREFS, Context.MODE_PRIVATE)
        return prefs.getBoolean(LocalData.KEY_NOTIFY_DMS, false) || prefs.getBoolean(LocalData.KEY_NOTIFY_INBOX, false) ||
            StellarWidgets.hasDmWidget(context)
    }

    private fun alarmIntent(context: Context): PendingIntent {
        val intent = Intent(context, StellarNotificationAlarm::class.java).setAction(ACTION_CHECK)
        return PendingIntent.getBroadcast(context, 73420, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun schedule(context: Context) {
        val app = context.applicationContext
        // The backstop: WorkManager's own repeating job.
        val request = PeriodicWorkRequestBuilder<StellarNotificationWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        runCatching {
            WorkManager.getInstance(app).enqueueUniquePeriodicWork(UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
        // The real trigger: an alarm Android delivers even while idle.
        // (setAndAllowWhileIdle needs no special permission.)
        runCatching {
            val am = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            // Battery Saver on: every 15 minutes instead of 5.
            val saver = runCatching { (app.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).isPowerSaveMode }.getOrDefault(false)
            val at = SystemClock.elapsedRealtime() + if (saver) 3 * ALARM_INTERVAL_MS else ALARM_INTERVAL_MS
            if (Build.VERSION.SDK_INT >= 23) am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, alarmIntent(app))
            else am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, alarmIntent(app))
        }
    }

    /** One check, as soon as there's a connection ("expedited": Android
     *  runs it now rather than when it finds convenient). */
    fun checkNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<StellarNotificationWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        runCatching {
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(UNIQUE_NOW_NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }

    fun cancel(context: Context) {
        val app = context.applicationContext
        runCatching { WorkManager.getInstance(app).cancelUniqueWork(UNIQUE_WORK_NAME) }
        runCatching { (app.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(alarmIntent(app)) }
    }
}
