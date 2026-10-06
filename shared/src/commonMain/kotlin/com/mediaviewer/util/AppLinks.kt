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

    /** A Bluesky profile or post Stellar was asked to open, as
     *  "<actor>" or "<actor>|<post rkey>" (what AppRoot's pendingProfileLink
     *  takes). Android gets these from bsky.app links directly; this is
     *  the way in for iOS, where they arrive as stellar:// links. */
    var pendingProfile by mutableStateOf<String?>(null)
        private set

    fun clearProfile() { pendingProfile = null }

    private val bskyLink = Regex("""bsky\.app/profile/([^/\s?#&]+)(?:/post/([^/\s?#&]+))?""")
    private val stellarProfile = Regex("""^stellar://profile/([^/\s?#&]+)(?:/post/([^/\s?#&]+))?""")

    /** "<actor>" / "<actor>|<rkey>" when [link] names a Bluesky profile or
     *  post: a bsky.app link on its own, one handed over as
     *  stellar://open?url=<link> (encoded or not), or
     *  stellar://profile/<actor>[/post/<rkey>]. */
    private fun profileTarget(link: String): String? {
        val text = if (link.contains('%')) runCatching { com.mediaviewer.platform.urlDecode(link) }.getOrDefault(link) else link
        val m = stellarProfile.find(text) ?: bskyLink.find(text) ?: return null
        val actor = m.groupValues[1]
        val rkey = m.groupValues[2]
        return if (rkey.isBlank()) actor else "$actor|$rkey"
    }

    fun open(link: String?) {
        val l = link?.trim()?.takeIf { it.isNotEmpty() } ?: return
        profileTarget(l)?.let { pendingProfile = it; return }
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
