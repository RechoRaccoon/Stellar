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
 * Saved on your own PDS — one record per status in [COLLECTION], so it's
 * on every device you sign in on, and public like the status itself — with
 * a copy on this device so it shows at once. A status goes in the moment
 * it's posted; deleting one deletes its record (tried again later if the
 * PDS can't be reached).
 */
object StatusHistory {
    const val COLLECTION = "com.rechoraccoon.stellar.statusHistory"

    @Serializable
    data class Entry(
        val text: String,
        val createdAt: String,
        val expiresAt: String? = null,
        /** Its record's key on the PDS; null until it's been saved there. */
        val rkey: String? = null
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

    /** The PDS's list wins: statuses deleted elsewhere go, ones posted
     *  elsewhere come in; any posted here that never reached it are sent,
     *  and deletes that didn't go through are tried again. */
    private fun sync(did: String) {
        if (syncedDid == did) return
        val r = remote ?: return
        syncedDid = did
        scope.launch {
            val ok = runCatching {
                pendingDeletes(did).forEach { rkey -> r.delete(did, rkey); forgetPendingDelete(did, rkey) }
                val fromPds = r.list(did)
                val unsent = withContext(Dispatchers.Main) {
                    if (loadedDid != did) return@withContext emptyList()
                    val local = entries.filter { it.rkey == null }
                    entries.clear()
                    entries.addAll((fromPds + local).sortedByDescending { it.createdAt })
                    save(did)
                    local
                }
                unsent.forEach { upload(did, it) }
            }.isSuccess
            if (!ok) withContext(Dispatchers.Main) { if (syncedDid == did) syncedDid = null }
        }
    }

    fun add(did: String, status: ProfileStatus) {
        if (did.isBlank() || status.text.isBlank()) return
        load(did)
        val entry = Entry(status.text, status.createdAt, status.expiresAt)
        entries.add(0, entry)
        save(did)
        scope.launch { runCatching { upload(did, entry) } }
    }

    /** Saves [entry] to the PDS and keeps its record key. Deleted here in
     *  the meantime: its new record is deleted too. */
    private suspend fun upload(did: String, entry: Entry) {
        val r = remote ?: return
        val rkey = r.add(did, entry)
        val stillHere = withContext(Dispatchers.Main) {
            val i = entries.indexOfFirst { it.sameAs(entry) }
            if (i >= 0 && loadedDid == did) { entries[i] = entries[i].copy(rkey = rkey); save(did); true } else false
        }
        if (!stillHere) runCatching { r.delete(did, rkey) }.onFailure { addPendingDelete(did, rkey) }
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
