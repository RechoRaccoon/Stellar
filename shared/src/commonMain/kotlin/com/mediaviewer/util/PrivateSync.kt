package com.mediaviewer.util

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mediaviewer.platform.PlatformContext
import com.mediaviewer.platform.PrivateFiles
import com.mediaviewer.platform.SharedPreferences
import com.mediaviewer.platform.currentTimeMillis
import com.mediaviewer.platform.ioDispatcher
import com.mediaviewer.platform.nowIsoString
import com.mediaviewer.platform.secureRandomBytes
import com.mediaviewer.platform.sharedPreferences
import com.mediaviewer.util.crypto.Sha256
import com.mediaviewer.util.crypto.VaultDecryptException
import com.mediaviewer.util.crypto.XChaCha20Poly1305
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Private Sync: what Stellar keeps only on your devices — drafts, notes,
 * the calendar, bookmark folders, archived posts (with their pictures and
 * videos), the Hub's layout, Blogs/Reviews lists, settings, saved emoji
 * accounts, status history — kept on your own PDS, end-to-end encrypted,
 * so it follows you to every device you sign in on.
 *
 * ## Keys
 * Your **Secret Key** is 160 random bits, shown as 32 characters
 * ("ABCD-…"). It never leaves your devices: the PDS only ever sees
 * ciphertext. From it (and your DID) HKDF-SHA256 derives a key-encryption
 * key, which seals a random 256-bit data key; the sealed data key sits in
 * the vault record, so the Secret Key can be checked (and could be
 * replaced) without re-encrypting everything. Because the Secret Key is
 * random rather than a typed password, no slow password hash is needed:
 * there's nothing to guess. This device keeps the Secret Key in its private
 * app storage (next to the data it protects, which is already there in
 * plain form) so you can see it again in Settings.
 *
 * ## On the PDS: one record
 * Everything is [COLLECTION]/self: the sealed data key, the encrypted
 * bundle (every section, as JSON) and every synced file (a draft's or
 * note's picture, an archived post's photo or video), all as blobs of at
 * most [CHUNK] bytes so even a PDS with the old 5 MB blob limit takes
 * them. The record only lists the blobs (so the PDS keeps them); which
 * blob is which file is inside the encrypted bundle. A sync, however much
 * it carries, is that one record written once (2 points); a file dropped
 * from the list is deleted by the PDS itself, with no write for it.
 * Every piece is XChaCha20-Poly1305 with its own random nonce, bound (as
 * associated data) to your DID, the record, its place and the number of
 * pieces, so nothing can be swapped, reordered or cut short unnoticed.
 *
 * ## Staying under the PDS's limits
 * A write only happens when something changed (checked every
 * [CHECK_MS]), at most once per [MIN_PUSH_GAP_MS], and every write is
 * counted against [WriteBudget]: well under Bluesky's 5,000 points an hour
 * and 35,000 a day, leaving the rest of the app plenty.
 *
 * ## Two devices at once
 * Each sync is pull → three-way merge (against what was last synced) →
 * push with compare-and-swap, so an edit on one device and another edit
 * on a second device both survive; only the same thing changed in two
 * places at once is settled by whichever changed it last.
 */
@OptIn(ExperimentalEncodingApi::class)
object PrivateSync {
    const val COLLECTION = "com.rechoraccoon.stellar.vault"
    private const val RKEY = "self"
    private const val VERSION = 1

    /** Plaintext bytes per encrypted piece (each blob is this + 40). */
    const val CHUNK = 4_000_000
    /** Files larger than this stay on their own device. */
    private const val MAX_FILE = 300L * 1024 * 1024
    private const val CHECK_MS = 20_000L
    private const val PULL_MS = 5 * 60_000L
    private const val MIN_PUSH_GAP_MS = 15_000L
    /** New files uploaded per sync; the rest go on the next one. */
    private const val FILES_PER_SYNC = 40
    /** Blobs the record may list (~130 bytes each keeps it far below the
     *  PDS's size limit for a record: about 2.8 GB of files). */
    private const val MAX_RECORD_BLOBS = 700

    class Rec(val rkey: String, val cid: String, val value: JsonObject)

    /** A compare-and-swap write lost the race: pull and merge again. */
    class SwapFailed : Exception("The vault changed meanwhile")

    /** The PDS side (set by MainViewModel; null while signed out). */
    interface Remote {
        suspend fun get(did: String, collection: String, rkey: String): Rec?
        suspend fun put(did: String, collection: String, rkey: String, record: Map<String, Any>, swap: String?): String
        suspend fun list(did: String, collection: String): List<Rec>
        suspend fun delete(did: String, collection: String, rkey: String)
        suspend fun upload(bytes: ByteArray): Map<String, Any>
        suspend fun blob(did: String, cid: String): ByteArray
    }
    var remote: Remote? = null

    enum class State {
        /** Signed out, or not started yet. */
        OFF,
        /** Looking for the vault. */
        CHECKING,
        /** No vault yet: show [newSecretKey] and make one. */
        NEEDS_SETUP,
        /** There's a vault but this device doesn't have its Secret Key. */
        NEEDS_KEY,
        READY,
        /** Couldn't reach the PDS; tried again later. */
        OFFLINE
    }

    var state by mutableStateOf(State.OFF)
        private set
    var syncing by mutableStateOf(false)
        private set
    /** When this device last finished a sync (ms), 0 = never. */
    var lastSynced by mutableStateOf(0L)
        private set
    var lastError by mutableStateOf<String?>(null)
        private set
    /** The Secret Key shown while setting up (formatted). */
    var newSecretKey by mutableStateOf("")
        private set
    /** True when the vault was re-keyed on another device (Start Over). */
    var keyChangedElsewhere by mutableStateOf(false)
        private set

    private var prefs: SharedPreferences? = null
    private var context: PlatformContext? = null
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val lock = Mutex()
    private var did = ""
    private var dek: ByteArray? = null
    private var loop: Job? = null
    private var lastPush = 0L
    private var lastPull = 0L
    private var pendingSecret: ByteArray? = null

    fun init(context: PlatformContext) {
        if (prefs != null) return
        this.context = context
        prefs = context.sharedPreferences("private_sync")
        WriteBudget.init(prefs!!)
    }

    private fun k(name: String) = "$name@$did"

    // ── Secret Key ─────────────────────────────────────────────────────

    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ" // Crockford base32

    fun formatSecret(bytes: ByteArray): String {
        val sb = StringBuilder()
        var buffer = 0
        var bits = 0
        for (b in bytes) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                sb.append(ALPHABET[(buffer ushr (bits - 5)) and 31])
                bits -= 5
            }
        }
        if (bits > 0) sb.append(ALPHABET[(buffer shl (5 - bits)) and 31])
        return sb.chunked(4).joinToString("-")
    }

    /** The 20 bytes of a typed Secret Key; forgiving about case, spaces,
     *  dashes and the look-alikes O/0 and I/L/1. Null if it can't be one. */
    fun parseSecret(text: String): ByteArray? {
        val clean = text.uppercase().filter { it.isLetterOrDigit() }
            .map { when (it) { 'O' -> '0'; 'I', 'L' -> '1'; else -> it } }
        if (clean.size != 32) return null
        val out = ByteArray(20)
        var buffer = 0L
        var bits = 0
        var n = 0
        for (c in clean) {
            val v = ALPHABET.indexOf(c)
            if (v < 0) return null
            buffer = (buffer shl 5) or v.toLong()
            bits += 5
            if (bits >= 8) {
                out[n++] = (buffer ushr (bits - 8)).toByte()
                bits -= 8
            }
        }
        return out
    }

    /** This device's copy of the signed-in account's Secret Key, if any. */
    fun savedSecretKey(): String? = prefs?.getString(k("secret"), null)?.let { parseSecret(it) }?.let { formatSecret(it) }

    private fun kek(secret: ByteArray, did: String) =
        Sha256.hkdf(secret, "stellar-private-sync-v1".encodeToByteArray(), "kek|$did".encodeToByteArray(), 32)

    // ── Lifecycle ──────────────────────────────────────────────────────

    /** The account [account] is signed in (at launch, after signing in,
     *  after switching): find its vault and keep it in sync. */
    fun start(account: String) {
        if (account.isBlank()) { stop(); return }
        if (account == did && state != State.OFF && state != State.OFFLINE) return
        stop()
        did = account
        state = State.CHECKING
        lastSynced = prefs?.getLong(k("synced_at"), 0L) ?: 0L
        scope.launch { open() }
    }

    fun stop() {
        loop?.cancel(); loop = null
        dek = null; pendingSecret = null; newSecretKey = ""
        keyChangedElsewhere = false
        state = State.OFF
        did = ""
    }

    private suspend fun open() {
        val account = did
        val r = remote ?: run { state = State.OFF; return }
        val rec = try {
            r.get(account, COLLECTION, RKEY)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            lastError = e.message
            if (did == account) { state = State.OFFLINE; retryLater() }
            return
        }
        if (did != account) return
        val saved = prefs?.getString(k("secret"), null)?.let { parseSecret(it) }
        if (rec == null) {
            if (saved != null && prefs?.getString(k("cid"), null) != null) {
                // It was here and is gone (deleted from the PDS): put it
                // back from this device, under the same key.
                val key = secureRandomBytes(32)
                dek = key
                setupPush(account, saved, key, swap = null)
                return
            }
            val fresh = secureRandomBytes(20)
            pendingSecret = fresh
            newSecretKey = formatSecret(fresh)
            state = State.NEEDS_SETUP
            return
        }
        val key = saved?.let { unwrap(rec.value, it, account) }
        if (key == null) {
            keyChangedElsewhere = saved != null
            state = State.NEEDS_KEY
            return
        }
        dek = key
        state = State.READY
        runLoop()
    }

    private fun retryLater() {
        loop?.cancel()
        val account = did
        loop = scope.launch {
            delay(60_000)
            if (did == account && state == State.OFFLINE) { state = State.CHECKING; open() }
        }
    }

    /** NEEDS_SETUP: makes the vault with [newSecretKey] and this device's data. */
    suspend fun createVault(): String? {
        val account = did
        val secret = pendingSecret ?: return "Nothing to set up"
        return setupPush(account, secret, secureRandomBytes(32), swap = null)
    }

    /** NEEDS_KEY: checks a typed Secret Key; null when it opened the vault. */
    suspend fun unlock(typed: String): String? {
        val account = did
        val secret = parseSecret(typed) ?: return "A Secret Key is 32 letters and numbers"
        val r = remote ?: return "Not signed in"
        val rec = try { r.get(account, COLLECTION, RKEY) } catch (e: Exception) { return e.message ?: "Couldn't reach your PDS" }
            ?: return "Your Private Sync is gone; close this and it will be set up again"
        val key = unwrap(rec.value, secret, account) ?: return "That Secret Key doesn't open your Private Sync"
        if (did != account) return null
        prefs?.edit()?.putString(k("secret"), formatSecret(secret))?.commit()
        // (A first sync on this device: nothing synced before, so the merge
        // keeps everything from both sides.)
        prefs?.edit()?.remove(k("base"))?.remove(k("cid"))?.commit()
        dek = key
        keyChangedElsewhere = false
        state = State.READY
        runLoop()
        return null
    }

    /** Lost Secret Key: a new key and a new vault, made from this device's
     *  data. What only the old vault had can't be read anymore and is
     *  removed. */
    suspend fun startOver(): String? {
        val account = did
        val secret = secureRandomBytes(20)
        newSecretKey = formatSecret(secret)
        prefs?.edit()?.remove(k("base"))?.remove(k("cid"))?.commit()
        // (The new record replaces the old one; its blobs, unlisted, are
        // removed by the PDS.)
        val current = runCatching { remote?.get(account, COLLECTION, RKEY) }.getOrNull()
        return setupPush(account, secret, secureRandomBytes(32), swap = current?.cid)
    }

    private suspend fun setupPush(account: String, secret: ByteArray, key: ByteArray, swap: String?): String? {
        if (remote == null) return "Not signed in"
        return lock.withLock {
            try {
                val ctx = context ?: return@withLock "Not ready"
                val local = Sections.collect(ctx, account)
                val bundle = withFiles(ctx, key, local, null)
                if (!WriteBudget.take(2)) return@withLock "Try again in a little while"
                val cid = writeVault(account, key, kek(secret, account), bundle, swap)
                if (did != account) return@withLock null
                prefs?.edit()
                    ?.putString(k("secret"), formatSecret(secret))
                    ?.putString(k("base"), bundle.toString())
                    ?.putString(k("cid"), cid)
                    ?.commit()
                dek = key
                pendingSecret = null
                keyChangedElsewhere = false
                markSynced()
                state = State.READY
                runLoop()
                null
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                lastError = e.message
                e.message ?: "Couldn't reach your PDS"
            }
        }
    }

    /** Settings → Private Sync → Sync Now. */
    fun syncNow() {
        if (state == State.OFFLINE) { state = State.CHECKING; scope.launch { open() }; return }
        if (state != State.READY) return
        scope.launch { sync(force = true) }
    }

    private fun runLoop() {
        loop?.cancel()
        val account = did
        loop = scope.launch {
            sync(force = true)
            while (isActive && did == account && state == State.READY) {
                delay(CHECK_MS)
                val now = currentTimeMillis()
                val due = now - lastPull > PULL_MS
                if (due || (now - lastPush > MIN_PUSH_GAP_MS && changedLocally())) sync(force = due)
            }
        }
    }

    private suspend fun changedLocally(): Boolean {
        val ctx = context ?: return false
        val base = prefs?.getString(k("base"), null)?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
            ?: return true
        val local = runCatching { Sections.collect(ctx, did) }.getOrNull() ?: return false
        return sectionsOf(base) != local
    }

    private fun markSynced() {
        lastSynced = currentTimeMillis()
        prefs?.edit()?.putLong(k("synced_at"), lastSynced)?.apply()
        lastError = null
    }

    // ── One sync ───────────────────────────────────────────────────────

    private suspend fun sync(force: Boolean) {
        val account = did
        val key = dek ?: return
        val r = remote ?: return
        val ctx = context ?: return
        lock.withLock {
            if (did != account) return
            syncing = true
            try {
                repeat(3) { attempt ->
                    lastPull = currentTimeMillis()
                    val rec = r.get(account, COLLECTION, RKEY)
                    val baseRaw = prefs?.getString(k("base"), null)
                    val base = baseRaw?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
                    val knownCid = prefs?.getString(k("cid"), null)
                    val remoteBundle: JsonObject? = when {
                        rec == null -> null
                        rec.cid == knownCid && base != null -> base
                        else -> {
                            // Re-keyed elsewhere (Start Over): this key no longer opens it.
                            val keyNow = unwrap(rec.value, prefs?.getString(k("secret"), null)?.let { parseSecret(it) } ?: ByteArray(0), account)
                            if (keyNow == null || !keyNow.contentEquals(key)) {
                                withContext(Dispatchers.Main) {
                                    dek = null; keyChangedElsewhere = true; state = State.NEEDS_KEY
                                }
                                prefs?.edit()?.remove(k("secret"))?.commit()
                                return
                            }
                            readVault(account, key, rec)
                        }
                    }
                    val local = Sections.collect(ctx, account)
                    val merged = merge(base, local, remoteBundle)
                    // This device takes in what changed elsewhere.
                    val changedHere = sectionsOf(merged).filter { (name, v) -> local[name] != v }
                    if (changedHere.isNotEmpty()) withContext(Dispatchers.Main) {
                        if (did == account) Sections.apply(ctx, account, changedHere)
                    }
                    fetchMissingFiles(ctx, account, key, merged)
                    val final = withFiles(ctx, key, sectionsOf(merged), merged["files"] as? JsonObject)
                    val unchanged = remoteBundle != null && final == remoteBundle
                    if (unchanged) {
                        prefs?.edit()?.putString(k("base"), final.toString())?.putString(k("cid"), rec!!.cid)?.commit()
                        markSynced()
                        return
                    }
                    if (!WriteBudget.take(2)) {
                        // (Over budget: what's here stays here until the next try.)
                        lastError = "Waiting to stay within your PDS's limits"
                        return
                    }
                    try {
                        val cid = writeVault(account, key, null, final, rec?.cid, keep = rec?.value)
                        prefs?.edit()?.putString(k("base"), final.toString())?.putString(k("cid"), cid)?.commit()
                        lastPush = currentTimeMillis()
                        markSynced()
                        StatusHistory.retirePublic(account)
                        return
                    } catch (e: SwapFailed) {
                        // Another device wrote first: pull its version and merge again.
                        if (attempt == 2) throw e
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                lastError = e.message
            } finally {
                syncing = false
            }
        }
    }

    // ── The vault record ───────────────────────────────────────────────

    private fun b64(b: ByteArray) = Base64.Default.encode(b)
    private fun unb64(s: String?): ByteArray? = s?.let { runCatching { Base64.Default.decode(it) }.getOrNull() }

    private fun unwrap(record: JsonObject, secret: ByteArray, account: String): ByteArray? {
        if (secret.size != 20) return null
        val keyObj = record["key"] as? JsonObject ?: return null
        val nonce = unb64(keyObj["nonce"]?.jsonPrimitive?.contentOrNull) ?: return null
        val wrapped = unb64(keyObj["wrapped"]?.jsonPrimitive?.contentOrNull) ?: return null
        if (nonce.size != XChaCha20Poly1305.NONCE_BYTES) return null
        return try {
            XChaCha20Poly1305.open(kek(secret, account), nonce, wrapped, "stellar-sync-key|v1|$account".encodeToByteArray())
                .takeIf { it.size == 32 }
        } catch (_: VaultDecryptException) {
            null
        }
    }

    /** Encrypts [bundle] and writes the vault. [kek] set = a new data key
     *  is being sealed; otherwise the record's existing sealed key ([keep])
     *  is kept as it is. */
    private suspend fun writeVault(account: String, key: ByteArray, kek: ByteArray?, bundle: JsonObject, swap: String?, keep: JsonObject? = null): String {
        val r = remote ?: error("Not signed in")
        val keyField: Any = if (kek != null) {
            val n = secureRandomBytes(XChaCha20Poly1305.NONCE_BYTES)
            mapOf("nonce" to b64(n), "wrapped" to b64(XChaCha20Poly1305.seal(kek, n, key, "stellar-sync-key|v1|$account".encodeToByteArray())))
        } else {
            val o = keep?.get("key") as? JsonObject ?: error("The vault has no key")
            mapOf("nonce" to (o["nonce"]?.jsonPrimitive?.content ?: ""), "wrapped" to (o["wrapped"]?.jsonPrimitive?.content ?: ""))
        }
        val set = b64(secureRandomBytes(12))
        val chunks = sealPieces(bundle.toString().encodeToByteArray(), key) { i, n -> "stellar-sync-data|v1|$account|$set|$i|$n" }
            .map { r.upload(it) }
        val record = linkedMapOf<String, Any>(
            "\$type" to COLLECTION,
            "v" to VERSION,
            "key" to keyField,
            "set" to set,
            "chunks" to chunks,
            "files" to fileBlobs(bundle),
            "updatedAt" to nowIsoString()
        )
        return r.put(account, COLLECTION, RKEY, record, swap)
    }

    private suspend fun readVault(account: String, key: ByteArray, rec: Rec): JsonObject {
        val set = rec.value["set"]?.jsonPrimitive?.contentOrNull ?: error("The vault is incomplete")
        val bytes = openPieces(account, key, rec.value) { i, n -> "stellar-sync-data|v1|$account|$set|$i|$n" }
        return Json.parseToJsonElement(bytes.decodeToString()) as? JsonObject ?: error("The vault is unreadable")
    }

    private fun sealPieces(plain: ByteArray, key: ByteArray, aad: (Int, Int) -> String): List<ByteArray> {
        val n = maxOf(1, (plain.size + CHUNK - 1) / CHUNK)
        return (0 until n).map { i ->
            val part = plain.copyOfRange(i * CHUNK, minOf(plain.size, (i + 1) * CHUNK))
            seal(key, part, aad(i, n))
        }
    }

    private fun seal(key: ByteArray, part: ByteArray, aad: String): ByteArray {
        val nonce = secureRandomBytes(XChaCha20Poly1305.NONCE_BYTES)
        return nonce + XChaCha20Poly1305.seal(key, nonce, part, aad.encodeToByteArray())
    }

    private fun open(key: ByteArray, piece: ByteArray, aad: String): ByteArray {
        if (piece.size < XChaCha20Poly1305.NONCE_BYTES + XChaCha20Poly1305.TAG_BYTES) throw VaultDecryptException()
        return XChaCha20Poly1305.open(
            key, piece.copyOfRange(0, XChaCha20Poly1305.NONCE_BYTES),
            piece.copyOfRange(XChaCha20Poly1305.NONCE_BYTES, piece.size), aad.encodeToByteArray()
        )
    }

    private fun blobCids(record: JsonObject): List<String> =
        (record["chunks"] as? JsonArray).orEmpty().map { c ->
            ((c as? JsonObject)?.get("ref") as? JsonObject)?.get("\$link")?.jsonPrimitive?.contentOrNull ?: error("A piece is missing")
        }

    private suspend fun openPieces(account: String, key: ByteArray, record: JsonObject, aad: (Int, Int) -> String): ByteArray {
        val r = remote ?: error("Not signed in")
        val cids = blobCids(record)
        var out = ByteArray(0)
        cids.forEachIndexed { i, cid -> out += open(key, r.blob(account, cid), aad(i, cids.size)) }
        return out
    }

    // ── Files ──────────────────────────────────────────────────────────

    private val TOKEN = Regex("stellar-sync://(private|media)/[^\\s)\"'<>\\]]+")

    private fun localUri(ctx: PlatformContext, token: String): String? = when {
        token.startsWith(Sections.PRIVATE_TOKEN + "/") -> PrivateFiles.rootUri(ctx) + token.removePrefix(Sections.PRIVATE_TOKEN)
        token.startsWith(Sections.MEDIA_TOKEN + "/") -> PrivateFiles.mediaRootUri(ctx) + token.removePrefix(Sections.MEDIA_TOKEN)
        else -> null
    }

    private fun referencedFiles(sections: JsonObject): Set<String> {
        val out = linkedSetOf<String>()
        fun walk(e: JsonElement) {
            when (e) {
                is JsonObject -> e.values.forEach { walk(it) }
                is JsonArray -> e.forEach { walk(it) }
                is JsonPrimitive -> if (e.isString && e.content.contains("stellar-sync://")) TOKEN.findAll(e.content).forEach { out += it.value }
                else -> {}
            }
        }
        walk(sections)
        return out
    }

    /** A file's blobs, from its bundle entry {"set", "blobs": [{"c": cid, "z": size}]}. */
    private fun entryBlobs(entry: JsonElement?): List<Pair<String, Long>> =
        ((entry as? JsonObject)?.get("blobs") as? JsonArray).orEmpty().mapNotNull { b ->
            val o = b as? JsonObject ?: return@mapNotNull null
            val cid = o["c"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            cid to (o["z"]?.jsonPrimitive?.longOrNull ?: 0L)
        }

    /** Every file blob the bundle uses, as the record lists them. */
    private fun fileBlobs(bundle: JsonObject): List<Map<String, Any>> =
        (bundle["files"] as? JsonObject).orEmpty().values.flatMap { entryBlobs(it) }.map { (cid, size) ->
            mapOf("\$type" to "blob", "ref" to mapOf("\$link" to cid), "mimeType" to "application/octet-stream", "size" to size)
        }

    /** The bundle for [sections]: files they point at that the vault
     *  doesn't hold yet are uploaded (a few per sync, while the record has
     *  room), and ones nothing points at anymore leave the list. */
    private suspend fun withFiles(ctx: PlatformContext, key: ByteArray, sections: JsonObject, known: JsonObject?): JsonObject {
        val r = remote ?: error("Not signed in")
        val refs = referencedFiles(sections)
        val files = linkedMapOf<String, JsonElement>()
        known?.forEach { (token, entry) -> if (token in refs && entryBlobs(entry).isNotEmpty()) files[token] = entry }
        var blobCount = files.values.sumOf { entryBlobs(it).size }
        var budget = FILES_PER_SYNC
        for (token in refs) {
            if (token in files || budget <= 0) continue
            val uri = localUri(ctx, token) ?: continue
            val size = PrivateFiles.ownSize(ctx, uri)
            if (size <= 0L || size > MAX_FILE) continue
            val n = ((size + CHUNK - 1) / CHUNK).toInt()
            if (blobCount + n > MAX_RECORD_BLOBS) continue
            budget--
            val set = b64(secureRandomBytes(12))
            val blobs = mutableListOf<JsonElement>()
            var ok = true
            for (i in 0 until n) {
                val part = PrivateFiles.readRange(ctx, uri, i.toLong() * CHUNK, CHUNK)
                if (part == null || (part.size != CHUNK && i < n - 1)) { ok = false; break }
                val up = r.upload(seal(key, part, "stellar-sync-file|v1|$set|$i|$n"))
                val cid = ((up["ref"] as? Map<*, *>)?.get("\$link") as? String) ?: run { ok = false; null } ?: break
                blobs += buildJsonObject { put("c", cid); put("z", (up["size"] as? Number)?.toLong() ?: 0L) }
            }
            if (!ok) continue
            files[token] = buildJsonObject { put("set", set); put("blobs", JsonArray(blobs)) }
            blobCount += n
        }
        return buildJsonObject {
            put("v", VERSION)
            put("s", sections)
            put("files", JsonObject(files))
        }
    }

    private suspend fun fetchMissingFiles(ctx: PlatformContext, account: String, key: ByteArray, bundle: JsonObject) {
        val r = remote ?: return
        val files = bundle["files"] as? JsonObject ?: return
        for ((token, entry) in files) {
            val uri = localUri(ctx, token) ?: continue
            if (PrivateFiles.ownSize(ctx, uri) >= 0L) continue
            val set = ((entry as? JsonObject)?.get("set") as? JsonPrimitive)?.contentOrNull ?: continue
            val blobs = entryBlobs(entry)
            if (blobs.isEmpty()) continue
            runCatching {
                var ok = true
                blobs.forEachIndexed { i, (cid, _) ->
                    val part = open(key, r.blob(account, cid), "stellar-sync-file|v1|$set|$i|${blobs.size}")
                    if (!PrivateFiles.appendBytes(ctx, uri, part, restart = i == 0)) ok = false
                }
                if (!ok) PrivateFiles.delete(ctx, uri)
            }.onFailure { runCatching { PrivateFiles.delete(ctx, uri) } }
        }
    }

    // ── Merging ────────────────────────────────────────────────────────

    private fun sectionsOf(bundle: JsonObject?): JsonObject = bundle?.get("s") as? JsonObject ?: JsonObject(emptyMap())

    /** Three-way merge of whole bundles (sections and the file list). */
    private fun merge(base: JsonObject?, local: JsonObject, remoteBundle: JsonObject?): JsonObject {
        if (remoteBundle == null) return buildJsonObject { put("v", VERSION); put("s", local); put("files", base?.get("files") ?: JsonObject(emptyMap())) }
        val b = sectionsOf(base)
        val r = sectionsOf(remoteBundle)
        val names = LinkedHashSet(r.keys + local.keys)
        val s = linkedMapOf<String, JsonElement>()
        for (name in names) {
            // A section only one side knows (a newer app version's, say)
            // passes through as it is.
            // The same thing changed here and elsewhere: this device's change
            // is the newer one (it's pushed within seconds), except on a
            // device joining for the first time, whose values are just its
            // defaults.
            JsonMerge.merge3(b[name], local[name], r[name], localWins = base != null)?.let { s[name] = it }
        }
        // (The file list is the vault's; this device's additions join it in
        // withFiles once they're uploaded.)
        val files = remoteBundle["files"] ?: base?.get("files") ?: JsonObject(emptyMap())
        return buildJsonObject { put("v", VERSION); put("s", JsonObject(s)); put("files", files) }
    }

    // ── The write budget ───────────────────────────────────────────────

    /**
     * Counts this feature's PDS writes (create 3 points, update 2, delete
     * 1 — Bluesky's own accounting) over the last hour and day, and says no
     * well before the PDS would: 1,500 points an hour and 10,000 a day,
     * against limits of 5,000 and 35,000, so the rest of the app (posting,
     * liking, following) is never squeezed.
     */
    internal object WriteBudget {
        private const val HOUR_CAP = 1_500
        private const val DAY_CAP = 10_000
        private var prefs: SharedPreferences? = null
        private val log = ArrayDeque<Pair<Long, Int>>()

        fun init(p: SharedPreferences) {
            prefs = p
            p.getString("budget", null)?.split(',')?.forEach { e ->
                val (t, c) = e.split(':').takeIf { it.size == 2 } ?: return@forEach
                val time = t.toLongOrNull() ?: return@forEach
                log.addLast(time to (c.toIntOrNull() ?: 0))
            }
        }

        fun take(points: Int): Boolean = com.mediaviewer.platform.synchronizedCompat(this) {
            val now = currentTimeMillis()
            while (log.isNotEmpty() && now - log.first().first > 86_400_000L) log.removeFirst()
            val day = log.sumOf { it.second }
            val hour = log.filter { now - it.first < 3_600_000L }.sumOf { it.second }
            if (hour + points > HOUR_CAP || day + points > DAY_CAP) return@synchronizedCompat false
            log.addLast(now to points)
            prefs?.edit()?.putString("budget", log.joinToString(",") { "${it.first}:${it.second}" })?.apply()
            true
        }
    }
}
