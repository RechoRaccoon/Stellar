package com.mediaviewer.platform

import com.mediaviewer.repository.BlueskyRepository
import com.mediaviewer.util.AppLinks
import com.mediaviewer.util.BskyServices
import com.mediaviewer.util.LocalData
import com.mediaviewer.util.PreferencesManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Supporter Settings → Notifications on the web: the same check Android
 * runs (worker/StellarNotificationWorker.kt) — unread DMs and new Inbox
 * activity, read straight from Bluesky — every few minutes while Stellar's
 * tab is open but not being looked at, shown as the browser's own
 * notifications (once it's been allowed to show them). Clicking one
 * brings the tab forward on that chat or the Inbox. Nothing runs once the
 * tab is closed.
 */
object WebNotifications {
    private const val STATE_PREFS = "stellar_notification_state"
    private const val EVERY_MS = 3 * 60_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null

    /** True while Stellar's tab is the one being looked at. */
    private val windowFocused: Boolean get() = runCatching { pageVisible() }.getOrDefault(true)

    private fun wanted(): Boolean {
        val prefs = WebContext.sharedPreferences(LocalData.PREFS)
        return prefs.getBoolean(LocalData.KEY_NOTIFY_DMS, false) || prefs.getBoolean(LocalData.KEY_NOTIFY_INBOX, false)
    }

    /** Starts or stops the check to match the two Supporter Settings
     *  switches (asking the browser for permission when one is turned on). */
    fun sync(requestPermission: Boolean = false) {
        val on = wanted()
        if (on && requestPermission) runCatching { askPermission() }
        if (!on) { loop?.cancel(); loop = null; return }
        if (loop?.isActive == true) return
        loop = scope.launch {
            while (isActive) {
                delay(EVERY_MS)
                withTimeoutOrNull(60_000) { runCatching { check() } }
            }
        }
    }

    private suspend fun check() {
        // While Stellar's window is in front it shows all of this itself
        // (badges, the in-app banner).
        if (windowFocused) return
        val context = WebContext
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
        if (!com.mediaviewer.util.FeatureFlags.ALL_FEATURES_FREE && did !in supporters) return

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

        // (Only the 50 most recently active chats: anything unread is
        // among them.)
        var convos = repo.listConvos(token, did, maxPages = 1)
        if (convos.isFailure && authError(convos.exceptionOrNull()) && refresh()) convos = repo.listConvos(token, did, maxPages = 1)
        val list = convos.getOrNull()
        if (list != null) {
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

    /** One notification; [link] is where a click goes. */
    @Suppress("UNUSED_PARAMETER")
    private fun post(id: String, thread: String, title: String, text: String, link: String) {
        runCatching { showNotification(id, title, text) { AppLinks.open(link) } }
    }
}

private fun pageVisible(): Boolean = js("document.visibilityState === 'visible'")

private fun askPermission(): Unit = js("{ if ('Notification' in window && Notification.permission === 'default') Notification.requestPermission().catch(() => {}); }")

private fun showNotification(tag: String, title: String, body: String, onClick: () -> Unit): Unit = js("""{
    if (!('Notification' in window) || Notification.permission !== 'granted') return;
    const n = new Notification(title, { body: body, tag: tag, icon: 'icon-192.png' });
    n.onclick = () => { window.focus(); onClick(); n.close(); };
}""")

