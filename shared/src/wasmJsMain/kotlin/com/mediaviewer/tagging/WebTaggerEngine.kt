package com.mediaviewer.tagging

import com.mediaviewer.platform.WebFiles
import com.mediaviewer.platform.WebPaths
import com.mediaviewer.util.BskyCdn
import com.mediaviewer.worker.BlueskyBlobResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.await
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.js.Promise

/**
 * AI Tagging in a browser: the same model as Android, run by
 * onnxruntime-web (on the graphics card through WebGPU where the browser
 * has it, otherwise WebAssembly), loaded from the CDN only once tagging is
 * first used.
 *
 * The model (~390 MB) is kept in the browser's Cache Storage, which is
 * made for big files; the tag list, a marker that the model is complete
 * and the dataset are small and live in [WebFiles] like the rest of
 * Stellar's data. Clearing the site's data removes all of it.
 */
class WebTaggerEngine : TaggerEngine {
    private val dir = WebPaths.FILES + "/tagger"
    private val tagsPath = "$dir/${TaggerFiles.TAGS_NAME}"
    private val readyPath = "$dir/model.ready"
    private val datasetPath = "$dir/dataset.json"
    private val runLock = Mutex()
    private var loaded = false

    override fun hasModel(): Boolean = WebFiles.exists(readyPath) && WebFiles.exists(tagsPath)

    override suspend fun download(onProgress: (Long, Long) -> Unit) {
        WebFiles.delete(readyPath)
        try {
            val csv = withProgress(onProgress) { jsDownload(TaggerFiles.TAGS_URL, CACHE, "", true) }
            WebFiles.write(tagsPath, csv.encodeToByteArray())
            withProgress(onProgress) { jsDownload(TaggerFiles.MODEL_URL, CACHE, MODEL_KEY, false) }
            WebFiles.write(readyPath, byteArrayOf(1))
        } catch (e: Throwable) {
            WebFiles.delete(tagsPath)
            runCatching { jsDropModel(CACHE).await<JsAny?>() }
            if (e is CancellationException) throw e
            throw IllegalStateException(friendly(e, "Download failed"))
        }
    }

    /** The message for a failure thrown on the JavaScript side (a code, or
     *  the browser's own words). */
    private fun friendly(e: Throwable, fallback: String): String {
        val raw = e.message.orEmpty().substringAfter("Error: ")
        return when {
            raw == "NO_STORAGE" -> "This browser can't store the model"
            raw == "CUT_OFF" -> "Download was cut off"
            raw == "NO_RUNTIME" -> "Couldn't load ONNX Runtime"
            raw.startsWith("HTTP ") -> "Download failed (HTTP ${raw.removePrefix("HTTP ")})"
            raw.startsWith("LOAD ") -> "Model load failed" + ": " + raw.removePrefix("LOAD ")
            else -> raw.ifBlank { fallback }
        }
    }

    /** Runs [start] while passing on how far the browser's download has got. */
    private suspend fun withProgress(onProgress: (Long, Long) -> Unit, start: () -> Promise<JsAny?>): String = coroutineScope {
        val job = async { start().await<JsAny?>()?.toString().orEmpty() }
        try {
            while (!job.isCompleted) {
                onProgress(jsProgressDone().toLong(), jsProgressTotal().toLong())
                delay(250)
            }
        } catch (e: CancellationException) {
            jsAbortDownload()
            throw e
        }
        job.await().also { onProgress(jsProgressDone().toLong(), jsProgressTotal().toLong()) }
    }

    override fun tagsCsv(): String? = WebFiles.read(tagsPath)?.decodeToString()

    override suspend fun load() {
        if (loaded) return
        try {
            jsLoadOrt(ORT_BASE).await<JsAny?>()
            jsLoadSession(CACHE, MODEL_KEY).await<JsAny?>()
            loaded = true
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            if (e.message.orEmpty().substringAfter("Error: ") == "MISSING") {
                // The browser cleared the model to free up space.
                WebFiles.delete(readyPath)
                error("The tagger model was removed by the browser. Download it again in Settings.")
            }
            error(friendly(e, "Model load failed"))
        }
    }

    override fun isLoaded(): Boolean = loaded

    override suspend fun tag(imageUrl: String, minScore: Float): List<Pair<Int, Float>>? {
        // Bluesky's picture CDN may refuse to let pages read its pictures:
        // then the poster's own server (their PDS) has the same file.
        val blob = BskyCdn.parse(imageUrl)
        val pds = blob?.let { runCatching { BlueskyBlobResolver.resolveBlobUrl(it.first, it.second) }.getOrNull() }
        val urls = (if (BskyCdn.blocked) listOfNotNull(pds, imageUrl) else listOfNotNull(imageUrl, pds)).distinct()
        // The model uses the whole graphics card / every thread for one picture.
        val hits = runLock.withLock {
            runCatching { jsTag(urls.joinToString("\n"), minScore.toDouble()).await<JsAny?>() }.getOrNull()
        } ?: return null
        val n = jsLength(hits)
        val out = ArrayList<Pair<Int, Float>>(n / 2)
        var i = 0
        while (i + 1 < n) {
            out.add(jsNumberAt(hits, i).toInt() to jsNumberAt(hits, i + 1).toFloat())
            i += 2
        }
        return out
    }

    override fun readDataset(): String? = WebFiles.read(datasetPath)?.decodeToString()
    override fun writeDataset(text: String): Boolean = runCatching { WebFiles.write(datasetPath, text.encodeToByteArray()) }.isSuccess

    private companion object {
        const val ORT_BASE = "https://cdn.jsdelivr.net/npm/onnxruntime-web@1.18.0/dist/"
        const val CACHE = "stellar-tagger"
        const val MODEL_KEY = "/stellar-tagger/" + TaggerFiles.MODEL_NAME
    }
}

private fun jsProgressDone(): Double = js("(window.__stellarTaggerProgress ? window.__stellarTaggerProgress.done : 0)")
private fun jsProgressTotal(): Double = js("(window.__stellarTaggerProgress ? window.__stellarTaggerProgress.total : 0)")
private fun jsAbortDownload(): Unit = js("{ try { if (window.__stellarTaggerAbort) window.__stellarTaggerAbort.abort(); } catch (e) {} }")
private fun jsLength(a: JsAny): Int = js("a.length")
private fun jsNumberAt(a: JsAny, i: Int): Double = js("a[i]")

/** Fetches [url]: as text (returned), or streamed into Cache Storage under
 *  [key]. Progress goes to window.__stellarTaggerProgress. */
private fun jsDownload(url: String, cacheName: String, key: String, asText: Boolean): Promise<JsAny?> = js("""(async () => {
    const p = window.__stellarTaggerProgress = { done: 0, total: 0 };
    const abort = window.__stellarTaggerAbort = new AbortController();
    const resp = await fetch(url, { signal: abort.signal });
    if (!resp.ok) throw new Error('HTTP ' + resp.status);
    p.total = Number(resp.headers.get('content-length')) || 0;
    if (asText) { const t = await resp.text(); p.done = t.length; return t; }
    if (!self.caches) throw new Error('NO_STORAGE');
    const reader = resp.body.getReader();
    const counted = new ReadableStream({
        async pull(c) {
            const r = await reader.read();
            if (r.done) { c.close(); return; }
            p.done += r.value.length;
            c.enqueue(r.value);
        },
        cancel(reason) { reader.cancel(reason); }
    });
    const cache = await caches.open(cacheName);
    await cache.put(key, new Response(counted, { headers: { 'content-type': 'application/octet-stream' } }));
    if (p.total > 0 && p.done < p.total) { await cache.delete(key); throw new Error('CUT_OFF'); }
    return '';
})()""")

private fun jsDropModel(cacheName: String): Promise<JsAny?> = js("(self.caches ? caches.delete(cacheName) : Promise.resolve(false))")

/** Adds onnxruntime-web to the page (once). */
private fun jsLoadOrt(base: String): Promise<JsAny?> = js("""(() => {
    if (window.ort && window.ort.InferenceSession) return Promise.resolve(true);
    if (window.__stellarOrtLoading) return window.__stellarOrtLoading;
    window.__stellarOrtLoading = new Promise((resolve, reject) => {
        const s = document.createElement('script');
        s.src = base + 'ort.webgpu.min.js';
        s.onload = () => { try { ort.env.wasm.wasmPaths = base; } catch (e) {} resolve(true); };
        s.onerror = () => { window.__stellarOrtLoading = null; reject(new Error('NO_RUNTIME')); };
        document.head.appendChild(s);
    });
    return window.__stellarOrtLoading;
})()""")

/** Makes the model's session: WebGPU first where there is one. */
private fun jsLoadSession(cacheName: String, key: String): Promise<JsAny?> = js("""(async () => {
    if (window.__stellarTaggerSession) return true;
    const cache = self.caches ? await caches.open(cacheName) : null;
    const r = cache ? await cache.match(key) : null;
    if (!r) throw new Error('MISSING');
    const model = new Uint8Array(await r.arrayBuffer());
    let lastError = null;
    for (const ep of (navigator.gpu ? ['webgpu', 'wasm'] : ['wasm'])) {
        try {
            window.__stellarTaggerSession = await ort.InferenceSession.create(model, { executionProviders: [ep], graphOptimizationLevel: 'all' });
            return true;
        } catch (e) { lastError = e; }
    }
    throw new Error('LOAD ' + (lastError && lastError.message ? lastError.message : 'unknown'));
})()""")

/**
 * Tags the first of [urls] (one per line) that the page can read: drawn
 * onto a white 448×448 square keeping its shape, fed to the model as raw
 * 0–255 BGR values (as on Android). Returns [index, score, …] for every
 * score of at least [minScore], or null if no picture could be read.
 */
private fun jsTag(urls: String, minScore: Double): Promise<JsAny?> = js("""(async () => {
    const session = window.__stellarTaggerSession;
    if (!session) return null;
    let bmp = null;
    for (const u of urls.split('\n')) {
        if (!u) continue;
        try {
            const r = await fetch(u);
            if (!r.ok) continue;
            bmp = await createImageBitmap(await r.blob());
            break;
        } catch (e) {}
    }
    if (!bmp) return null;
    const S = 448;
    const canvas = new OffscreenCanvas(S, S);
    const g = canvas.getContext('2d', { willReadFrequently: true });
    g.fillStyle = '#ffffff';
    g.fillRect(0, 0, S, S);
    const scale = Math.min(S / bmp.width, S / bmp.height);
    const w = Math.max(1, Math.floor(bmp.width * scale));
    const h = Math.max(1, Math.floor(bmp.height * scale));
    g.imageSmoothingEnabled = true;
    g.imageSmoothingQuality = 'high';
    g.drawImage(bmp, (S - w) / 2, (S - h) / 2, w, h);
    bmp.close();
    const px = g.getImageData(0, 0, S, S).data;
    const input = new Float32Array(S * S * 3);
    for (let i = 0, o = 0; i < px.length; i += 4, o += 3) {
        input[o] = px[i + 2]; input[o + 1] = px[i + 1]; input[o + 2] = px[i];
    }
    const feeds = {};
    feeds[session.inputNames[0]] = new ort.Tensor('float32', input, [1, S, S, 3]);
    const out = await session.run(feeds);
    const scores = out[session.outputNames[0]].data;
    const hits = [];
    for (let i = 0; i < scores.length; i++) if (scores[i] >= minScore) hits.push(i, scores[i]);
    return hits;
})()""")
