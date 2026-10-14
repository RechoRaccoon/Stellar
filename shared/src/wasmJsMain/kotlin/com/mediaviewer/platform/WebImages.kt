package com.mediaviewer.platform

import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface

/**
 * Picture work on the web, with Skia (the engine Compose draws with, the
 * same as the desktop app's): decoding, turning upright, scaling and
 * encoding.
 */
object WebImages {
    private val SMOOTH = FilterMipmap(FilterMode.LINEAR, MipmapMode.LINEAR)

    fun decode(bytes: ByteArray): Image? = runCatching { Image.makeFromEncoded(bytes) }.getOrNull()

    /** [image] drawn at [w]×[h] (transparency kept, or on [background]). */
    fun draw(image: Image, w: Int, h: Int, background: Int? = null): Image {
        val surface = Surface.makeRasterN32Premul(w, h)
        val c = surface.canvas
        c.clear(background ?: 0)
        c.drawImageRect(image, Rect.makeWH(image.width.toFloat(), image.height.toFloat()), Rect.makeWH(w.toFloat(), h.toFloat()), SMOOTH, Paint(), true)
        return surface.makeImageSnapshot()
    }

    fun jpeg(image: Image, quality: Int): ByteArray {
        // (JPEG has no transparency: see-through areas become white, not black.)
        val flat = if (image.imageInfo.colorAlphaType == ColorAlphaType.OPAQUE) image else draw(image, image.width, image.height, background = -1)
        return flat.encodeToData(EncodedImageFormat.JPEG, quality)?.bytes ?: error("Couldn't encode image")
    }

    fun png(image: Image): ByteArray = image.encodeToData(EncodedImageFormat.PNG)?.bytes ?: error("Couldn't encode image")

    /** True when the picture has see-through pixels worth keeping. */
    fun hasAlpha(image: Image): Boolean {
        if (image.imageInfo.colorAlphaType == ColorAlphaType.OPAQUE) return false
        val bmp = org.jetbrains.skia.Bitmap()
        val small = draw(image, minOf(image.width, 256), minOf(image.height, 256))
        bmp.allocPixels(ImageInfo(small.width, small.height, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL))
        if (!small.readPixels(bmp)) return true
        val px = bmp.readPixels() ?: return true
        var i = 3
        while (i < px.size) { if ((px[i].toInt() and 0xFF) < 250) return true; i += 4 }
        return false
    }
}
