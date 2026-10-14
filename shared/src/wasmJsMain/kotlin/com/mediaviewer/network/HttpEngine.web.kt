package com.mediaviewer.network

import com.mediaviewer.platform.IOException
import com.mediaviewer.platform.JsBytes
import com.mediaviewer.platform.Log
import com.mediaviewer.platform.WebBlobs
import com.mediaviewer.platform.jsArrayAt
import com.mediaviewer.platform.jsArrayLength
import com.mediaviewer.platform.jsGet
import com.mediaviewer.platform.jsGetInt
import com.mediaviewer.platform.jsGetString
import com.mediaviewer.platform.jsNewArray
import com.mediaviewer.platform.jsObject
import com.mediaviewer.platform.jsPush
import com.mediaviewer.platform.jsSet
import com.mediaviewer.platform.toByteArray
import com.mediaviewer.platform.toJsBytes
import com.mediaviewer.util.BlockedHosts
import kotlinx.coroutines.await
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.js.Promise

/**
 * The web version's HTTP: the browser's own fetch(). Same rules as the
 * other versions' clients (blocked hosts, timeouts), with what a browser
 * won't allow left out: a page can't set its User-Agent (e621 is told who
 * is asking with its "_client" parameter instead, as its API asks of
 * browser apps), and it can only reach servers that allow it (CORS) — a
 * request a server doesn't allow fails like a network error, and is
 * written to the browser's console.
 */
private class FetchHttpEngine(private val profile: HttpProfile) : HttpEngine {
    override suspend fun execute(request: HttpRequest): HttpResponseData {
        val url = buildUrl(request)
        val host = runCatching { com.mediaviewer.platform.uriHost(url) }.getOrNull()
        if (BlockedHosts.isBlocked(host)) {
            return HttpResponseData(403, "Blocked by Stellar", emptyList(), ByteArray(0), null)
        }
        val init = jsObject()
        jsSet(init, "method", request.method.toJsString())
        val headers = jsNewArray()
        fun header(k: String, v: String) {
            val pair = jsNewArray()
            jsPush(pair, k.toJsString()); jsPush(pair, v.toJsString())
            jsPush(headers, pair)
        }
        for ((k, v) in request.headers) {
            if (k.lowercase() in FORBIDDEN) continue
            if (profile == HttpProfile.DIRECT_SERVICE && k.equals("atproto-proxy", ignoreCase = true)) continue
            header(k, v)
        }
        // Wikimedia asks every client to say who it is; in a browser that's
        // this header (its API allows it).
        if (host != null && host != "upload.wikimedia.org" && BlockedHosts.isWikimediaHost(host)) {
            header("Api-User-Agent", BlockedHosts.WIKIMEDIA_USER_AGENT)
        }
        val body = request.body
        when {
            body == null -> if (request.method == "POST" || request.method == "PUT" || request.method == "PATCH") jsSet(init, "body", "".toJsString())
            body.form != null -> {
                header("Content-Type", "application/x-www-form-urlencoded")
                jsSet(init, "body", body.form.joinToString("&") { (k, v) -> formEncode(k) + "=" + formEncode(v) }.toJsString())
            }
            body.stream != null -> {
                val src = body.stream as WebBlobStreamSource
                body.contentType?.let { header("Content-Type", it) }
                jsSet(init, "body", WebBlobs.blobOf(src.path) ?: throw IOException("Couldn't read the file to upload"))
            }
            else -> {
                body.contentType?.let { header("Content-Type", it) }
                jsSet(init, "body", (body.bytes ?: ByteArray(0)).toJsBytes())
            }
        }
        jsSet(init, "headers", headers)
        val timeoutMs = if (profile == HttpProfile.VIDEO) 30 * 60_000 else 3 * 60_000
        val result: JsAny = suspendCancellableCoroutine { cont ->
            val abort = newAbortController()
            jsSet(init, "signal", abortSignal(abort))
            cont.invokeOnCancellation { runCatching { abortNow(abort) } }
            doFetch(url, init, timeoutMs, abort).then(
                onFulfilled = { r -> cont.resume(r); null },
                onRejected = { e ->
                    val why = errorText(e)
                    if (cont.isActive) {
                        // (Most often: the server doesn't allow requests
                        // from web pages — CORS — or the network is down.)
                        Log.w("Http", "${request.method} ${where(url)} failed: $why")
                        cont.resumeWithException(IOException("Network error: $why"))
                    }
                    null
                }
            )
        }
        val code = jsGetInt(result, "status")
        val bytes = jsGet(result, "body")?.unsafeCast<JsBytes>()?.toByteArray() ?: ByteArray(0)
        val headerList = ArrayList<Pair<String, String>>()
        jsGet(result, "headers")?.let { hs ->
            for (i in 0 until jsArrayLength(hs)) {
                val pair = jsArrayAt(hs, i)
                headerList += (jsGetString(pair, "0") ?: continue) to (jsGetString(pair, "1") ?: "")
            }
        }
        if ((code !in 200..299) && code != 304) {
            Log.w("Http", "${request.method} ${where(url)} -> $code ${bytes.decodeToString().take(300).replace('\n', ' ')}")
        }
        return HttpResponseData(
            code = code,
            message = jsGetString(result, "statusText")?.ifBlank { null } ?: reason(code),
            headers = headerList,
            body = bytes,
            contentType = headerList.lastOrNull { it.first.equals("content-type", ignoreCase = true) }?.second,
        )
    }

    private fun buildUrl(request: HttpRequest): String {
        val params = ArrayList(request.query)
        val host = runCatching { com.mediaviewer.platform.uriHost(request.url) }.getOrNull().orEmpty()
        if (profile == HttpProfile.E621 || host == "e621.net" || host.endsWith(".e621.net")) {
            if (host == "e621.net" && params.none { it.first == "_client" }) params += "_client" to E621_CLIENT
        }
        if (params.isEmpty()) return request.url
        val q = params.joinToString("&") { (k, v) -> queryEncode(k) + "=" + queryEncode(v) }
        return request.url + (if ('?' in request.url) "&" else "?") + q
    }

    companion object {
        /** Who's asking, for e621's API (it asks browser apps for this). */
        const val E621_CLIENT = "Stellar/1.0 (by RechoRaccoon on e621)"

        /** Headers a page isn't allowed to set (the browser sets them). */
        private val FORBIDDEN = setOf(
            "user-agent", "accept-encoding", "accept-charset", "connection", "content-length", "cookie", "cookie2",
            "date", "dnt", "expect", "host", "keep-alive", "origin", "referer", "te", "trailer", "transfer-encoding", "upgrade", "via"
        )
    }
}

/** A picked file sent as a request body straight from the browser (big
 *  video uploads), see WebBlobs. */
class WebBlobStreamSource(val path: String, override val contentLength: Long) : StreamSource

/** OkHttp's addQueryParameter encoding (spaces as %20). */
private fun queryEncode(s: String): String = com.mediaviewer.platform.jsEncodeUriComponentPublic(s)

/** application/x-www-form-urlencoded (spaces as '+'). */
private fun formEncode(s: String): String = com.mediaviewer.platform.urlEncode(s)

private fun where(url: String): String = runCatching {
    val c = com.mediaviewer.platform.parseUriComponents(url)
    c.host + c.path
}.getOrDefault(url.substringBefore('?'))

private fun reason(code: Int): String = when (code) {
    200 -> "OK"; 201 -> "Created"; 202 -> "Accepted"; 204 -> "No Content"
    301 -> "Moved Permanently"; 302 -> "Found"; 304 -> "Not Modified"
    400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"; 404 -> "Not Found"
    409 -> "Conflict"; 413 -> "Payload Too Large"; 429 -> "Too Many Requests"
    500 -> "Internal Server Error"; 502 -> "Bad Gateway"; 503 -> "Service Unavailable"; 504 -> "Gateway Timeout"
    else -> ""
}

private fun newAbortController(): JsAny = js("new AbortController()")
private fun abortSignal(c: JsAny): JsAny = js("c.signal")
private fun abortNow(c: JsAny): Unit = js("c.abort()")
private fun errorText(e: JsAny?): String = js("(e && (e.message || String(e))) || 'failed'")

private fun doFetch(url: String, init: JsAny, timeoutMs: Int, abort: JsAny): Promise<JsAny> = js("""(async () => {
    const timer = setTimeout(() => abort.abort(new Error('timed out')), timeoutMs);
    try {
        const r = await fetch(url, Object.assign({ credentials: 'omit', redirect: 'follow' }, init));
        const buf = await r.arrayBuffer();
        const headers = [];
        r.headers.forEach((v, k) => headers.push([k, v]));
        return { status: r.status, statusText: r.statusText, headers: headers, body: new Uint8Array(buf) };
    } finally {
        clearTimeout(timer);
    }
})()""")

actual fun createHttpEngine(profile: HttpProfile): HttpEngine = FetchHttpEngine(profile)
