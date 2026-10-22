package com.mediaviewer.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.compose.material3.LocalTextStyle

/**
 * Text that shrinks until all of it fits in the space it's given (the box
 * this is placed in, e.g. a picture's own bounds), never cut off unless even
 * [minSize] doesn't fit. Used for the tags laid over a just-tagged post and
 * for alt text shown over a picture in the composer.
 */
@Composable
fun FitText(
    text: String,
    modifier: Modifier = Modifier,
    maxSize: TextUnit = 17.sp,
    minSize: TextUnit = 7.sp,
    color: Color = Color.White,
    fontWeight: FontWeight = FontWeight.SemiBold,
    textAlign: TextAlign = TextAlign.Center,
    lineHeightFactor: Float = 1.25f,
    /** One line only, shrunk to the width (the height isn't looked at). */
    singleLine: Boolean = false,
    shadow: Boolean = true,
    /** Drawn instead of [text] (the same words, with styles of their own:
     *  colors that don't change its size). */
    styled: androidx.compose.ui.text.AnnotatedString? = null,
    /** Where it sits in its box. */
    alignment: Alignment = Alignment.Center
) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val base = LocalTextStyle.current
    BoxWithConstraints(modifier, contentAlignment = alignment) {
        val maxW = constraints.maxWidth
        val maxH = constraints.maxHeight
        fun style(size: Float) = base.merge(TextStyle(
            color = color, fontSize = size.sp, fontWeight = fontWeight, textAlign = textAlign,
            lineHeight = (size * lineHeightFactor).sp,
            shadow = if (shadow) Shadow(Color.Black.copy(alpha = 0.8f), Offset(0f, 1.5f), 4f) else null
        ))
        val size = remember(text, maxW, maxH, density, base) {
            if (maxW <= 0 || maxW == Constraints.Infinity) return@remember maxSize.value
            if (!singleLine && (maxH <= 0 || maxH == Constraints.Infinity)) return@remember maxSize.value
            fun fits(s: Float): Boolean {
                if (singleLine) {
                    val r = measurer.measure(text, style(s), softWrap = false, maxLines = 1, density = density)
                    return r.size.width <= maxW
                }
                val r = measurer.measure(text, style(s), constraints = Constraints(maxWidth = maxW), density = density)
                return r.size.height <= maxH
            }
            if (fits(maxSize.value)) return@remember maxSize.value
            var lo = minSize.value
            var hi = maxSize.value
            repeat(10) {
                val mid = (lo + hi) / 2f
                if (fits(mid)) lo = mid else hi = mid
            }
            lo
        }
        Box(if (singleLine) Modifier else Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            // The person's own words (tags, alt text): never translated.
            if (styled != null) androidx.compose.material3.Text(
                styled, style = style(size).copy(shadow = null),
                maxLines = if (singleLine) 1 else Int.MAX_VALUE, softWrap = !singleLine
            ) else androidx.compose.material3.Text(
                text, style = style(size),
                maxLines = if (singleLine) 1 else Int.MAX_VALUE, softWrap = !singleLine
            )
        }
    }
}
