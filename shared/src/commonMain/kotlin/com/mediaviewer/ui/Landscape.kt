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
     * side list (1 to 10, see UiToggles.landscapeColumns) — text posts
     * too (they shrink to keep their shape, see shrinkToWidth).
     */
    @Suppress("UNUSED_PARAMETER")
    fun postLanes(portrait: Int, widthDp: Int): Int = com.mediaviewer.util.UiToggles.landscapeColumns

    /** Text cards side by side in landscape (a profile's blogs, reviews,
     *  VODs, lists): as many across as the slider says, each laid out
     *  [TEXT_CARD_REFERENCE] wide and shrunk to its column (shrinkToWidth). */
    fun textCardLanes(widthDp: Int): Int = if (widthDp > 0) com.mediaviewer.util.UiToggles.landscapeColumns else 1
    val TEXT_CARD_REFERENCE: Dp = 420.dp

    /** Video cards (horizontal videos): as many across as the slider says. */
    @Suppress("UNUSED_PARAMETER")
    fun videoCardLanes(widthDp: Int): Int = com.mediaviewer.util.UiToggles.landscapeColumns
}

/**
 * Landscape: how big the post UI is drawn by
 * default, before any pinch on the interaction bar. Worked out from the
 * screen's height (in dp, so already independent of pixel density), through
 * these points, straight lines between them:
 *
 *  - up to 430 dp — phones on their side (about 360–430 dp tall): 0.85.
 *    The bar's buttons stay about 31 × 39 dp, big enough to hit with a
 *    thumb, while the text bubble and bar together take under a fifth of
 *    the height, leaving the picture the room.
 *  - 640 dp — unfolded foldables, small laptops: 1.0, the size it's drawn at.
 *  - 820 dp — tablets on their side (about 744–1024 dp tall): 1.1, so it
 *    doesn't look lost on the bigger screen and is easy to reach.
 *  - 1000 dp and up — tall desktop windows and big tablets: 1.15, the most;
 *    any bigger only covers more of the picture.
 *
 * It's also never so big that the bottom-left cluster (about 364 dp wide at
 * 1.0) takes more than 40% of the width — which only matters for unusually
 * narrow landscape windows — nor smaller than 0.72.
 */
fun landscapeUiBaseScale(heightDp: Int, widthDp: Int = Int.MAX_VALUE): Float {
    val points = listOf(430f to 0.85f, 640f to 1.0f, 820f to 1.1f, 1000f to 1.15f)
    val h = heightDp.toFloat()
    val byHeight = when {
        h <= points.first().first -> points.first().second
        h >= points.last().first -> points.last().second
        else -> {
            val i = points.indexOfFirst { it.first >= h }
            val (h0, s0) = points[i - 1]
            val (h1, s1) = points[i]
            s0 + (s1 - s0) * (h - h0) / (h1 - h0)
        }
    }
    val byWidth = if (widthDp == Int.MAX_VALUE) byHeight else widthDp * 0.40f / 364f
    return minOf(byHeight, byWidth).coerceAtLeast(0.72f)
}


/**
 * Lays the content out [reference] wide and, when its column is narrower
 * than that, shrinks the whole thing to fit — so a text bubble or a video
 * card in a grid of many columns keeps its shape (smaller, not taller).
 * Wider columns lay it out normally.
 */
/** Lays its content out [reference] tall (and as wide as that height
 *  leaves it, at the same proportions) and scales it, up or down, to the
 *  height it really has — a profile banner's buttons and name pills. */
fun Modifier.scaleToHeight(reference: Dp): Modifier = this.then(
    Modifier.layout { measurable, constraints ->
        val ref = reference.roundToPx().coerceAtLeast(1)
        val w = constraints.maxWidth
        val h = constraints.maxHeight
        val inf = androidx.compose.ui.unit.Constraints.Infinity
        if (w == inf || h == inf || h <= 0) {
            val p = measurable.measure(constraints)
            layout(p.width, p.height) { p.place(0, 0) }
        } else {
            val scale = h.toFloat() / ref
            val p = measurable.measure(androidx.compose.ui.unit.Constraints.fixed((w / scale).toInt().coerceAtLeast(1), ref))
            layout(w, h) {
                p.placeWithLayer(0, 0) {
                    scaleX = scale; scaleY = scale
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
                }
            }
        }
    }
)

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
/** Landscape's side lists: always laid out at [reference] wide and scaled,
 *  up or down, to the width they really have — dragging the list's edge
 *  makes everything in it bigger or smaller together, never squished or
 *  stretched. */
fun Modifier.scaleToFit(reference: Dp): Modifier = this.then(
    Modifier.layout { measurable, constraints ->
        val ref = reference.roundToPx()
        val w = constraints.maxWidth
        val inf = androidx.compose.ui.unit.Constraints.Infinity
        if (w == inf || w <= 0 || constraints.maxHeight == inf) {
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
