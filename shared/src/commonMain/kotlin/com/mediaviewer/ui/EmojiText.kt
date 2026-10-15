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
 */
@Composable
internal fun EmojiFieldOverlay(text: String, layout: TextLayoutResult?, store: EmojiStore) {
    val l = layout ?: return
    if (l.layoutInput.text.length != text.length) return
    val density = LocalDensity.current
    text.forEachIndexed { i, c ->
        val bmp = store.imageBitmapForChar(c) ?: return@forEachIndexed
        val box = l.getBoundingBox(i)
        val side = min(box.width, box.height) * 0.92f
        if (side <= 0f) return@forEachIndexed
        Image(
            bitmap = bmp, contentDescription = null, contentScale = ContentScale.Fit,
            modifier = Modifier
                .offset { IntOffset((box.left + (box.width - side) / 2f).roundToInt(), (box.top + (box.height - side) / 2f).roundToInt()) }
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
    lineHeight: TextUnit
) {
    val spans = remember(text, facets) { EmojiCloud.emojiSpans(text, facets) }
    if (spans.isEmpty()) {
        // (Someone's own words: never translated.)
        androidx.compose.material3.Text(text, color = color, fontSize = fontSize, lineHeight = lineHeight)
        return
    }
    val onlyEmoji = remember(text, spans) {
        var rest = text
        spans.sortedByDescending { it.start }.forEach { rest = rest.removeRange(it.start, it.end) }
        rest.isBlank()
    }
    val side = if (onlyEmoji) 2.6.em else 1.35.em
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
    val inline = remember(spans, side) {
        spans.mapIndexed { i, s ->
            "emoji$i" to InlineTextContent(Placeholder(side, side, PlaceholderVerticalAlign.TextCenter)) {
                val url by produceState<String?>(null, s.did, s.cid) {
                    value = runCatching { com.mediaviewer.worker.BlueskyBlobResolver.resolveBlobUrl(s.did, s.cid) }.getOrNull()
                }
                AsyncImage(model = url, contentDescription = s.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            }
        }.toMap()
    }
    androidx.compose.material3.Text(
        annotated, color = color, fontSize = fontSize,
        lineHeight = if (onlyEmoji) (fontSize.value * 2.9f).sp else lineHeight,
        inlineContent = inline
    )
}
