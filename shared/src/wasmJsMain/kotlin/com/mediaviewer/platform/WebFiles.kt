package com.mediaviewer.platform

import kotlinx.coroutines.await
import kotlin.js.Promise

/**
 * The web version's "disk". A browser page has no files of its own, so
 * everything Stellar would keep in a file (settings, drafts and notes with
 * their pictures, custom emoji, fonts, archived posts) lives here: paths
 * like "/files/emoji/e_3.png", held in memory while Stellar runs and saved
 * to the browser's IndexedDB, which keeps them between visits (until the
 * site's data is cleared).
 *
 * All of it is read once, before the app starts ([load]), so reading is
 * instant everywhere and the shared code's file calls can stay
 * synchronous; writes are saved in the background a moment later.
 *
 * Big picked files (a video to upload) aren't copied in at all: they stay
 * the browser's own File object ([WebBlobs]) until something reads them.
 */
object WebFiles {
    /** "file://" + path is a file's uri. */
    const val SCHEME = "file://"

    private val files = HashMap<String, ByteArray>()
    private val dirty = LinkedHashSet<String>()
    private val deleted = LinkedHashSet<String>()
    private var flushScheduled = false
    private var loaded = false

    /** Reads everything saved by earlier visits. Call once, before the app starts. */
    suspend fun load() {
        if (loaded) return
        loaded = true
        runCatching {
            val all = idbLoadAll().await<JsAny>()
            val n = jsArrayLength(all)
            for (i in 0 until n) {
                val entry = jsArrayAt(all, i)
                val path = jsGetString(entry, "k") ?: continue
                val bytes = jsGet(entry, "v")?.unsafeCast<JsBytes>() ?: continue
                files[path] = bytes.toByteArray()
            }
        }.onFailure { Log.w("WebFiles", "Couldn't read saved data: ${it.message}") }
        // Ask the browser not to clear Stellar's data when space runs low.
        runCatching { requestPersistentStorage() }
        onPageHidden { flush() }
    }

    fun normalize(path: String): String {
        val p = path.removePrefix(SCHEME).replace('\\', '/')
        val parts = ArrayList<String>()
        for (part in p.split('/')) when (part) {
            "", "." -> {}
            ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
            else -> parts.add(part)
        }
        return "/" + parts.joinToString("/")
    }

    fun uriOf(path: String): String = SCHEME + normalize(path)

    fun read(path: String): ByteArray? {
        val p = normalize(path)
        return files[p] ?: WebBlobs.bytesOf(p)?.also { files[p] = it }
    }

    /** True when the file's bytes are held here (not only as a picked File). */
    fun isInMemory(path: String): Boolean = files.containsKey(normalize(path)) && !WebBlobs.has(normalize(path))

    fun exists(path: String): Boolean = normalize(path).let { files.containsKey(it) || WebBlobs.has(it) }

    fun size(path: String): Long = normalize(path).let { p -> files[p]?.size?.toLong() ?: WebBlobs.sizeOf(p) ?: 0L }

    fun write(path: String, bytes: ByteArray) {
        val p = normalize(path)
        // "Save as…" (a backup, an export): the browser downloads it.
        if (p.startsWith(DOWNLOADS)) {
            WebDownloads.saveBytes(bytes, p.substringAfterLast('/'), mimeForName(p))
            return
        }
        files[p] = bytes
        if (p.startsWith(TEMP)) return
        deleted.remove(p)
        dirty.add(p)
        scheduleFlush()
    }

    fun delete(path: String) {
        val p = normalize(path)
        WebBlobs.forget(p)
        if (files.remove(p) != null && !p.startsWith(TEMP)) {
            dirty.remove(p)
            deleted.add(p)
            scheduleFlush()
        }
    }

    /** Every file whose path starts with [folder] + "/". */
    fun list(folder: String): List<String> {
        val prefix = normalize(folder).trimEnd('/') + "/"
        return (files.keys + WebBlobs.paths()).filter { it.startsWith(prefix) }.distinct().sorted()
    }

    fun deleteFolder(folder: String) = list(folder).forEach { delete(it) }

    /** Copies one file (a picked one included). */
    fun copy(from: String, to: String): Boolean {
        val bytes = read(from) ?: return false
        write(to, bytes)
        return true
    }

    /** Paths under here are never saved (picked files, temporary work). */
    const val TEMP = "/tmp/"

    /** Writing a file under here downloads it ("Save as…" on the web). */
    const val DOWNLOADS = "/downloads/"

    private fun scheduleFlush() {
        if (flushScheduled) return
        flushScheduled = true
        setTimeoutMs(400) { flushScheduled = false; flush() }
    }

    /** Saves what changed to IndexedDB now. */
    fun flush() {
        if (dirty.isEmpty() && deleted.isEmpty()) return
        val puts = dirty.toList()
        val dels = deleted.toList()
        dirty.clear(); deleted.clear()
        val batch = jsNewArray()
        for (p in puts) {
            val bytes = files[p] ?: continue
            val o = jsObject()
            jsSet(o, "k", p.toJsString())
            jsSet(o, "v", bytes.toJsBytes())
            jsPush(batch, o)
        }
        val gone = jsNewArray()
        for (p in dels) jsPush(gone, p.toJsString())
        idbWrite(batch, gone)
    }
}

/**
 * Files the person picked, kept as the browser's own File objects (a big
 * video is never copied into Stellar's memory until something needs its
 * bytes; uploads send it straight from here).
 */
object WebBlobs {
    private class Entry(val blob: JsAny, val name: String, val type: String, val size: Long)
    private val blobs = HashMap<String, Entry>()
    private val videoSizes = HashMap<String, Pair<Int, Int>>()

    /** A picked video's picture size, as the browser read it. */
    fun videoSizeOf(path: String): Pair<Int, Int>? = videoSizes[WebFiles.normalize(path)]
    fun setVideoSize(path: String, w: Int, h: Int) { videoSizes[WebFiles.normalize(path)] = w to h }

    fun put(path: String, blob: JsAny, name: String, type: String, size: Long) {
        blobs[WebFiles.normalize(path)] = Entry(blob, name, type, size)
    }

    fun has(path: String) = blobs.containsKey(path)
    fun paths(): Set<String> = blobs.keys
    fun forget(path: String) { blobs.remove(path) }
    fun blobOf(path: String): JsAny? = blobs[WebFiles.normalize(path)]?.blob
    fun nameOf(path: String): String? = blobs[WebFiles.normalize(path)]?.name
    fun typeOf(path: String): String? = blobs[WebFiles.normalize(path)]?.type?.takeIf { it.isNotBlank() }
    fun sizeOf(path: String): Long? = blobs[WebFiles.normalize(path)]?.size

    /** A picked file's bytes, read once and then kept (see [WebFiles.read]). */
    fun bytesOf(path: String): ByteArray? = blobs[path]?.let { cachedBytes[path] }

    // Filled by [readAll] (the picker reads small files right away).
    private val cachedBytes = HashMap<String, ByteArray>()
    fun cache(path: String, bytes: ByteArray) { cachedBytes[WebFiles.normalize(path)] = bytes }

    /** Reads a picked file's bytes (asynchronously, as browsers require). */
    suspend fun readAll(path: String): ByteArray? {
        val p = WebFiles.normalize(path)
        cachedBytes[p]?.let { return it }
        val blob = blobs[p]?.blob ?: return null
        val bytes = runCatching { blobBytes(blob).await<JsBytes>().toByteArray() }.getOrNull() ?: return null
        cachedBytes[p] = bytes
        return bytes
    }

    /** A blob: URL the browser can show or play directly. */
    fun objectUrl(path: String): String? {
        val p = WebFiles.normalize(path)
        objectUrls[p]?.let { return it }
        val blob = blobs[p]?.blob ?: WebFiles.read(p)?.let { bytesToBlob(it.toJsBytes(), mimeForName(p)) } ?: return null
        return createObjectUrl(blob).also { objectUrls[p] = it }
    }
    private val objectUrls = HashMap<String, String>()
}

/** A MIME type from a file name's extension. */
fun mimeForName(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "jpg", "jpeg" -> "image/jpeg"
    "png" -> "image/png"
    "webp" -> "image/webp"
    "gif" -> "image/gif"
    "heic" -> "image/heic"
    "heif" -> "image/heif"
    "avif" -> "image/avif"
    "bmp" -> "image/bmp"
    "mp4", "m4v" -> "video/mp4"
    "mov" -> "video/quicktime"
    "webm" -> "video/webm"
    "mkv" -> "video/x-matroska"
    "mp3" -> "audio/mpeg"
    "wav" -> "audio/wav"
    "ogg" -> "audio/ogg"
    "m4a" -> "audio/mp4"
    "json" -> "application/json"
    "txt" -> "text/plain"
    "csv" -> "text/csv"
    "ttf" -> "font/ttf"
    "otf" -> "font/otf"
    else -> "application/octet-stream"
}

// ── IndexedDB ─────────────────────────────────────────────────────────────

private fun idbLoadAll(): Promise<JsAny> = js("""new Promise((resolve, reject) => {
    const open = indexedDB.open('stellar', 1);
    open.onupgradeneeded = () => open.result.createObjectStore('files');
    open.onerror = () => reject(open.error);
    open.onsuccess = () => {
        const db = open.result;
        globalThis.__stellarDb = db;
        const out = [];
        const tx = db.transaction('files', 'readonly');
        const req = tx.objectStore('files').openCursor();
        req.onsuccess = () => {
            const c = req.result;
            if (c) { out.push({ k: c.key, v: new Uint8Array(c.value) }); c.continue(); }
        };
        tx.oncomplete = () => resolve(out);
        tx.onerror = () => reject(tx.error);
    };
})""")

private fun idbWrite(puts: JsAny, deletes: JsAny): Unit = js("""{
    const db = globalThis.__stellarDb;
    if (!db) return;
    try {
        const tx = db.transaction('files', 'readwrite');
        const store = tx.objectStore('files');
        for (const p of puts) store.put(p.v, p.k);
        for (const k of deletes) store.delete(k);
        tx.onerror = () => console.warn('Stellar: saving failed', tx.error);
    } catch (e) { console.warn('Stellar: saving failed', e); }
}""")

private fun requestPersistentStorage(): Unit = js("""{ if (navigator.storage && navigator.storage.persist) navigator.storage.persist().catch(() => {}); }""")

internal fun jsNewArray(): JsAny = js("[]")
internal fun jsPush(a: JsAny, v: JsAny?): Unit = js("a.push(v)")
internal fun jsArrayLength(a: JsAny): Int = js("a.length")
internal fun jsArrayAt(a: JsAny, i: Int): JsAny = js("a[i]")

private fun blobBytes(blob: JsAny): Promise<JsBytes> = js("blob.arrayBuffer().then(b => new Uint8Array(b))")
internal fun bytesToBlob(bytes: JsBytes, type: String): JsAny = js("new Blob([bytes], { type: type })")
internal fun createObjectUrl(blob: JsAny): String = js("URL.createObjectURL(blob)")
internal fun revokeObjectUrl(url: String): Unit = js("URL.revokeObjectURL(url)")

/** Runs [block] after [ms] milliseconds (on the page's event loop). */
fun setTimeoutMs(ms: Int, block: () -> Unit) { jsSetTimeout(ms, block) }
private fun jsSetTimeout(ms: Int, block: () -> Unit): Unit = js("setTimeout(() => block(), ms)")

/** Runs [block] whenever the page is hidden or closed (last chance to save). */
fun onPageHidden(block: () -> Unit) { jsOnPageHidden(block) }
private fun jsOnPageHidden(block: () -> Unit): Unit = js("""{
    document.addEventListener('visibilitychange', () => { if (document.visibilityState === 'hidden') block(); });
    window.addEventListener('pagehide', () => block());
}""")
