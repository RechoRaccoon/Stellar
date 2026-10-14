package com.mediaviewer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * Landscape: a window wider than it is tall (a tablet or phone turned
 * sideways, a computer's window, a browser). Stellar was made for
 * portrait; sideways, its pages use the extra room the way a video site
 * does — grids grow more columns as the window widens, comments sit
 * beside the post, DMs show the chat list beside the open chat — and the
 * pages that are mostly text (Settings, the composer, sign-in) keep a
 * comfortable reading width in the middle instead of stretching.
 *
 * Everything here is only ever read in landscape; portrait is exactly as
 * it always was.
 */
object Landscape {
    /** The widest a page of text and rows gets (Settings, the composer…). */
    val READABLE_WIDTH: Dp = 720.dp

    /** The sign-in page's fields and buttons. */
    val FORM_WIDTH: Dp = 460.dp

    /** Comments beside the post they're on. */
    val SIDE_PANEL_WIDTH: Dp = 420.dp

    /**
     * Columns for a grid that has [portrait] columns in portrait: in
     * landscape, as many columns of about [tileDp] as fit across
     * [widthDp] (never fewer than portrait has).
     */
    fun lanes(portrait: Int, widthDp: Int, tileDp: Int): Int =
        maxOf(portrait, widthDp / tileDp)

    /**
     * The column count for one of the post grids that has [portrait]
     * columns in portrait: the count set with the slider under a profile's
     * side list (5 to 10, see UiToggles.landscapeColumns) for picture
     * grids, two fewer (at least 3) for text posts set side by side.
     */
    @Suppress("UNUSED_PARAMETER")
    fun postLanes(portrait: Int, widthDp: Int): Int {
        val n = com.mediaviewer.util.UiToggles.landscapeColumns
        return if (portrait >= 2) n else maxOf(1, n - 2)
    }

    /** Video cards (horizontal videos): as many across as the slider says. */
    @Suppress("UNUSED_PARAMETER")
    fun videoCardLanes(widthDp: Int): Int = com.mediaviewer.util.UiToggles.landscapeColumns
}

/**
 * Lays the content out [reference] wide and, when its column is narrower
 * than that, shrinks the whole thing to fit — so a text bubble or a video
 * card in a grid of many columns keeps its shape (smaller, not taller).
 * Wider columns lay it out normally.
 */
fun Modifier.shrinkToWidth(reference: Dp): Modifier = this.then(
    Modifier.layout { measurable, constraints ->
        val ref = reference.roundToPx()
        val w = constraints.maxWidth
        if (w == androidx.compose.ui.unit.Constraints.Infinity || w >= ref) {
            val p = measurable.measure(constraints)
            layout(p.width, p.height) { p.place(0, 0) }
        } else {
            val scale = w.toFloat() / ref
            val p = measurable.measure(androidx.compose.ui.unit.Constraints(minWidth = ref, maxWidth = ref))
            layout(w, kotlin.math.ceil(p.height * scale).toInt()) {
                p.placeWithLayer(0, 0) {
                    scaleX = scale; scaleY = scale
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
                }
            }
        }
    }
)

/** True while the window is wider than it is tall. */
@Composable
fun rememberLandscape(): Boolean {
    val s = com.mediaviewer.ui.compat.rememberScreenSizeDp()
    return s.width > s.height
}

/** The window's width in dp while in landscape; 0 in portrait (so code
 *  can pass it along and portrait keeps its own layout). */
@Composable
fun rememberLandscapeWidthDp(): Int {
    val s = com.mediaviewer.ui.compat.rememberScreenSizeDp()
    return if (s.width > s.height) s.width else 0
}

/** In landscape: this content at most [maxWidth] wide, centered (it still
 *  fills narrower windows). Portrait: unchanged. */
@Composable
fun Modifier.landscapeReadable(maxWidth: Dp = Landscape.READABLE_WIDTH): Modifier =
    if (!rememberLandscape()) this
    else this.fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = maxWidth).fillMaxWidth()

/** For a lazy list that should read like [landscapeReadable] while it
 *  still scrolls from anywhere across the window: the extra padding each
 *  side needs in landscape (0 in portrait). */
@Composable
fun rememberLandscapeSidePadding(maxWidth: Dp = Landscape.READABLE_WIDTH): Dp {
    val s = com.mediaviewer.ui.compat.rememberScreenSizeDp()
    if (s.width <= s.height) return 0.dp
    return ((s.width.dp - maxWidth) / 2).coerceAtLeast(0.dp)
}

/**
 * [list] as lazy rows of [columns] items side by side, each [content] given
 * its share of the row (portrait passes 1: one item a row, as before).
 * [key] must be unique per item.
 */
fun <T> LazyListScope.landscapeGridRows(
    list: List<T>, columns: Int, key: (T) -> Any,
    rowModifier: Modifier = Modifier, spacing: Dp = 10.dp,
    content: @Composable (item: T, modifier: Modifier) -> Unit
) {
    if (columns <= 1) {
        list.forEach { entry -> item(key = key(entry)) { content(entry, Modifier) } }
        return
    }
    val rows = list.chunked(columns)
    rows.forEach { row ->
        item(key = "row_" + key(row.first()).toString()) {
            Row(rowModifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(spacing)) {
                row.forEach { entry -> content(entry, Modifier.weight(1f)) }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/**
 * For a side list made narrower than [reference]: lays its content out
 * [reference] wide (and as tall as it then needs to be to fill the room)
 * and shrinks all of it evenly to fit — so nothing gets squeezed, wrapped
 * or cut off; it just gets smaller. At [reference] or wider, it's laid out
 * as normal.
 */
fun Modifier.shrinkToFit(reference: Dp): Modifier = this.then(
    Modifier.layout { measurable, constraints ->
        val ref = reference.roundToPx()
        val w = constraints.maxWidth
        val inf = androidx.compose.ui.unit.Constraints.Infinity
        if (w == inf || w >= ref || constraints.maxHeight == inf) {
            val p = measurable.measure(constraints)
            layout(p.width, p.height) { p.place(0, 0) }
        } else {
            val scale = w.toFloat() / ref
            val p = measurable.measure(
                androidx.compose.ui.unit.Constraints(
                    minWidth = ref, maxWidth = ref,
                    minHeight = (constraints.minHeight / scale).toInt(), maxHeight = (constraints.maxHeight / scale).toInt()
                )
            )
            layout(w, (p.height * scale).toInt().coerceAtMost(constraints.maxHeight)) {
                p.placeWithLayer(0, 0) {
                    scaleX = scale; scaleY = scale
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
                }
            }
        }
    }
)
