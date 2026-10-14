@file:JsModule("./skiko.mjs")

package com.mediaviewer.platform

/** Skia's own web module (the one Compose draws with): its memory is where
 *  pictures live, see [SkiaPixels]. */
internal external val loadedWasm: JsAny
