package com.mediaviewer.util

import androidx.compose.runtime.mutableStateMapOf
import com.mediaviewer.json.StellarJson
import com.mediaviewer.platform.ioDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Public bookmark folders: a folder switched to Public (the world icon) is
 * written, unencrypted, as one [COLLECTION] record on your own PDS — its
 * name, its posts' links and its cover when that's a post's picture — so
 * anyone can see it on your profile (Collections → Folders). Private
 * folders (the lock, the default) never leave the encrypted vault.
 *
 * Switching a folder between the two is safe for the vault: it's sealed
 * with XChaCha20-Poly1305 under a random nonce each time it's written, as
 * one blob holding everything, so a folder's plain copy appearing (or
 * going) tells an onlooker nothing about the key, and nothing lines up
 * with any part of the sealed vault.
 */
object PublicFolders {
    const val COLLECTION = "com.rechoraccoon.stellar.folder"

    /** One public folder, as read from someone's PDS. */
    data class Folder(
        val rkey: String, val did: String, val name: String, val posts: List<String>, val cover: String?, val updatedAt: Long,
        /** How it's laid out when opened (see BookmarkFolder.layout). */
        val layout: String = "grid"
    )

    /** The PDS side (set by the ViewModel; null while signed out). */
    interface Remote {
        val did: String
        suspend fun put(rkey: String, record: Map<String, Any>)
        suspend fun delete(rkey: String)
        /** Every public folder record in [did]'s repo (its at:// URI and
         *  value), read the same way the app's other public records are. */
        suspend fun list(did: String): List<Pair<String, JsonObject>>
    }
    var remote: Remote? = null
    /** Tells the person a folder couldn't be published (set by the ViewModel). */
    var onError: ((String) -> Unit)? = null

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val pending = HashMap<String, Job>()

    /** Read this session, by account (Compose state: a profile's Folders
     *  tab fills in as they arrive). */
    val byAccount = mutableStateMapOf<String, List<Folder>>()

    /** Writes [folder]'s public copy (a moment later, so a burst of
     *  changes is one write). */
    fun publish(folder: BookmarkFolder) {
        val r = remote ?: return
        val record = linkedMapOf<String, Any>(
            "\$type" to COLLECTION,
            "name" to folder.name,
            "posts" to folder.posts.take(1000),
            "createdAt" to isoFromMillis(folder.createdAt.takeIf { it > 0 } ?: com.mediaviewer.platform.currentTimeMillis()),
            "updatedAt" to isoFromMillis(maxOf(folder.updatedAt, folder.createdAt, com.mediaviewer.platform.currentTimeMillis()))
        )
        // (Only a picture that's already public: never a file from this device.)
        folder.cover?.takeIf { it.startsWith("https://") }?.let { record["cover"] = it }
        if (folder.layout != "grid") record["layout"] = folder.layout
        later(folder.id, "Couldn't publish \"${folder.name}\"") { r.put(folder.id, record) }
        byAccount[r.did] = mine(r.did)
    }

    /** Your own public folders, straight from this device: they show on your
     *  profile (and in your Home feeds) at once, whatever the PDS has yet. */
    private fun mine(did: String): List<Folder> = LocalData.bookmarkFolders.filter { it.public }.map { f ->
        Folder(f.id, did, f.name, f.posts, f.cover?.takeIf { it.startsWith("https://") }, maxOf(f.updatedAt, f.createdAt), f.layout)
    }

    /** Takes [id]'s public copy down (it went private, or was deleted). */
    fun unpublish(id: String) {
        val r = remote ?: return
        later(id, "Couldn't make that folder private on your PDS") { r.delete(id) }
        byAccount[r.did] = mine(r.did).filterNot { it.rkey == id }
    }

    private fun later(id: String, failure: String, write: suspend () -> Unit) {
        com.mediaviewer.platform.synchronizedCompat(pending) {
            pending.remove(id)?.cancel()
            pending[id] = scope.launch {
                delay(1500)
                // (A failed write is tried twice more, then said out loud —
                // it used to be dropped silently, so a folder could stay off
                // your profile with nothing to show why.)
                var last: Throwable? = null
                for (attempt in 0 until 3) {
                    if (!PrivateSync.WriteBudget.take(1)) { last = IllegalStateException("Too many writes this hour"); break }
                    last = runCatching { write() }.exceptionOrNull() ?: break
                    delay(4000L * (attempt + 1))
                }
                last?.let { e -> onError?.invoke("$failure: ${e.message ?: "unknown error"}") }
                com.mediaviewer.platform.synchronizedCompat(pending) { pending.remove(id) }
            }
        }
    }

    /** [did]'s public folders, newest first, straight from their own PDS
     *  (remembered this session; [fresh] reads again). */
    suspend fun load(did: String, fresh: Boolean = false): List<Folder> {
        if (did == remote?.did) return mine(did).also { byAccount[did] = it }
        if (!fresh) byAccount[did]?.let { return it }
        // (Through the app's own API client first — how Home tabs and the
        // rest of the public records are read; straight from the account's
        // PDS when that can't be used. This used to go direct only, and on
        // real devices that came back empty, so other people's folders
        // showed nowhere — not in Collections, not in Home rows — even
        // though the records were sitting on their PDS the whole time.)
        val viaApi = remote?.let { r -> runCatching { r.list(did) } }
        val list = viaApi?.getOrNull()?.map { (uri, value) -> folderOf(uri.substringAfterLast('/'), did, value) }
            ?: runCatching { read(did) }.getOrNull()
            ?: return byAccount[did].orEmpty()
        byAccount[did] = list
        return list
    }

    /** One folder record's value as a [Folder]. */
    @OptIn(kotlin.time.ExperimentalTime::class)
    private fun folderOf(rkey: String, did: String, value: JsonObject): Folder {
        fun text(k: String) = (value[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val posts = (value["posts"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            ?.filter { it.startsWith("at://") }.orEmpty()
        return Folder(
            rkey = rkey,
            did = did, name = text("name").orEmpty().ifBlank { "Folder" }, posts = posts,
            cover = text("cover")?.takeIf { it.startsWith("https://") },
            updatedAt = text("updatedAt")?.let { runCatching { kotlin.time.Instant.parse(it).toEpochMilliseconds() }.getOrNull() } ?: 0L,
            layout = text("layout")?.takeIf { it == "vertical" || it == "horizontal" } ?: "grid"
        )
    }

    @OptIn(kotlin.time.ExperimentalTime::class)
    private suspend fun read(did: String): List<Folder> {
        val pds = com.mediaviewer.worker.BlueskyBlobResolver.pdsEndpoint(did)
        val out = ArrayList<Folder>()
        var cursor: String? = null
        var pages = 0
        do {
            val resp = com.mediaviewer.network.PlainHttp.get(
                "$pds/xrpc/com.atproto.repo.listRecords",
                listOfNotNull("repo" to did, "collection" to COLLECTION, "limit" to "100", cursor?.let { "cursor" to it })
            )
            if (resp.code !in 200..299) error("Folders ${resp.code}")
            val root = StellarJson.default.parseToJsonElement(resp.body.decodeToString()) as? JsonObject ?: break
            val records = root["records"] as? JsonArray ?: break
            for (r in records) {
                val o = r as? JsonObject ?: continue
                val value = o["value"] as? JsonObject ?: continue
                out += folderOf((o["uri"] as? JsonPrimitive)?.content.orEmpty().substringAfterLast('/'), did, value)
            }
            cursor = (root["cursor"] as? JsonPrimitive)?.content?.takeIf { records.isNotEmpty() }
        } while (cursor != null && ++pages < 10)
        return out.sortedByDescending { it.updatedAt }
    }
}
