package com.mediaviewer.util

import com.mediaviewer.network.PlainHttp
import com.mediaviewer.network.isSuccessful
import com.mediaviewer.platform.Log
import org.jetbrains.skia.Data
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.Typeface
import org.jetbrains.skia.paragraph.FontCollection
import org.jetbrains.skia.paragraph.TypefaceFontProviderWithFallback

/**
 * Fonts on the web. A browser doesn't let a page draw with the computer's
 * own fonts, so the text Compose's built-in font can't show — emoji,
 * Japanese, Korean, Chinese — needs fonts Stellar brings itself. They're
 * big (several MB each), so they come from the site's fonts/ folder only
 * when needed: emoji soon after starting, the CJK font right away when the
 * app is in Japanese, Korean or Chinese, otherwise a little later. The
 * browser keeps them cached after the first visit.
 */
object WebFonts {
    class WebFont(val name: String, val file: String)

    val LATIN = WebFont("Noto Sans", "NotoSans-Regular.ttf")
    val LATIN_BOLD = WebFont("Noto Sans Bold", "NotoSans-Bold.ttf")
    val EMOJI = WebFont("Noto Color Emoji", "NotoColorEmoji.ttf")
    private val CJK = mapOf(
        "ja" to WebFont("Noto Sans CJK JP", "NotoSansCJKjp-Regular.otf"),
        "ko" to WebFont("Noto Sans CJK KR", "NotoSansCJKkr-Regular.otf"),
        "zh" to WebFont("Noto Sans CJK SC", "NotoSansCJKsc-Regular.otf"),
    )

    /** The CJK font that suits [languageCode] (Japanese's when it's none of the three). */
    fun cjkFor(languageCode: String): WebFont = CJK[languageCode.substringBefore('-').lowercase()] ?: CJK.getValue("ja")

    fun isCjk(languageCode: String): Boolean = languageCode.substringBefore('-').lowercase() in CJK

    private val provider = TypefaceFontProviderWithFallback()
    private var loaded = LinkedHashMap<String, ByteArray>()
    private var fontCollection: FontCollection? = null

    /** Downloads [font] (once); its bytes, or null if it isn't there. */
    suspend fun fetch(font: WebFont): ByteArray? {
        loaded[font.file]?.let { return it }
        val bytes = runCatching {
            val resp = PlainHttp.get("fonts/" + font.file)
            if (resp.isSuccessful && resp.body.size > 1024) resp.body else null
        }.getOrNull()
        if (bytes == null) {
            Log.w("WebFonts", "Couldn't load fonts/${font.file}")
            return null
        }
        loaded[font.file] = bytes
        runCatching {
            val face: Typeface? = FontMgr.default.makeFromData(Data.makeFromBytes(bytes))
            // (Registered for its name and as a fallback for any text.)
            if (face != null) provider.registerTypeface(face, font.name)
            fontCollection = null
        }
        return bytes
    }

    /** Every loaded font, for drawing outside Compose (Textshot). */
    fun collection(): FontCollection = fontCollection ?: FontCollection().apply {
        setDefaultFontManager(provider, LATIN.name)
        setEnableFallback(true)
    }.also { fontCollection = it }
}
