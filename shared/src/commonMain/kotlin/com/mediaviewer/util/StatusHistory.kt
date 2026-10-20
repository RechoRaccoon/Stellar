package com.mediaviewer.util

import androidx.compose.runtime.mutableStateListOf
import com.mediaviewer.json.StellarJson
import com.mediaviewer.platform.PlatformContext
import com.mediaviewer.platform.SharedPreferences
import com.mediaviewer.platform.ioDispatcher
import com.mediaviewer.platform.sharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

/**
 * Every status you've posted, newest first (the status box's history).
 * Kept on this device and carried to your other devices by Private Sync,
 * encrypted, so only you can read it.
 *
 * Earlier versions saved each status as a public record in [COLLECTION].
 * Those are read once more, folded into the history, and removed from the
 * PDS as soon as Private Sync has a copy ([retirePublic]).
 */
object StatusHistory {
    const val COLLECTION = "com.rechoraccoon.stellar.statusHistory"

    @Serializable
    data class Entry(
        val text: String,
        val createdAt: String,
        val expiresAt: String? = null,
        /** Its record's key on the PDS; null until it's been saved there. */
        val rkey: String? = null,
        /** Custom emoji in [text] (see ProfileStatus.emoji). */
        val emoji: List<StatusEmoji> = emptyList()
    ) {
        /** The same status, whether or not it has been saved yet. */
        fun sameAs(other: Entry) = text == other.text && createdAt == other.createdAt
    }

    /** The PDS side. Set by the ViewModel (null while signed out). */
    interface Remote {
        suspend fun list(did: String): List<Entry>
        suspend fun add(did: String, entry: Entry): String
        suspend fun delete(did: String, rkey: String)
    }
    var remote: Remote? = null

    private var prefs: SharedPreferences? = null
    private var loadedDid: String? = null
    private var syncedDid: String? = null
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    /** The signed-in account's history, newest first (Compose state). */
    val entries = mutableStateListOf<Entry>()

    fun init(context: PlatformContext) {
        if (prefs == null) prefs = context.sharedPreferences("status_history")
    }

    private fun key(did: String) = "entries_$did"
    private fun deletesKey(did: String) = "pending_deletes_$did"

    /** Loads [did]'s history into [entries] (once per account), then brings
     *  it up to date with the PDS (once per session). */
    fun load(did: String) {
        if (did.isBlank()) return
        val p = prefs ?: return
        if (did != loadedDid) {
            loadedDid = did
            entries.clear()
            p.getString(key(did), null)?.let { raw ->
                runCatching { StellarJson.default.decodeFromString(ListSerializer(Entry.serializer()), raw) }
                    .getOrNull()?.let { entries.addAll(it) }
            }
        }
        sync(did)
    }

    /** Folds any old public history records into this device's history
     *  (once per session), and retries deletes that didn't go through. */
    private fun sync(did: String) {
        if (syncedDid == did) return
        val r = remote ?: return
        syncedDid = did
        scope.launch {
            val ok = runCatching {
                pendingDeletes(did).forEach { rkey -> r.delete(did, rkey); forgetPendingDelete(did, rkey) }
                val fromPds = r.list(did)
                if (fromPds.isNotEmpty()) withContext(Dispatchers.Main) { foldIn(did, fromPds) }
            }.isSuccess
            if (!ok) withContext(Dispatchers.Main) { if (syncedDid == did) syncedDid = null }
        }
    }

    private fun foldIn(did: String, fromPds: List<Entry>) {
        if (loadedDid != did) return
        val merged = entries.toMutableList()
        for (e in fromPds) {
            val i = merged.indexOfFirst { it.sameAs(e) }
            if (i >= 0) merged[i] = merged[i].copy(rkey = e.rkey) else merged += e
        }
        entries.clear()
        entries.addAll(merged.sortedByDescending { it.createdAt })
        save(did)
    }

    private var retiredDid: String? = null

    /** Private Sync has just saved [did]'s history (encrypted): the old
     *  public copies of it can go. Once per session. */
    suspend fun retirePublic(did: String) {
        if (retiredDid == did) return
        val r = remote ?: return
        val saved = withContext(Dispatchers.Main) { if (loadedDid == did) entries.toList() else null } ?: return
        val onPds = r.list(did)
        retiredDid = did
        val (kept, missing) = onPds.partition { p -> saved.any { it.sameAs(p) } }
        // (One that somehow isn't here yet is folded in first and removed
        // after the next sync has it.)
        if (missing.isNotEmpty()) { withContext(Dispatchers.Main) { foldIn(did, missing) }; retiredDid = null }
        for (e in kept) {
            val rkey = e.rkey ?: continue
            if (!PrivateSync.WriteBudget.take(1)) { retiredDid = null; break }
            runCatching { r.delete(did, rkey) }.onSuccess {
                withContext(Dispatchers.Main) {
                    val i = entries.indexOfFirst { it.sameAs(e) }
                    if (i >= 0 && loadedDid == did) { entries[i] = entries[i].copy(rkey = null); save(did) }
                }
            }
        }
    }

    /** Private Sync wrote a new history: show it. */
    fun reload(did: String) {
        if (loadedDid != did) return
        loadedDid = null
        load(did)
    }

    fun add(did: String, status: ProfileStatus) {
        if (did.isBlank() || status.text.isBlank()) return
        load(did)
        entries.add(0, Entry(status.text, status.createdAt, status.expiresAt, emoji = status.emoji))
        save(did)
    }

    fun delete(did: String, entry: Entry) {
        load(did)
        // (Not saved yet: when its upload finishes, upload() deletes it.)
        val rkey = entry.rkey ?: entries.firstOrNull { it.sameAs(entry) }?.rkey
        if (entries.removeAll { it.sameAs(entry) }) save(did)
        if (rkey == null) return
        addPendingDelete(did, rkey)
        scope.launch {
            val r = remote ?: return@launch
            runCatching { r.delete(did, rkey) }.onSuccess { forgetPendingDelete(did, rkey) }
        }
    }

    private fun save(did: String) {
        val p = prefs ?: return
        p.edit().putString(key(did), StellarJson.default.encodeToString(ListSerializer(Entry.serializer()), entries.toList())).apply()
    }

    private fun pendingDeletes(did: String): List<String> =
        prefs?.getString(deletesKey(did), null)?.let { raw ->
            runCatching { StellarJson.default.decodeFromString(ListSerializer(String.serializer()), raw) }.getOrNull()
        }.orEmpty()

    private fun writePendingDeletes(did: String, list: List<String>) {
        prefs?.edit()?.putString(deletesKey(did), StellarJson.default.encodeToString(ListSerializer(String.serializer()), list))?.apply()
    }

    private fun addPendingDelete(did: String, rkey: String) = com.mediaviewer.platform.synchronizedCompat(this) {
        writePendingDeletes(did, (pendingDeletes(did) + rkey).distinct())
    }

    private fun forgetPendingDelete(did: String, rkey: String) = com.mediaviewer.platform.synchronizedCompat(this) {
        writePendingDeletes(did, pendingDeletes(did) - rkey)
    }
}
