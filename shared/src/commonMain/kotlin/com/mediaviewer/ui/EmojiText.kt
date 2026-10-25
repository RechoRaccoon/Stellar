package com.mediaviewer.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.mediaviewer.util.EmojiCloud
import com.mediaviewer.util.EmojiStore
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Custom emoji drawn over a text field: each emoji is one private-use
 * character the field shows as a blank slot (see [emojiVisualTransformation]);
 * this puts its picture right there. Place it in the same Box as the field,
 * after it. [layout] is the field's last text layout.
 *
 * Timeline-sized (1.75em of the field's font), centred on the slot
 * sideways and on the LINE vertically — the slot glyph's own box sits
 * wherever the fallback font puts it, which left pictures small and high
 * (quote) or at uneven heights (composer). The line's box is
 * font-independent, so every emoji on a line shares one centre, lifted
 * a little like the timeline's inline emoji.
 */
@Composable
internal fun EmojiFieldOverlay(text: String, layout: TextLayoutResult?, store: EmojiStore) {
    val l = layout ?: return
    if (l.layoutInput.text.length != text.length) return
    val density = LocalDensity.current
    val fontPx = with(density) { l.layoutInput.style.fontSize.toPx() }
    text.forEachIndexed { i, c ->
        val bmp = store.imageBitmapForChar(c) ?: return@forEachIndexed
        val box = l.getBoundingBox(i)
        val side = if (fontPx > 0f && fontPx < Float.POSITIVE_INFINITY) fontPx * 1.75f
            else min(box.width, box.height) * 0.92f
        if (side <= 0f) return@forEachIndexed
        val line = l.getLineForOffset(i)
        val lineCenter = (l.getLineTop(line) + l.getLineBottom(line)) / 2f
        Image(
            bitmap = bmp, contentDescription = null, contentScale = ContentScale.Fit,
            modifier = Modifier
                .offset { IntOffset((box.left + (box.width - side) / 2f).roundToInt(), (lineCenter - side / 2f - side * 0.1f).roundToInt()) }
                .size(with(density) { side.toDp() })
        )
    }
}

/**
 * A DM's text with its custom emoji (Stellar's emoji facets, see
 * [EmojiCloud]) drawn as pictures in place of their ":name:". A message
 * that's nothing but emoji shows them bigger. Messages without any are
 * plain text.
 */
@Composable
internal fun DmMessageText(
    text: String,
    facets: List<kotlinx.serialization.json.JsonElement>?,
    color: Color,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    // Posts and comments reuse this for their own text (see PostTextBubble):
    // the same knobs a plain Text call there would have.
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    overflow: androidx.compose.ui.text.style.TextOverflow = androidx.compose.ui.text.style.TextOverflow.Clip,
    textAlign: androidx.compose.ui.text.style.TextAlign = androidx.compose.ui.text.style.TextAlign.Unspecified,
    fontWeight: androidx.compose.ui.text.font.FontWeight? = null
) {
    val spans = remember(text, facets) { EmojiCloud.emojiSpans(text, facets) }
    if (spans.isEmpty()) {
        // (Someone's own words: never translated.)
        androidx.compose.material3.Text(text, color = color, fontSize = fontSize, lineHeight = lineHeight,
            modifier = modifier, maxLines = maxLines, overflow = overflow, textAlign = textAlign, fontWeight = fontWeight)
        return
    }
    val onlyEmoji = remember(text, spans) { EmojiCloud.isOnlyEmoji(text, spans) }
    // Discord-sized: about a line and three quarters tall among words, and
    // big ("jumbo") when the message is only emoji.
    val side = if (onlyEmoji) JUMBO_EM.em else INLINE_EM.em
    val annotated = remember(text, spans) {
        buildAnnotatedString {
            var at = 0
            spans.forEachIndexed { i, s ->
                append(text.substring(at, s.start))
                appendInlineContent("emoji$i", text.substring(s.start, s.end))
                at = s.end
            }
            append(text.substring(at))
        }
    }
    val inline = remember(spans, side, onlyEmoji) {
        spans.mapIndexed { i, s ->
            "emoji$i" to InlineTextContent(Placeholder(side, side, PlaceholderVerticalAlign.TextCenter)) {
                // (Animated emoji move, and loop.) Among words, lifted a
                // little: "text center" counts the font's deep descent, which
                // left them sitting low next to the letters.
                RemoteEmojiPicture(s.did, s.cid, s.animated, s.name, Modifier.fillMaxSize()
                    .then(if (onlyEmoji) Modifier else Modifier.graphicsLayer { translationY = -size.height * 0.1f }))
            }
        }.toMap()
    }
    androidx.compose.material3.Text(
        annotated, color = color, fontSize = fontSize,
        // Only emoji: the line is exactly as tall as they are (no space
        // above or below), so the bubble's padding is all there is.
        lineHeight = if (onlyEmoji) (fontSize.value * JUMBO_EM).sp else maxOf(lineHeight.value, fontSize.value * INLINE_EM).sp,
        style = if (onlyEmoji) androidx.compose.material3.LocalTextStyle.current.copy(
            lineHeightStyle = androidx.compose.ui.text.style.LineHeightStyle(
                androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center,
                androidx.compose.ui.text.style.LineHeightStyle.Trim.Both
            )
        ) else androidx.compose.material3.LocalTextStyle.current,
        inlineContent = inline,
        modifier = modifier, maxLines = maxLines, overflow = overflow, textAlign = textAlign, fontWeight = fontWeight
    )
}

/** Custom emoji in a DM, in ems of the text: among words, and alone. */
private const val INLINE_EM = 1.75f
private const val JUMBO_EM = 3.6f
