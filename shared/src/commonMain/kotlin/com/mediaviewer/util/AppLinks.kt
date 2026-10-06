package com.mediaviewer.util

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Places inside Stellar that something outside the page can ask for: a
 * tapped notification, a home-screen widget, the in-app notification
 * banner. Written as short links:
 *   "dm:<convoId>"  a chat          "dms"       the DM list
 *   "inbox"         the Inbox       "calendar"  Launchpad → Calendar
 *   "calendar:<yyyymmdd>"  the Calendar, on that day
 *   "note:<id>"     one note        "notes"     Launchpad → Notes
 * The platform shell sets [pending] (Android: the launch intent's
 * "stellar_open" extra; iOS: a stellar:// link) and the app opens it as
 * soon as it's ready (see LocalOverlayHost).
 */
object AppLinks {
    const val EXTRA = "stellar_open"

    var pending by mutableStateOf<String?>(null)
        private set

    fun open(link: String?) {
        val l = link?.trim()?.takeIf { it.isNotEmpty() } ?: return
        // "stellar://dm/abc" → "dm:abc"
        pending = if (l.startsWith("stellar://")) {
            val rest = l.removePrefix("stellar://").trim('/')
            val kind = rest.substringBefore('/')
            val value = rest.substringAfter('/', "")
            if (value.isEmpty()) kind else "$kind:$value"
        } else l
    }

    fun take(): String? = pending.also { pending = null }
}

/** One in-app notification banner. */
data class InAppNotice(
    val id: Long,
    val title: String,
    val text: String,
    val avatarUrl: String?,
    /** Where a tap goes (see [AppLinks]). */
    val link: String
)

/**
 * Notifications while Stellar is open (supporters): a new DM or new Inbox
 * activity slides in at the top of whatever page you're on — unless you're
 * already looking at the DMs / the Inbox — and a tap opens it.
 */
object InAppNotices {
    var current by mutableStateOf<InAppNotice?>(null)
        private set
    private var nextId = 1L

    fun show(title: String, text: String, avatarUrl: String?, link: String) {
        current = InAppNotice(nextId++, title, text, avatarUrl, link)
    }

    fun dismiss(id: Long) { if (current?.id == id) current = null }
}
