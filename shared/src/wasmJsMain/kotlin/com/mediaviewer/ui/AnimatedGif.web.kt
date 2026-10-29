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
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Data
import org.jetbrains.skia.ImageInfo
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.mediaviewer.platform.JsBytes
import com.mediaviewer.platform.jsGet
import com.mediaviewer.platform.jsGetInt
import com.mediaviewer.platform.jsGetString
import com.mediaviewer.platform.toByteArray
import com.mediaviewer.platform.toJsBytes
import kotlinx.coroutines.await

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
                decodeAnimatedFrames(resp.body, 90, 6_000_000L)
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
                decodeAnimatedFrames(bytes, 150, 600_000L)
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


/**
 * Frames of an animated picture. An animated WebP goes through the
 * browser's own decoder (the Skia build running here doesn't hand out its
 * frames, which left every animated WebP emoji standing still); anything
 * else, or a WebP the browser couldn't take apart, goes through Skia.
 */
internal suspend fun decodeAnimatedFrames(bytes: ByteArray, maxFrames: Int, maxPixels: Long): List<Pair<ImageBitmap, Int>> {
    if (com.mediaviewer.util.EmojiMedia.isAnimatedWebP(bytes)) {
        decodeWebPInBrowser(bytes, maxFrames, maxPixels)?.let { return it }
    }
    return decodeGifFrames(bytes, maxFrames, maxPixels)
}

private suspend fun decodeWebPInBrowser(bytes: ByteArray, maxFrames: Int, maxPixels: Long): List<Pair<ImageBitmap, Int>>? {
    val res = runCatching { webpFramesInBrowser(bytes.toJsBytes(), maxFrames, maxPixels.toDouble()).await<JsAny?>() }.getOrNull() ?: return null
    val w = jsGetInt(res, "w")
    val h = jsGetInt(res, "h")
    val delays = jsGetString(res, "delays")?.split(',')?.mapNotNull { it.toIntOrNull() } ?: return null
    val frames = jsGet(res, "frames") ?: return null
    if (w <= 0 || h <= 0 || delays.isEmpty()) return null
    val out = ArrayList<Pair<ImageBitmap, Int>>(delays.size)
    for (i in delays.indices) {
        val px = runCatching { jsArrayAt(frames, i).unsafeCast<JsBytes>().toByteArray() }.getOrNull() ?: return null
        if (px.size < w * h * 4) return null
        val image = runCatching {
            org.jetbrains.skia.Image.makeRaster(ImageInfo(w, h, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL), px, w * 4).toComposeImageBitmap()
        }.getOrNull() ?: return null
        out += image to delays[i]
    }
    return out.takeIf { it.isNotEmpty() }
}

private fun jsArrayAt(a: JsAny, i: Int): JsAny = js("a[i]")

/**
 * Takes an animated WebP apart in the browser: each frame (an ANMF chunk)
 * is wrapped as a WebP file of its own, decoded by the browser, and
 * drawn onto a canvas the size of the whole picture with the file's own
 * offset, blend and dispose rules; the canvas after each frame is one
 * picture. Resolves to {w, h, delays: "ms,ms,…", frames: [RGBA bytes]} —
 * every n-th frame when there are too many pixels to keep them all (each
 * shown for the frames it stands for) — or null when it can't be done.
 */
private fun webpFramesInBrowser(bytes: JsBytes, maxFrames: Int, maxPixels: Double): kotlin.js.Promise<JsAny?> = js("""(async () => {
    try {
        const b = bytes;
        const u24 = (o) => b[o] | (b[o + 1] << 8) | (b[o + 2] << 16);
        const u32 = (o) => (b[o] | (b[o + 1] << 8) | (b[o + 2] << 16) | (b[o + 3] << 24)) >>> 0;
        const tag = (o) => String.fromCharCode(b[o], b[o + 1], b[o + 2], b[o + 3]);
        if (b.length < 30 || tag(0) !== 'RIFF' || tag(8) !== 'WEBP') return null;
        let W = 0, H = 0;
        const list = [];
        let o = 12;
        while (o + 8 <= b.length) {
            const t = tag(o), size = u32(o + 4), body = o + 8;
            if (body + size > b.length) break;
            if (t === 'VP8X') { W = u24(body + 4) + 1; H = u24(body + 7) + 1; }
            else if (t === 'ANMF') {
                list.push({
                    x: u24(body) * 2, y: u24(body + 3) * 2, w: u24(body + 6) + 1, h: u24(body + 9) + 1,
                    d: u24(body + 12), flags: b[body + 15], start: body + 16, end: body + size
                });
            }
            o = body + size + (size & 1);
        }
        if (!W || !H || list.length < 2 || W * H > 16000000) return null;
        const count = Math.min(list.length, Math.max(1, maxFrames));
        const fit = Math.max(1, Math.floor(maxPixels / (W * H)));
        const step = Math.max(1, Math.ceil(count / fit));
        const canvas = document.createElement('canvas');
        canvas.width = W; canvas.height = H;
        const ctx = canvas.getContext('2d', { willReadFrequently: true });
        const frames = [];
        const delays = [];
        let prev = null;
        for (let i = 0; i < count; i++) {
            const f = list[i];
            // (The frame before it, if it asked to be wiped when done.)
            if (prev && (prev.flags & 1)) ctx.clearRect(prev.x, prev.y, prev.w, prev.h);
            const payload = b.subarray(f.start, f.end);
            const file = new Uint8Array(30 + payload.length);
            const dv = new DataView(file.buffer);
            file.set([0x52, 0x49, 0x46, 0x46], 0);
            dv.setUint32(4, 22 + payload.length, true);
            file.set([0x57, 0x45, 0x42, 0x50, 0x56, 0x50, 0x38, 0x58], 8);
            dv.setUint32(16, 10, true);
            file[20] = 0x10;
            file[24] = (f.w - 1) & 255; file[25] = ((f.w - 1) >> 8) & 255; file[26] = ((f.w - 1) >> 16) & 255;
            file[27] = (f.h - 1) & 255; file[28] = ((f.h - 1) >> 8) & 255; file[29] = ((f.h - 1) >> 16) & 255;
            file.set(payload, 30);
            const bmp = await createImageBitmap(new Blob([file], { type: 'image/webp' }));
            // (Bit 1 set: no blending, the frame replaces what's under it.)
            if (f.flags & 2) ctx.clearRect(f.x, f.y, f.w, f.h);
            ctx.drawImage(bmp, f.x, f.y);
            if (bmp.close) bmp.close();
            const ms = f.d < 20 ? 100 : f.d;
            if (i % step === 0) {
                const d = ctx.getImageData(0, 0, W, H).data;
                frames.push(new Uint8Array(d.buffer, d.byteOffset, d.byteLength));
                delays.push(0);
            }
            delays[delays.length - 1] += ms;
            prev = f;
        }
        return { w: W, h: H, delays: delays.join(','), frames: frames };
    } catch (e) { return null; }
})()""")
