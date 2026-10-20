package com.mediaviewer.util

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.Uri
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.asImage
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.toUri
import coil3.fetch.ImageFetchResult
import coil3.fetch.SourceFetchResult
import coil3.memory.MemoryCache
import coil3.request.ImageRequest
import coil3.request.Options
import coil3.size.Dimension
import coil3.size.Scale
import com.mediaviewer.network.PlainHttp
import com.mediaviewer.platform.JsBytes
import com.mediaviewer.platform.WebBlobs
import com.mediaviewer.platform.WebFiles
import com.mediaviewer.platform.WebImagePreloader
import com.mediaviewer.platform.jsGet
import com.mediaviewer.platform.jsGetInt
import com.mediaviewer.platform.toByteArray
import com.mediaviewer.platform.webPathOf
import kotlinx.coroutines.await
import okio.Buffer
import kotlin.js.Promise

/**
 * The app-wide Coil image loader on the web — same rules as the other
 * versions' (a large memory cache, blocked hosts never contacted): web
 * pictures through the browser's fetch (its own cache stands in for the
 * disk cache), and Stellar's stored files ("file://…") from WebFiles —
 * for a video, its first frame.
 *
 * Speed: a page has one thread, and decoding a full-size JPEG on it
 * stalls everything (scrolling a grid decodes dozens). So web pictures
 * are decoded by the browser itself, off that thread, already shrunk to
 * the size they're shown at (createImageBitmap), and only those pixels
 * come into the app. Pictures shown small are asked of Bluesky's CDN in
 * its thumbnail sizes. Anything that can't go that way falls back to the
 * ordinary download-and-decode.
 */
object WebImageLoading {
    private var installed = false

    fun install() {
        if (installed) return
        installed = true
        SingletonImageLoader.setSafe { context ->
            ImageLoader.Builder(context)
                .components {
                    add(BlockedHostsImageInterceptor)
                    // A picked file (the composer's attached pictures) is a
                    // WebUri: the loader reads it by its address.
                    add(WebUriMapper)
                    add(WebFetcher.Factory)
                }
                .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.25).build() }
                .build()
        }
        WebImagePreloader.preloader = { urls ->
            val ctx = PlatformContext.INSTANCE
            val loader = SingletonImageLoader.get(ctx)
            urls.filter { it.isNotBlank() }.forEach { loader.enqueue(ImageRequest.Builder(ctx).data(it).build()) }
        }
    }
}

private object WebUriMapper : coil3.map.Mapper<com.mediaviewer.platform.WebUri, coil3.Uri> {
    override fun map(data: com.mediaviewer.platform.WebUri, options: Options): coil3.Uri = data.urlString.toUri()
}

private class WebFetcher(private val uri: String, private val options: Options) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val path = webPathOf(uri)
        if (path != null) {
            val type = WebBlobs.typeOf(path) ?: com.mediaviewer.platform.mimeForName(WebBlobs.nameOf(path) ?: path)
            if (type.startsWith("video/")) {
                val url = WebBlobs.objectUrl(path) ?: error("Couldn't read the video")
                val frame = videoFrame(url).await<JsBytes>().toByteArray()
                if (frame.isEmpty()) error("Couldn't read the video")
                return SourceFetchResult(ImageSource(Buffer().write(frame), options.fileSystem), "image/jpeg", DataSource.DISK)
            }
            val bytes = WebBlobs.readAll(path) ?: WebFiles.read(path) ?: error("File not found: $path")
            return SourceFetchResult(ImageSource(Buffer().write(bytes), options.fileSystem), null, DataSource.DISK)
        }
        // Bluesky's picture CDN may not let web pages read its pictures
        // (no CORS headers): then the same picture is fetched from the
        // poster's own server (their PDS), which does.
        val blob = BskyCdn.parse(uri)
        if (blob != null && BskyCdn.blocked) return fromPds(blob)
        // The fast way first (see WebImageLoading): decoded and shrunk by
        // the browser.
        when (val fast = browserDecoded(BskyCdn.sized(uri, targetBox()))) {
            is Decoded.Image -> return fast.result
            Decoded.Refused -> if (blob != null) {
                if (!BskyCdn.blocked) {
                    BskyCdn.blocked = true
                    com.mediaviewer.platform.Log.w("Images", "cdn.bsky.app refused the page; loading pictures from each account's server instead")
                }
                return fromPds(blob)
            }
            Decoded.Failed -> {}
        }
        val resp = try {
            PlainHttp.get(uri)
        } catch (e: com.mediaviewer.platform.IOException) {
            if (blob == null) throw e
            if (!BskyCdn.blocked) {
                BskyCdn.blocked = true
                com.mediaviewer.platform.Log.w("Images", "cdn.bsky.app refused the page; loading pictures from each account's server instead")
            }
            return fromPds(blob)
        }
        if (resp.code !in 200..299) {
            if (blob != null) return fromPds(blob)
            error("HTTP ${resp.code}")
        }
        return SourceFetchResult(ImageSource(Buffer().write(resp.body), options.fileSystem), resp.contentType, DataSource.NETWORK)
    }

    /** The picture's original file, from the account's PDS (getBlob). */
    private suspend fun fromPds(blob: Pair<String, String>): FetchResult {
        val url = com.mediaviewer.worker.BlueskyBlobResolver.resolveBlobUrl(blob.first, blob.second)
        (browserDecoded(url) as? Decoded.Image)?.let { return it.result }
        val resp = PlainHttp.get(url)
        if (resp.code !in 200..299) error("HTTP ${resp.code}")
        return SourceFetchResult(ImageSource(Buffer().write(resp.body), options.fileSystem), resp.contentType, DataSource.NETWORK)
    }

    /** The box (in pixels) the picture is shown in; 0 where it's unknown. */
    private fun targetBox(): Pair<Int, Int> {
        val w = (options.size.width as? Dimension.Pixels)?.px ?: 0
        val h = (options.size.height as? Dimension.Pixels)?.px ?: 0
        return w to h
    }

    private sealed interface Decoded {
        class Image(val result: FetchResult) : Decoded
        /** The server answered but wouldn't let the page read it (CORS),
         *  or said no — worth trying another server. */
        data object Refused : Decoded
        /** Anything else (an old browser, an odd format): the ordinary way. */
        data object Failed : Decoded
    }

    private suspend fun browserDecoded(url: String): Decoded {
        val (w, h) = targetBox()
        val r: JsAny? = try {
            decodeInBrowser(url, w, h, options.scale == Scale.FILL, MAX_SIDE).await()
        } catch (e: Throwable) {
            // fetch() only throws when the server can't be read at all.
            return Decoded.Refused
        }
        if (r == null) return Decoded.Failed
        val status = jsGetInt(r, "status")
        if (status != 0) return if (status in 400..599) Decoded.Refused else Decoded.Failed
        val width = jsGetInt(r, "w")
        val height = jsGetInt(r, "h")
        val data = jsGet(r, "data")?.unsafeCast<JsBytes>() ?: return Decoded.Failed
        if (width <= 0 || height <= 0) return Decoded.Failed
        // (Straight into Skia's memory when possible — see SkiaPixels.)
        val bitmap = com.mediaviewer.platform.SkiaPixels.bitmap(data, width, height) ?: run {
            val b = org.jetbrains.skia.Bitmap()
            val info = org.jetbrains.skia.ImageInfo(width, height, org.jetbrains.skia.ColorType.RGBA_8888, org.jetbrains.skia.ColorAlphaType.UNPREMUL)
            if (!b.installPixels(info, data.toByteArray(), width * 4)) return Decoded.Failed
            b
        }
        // Immutable: drawn as is every frame, never copied again.
        bitmap.setImmutable()
        return Decoded.Image(ImageFetchResult(bitmap.asImage(), isSampled = jsGetInt(r, "scaled") == 1, dataSource = DataSource.NETWORK))
    }

    object Factory : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            val s = data.toString()
            return if (s.startsWith("http://") || s.startsWith("https://") || s.startsWith("file:")) WebFetcher(s, options) else null
        }
    }
}

/** Longest side a picture is ever kept at (a full-screen one included). */
private const val MAX_SIDE = 2560

/** Bluesky's picture CDN: https://cdn.bsky.app/img/<size>/plain/<did>/<cid>@<format>. */
internal object BskyCdn {
    /** [url] in the CDN's smaller size when the picture is shown small
     *  enough for it: avatars at up to 160 px use the avatar thumbnail
     *  (128 px), feed pictures at up to 1000 px the feed thumbnail. */
    fun sized(url: String, box: Pair<Int, Int>): String {
        if (!url.startsWith("https://cdn.bsky.app/img/")) return url
        val side = maxOf(box.first, box.second)
        if (side <= 0) return url
        return when {
            side <= 160 && url.contains("/img/avatar/") -> url.replace("/img/avatar/", "/img/avatar_thumbnail/")
            side <= 1000 && url.contains("/img/feed_fullsize/") -> url.replace("/img/feed_fullsize/", "/img/feed_thumbnail/")
            else -> url
        }
    }

    /** Set once the CDN has refused the page this session. */
    var blocked = false

    private val URL = Regex("^https://cdn\\.bsky\\.app/img/[^/]+/plain/([^/]+)/([^/@?#]+)")

    /** (did, cid) of a CDN picture, or null for any other address. */
    fun parse(url: String): Pair<String, String>? {
        val m = URL.find(url) ?: return null
        val did = m.groupValues[1].replace("%3A", ":").replace("%3a", ":")
        return if (did.startsWith("did:")) did to m.groupValues[2] else null
    }
}

/** The first frame of a video ([url] a blob: URL) as JPEG bytes. */
private fun videoFrame(url: String): Promise<JsBytes> = js("""new Promise((resolve) => {
    const v = document.createElement('video');
    v.muted = true; v.preload = 'auto'; v.playsInline = true;
    const fail = () => resolve(new Uint8Array(0));
    v.onerror = fail;
    setTimeout(fail, 10000);
    v.onloadeddata = () => { try { v.currentTime = Math.min(0.1, (v.duration || 1) / 2); } catch (e) { fail(); } };
    v.onseeked = () => {
        try {
            const c = document.createElement('canvas');
            c.width = v.videoWidth; c.height = v.videoHeight;
            c.getContext('2d').drawImage(v, 0, 0);
            c.toBlob((b) => b ? b.arrayBuffer().then((a) => resolve(new Uint8Array(a))) : fail(), 'image/jpeg', 0.9);
        } catch (e) { fail(); }
    };
    v.src = url;
})""")

/**
 * Fetches [url] and lets the browser decode it off the page's thread (in a
 * background worker where it can),
 * shrunk to fit ([fill] false) or cover ([fill] true) a [w]×[h] box (never
 * enlarged; 0 = any size, up to [maxSide]). Resolves to {w, h, data
 * (RGBA bytes), scaled}, {status} when the server said no, or null when
 * this browser can't do it.
 */
private fun decodeInBrowser(url: String, w: Int, h: Int, fill: Boolean, maxSide: Int): Promise<JsAny?> = js("""(() => {
    // The whole job (download, decode, shrink, read the pixels) runs in a
    // background worker when the browser has them, so the page's own thread
    // only receives finished pixels; otherwise the same steps run here.
    const job = async (url, w, h, fill, maxSide) => {
        if (typeof createImageBitmap !== 'function' || typeof OffscreenCanvas !== 'function') return null;
        const r = await fetch(url, { credentials: 'omit' });
        if (!r.ok) return { status: r.status || 1 };
        try {
            const bmp = await createImageBitmap(await r.blob());
            let tw = bmp.width, th = bmp.height;
            if (w > 0 && h > 0) {
                const s = fill ? Math.max(w / tw, h / th) : Math.min(w / tw, h / th);
                if (s < 1) { tw = Math.max(1, Math.round(tw * s)); th = Math.max(1, Math.round(th * s)); }
            } else if (w > 0 || h > 0) {
                const s = w > 0 ? w / tw : h / th;
                if (s < 1) { tw = Math.max(1, Math.round(tw * s)); th = Math.max(1, Math.round(th * s)); }
            }
            const big = Math.max(tw, th);
            if (big > maxSide) { const s = maxSide / big; tw = Math.max(1, Math.round(tw * s)); th = Math.max(1, Math.round(th * s)); }
            const scaled = (tw !== bmp.width || th !== bmp.height);
            let src = bmp;
            if (scaled) {
                try { src = await createImageBitmap(bmp, { resizeWidth: tw, resizeHeight: th, resizeQuality: 'high' }); bmp.close(); } catch (e) { src = bmp; }
            }
            const c = new OffscreenCanvas(tw, th);
            const g = c.getContext('2d');
            if (!g) { src.close(); return null; }
            g.imageSmoothingQuality = 'high';
            g.drawImage(src, 0, 0, tw, th);
            src.close();
            const px = g.getImageData(0, 0, tw, th).data;
            return { status: 0, w: tw, h: th, scaled: scaled ? 1 : 0, data: new Uint8Array(px.buffer, px.byteOffset, px.byteLength) };
        } catch (e) { return null; }
    };
    const g = globalThis;
    if (g.__stellarDecoder === undefined) {
        g.__stellarDecoder = null;
        try {
            const code = 'const job = ' + job.toString() + ';' +
                'onmessage = async (e) => { const m = e.data; let r; let failed = false;' +
                ' try { r = await job(m.url, m.w, m.h, m.fill, m.maxSide); } catch (err) { failed = true; }' +
                ' if (r && r.data) postMessage({ id: m.id, failed, r }, [r.data.buffer]); else postMessage({ id: m.id, failed, r }); };';
            const worker = new Worker(URL.createObjectURL(new Blob([code], { type: 'text/javascript' })));
            const waiting = new Map();
            let next = 1;
            worker.onmessage = (e) => {
                const m = e.data, p = waiting.get(m.id);
                if (!p) return;
                waiting.delete(m.id);
                if (m.failed) p.reject(new Error('fetch failed')); else p.resolve(m.r || null);
            };
            worker.onerror = () => { g.__stellarDecoder = null; waiting.forEach((p) => p.resolve(null)); waiting.clear(); };
            g.__stellarDecoder = (url, w, h, fill, maxSide) => new Promise((resolve, reject) => {
                const id = next++;
                waiting.set(id, { resolve, reject });
                worker.postMessage({ id, url, w, h, fill, maxSide });
            });
        } catch (e) { g.__stellarDecoder = null; }
    }
    return g.__stellarDecoder ? g.__stellarDecoder(url, w, h, fill, maxSide) : job(url, w, h, fill, maxSide);
})()""")
