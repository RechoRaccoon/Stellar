package com.mediaviewer.util

import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import org.jetbrains.skia.paragraph.Alignment
import org.jetbrains.skia.paragraph.BaselineMode
import org.jetbrains.skia.paragraph.FontCollection
import org.jetbrains.skia.paragraph.Paragraph
import org.jetbrains.skia.paragraph.ParagraphBuilder
import org.jetbrains.skia.paragraph.ParagraphStyle
import org.jetbrains.skia.paragraph.PlaceholderAlignment
import org.jetbrains.skia.paragraph.PlaceholderStyle
import org.jetbrains.skia.paragraph.TextStyle
import kotlin.math.max
import kotlin.math.min

/**
 * Textshot mode's picture on iOS — black background, white text grown to
 * fill the square, custom emoji drawn inline — following the same rules as
 * the Android renderer:
 *  - whole words wrap whole (only a word longer than 25 characters may
 *    split), no hyphenation, centred lines;
 *  - the text's actual ink is what fills the frame (minus a small pad), so
 *    a single letter fills the square edge to edge.
 * The live preview and the posted picture both come from here.
 *
 * How the ink is fitted: the largest font size whose lines fit is found
 * first; the text is then drawn once, small, just to find where its ink
 * really is, and the final picture is drawn scaled so that ink fills the
 * frame. (Text is drawn as shapes, so the scaled result is still sharp.)
 */
object IosTextshotRenderer {
    private const val LINE_SPACING = 1.15f
    private const val MIN_TEXT_SIZE = 20f
    /** The side of the small picture used only to find the ink. */
    private const val SCAN_SIDE = 360

    private val fonts by lazy { FontCollection().setDefaultFontManager(FontMgr.default) }

    fun render(text: String, sizePx: Int = 1080, emoji: ((Char) -> Image?)? = null): Image {
        val surface = Surface.makeRasterN32Premul(sizePx, sizePx)
        val canvas = surface.canvas
        canvas.clear(0xFF000000.toInt())
        if (text.isBlank()) return surface.makeImageSnapshot()

        val pad = sizePx * com.mediaviewer.ui.TEXTSHOT_PAD_FRACTION
        val maxW = sizePx - 2 * pad
        val maxH = sizePx - 2 * pad

        // Which characters are emoji with a picture (in text order).
        val pictures = LinkedHashMap<Int, Image>()
        if (emoji != null) {
            for (i in text.indices) if (EmojiStore.isTokenChar(text[i])) emoji(text[i])?.let { pictures[i] = it }
        }

        fun build(size: Float): Paragraph {
            val style = ParagraphStyle()
            style.alignment = Alignment.CENTER
            val builder = ParagraphBuilder(style, fonts)
            builder.pushStyle(TextStyle().setColor(0xFFFFFFFF.toInt()).setFontSize(size).setHeight(LINE_SPACING))
            val run = StringBuilder()
            for (i in text.indices) {
                if (pictures.containsKey(i)) {
                    if (run.isNotEmpty()) { builder.addText(run.toString()); run.clear() }
                    // A square as tall as the line, like a normal emoji.
                    builder.addPlaceholder(PlaceholderStyle(size * 1.2f, size * 1.2f, PlaceholderAlignment.MIDDLE, BaselineMode.ALPHABETIC, 0f))
                } else run.append(text[i])
            }
            if (run.isNotEmpty()) builder.addText(run.toString())
            return builder.build().layout(maxW)
        }

        fun fits(size: Float): Boolean {
            val p = build(size)
            // A normal word (25 characters or fewer) must never be split.
            val lines = p.lineMetrics
            for (i in 0 until lines.size - 1) {
                val b = lines[i].endIndex.toInt()
                if (b <= 0 || b >= text.length) continue
                if (pictures.containsKey(b - 1) || pictures.containsKey(b)) continue
                if (!text[b - 1].isWhitespace() && !text[b].isWhitespace()) {
                    var s = b - 1
                    while (s > 0 && !text[s - 1].isWhitespace()) s--
                    var e = b
                    while (e < text.length && !text[e].isWhitespace()) e++
                    if (e - s <= 25) return false
                }
            }
            return p.longestLine <= maxW + 0.5f && p.height <= maxH
        }

        var lo = MIN_TEXT_SIZE
        var hi = sizePx * 2f
        repeat(18) {
            val mid = (lo + hi) / 2f
            if (fits(mid)) lo = mid else hi = mid
        }

        val paragraph = build(lo)
        val top = (sizePx - paragraph.height) / 2f

        fun paint(target: Canvas) {
            paragraph.paint(target, pad, top)
            val boxes = paragraph.rectsForPlaceholders
            var k = 0
            for ((_, picture) in pictures) {
                val box = boxes.getOrNull(k++)?.rect ?: continue
                if (picture.width <= 0 || picture.height <= 0) continue
                val scale = min(box.width / picture.width, box.height / picture.height)
                val w = picture.width * scale
                val h = picture.height * scale
                val cx = pad + (box.left + box.right) / 2f
                val cy = top + (box.top + box.bottom) / 2f
                target.drawImageRect(picture, Rect.makeLTRB(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f))
            }
        }

        // Where the ink really is: drawn small on a clear picture and read back.
        val ink = runCatching {
            val scan = Surface.makeRasterN32Premul(SCAN_SIDE, SCAN_SIDE)
            val k = SCAN_SIDE / sizePx.toFloat()
            scan.canvas.clear(0)
            scan.canvas.scale(k, k)
            paint(scan.canvas)
            val bitmap = Bitmap()
            bitmap.allocN32Pixels(SCAN_SIDE, SCAN_SIDE)
            if (!scan.readPixels(bitmap, 0, 0)) return@runCatching null
            val pixels = bitmap.readPixels() ?: return@runCatching null
            var minX = SCAN_SIDE; var minY = SCAN_SIDE; var maxX = -1; var maxY = -1
            for (y in 0 until SCAN_SIDE) {
                val row = y * SCAN_SIDE * 4
                for (x in 0 until SCAN_SIDE) {
                    // (The fourth byte of each pixel is how solid it is.)
                    if ((pixels[row + x * 4 + 3].toInt() and 0xFF) > 24) {
                        if (x < minX) minX = x
                        if (x > maxX) maxX = x
                        if (y < minY) minY = y
                        if (y > maxY) maxY = y
                    }
                }
            }
            if (maxX < minX || maxY < minY) null
            else Rect.makeLTRB(max(0f, (minX - 1) / k), max(0f, (minY - 1) / k), min(sizePx.toFloat(), (maxX + 2) / k), min(sizePx.toFloat(), (maxY + 2) / k))
        }.getOrNull()

        if (ink == null || ink.width <= 0f || ink.height <= 0f) {
            paint(canvas)
            return surface.makeImageSnapshot()
        }
        // The ink, scaled to fill the frame and centred on both axes.
        val grow = min(maxW / ink.width, maxH / ink.height).coerceIn(0.5f, 12f)
        canvas.save()
        canvas.translate(sizePx / 2f, sizePx / 2f)
        canvas.scale(grow, grow)
        canvas.translate(-(ink.left + ink.right) / 2f, -(ink.top + ink.bottom) / 2f)
        paint(canvas)
        canvas.restore()
        return surface.makeImageSnapshot()
    }
}
