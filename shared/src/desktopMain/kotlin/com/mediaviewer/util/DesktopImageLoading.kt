package com.mediaviewer.util

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.disk.DiskCache
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.map.Mapper
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.ImageRequest
import coil3.request.Options
import com.mediaviewer.network.DesktopHttpClients
import com.mediaviewer.platform.DesktopFfmpeg
import com.mediaviewer.platform.DesktopImagePreloader
import com.mediaviewer.platform.DesktopImages
import com.mediaviewer.platform.DesktopPaths
import com.mediaviewer.platform.DesktopUri
import com.mediaviewer.platform.fileOfUri
import okio.Buffer
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import java.io.File

/**
 * The app-wide Coil image loader on the desktop app — same rules as
 * Android's ImageLoading and the iOS one: a large memory cache plus a
 * 512 MB disk cache, blocked hosts never contacted, and Wikimedia asked
 * with Stellar's own User-Agent (both through the shared OkHttp client).
 */
object DesktopImageLoading {
    private var installed = false

    fun install() {
        if (installed) return
        installed = true
        SingletonImageLoader.setSafe { context ->
            ImageLoader.Builder(context)
                .components {
                    add(BlockedHostsImageInterceptor)
                    // The app's own files ("file:///C:/…" on Windows too).
                    add(LocalFileMapper)
                    add(DesktopUriMapper)
                    add(LocalFileFetcher.Factory)
                    add(OkHttpNetworkFetcherFactory(callFactory = { DesktopHttpClients.downloadClient }))
                }
                .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.25).build() }
                .diskCache {
                    DiskCache.Builder()
                        .directory(File(DesktopPaths.cacheDir, "image_cache").toOkioPath())
                        .maxSizeBytes(512L * 1024 * 1024)
                        .build()
                }
                .build()
        }
        DesktopImagePreloader.preloader = { urls ->
            val ctx = PlatformContext.INSTANCE
            val loader = SingletonImageLoader.get(ctx)
            urls.filter { it.isNotBlank() }.forEach { loader.enqueue(ImageRequest.Builder(ctx).data(it).build()) }
        }
    }
}

/** A file on this computer to show. */
class LocalImageFile(val file: File)

/** "file:…" strings → [LocalImageFile]. */
private object LocalFileMapper : Mapper<String, LocalImageFile> {
    override fun map(data: String, options: Options): LocalImageFile? =
        if (data.startsWith("file:")) fileOfUri(data)?.let { LocalImageFile(it) } else null
}

/** Picked files ([DesktopUri]) → [LocalImageFile], or their web address. */
private object DesktopUriMapper : Mapper<DesktopUri, Any> {
    override fun map(data: DesktopUri, options: Options): Any =
        if (data.urlString.startsWith("file:")) fileOfUri(data.urlString)?.let { LocalImageFile(it) } ?: data.urlString else data.urlString
}

/** Reads a [LocalImageFile]; for a video, its first frame. */
private class LocalFileFetcher(private val data: LocalImageFile) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val f = data.file
        val ext = f.extension.lowercase()
        if (ext in VIDEO_EXTENSIONS) {
            val png = videoFrame(f) ?: error("Couldn't read the video")
            return SourceFetchResult(ImageSource(Buffer().write(png), FileSystem.SYSTEM), "image/png", DataSource.DISK)
        }
        return SourceFetchResult(ImageSource(f.toOkioPath(), FileSystem.SYSTEM), null, DataSource.DISK)
    }

    private fun videoFrame(f: File): ByteArray? = runCatching {
        var png: ByteArray? = null
        DesktopFfmpeg.frames(f.absolutePath, maxFps = 1.0, maxSeconds = 0.5, maxSide = 1280) { px, w, h, _ ->
            if (png == null) {
                val bmp = org.jetbrains.skia.Bitmap()
                bmp.allocPixels(org.jetbrains.skia.ImageInfo.makeN32(w, h, org.jetbrains.skia.ColorAlphaType.OPAQUE))
                val bytes = ByteArray(w * h * 4)
                java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).asIntBuffer().put(px)
                bmp.installPixels(bytes)
                png = DesktopImages.png(org.jetbrains.skia.Image.makeFromBitmap(bmp))
            }
        }
        png
    }.getOrNull()

    object Factory : Fetcher.Factory<LocalImageFile> {
        override fun create(data: LocalImageFile, options: Options, imageLoader: ImageLoader): Fetcher = LocalFileFetcher(data)
    }

    companion object {
        val VIDEO_EXTENSIONS = setOf("mp4", "m4v", "mov", "webm", "mkv", "avi")
    }
}
