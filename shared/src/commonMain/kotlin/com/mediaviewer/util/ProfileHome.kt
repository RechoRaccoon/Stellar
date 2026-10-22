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
 * A profile's Home tab: rows of Feed Builder feeds the account picked to
 * show first on their profile. Unlike Hub feeds (this device's encrypted
 * data) these are meant to be seen, so each one is a plain [COLLECTION]
 * record on the account's own PDS: the feed's spec as JSON, plus where it
 * sits in the tab. Only public folders can be a source (see
 * FeedBuilderPopup's onlyPublicFolders), so nothing private ends up here.
 */
object ProfileHome {
    const val COLLECTION = "com.rechoraccoon.stellar.home"

    /** The PDS side (set by the ViewModel; null while signed out). */
    interface Remote {
        val did: String
        suspend fun put(rkey: String, record: Map<String, Any>)
        suspend fun delete(rkey: String)
    }
    var remote: Remote? = null

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val pending = HashMap<String, Job>()

    /** Read this session, by account, in the order they're shown (Compose
     *  state: the tab fills in once they arrive). */
    val byAccount = mutableStateMapOf<String, List<LocalFeed>>()

    /** Saves [feed] into your own Home tab (a new one goes last) and
     *  returns it with its id. */
    fun save(feed: LocalFeed): LocalFeed {
        val r = remote ?: return feed
        val saved = if (feed.id.isNotBlank()) feed else feed.copy(id = "h" + com.mediaviewer.platform.currentTimeMillis().toString(36))
        val mine = byAccount[r.did].orEmpty()
        val i = mine.indexOfFirst { it.id == saved.id }
        val list = if (i >= 0) mine.toMutableList().also { it[i] = saved } else mine + saved
        byAccount[r.did] = list
        write(list.indexOf(saved), saved)
        return saved
    }

    fun delete(id: String) {
        val r = remote ?: return
        byAccount[r.did]?.let { list -> byAccount[r.did] = list.filterNot { it.id == id } }
        later(id) { r.delete(id) }
    }

    /** Moves your feed at [from] to [to] (dragged in Customize). Every row
     *  that changed place gets its new position (each write waits a moment
     *  and only the last of a quick run of moves is sent). */
    fun moveTo(from: Int, to: Int) {
        val r = remote ?: return
        val list = byAccount[r.did].orEmpty().toMutableList()
        if (from !in list.indices || to !in list.indices || from == to) return
        list.add(to, list.removeAt(from))
        byAccount[r.did] = list
        for (i in minOf(from, to)..maxOf(from, to)) write(i, list[i])
    }

    /** How a Home tab's row names look (Home Settings; see RowHeaderStyle):
     *  [align] 0 left, 1 center, 2 right; [divider] small between lines;
     *  [lines] beside a big name. Public like the rows (it's how others see
     *  the tab), kept in one more record, [SETTINGS_RKEY]. */
    data class Header(val align: Int = 0, val divider: Boolean = false, val lines: Boolean = false)
    const val SETTINGS_RKEY = "settings"
    val headers = mutableStateMapOf<String, Header>()

    fun setHeader(header: Header) {
        val r = remote ?: return
        headers[r.did] = header
        val record = linkedMapOf<String, Any>(
            "\$type" to COLLECTION,
            "headerAlign" to header.align,
            "dividerHeaders" to header.divider,
            "headerLines" to header.lines,
            "createdAt" to isoFromMillis(com.mediaviewer.platform.currentTimeMillis())
        )
        later(SETTINGS_RKEY) { r.put(SETTINGS_RKEY, record) }
    }

    /** Moves your feed [id] one place up ([by] = -1) or down (1). */
    fun move(id: String, by: Int) {
        val r = remote ?: return
        val list = byAccount[r.did].orEmpty().toMutableList()
        val from = list.indexOfFirst { it.id == id }
        val to = from + by
        if (from < 0 || to !in list.indices) return
        list.add(to, list.removeAt(from))
        byAccount[r.did] = list
        // (Both rows that swapped places get their new position.)
        write(to, list[to])
        write(from, list[from])
    }

    private fun write(position: Int, feed: LocalFeed) {
        val r = remote ?: return
        val now = isoFromMillis(com.mediaviewer.platform.currentTimeMillis())
        val record = linkedMapOf<String, Any>(
            "\$type" to COLLECTION,
            "name" to feed.name,
            "position" to position,
            "spec" to StellarJson.default.encodeToString(LocalFeed.serializer(), feed),
            "createdAt" to now
        )
        later(feed.id) { r.put(feed.id, record) }
    }

    private fun later(id: String, write: suspend () -> Unit) {
        com.mediaviewer.platform.synchronizedCompat(pending) {
            pending.remove(id)?.cancel()
            pending[id] = scope.launch {
                delay(1500)
                if (PrivateSync.WriteBudget.take(1)) runCatching { write() }
                com.mediaviewer.platform.synchronizedCompat(pending) { pending.remove(id) }
            }
        }
    }

    /** [did]'s Home tab feeds, straight from their own PDS (remembered this
     *  session; [fresh] reads again). */
    suspend fun load(did: String, fresh: Boolean = false): List<LocalFeed> {
        if (!fresh) byAccount[did]?.let { return it }
        val list = runCatching { read(did) }.getOrNull() ?: return byAccount[did].orEmpty()
        byAccount[did] = list
        return list
    }

    private suspend fun read(did: String): List<LocalFeed> {
        val pds = com.mediaviewer.worker.BlueskyBlobResolver.pdsEndpoint(did)
        val out = ArrayList<Pair<Int, LocalFeed>>()
        val resp = com.mediaviewer.network.PlainHttp.get(
            "$pds/xrpc/com.atproto.repo.listRecords",
            listOf("repo" to did, "collection" to COLLECTION, "limit" to "50")
        )
        if (resp.code !in 200..299) error("Home ${resp.code}")
        val root = StellarJson.default.parseToJsonElement(resp.body.decodeToString()) as? JsonObject ?: return out.map { it.second }
        val records = root["records"] as? JsonArray ?: return emptyList()
        for (r in records) {
            val value = (r as? JsonObject)?.get("value") as? JsonObject ?: continue
            if ((r["uri"] as? JsonPrimitive)?.content.orEmpty().endsWith("/$SETTINGS_RKEY")) {
                fun prim(k: String) = value[k] as? JsonPrimitive
                headers[did] = Header(
                    align = prim("headerAlign")?.content?.toIntOrNull()?.coerceIn(0, 2) ?: 0,
                    divider = prim("dividerHeaders")?.content == "true",
                    lines = prim("headerLines")?.content == "true"
                )
                continue
            }
            val spec = (value["spec"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: continue
            val feed = runCatching { StellarJson.default.decodeFromString(LocalFeed.serializer(), spec) }.getOrNull() ?: continue
            val rkey = (r["uri"] as? JsonPrimitive)?.content.orEmpty().substringAfterLast('/')
            val position = (value["position"] as? JsonPrimitive)?.content?.toIntOrNull() ?: Int.MAX_VALUE
            // (Someone else's private folders can't be read anyway; only
            // public ones are kept as sources.)
            out += position to feed.copy(id = rkey.ifBlank { feed.id }, sources = feed.sources.filter { it.kind != "folder" })
        }
        return out.sortedBy { it.first }.map { it.second }
    }
}
