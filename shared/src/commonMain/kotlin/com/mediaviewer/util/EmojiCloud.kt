package com.mediaviewer.util

import com.mediaviewer.json.StellarJson
import com.mediaviewer.platform.PlatformContext
import com.mediaviewer.platform.SharedPreferences
import com.mediaviewer.platform.ioDispatcher
import com.mediaviewer.platform.sharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * Custom emoji on the person's own PDS, so they follow them to every device
 * and can be shown to whoever they message.
 *
 * - The library: each emoji is a [COLLECTION] record (its name, folder, a
 *   hash of the picture, and the picture as a blob). [sync] uploads emoji
 *   added on this device, downloads ones added on another, and deletes the
 *   record of one deleted here (an emoji that was here at the last sync and
 *   isn't any more).
 * - In a DM: each emoji is sent as ":name:" plus a facet of type
 *   [FACET_TYPE] over that text, pointing at the sender's record and blob.
 *   Stellar draws the picture there ([emojiSpans]); other apps, which don't
 *   know the facet, show the ":name:" text.
 */
object EmojiCloud {
    const val COLLECTION = "com.rechoraccoon.stellar.emoji"
    const val FACET_TYPE = "com.rechoraccoon.stellar.richtext.facet#emoji"

    /** One emoji record on a PDS. */
    data class RemoteEmoji(val rkey: String, val name: String, val cid: String, val hash: String, val folder: String?)

    @Serializable
    private data class Uploaded(val rkey: String, val cid: String)

    /** The PDS side. Set by the ViewModel. */
    interface Remote {
        suspend fun list(did: String): List<RemoteEmoji>
        /** Returns (record key, blob cid). */
        suspend fun create(did: String, name: String, png: ByteArray, hash: String, folder: String?): Pair<String, String>
        suspend fun delete(did: String, rkey: String)
        suspend fun fetch(did: String, cid: String): ByteArray
    }
    var remote: Remote? = null

    private var prefs: SharedPreferences? = null
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val mutex = Mutex()
    private var syncedThisSession: String? = null
    /** Picture hashes by stored file name (and its size). */
    private val hashCache = HashMap<String, String>()

    fun init(context: PlatformContext) {
        if (prefs == null) prefs = context.sharedPreferences("emoji_cloud")
    }

    /** FNV-1a (64-bit) of the picture: the same picture, the same hash, on
     *  every device. */
    fun hashOf(png: ByteArray): String {
        var h = -0x340d631b7bdddcdbL
        for (b in png) { h = h xor (b.toLong() and 0xFF); h *= 0x100000001b3L }
        return h.toULong().toString(16).padStart(16, '0')
    }

    private fun hashFor(store: EmojiStore, entry: EmojiEntry): String? {
        val png = store.pngBytes(entry) ?: return null
        val key = entry.file + ":" + png.size
        return hashCache.getOrPut(key) { hashOf(png) }
    }

    private fun uploaded(did: String): MutableMap<String, Uploaded> =
        prefs?.getString("uploaded_$did", null)?.let { raw ->
            runCatching { StellarJson.default.decodeFromString(MapSerializer(String.serializer(), Uploaded.serializer()), raw) }.getOrNull()
        }?.toMutableMap() ?: mutableMapOf()

    private fun saveUploaded(did: String, map: Map<String, Uploaded>) {
        prefs?.edit()?.putString("uploaded_$did", StellarJson.default.encodeToString(MapSerializer(String.serializer(), Uploaded.serializer()), map))?.apply()
    }

    private fun known(did: String): Set<String> =
        prefs?.getString("known_$did", null)?.let { raw ->
            runCatching { StellarJson.default.decodeFromString(ListSerializer(String.serializer()), raw) }.getOrNull()
        }?.toSet() ?: emptySet()

    private fun saveKnown(did: String, hashes: Set<String>) {
        prefs?.edit()?.putString("known_$did", StellarJson.default.encodeToString(ListSerializer(String.serializer()), hashes.toList()))?.apply()
    }

    /** [sync] in the background, once a session (or again with [force],
     *  after the library changed). */
    fun requestSync(store: EmojiStore, did: String, force: Boolean = false) {
        if (did.isBlank() || remote == null) return
        if (!force && syncedThisSession == did) return
        syncedThisSession = did
        scope.launch {
            runCatching { sync(store, did) }.onFailure { if (syncedThisSession == did) syncedThisSession = null }
        }
    }

    /** Brings this device's library and [did]'s PDS together (see the class doc). */
    suspend fun sync(store: EmojiStore, did: String) = mutex.withLock {
        val r = remote ?: return@withLock
        store.load()
        val records = r.list(did)
        val byHash = records.filter { it.hash.isNotBlank() }.groupBy { it.hash }
        val localByHash = LinkedHashMap<String, EmojiEntry>()
        for (e in store.state.index.emojis) hashFor(store, e)?.let { h -> if (h !in localByHash) localByHash[h] = e }
        val before = known(did)
        val up = uploaded(did)

        // On the PDS but not here: deleted here since the last sync → its
        // record goes; otherwise it was added on another device → it comes in.
        for ((hash, recs) in byHash) {
            if (hash in localByHash) continue
            if (hash in before) {
                recs.forEach { runCatching { r.delete(did, it.rkey) } }
                up.remove(hash)
            } else {
                val rec = recs.first()
                val png = runCatching { r.fetch(did, rec.cid) }.getOrNull() ?: continue
                val added = store.addPng(rec.name, png, rec.folder) ?: continue
                hashFor(store, added)?.let { localByHash[it] = added }
                up[hash] = Uploaded(rec.rkey, rec.cid)
            }
        }
        // Duplicate records of one picture: one is kept.
        for ((hash, recs) in byHash) {
            val keep = up[hash]?.rkey ?: recs.first().rkey
            recs.filter { it.rkey != keep }.forEach { runCatching { r.delete(did, it.rkey) } }
            if (hash in localByHash && up[hash] == null) recs.firstOrNull { it.rkey == keep }?.let { up[hash] = Uploaded(it.rkey, it.cid) }
        }
        // Here but not on the PDS: uploaded.
        val folders = store.state.index.folders
        for ((hash, entry) in localByHash) {
            if (byHash.containsKey(hash) && up[hash] != null) continue
            val png = store.pngBytes(entry) ?: continue
            val folder = folders.firstOrNull { entry.id in it.emojiIds }?.name
            val (rkey, cid) = runCatching { r.create(did, entry.name, png, hash, folder) }.getOrNull() ?: continue
            up[hash] = Uploaded(rkey, cid)
        }
        saveUploaded(did, up.filterKeys { it in localByHash })
        saveKnown(did, localByHash.keys)
    }

    /** [entry]'s record on [did]'s PDS, uploaded now if it isn't yet. */
    private suspend fun ensureUploaded(store: EmojiStore, did: String, entry: EmojiEntry): Uploaded? {
        val hash = hashFor(store, entry) ?: return null
        uploaded(did)[hash]?.let { return it }
        val r = remote ?: return null
        val png = store.pngBytes(entry) ?: return null
        val folder = store.state.index.folders.firstOrNull { entry.id in it.emojiIds }?.name
        val (rkey, cid) = runCatching { r.create(did, entry.name, png, hash, folder) }.getOrNull() ?: return null
        val up = uploaded(did)
        up[hash] = Uploaded(rkey, cid)
        saveUploaded(did, up)
        saveKnown(did, known(did) + hash)
        return Uploaded(rkey, cid)
    }

    /**
     * A DM's text from the field: every emoji (one private-use character)
     * becomes ":name:", with a [FACET_TYPE] facet over it pointing at the
     * emoji's record on [did]'s PDS (uploaded first if needed). An emoji
     * that can't be uploaded is sent as just its ":name:".
     */
    suspend fun prepareMessage(store: EmojiStore, did: String, text: String): Pair<String, List<Map<String, Any>>> {
        if (!store.containsEmoji(text)) return text to emptyList()
        val out = StringBuilder()
        val facets = ArrayList<Map<String, Any>>()
        var bytes = 0
        // Plain text since the last emoji, measured in UTF-8 as a whole (so
        // characters outside the BMP count right; emoji tokens are never
        // surrogates).
        val plain = StringBuilder()
        fun flush() { bytes += plain.toString().encodeToByteArray().size; out.append(plain); plain.clear() }
        for (c in text) {
            val entry = store.entryFor(c)
            if (entry == null) { plain.append(c); continue }
            flush()
            val code = ":${entry.name}:"
            val start = bytes
            out.append(code)
            bytes += code.encodeToByteArray().size
            val up = ensureUploaded(store, did, entry) ?: continue
            facets += mapOf(
                "index" to mapOf("byteStart" to start, "byteEnd" to bytes),
                "features" to listOf(mapOf(
                    "\$type" to FACET_TYPE,
                    "did" to did, "rkey" to up.rkey, "cid" to up.cid, "name" to entry.name
                ))
            )
        }
        flush()
        return out.toString() to facets
    }

    /** One emoji in a received message: its place (UTF-16 indices into the
     *  text) and where its picture is. */
    data class EmojiSpan(val start: Int, val end: Int, val did: String, val cid: String, val name: String)

    /** True when [text] is nothing but [spans] (and spaces). */
    fun isOnlyEmoji(text: String, spans: List<EmojiSpan>): Boolean {
        if (spans.isEmpty()) return false
        var rest = text
        spans.sortedByDescending { it.start }.forEach { rest = rest.removeRange(it.start, it.end) }
        return rest.isBlank()
    }

    /** The emoji in a message, from its facets (none for other apps' messages). */
    fun emojiSpans(text: String, facets: List<JsonElement>?): List<EmojiSpan> {
        if (facets.isNullOrEmpty()) return emptyList()
        // UTF-8 byte offset → UTF-16 index.
        val byteToChar = HashMap<Int, Int>()
        var b = 0
        var i = 0
        while (i < text.length) {
            byteToChar[b] = i
            val cp = if (text[i].isHighSurrogate() && i + 1 < text.length) 2 else 1
            b += text.substring(i, i + cp).encodeToByteArray().size
            i += cp
        }
        byteToChar[b] = text.length
        return facets.mapNotNull { f ->
            val o = f as? JsonObject ?: return@mapNotNull null
            val idx = o["index"] as? JsonObject ?: return@mapNotNull null
            val feature = (o["features"] as? JsonArray)?.firstOrNull { (it as? JsonObject)?.get("\$type")?.let { t -> (t as? JsonPrimitive)?.content } == FACET_TYPE } as? JsonObject
                ?: return@mapNotNull null
            fun str(k: String) = (feature[k] as? JsonPrimitive)?.content.orEmpty()
            val start = byteToChar[(idx["byteStart"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null] ?: return@mapNotNull null
            val end = byteToChar[(idx["byteEnd"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null] ?: return@mapNotNull null
            if (end <= start || str("did").isBlank() || str("cid").isBlank()) return@mapNotNull null
            EmojiSpan(start, end, str("did"), str("cid"), str("name"))
        }.sortedBy { it.start }.fold(ArrayList()) { acc, s -> if (acc.isEmpty() || s.start >= acc.last().end) acc.add(s); acc }
    }
}
