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
                val codec = Codec.makeFromData(Data.makeFromBytes(resp.body))
                val count = codec.frameCount.coerceIn(1, 90)
                val infos = codec.framesInfo
                (0 until count).map { i ->
                    val bmp = Bitmap()
                    bmp.allocPixels(codec.imageInfo)
                    codec.readPixels(bmp, i)
                    // (Frames with no delay play at the usual 10 fps.)
                    val ms = infos.getOrNull(i)?.duration ?: 100
                    bmp.asComposeImageBitmap() to (if (ms < 20) 100 else ms)
                }
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
                val codec = Codec.makeFromData(Data.makeFromBytes(bytes))
                val count = codec.frameCount.coerceIn(1, 150)
                val infos = codec.framesInfo
                (0 until count).map { i ->
                    val bmp = Bitmap()
                    bmp.allocPixels(codec.imageInfo)
                    codec.readPixels(bmp, i)
                    val ms = infos.getOrNull(i)?.duration ?: 100
                    bmp.asComposeImageBitmap() to (if (ms < 20) 100 else ms)
                }
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
