package com.mediaviewer.ui.compat

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFRelease
import platform.CoreGraphics.CGColorRenderingIntent
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGDataProviderCreateWithCFData
import platform.CoreGraphics.CGDataProviderRelease
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageCreate
import platform.CoreGraphics.CGImageRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.create
import platform.UIKit.UIImage

/**
 * A picture Compose drew, as the kind UIKit and video encoders take. Used
 * for what's captured over VRM mode's avatar (scene cards, effects). The
 * pixels are copied, so the result stands on its own.
 */
@OptIn(ExperimentalForeignApi::class)
fun ImageBitmap.toUIImage(): UIImage? = runCatching {
    val skia = asSkiaBitmap()
    val w = skia.width
    val h = skia.height
    if (w <= 0 || h <= 0) return@runCatching null
    // Asked for as plain red-green-blue-alpha bytes, whatever Compose
    // keeps them as inside.
    val bytes = skia.readPixels(ImageInfo(w, h, ColorType.RGBA_8888, ColorAlphaType.PREMUL), w * 4, 0, 0) ?: return@runCatching null
    val data = bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }
    val cfData = CFBridgingRetain(data) ?: return@runCatching null
    val provider = CGDataProviderCreateWithCFData(cfData.reinterpret<cnames.structs.__CFData>() as CFDataRef)
    CFRelease(cfData)
    val space = CGColorSpaceCreateDeviceRGB()
    val cg = CGImageCreate(
        w.toULong(), h.toULong(), 8u, 32u, (w * 4).toULong(), space,
        CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value, provider, null, false,
        CGColorRenderingIntent.kCGRenderingIntentDefault
    )
    CGColorSpaceRelease(space)
    CGDataProviderRelease(provider)
    if (cg == null) return@runCatching null
    val image = UIImage.imageWithCGImage(cg)
    CGImageRelease(cg)
    image
}.getOrNull()
