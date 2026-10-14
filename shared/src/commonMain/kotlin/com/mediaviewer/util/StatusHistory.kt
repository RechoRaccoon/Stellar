package com.mediaviewer.util

import androidx.compose.runtime.mutableStateListOf
import com.mediaviewer.json.StellarJson
import com.mediaviewer.platform.PlatformContext
import com.mediaviewer.platform.SharedPreferences
import com.mediaviewer.platform.sharedPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * Every status you've posted, newest first, kept on this device only (the
 * "Statuses" tab on the Archived page). A status goes in the moment it's
 * posted. Saved per account, and included in Export App Data (the
 * "status_history" preferences).
 */
object StatusHistory {
    @Serializable
    data class Entry(val text: String, val createdAt: String, val expiresAt: String? = null)

    private var prefs: SharedPreferences? = null
    private var loadedDid: String? = null
    /** The signed-in account's history, newest first (Compose state). */
    val entries = mutableStateListOf<Entry>()

    fun init(context: PlatformContext) {
        if (prefs == null) prefs = context.sharedPreferences("status_history")
    }

    private fun key(did: String) = "entries_$did"

    /** Loads [did]'s history into [entries] (once per account). */
    fun load(did: String) {
        if (did.isBlank() || did == loadedDid) return
        val p = prefs ?: return
        loadedDid = did
        entries.clear()
        p.getString(key(did), null)?.let { raw ->
            runCatching { StellarJson.default.decodeFromString(ListSerializer(Entry.serializer()), raw) }
                .getOrNull()?.let { entries.addAll(it) }
        }
    }

    fun add(did: String, status: ProfileStatus) {
        if (did.isBlank() || status.text.isBlank()) return
        load(did)
        entries.add(0, Entry(status.text, status.createdAt, status.expiresAt))
        save(did)
    }

    fun delete(did: String, entry: Entry) {
        load(did)
        if (entries.remove(entry)) save(did)
    }

    private fun save(did: String) {
        val p = prefs ?: return
        p.edit().putString(key(did), StellarJson.default.encodeToString(ListSerializer(Entry.serializer()), entries.toList())).apply()
    }
}
