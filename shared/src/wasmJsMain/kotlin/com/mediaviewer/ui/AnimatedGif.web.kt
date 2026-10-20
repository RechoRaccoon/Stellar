package com.mediaviewer.ui

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import com.mediaviewer.network.PlainHttp
import com.mediaviewer.network.isSuccessful
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data

/** Decoded GIFs this session (a comment scrolled away and back plays at
 *  once). Small: a few recent GIFs only. */
private val gifCache = LinkedHashMap<String, List<Pair<ImageBitmap, Int>>>()

@Composable
actual fun AnimatedGifImage(url: String, contentDescription: String?, modifier: Modifier, contentScale: ContentScale) {
    var frames by remember(url) { mutableStateOf(com.mediaviewer.platform.synchronizedCompat(gifCache) { gifCache[url] }) }
    LaunchedEffect(url) {
        if (frames != null) return@LaunchedEffect
        val decoded = withContext(Dispatchers.Default) {
            runCatching {
                val resp = PlainHttp.get(url)
                if (!resp.isSuccessful) return@runCatching null
                decodeGifFrames(resp.body, 90, 6_000_000L)
            }.getOrNull()
        }
        if (decoded != null && decoded.isNotEmpty()) {
            com.mediaviewer.platform.synchronizedCompat(gifCache) {
                gifCache[url] = decoded
                while (gifCache.size > 6) gifCache.remove(gifCache.keys.first())
            }
            frames = decoded
        }
    }
    val f = frames
    if (f.isNullOrEmpty()) {
        // Until it's decoded (or if it can't be): the still picture.
        AsyncImage(model = url, contentDescription = contentDescription, modifier = modifier, contentScale = contentScale)
        return
    }
    var index by remember(f) { mutableIntStateOf(0) }
    if (f.size > 1) LaunchedEffect(f) {
        while (true) {
            delay(f[index].second.toLong())
            index = (index + 1) % f.size
        }
    }
    Image(bitmap = f[index.coerceIn(0, f.lastIndex)].first, contentDescription = contentDescription, modifier = modifier, contentScale = contentScale)
}

/** Small decoded GIFs (custom emoji) by key: many of them can be on screen
 *  at once, so this holds more than [gifCache]. */
private val bytesGifCache = LinkedHashMap<String, List<Pair<ImageBitmap, Int>>>()

@Composable
actual fun AnimatedGifBytes(key: String, load: suspend () -> ByteArray?, contentDescription: String?, modifier: Modifier, contentScale: ContentScale) {
    var frames by remember(key) { mutableStateOf(com.mediaviewer.platform.synchronizedCompat(bytesGifCache) { bytesGifCache[key] }) }
    LaunchedEffect(key) {
        if (frames != null) return@LaunchedEffect
        val bytes = runCatching { load() }.getOrNull() ?: return@LaunchedEffect
        val decoded = withContext(Dispatchers.Default) {
            runCatching {
                decodeGifFrames(bytes, 150, 400_000L)
            }.getOrNull()
        }
        if (decoded != null && decoded.isNotEmpty()) {
            com.mediaviewer.platform.synchronizedCompat(bytesGifCache) {
                bytesGifCache[key] = decoded
                while (bytesGifCache.size > 48) bytesGifCache.remove(bytesGifCache.keys.first())
            }
            frames = decoded
        }
    }
    val f = frames
    if (f.isNullOrEmpty()) return
    var index by remember(f) { mutableIntStateOf(0) }
    if (f.size > 1) LaunchedEffect(f) {
        while (true) {
            delay(f[index].second.toLong())
            index = (index + 1) % f.size
        }
    }
    Image(bitmap = f[index.coerceIn(0, f.lastIndex)].first, contentDescription = contentDescription, modifier = modifier, contentScale = contentScale)
}


/** At most [maxPixels] pixels of decoded frames for one GIF: a long or big
 *  one keeps every n-th frame (each shown for the frames it stands for),
 *  so a page full of them can't run the browser out of memory. */
internal fun gifFrameStep(count: Int, width: Int, height: Int, maxPixels: Long): Int {
    val perFrame = (width.toLong() * height).coerceAtLeast(1)
    val fit = (maxPixels / perFrame).coerceAtLeast(1)
    return ((count + fit - 1) / fit).toInt().coerceAtLeast(1)
}

internal fun decodeGifFrames(bytes: ByteArray, maxFrames: Int, maxPixels: Long): List<Pair<ImageBitmap, Int>> {
    val data = Data.makeFromBytes(bytes)
    val codec = Codec.makeFromData(data)
    try {
        val count = codec.frameCount.coerceIn(1, maxFrames)
        val infos = codec.framesInfo
        val info = codec.imageInfo
        val step = gifFrameStep(count, info.width, info.height, maxPixels)
        return (0 until count step step).map { i ->
            val bmp = Bitmap()
            bmp.allocPixels(info)
            codec.readPixels(bmp, i)
            // (Frames with no delay play at the usual 10 fps.)
            var ms = 0
            for (j in i until minOf(count, i + step)) ms += (infos.getOrNull(j)?.duration ?: 100).let { if (it < 20) 100 else it }
            bmp.setImmutable()
            bmp.asComposeImageBitmap() to ms
        }
    } finally {
        codec.close()
        data.close()
    }
}
