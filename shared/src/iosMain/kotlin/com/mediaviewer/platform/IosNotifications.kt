package com.mediaviewer.platform

import com.mediaviewer.repository.BlueskyRepository
import com.mediaviewer.util.AppLinks
import com.mediaviewer.util.BskyServices
import com.mediaviewer.util.LocalData
import com.mediaviewer.util.PreferencesManager
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import platform.BackgroundTasks.BGAppRefreshTaskRequest
import platform.BackgroundTasks.BGTask
import platform.BackgroundTasks.BGTaskScheduler
import platform.Foundation.NSDate
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationState
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionBadge
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNAuthorizationStatusNotDetermined
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotification
import platform.UserNotifications.UNNotificationPresentationOptions
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationResponse
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNUserNotificationCenter
import platform.UserNotifications.UNUserNotificationCenterDelegateProtocol
import platform.darwin.NSObject

/**
 * Supporter Settings → Notifications on iOS: the same local check Android
 * runs (worker/StellarNotificationWorker.kt) — unread DMs and new Inbox
 * activity, read straight from Bluesky, posted as ordinary iPhone
 * notifications. No push server.
 *
 * What wakes it: iOS's "background app refresh". The app asks to be woken
 * no sooner than every 15 minutes; iOS alone decides when it really
 * happens (more often for apps that are opened a lot, never while Low
 * Power Mode or Settings › General › Background App Refresh has it off).
 * So these arrive later than Android's, and that can't be tightened
 * without a push server.
 *
 * The same check keeps the DMs home-screen widget current.
 */
@OptIn(ExperimentalForeignApi::class)
object IosNotifications {
    /** Also listed in Info.plist (BGTaskSchedulerPermittedIdentifiers);
     *  iOS refuses identifiers that aren't. */
    const val TASK_ID = "rechoraccoon.stellar.refresh"
    private const val EARLIEST_SECONDS = 15.0 * 60.0
    private const val STATE_PREFS = "stellar_notification_state"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var registered = false
    // (Kept here: the notification centre doesn't hold on to its delegate.)
    private val delegate = TapDelegate()

    /**
     * Tells iOS what to run when it grants a background refresh. iOS only
     * accepts this while the app is still launching, which is why the
     * Swift app calls it from its init() and not the Compose UI.
     */
    fun registerBackgroundTask() {
        if (registered) return
        registered = runCatching {
            BGTaskScheduler.sharedScheduler.registerForTaskWithIdentifier(TASK_ID, usingQueue = null) { task ->
                if (task != null) runInBackground(task)
            }
        }.getOrDefault(false)
    }

    /** Tapped notifications open their chat / the Inbox. */
    fun install() {
        runCatching { UNUserNotificationCenter.currentNotificationCenter().delegate = delegate }
    }

    private fun wanted(): Boolean {
        val prefs = IosContext.sharedPreferences(LocalData.PREFS)
        return prefs.getBoolean(LocalData.KEY_NOTIFY_DMS, false) || prefs.getBoolean(LocalData.KEY_NOTIFY_INBOX, false)
    }

    /** Matches the schedule to the two Supporter Settings switches, and
     *  (when one was just switched on) asks for iOS's permission. */
    fun sync(requestPermission: Boolean) {
        val on = wanted()
        if (on && requestPermission) {
            runCatching {
                val center = UNUserNotificationCenter.currentNotificationCenter()
                center.getNotificationSettingsWithCompletionHandler { settings ->
                    if (settings?.authorizationStatus == UNAuthorizationStatusNotDetermined) {
                        center.requestAuthorizationWithOptions(
                            UNAuthorizationOptionAlert or UNAuthorizationOptionSound or UNAuthorizationOptionBadge
                        ) { _, _ -> }
                    }
                }
            }
        }
        // (The DMs widget is refreshed by the same check, so it stays
        // scheduled whenever someone is signed in — iOS gives no way to ask
        // whether a widget is on the home screen from here.)
        schedule()
    }

    /** Asks iOS for the next background refresh. Safe to call often: a
     *  newer request for the same identifier replaces the older one. */
    fun schedule() {
        if (!registered) return
        runCatching {
            val request = BGAppRefreshTaskRequest(identifier = TASK_ID)
            request.earliestBeginDate = NSDate.dateWithTimeIntervalSinceNow(EARLIEST_SECONDS)
            BGTaskScheduler.sharedScheduler.submitTaskRequest(request, error = null)
        }
    }

    private fun runInBackground(task: BGTask) {
        // Ask for the one after this first, so the chain never breaks.
        schedule()
        // iOS must be told exactly once that the work is over — whichever
        // comes first, the check finishing or iOS calling time.
        val reported = kotlin.concurrent.AtomicInt(0)
        fun report(success: Boolean) {
            if (reported.compareAndSet(0, 1)) runCatching { task.setTaskCompletedWithSuccess(success) }
        }
        val job: Job = scope.launch {
            // iOS allows a refresh about 30 seconds.
            val finished = withTimeoutOrNull(25_000) { runCatching { check() } }
            report(finished != null)
        }
        task.expirationHandler = {
            job.cancel()
            report(false)
        }
    }

    private suspend fun check() {
        // While Stellar is on screen it shows all of this itself (badges,
        // the in-app banner) and keeps the widgets current.
        if (UIApplication.sharedApplication.applicationState == UIApplicationState.UIApplicationStateActive) return
        val context = IosContext
        val local = context.sharedPreferences(LocalData.PREFS)
        val wantDms = local.getBoolean(LocalData.KEY_NOTIFY_DMS, false)
        val wantInbox = local.getBoolean(LocalData.KEY_NOTIFY_INBOX, false)

        val p = PreferencesManager(context)
        var token = p.bskyAccessJwt.first().orEmpty()
        var did = p.bskyDid.first().orEmpty()
        if (token.isBlank() || did.isBlank()) return
        // Supporters only (the widget included, like on Android).
        val supporters = context.sharedPreferences("stellar_supporters")
            .getString("dids", null)?.split(',')?.toSet() ?: emptySet()
        if (did !in supporters) return

        // (An account on its own PDS is checked there.)
        BskyServices.init(context)
        val repo = BlueskyRepository().apply { updateServiceUrl(BskyServices.urlFor(did)) }
        val state = context.sharedPreferences(STATE_PREFS)

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

        var convos = repo.listConvos(token, did)
        if (convos.isFailure && authError(convos.exceptionOrNull()) && refresh()) convos = repo.listConvos(token, did)
        val list = convos.getOrNull()
        if (list != null) {
            // The DMs widget shows exactly what was just read. (Streaks
            // come from the app's own saved data.)
            runCatching {
                LocalData.init(context)
                IosWidgetBridge.publish(widgetChats(list))
            }
            if (wantDms) {
                val editor = state.edit()
                val firstRun = !state.contains("dm_seeded")
                for (c in list) {
                    if (c.unreadCount <= 0) continue
                    val key = "dm_" + c.convoId
                    // "Already told you about this": the chat's newest
                    // activity plus how many are unread.
                    val stamp = c.lastActivityAt + "|" + c.unreadCount
                    if (state.getString(key, null) == stamp) continue
                    editor.putString(key, stamp)
                    // The very first check only records what's already there.
                    if (firstRun) continue
                    val name = c.member.displayName.ifBlank { c.member.handle }.ifBlank { "New message" }
                    val text = c.lastMessageText.ifBlank {
                        if (c.unreadCount == 1) "Sent you a message" else "${c.unreadCount} new messages"
                    }
                    post("dm_" + c.convoId, "dms", name, text, "dm:" + c.convoId)
                }
                editor.putBoolean("dm_seeded", true).apply()
                // The number on Stellar's icon: chats with something unread.
                setBadge(list.count { it.unreadCount > 0 })
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
                    post("inbox", "inbox", title, text, "inbox")
                }
            }
            if (newest != null && newest != last) state.edit().putString("inbox_last", newest).apply()
        }
    }

    /** One notification. [id] replaces an earlier one with the same id (a
     *  chat only ever has one on screen); [link] is where a tap goes. */
    private fun post(id: String, thread: String, title: String, text: String, link: String) {
        runCatching {
            val content = UNMutableNotificationContent()
            content.setTitle(title)
            content.setBody(text)
            content.setSound(UNNotificationSound.defaultSound)
            content.setThreadIdentifier(thread)
            content.setUserInfo(mapOf<Any?, Any?>(AppLinks.EXTRA to link))
            val request = UNNotificationRequest.requestWithIdentifier(id, content = content, trigger = null)
            UNUserNotificationCenter.currentNotificationCenter().addNotificationRequest(request, withCompletionHandler = null)
        }
    }

    private fun setBadge(count: Int) {
        runCatching { UIApplication.sharedApplication.applicationIconBadgeNumber = count.toLong() }
    }

    /** Stellar is on screen again: what its notifications were saying is
     *  now in front of you, so they (and the icon's number) are cleared. */
    fun clearDelivered() {
        runCatching { UNUserNotificationCenter.currentNotificationCenter().removeAllDeliveredNotifications() }
        setBadge(0)
    }

    private class TapDelegate : NSObject(), UNUserNotificationCenterDelegateProtocol {
        /** A notification was tapped. */
        override fun userNotificationCenter(
            center: UNUserNotificationCenter,
            didReceiveNotificationResponse: UNNotificationResponse,
            withCompletionHandler: () -> Unit
        ) {
            val link = didReceiveNotificationResponse.notification.request.content.userInfo[AppLinks.EXTRA] as? String
            if (link != null) AppLinks.open(link)
            withCompletionHandler()
        }

        /** One arrived while Stellar is open: the in-app banner already
         *  covers that, so nothing more is shown. */
        override fun userNotificationCenter(
            center: UNUserNotificationCenter,
            willPresentNotification: UNNotification,
            withCompletionHandler: (UNNotificationPresentationOptions) -> Unit
        ) {
            withCompletionHandler(0u)
        }
    }
}

/** Called by the Swift app from its init(), before launch finishes (the
 *  only moment iOS accepts a background-refresh handler). */
fun registerIosBackgroundRefresh() {
    IosNotifications.registerBackgroundTask()
    // Also this early, so a tap on a notification that opens Stellar from
    // closed still reaches it.
    IosNotifications.install()
}

/** Called by the Swift app each time Stellar goes to the background. */
fun iosAppDidEnterBackground() {
    IosNotifications.schedule()
}

/** Called by the Swift app each time Stellar comes to the front. */
fun iosAppDidBecomeActive() {
    IosNotifications.clearDelivered()
}
