package com.mediaviewer.util

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.Uri
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.memory.MemoryCache
import coil3.request.ImageRequest
import coil3.request.Options
import com.mediaviewer.network.PlainHttp
import com.mediaviewer.platform.JsBytes
import com.mediaviewer.platform.WebBlobs
import com.mediaviewer.platform.WebFiles
import com.mediaviewer.platform.WebImagePreloader
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
        val resp = PlainHttp.get(url)
        if (resp.code !in 200..299) error("HTTP ${resp.code}")
        return SourceFetchResult(ImageSource(Buffer().write(resp.body), options.fileSystem), resp.contentType, DataSource.NETWORK)
    }

    object Factory : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            val s = data.toString()
            return if (s.startsWith("http://") || s.startsWith("https://") || s.startsWith("file:")) WebFetcher(s, options) else null
        }
    }
}

/** Bluesky's picture CDN: https://cdn.bsky.app/img/<size>/plain/<did>/<cid>@<format>. */
private object BskyCdn {
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
