package com.mediaviewer.util

import com.mediaviewer.platform.PlatformContext
import com.mediaviewer.platform.SharedPreferences
import com.mediaviewer.platform.sharedPreferences

/**
 * Which server each signed-in account talks to. Accounts hosted by Bluesky
 * sign in through bsky.social (as always); an account on its own PDS (any
 * other AT Protocol host) signs in at that PDS, and it's remembered here by
 * DID so the app, a restart, account switching and the background
 * notification check all go to the right place.
 */
object BskyServices {
    const val DEFAULT = "https://bsky.social/"
    private var prefs: SharedPreferences? = null

    fun init(context: PlatformContext) {
        if (prefs == null) prefs = context.sharedPreferences("bsky_services")
    }

    fun urlFor(did: String?): String =
        if (did.isNullOrBlank()) DEFAULT else prefs?.getString(did, null)?.takeIf { it.startsWith("https://") } ?: DEFAULT

    fun set(did: String, url: String) {
        if (did.isBlank()) return
        val p = prefs ?: return
        if (url == DEFAULT) p.edit().remove(did).apply() else p.edit().putString(did, url).apply()
    }
}
