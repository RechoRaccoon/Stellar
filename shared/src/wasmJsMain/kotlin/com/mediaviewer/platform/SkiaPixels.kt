package com.mediaviewer.platform

/**
 * Pixels from the browser into Skia, in one copy. Skia's own way in (a
 * ByteArray) crosses into JavaScript once per byte — millions of calls for
 * each picture, all on the page's only thread; here the browser's pixels
 * are written straight into Skia's memory instead.
 */
internal object SkiaPixels {
    /** False once the direct copy has failed (then the slow way is used). */
    private var works = true

    /** An RGBA [width]×[height] picture from [rgba] (straight, not
     *  premultiplied), or null when this can't be done. */
    fun bitmap(rgba: JsBytes, width: Int, height: Int): org.jetbrains.skia.Bitmap? {
        if (!works) return null
        return runCatching {
            val length = width * height * 4
            val data = org.jetbrains.skia.Data.makeUninitialized(length)
            try {
                copyInto(loadedWasm, rgba, data.writableData(), length)
                val info = org.jetbrains.skia.ImageInfo(width, height, org.jetbrains.skia.ColorType.RGBA_8888, org.jetbrains.skia.ColorAlphaType.UNPREMUL)
                val image = org.jetbrains.skia.Image.makeRaster(info, data, width * 4)
                try { org.jetbrains.skia.Bitmap.makeFromImage(image) } finally { image.close() }
            } finally {
                data.close()
            }
        }.onFailure { works = false; Log.w("Images", "Direct pixel copy failed; using the slow way: ${it.message}") }.getOrNull()
    }
}

private fun copyInto(module: JsAny, src: JsBytes, address: Int, length: Int): Unit = js("""{
    const heap = module.HEAPU8;
    if (!heap || src.length < length) throw new Error('no heap');
    heap.set(src.length === length ? src : src.subarray(0, length), address);
}""")
