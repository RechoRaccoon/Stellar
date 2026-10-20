package com.mediaviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import com.mediaviewer.ui.theme.CircleShape
import com.mediaviewer.ui.theme.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.mediaviewer.i18n.Text
import com.mediaviewer.ui.theme.DimGray
import com.mediaviewer.util.rememberHapticTap

/*
 * Landscape's side list (profiles, Explore): like a video site's, a column
 * down the left of the page — a Back button at the top, then the page's
 * main tabs and its filters as an even list of buttons, the two parts
 * split by a line in the page's color. The tab and filter rows portrait
 * shows draw themselves as these buttons while [LocalSideList] is true, so
 * the very same tabs work both ways.
 */

/** True while drawing inside a landscape side list. */
val LocalSideList = compositionLocalOf { false }

/**
 * How wide the side list is — the same on every split page, set by
 * dragging the handle on its edge (see [SideListEdge]); never more than
 * about half the window.
 */
@Composable
fun rememberSideListWidth(): Dp {
    val screen = com.mediaviewer.ui.compat.rememberScreenSizeDp()
    val max = minOf(com.mediaviewer.util.UiToggles.SIDE_LIST_MAX_DP, (screen.width * 0.45f).toInt())
        .coerceAtLeast(com.mediaviewer.util.UiToggles.SIDE_LIST_MIN_DP)
    return com.mediaviewer.util.UiToggles.sideListWidthDp.coerceIn(com.mediaviewer.util.UiToggles.SIDE_LIST_MIN_DP, max).dp
}

/** The width a side list's content is laid out at; whatever width the list
 *  is dragged to, it all scales evenly to fit (see scaleToFit) instead of
 *  being squeezed or stretched. */
val SIDE_LIST_LAYOUT_WIDTH: Dp = 280.dp

/**
 * The side list's edge: a small upright pill, centred, to grab and drag
 * sideways — every split page's side list follows. While it's held, a line
 * in the page's [tint] runs the whole height of the edge (like resizing
 * split windows). Takes no room in its row: place it right after the side
 * list.
 */
@Composable
fun SideListEdge(tint: Color, modifier: Modifier = Modifier) {
    var dragging by remember { mutableStateOf(false) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val width = rememberSideListWidth()
    val latestWidth by androidx.compose.runtime.rememberUpdatedState(width)
    val grab = 24.dp
    val line = lerp(tint, Color.White, 0.3f)
    androidx.compose.ui.layout.Layout(
        content = {
            Box(
                Modifier.fillMaxHeight().width(grab)
                    .pointerInput(Unit) {
                        var dragDp = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { dragging = true; dragDp = latestWidth.value },
                            onDragEnd = { dragging = false; com.mediaviewer.util.UiToggles.updateSideListWidth(latestWidth.value.toInt(), save = true) },
                            onDragCancel = { dragging = false; com.mediaviewer.util.UiToggles.updateSideListWidth(latestWidth.value.toInt(), save = true) }
                        ) { change, dx ->
                            change.consume()
                            dragDp += dx / density.density
                            com.mediaviewer.util.UiToggles.updateSideListWidth(dragDp.toInt(), save = false)
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                if (dragging) Box(Modifier.fillMaxHeight().width(2.dp).background(line))
                Box(
                    Modifier.width(if (dragging) 7.dp else 5.dp).height(44.dp).clip(RoundedCornerShape(50))
                        .background(if (dragging) line else line.copy(alpha = 0.75f))
                )
            }
        },
        modifier = modifier.fillMaxHeight().zIndex(2f)
    ) { measurables, constraints ->
        val g = grab.roundToPx()
        val p = measurables.first().measure(androidx.compose.ui.unit.Constraints.fixed(g, constraints.maxHeight))
        layout(0, constraints.maxHeight) { p.place(-g / 2, 0) }
    }
}

/** One button in a side list: full width, its text at the left. */
@Composable
fun SideListButton(
    label: String, selected: Boolean, liquidGlass: Boolean, tint: Color,
    modifier: Modifier = Modifier,
    iconUrl: String? = null, icon: ImageVector? = null,
    /** Filters are a little smaller than the main tabs. */
    small: Boolean = false,
    /** A supporter-only button shown to someone who isn't one. */
    locked: Boolean = false,
    onClick: () -> Unit
) {
    val tap = rememberHapticTap()
    val shape = RoundedCornerShape(if (small) 11.dp else 13.dp)
    // (Sized to the list's width: big, readable labels with little empty
    // padding around them.)
    Row(
        modifier.fillMaxWidth().heightIn(min = if (small) 42.dp else 48.dp)
            .then(
                if (liquidGlass) Modifier.glassPanel(true, tint = if (selected) tint else tint.copy(alpha = 0.4f), shape = shape)
                else Modifier.clip(shape).background(if (selected) Color.White.copy(0.15f) else Color.White.copy(0.06f))
            )
            .clickable { tap(); onClick() }
            .padding(horizontal = 11.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (iconUrl != null) {
            AsyncImage(
                model = iconUrl, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.size(if (small) 24.dp else 26.dp).clip(CircleShape)
            )
        } else if (icon != null) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(if (small) 23.dp else 25.dp))
        }
        Text(
            label, color = if (selected || locked) Color.White else DimGray,
            fontSize = if (small) 17.sp else 18.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).supporterShine(locked)
        )
    }
}

/** The line between a side list's main tabs and its filters. */
@Composable
fun SideListDivider(tint: Color) {
    Box(
        Modifier.fillMaxWidth().padding(vertical = 8.dp).height(1.dp).clip(RoundedCornerShape(1.dp))
            .background(lerp(tint, Color.White, 0.2f).copy(alpha = 0.75f))
    )
}

/**
 * The side list itself: [rememberSideListWidth] wide, the page's full height,
 * scrolling if it runs long. [footer] sits at the bottom.
 */
@Composable
fun LandscapeSideList(
    tint: Color, liquidGlass: Boolean,
    backLabel: String, onBack: () -> Unit,
    modifier: Modifier = Modifier,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier.width(rememberSideListWidth()).fillMaxHeight().scaleToFit(SIDE_LIST_LAYOUT_WIDTH)
            .padding(start = 12.dp, end = 12.dp, top = rememberTopCutoutClearance(), bottom = 12.dp)
    ) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SideListButton(backLabel, selected = false, liquidGlass = liquidGlass, tint = tint, icon = Icons.AutoMirrored.Filled.ArrowBack, onClick = onBack)
            Spacer(Modifier.height(4.dp))
            CompositionLocalProvider(LocalSideList provides true) { content() }
        }
        if (footer != null) {
            Spacer(Modifier.height(8.dp))
            footer()
        }
    }
}

/** Landscape: how many posts across the post grids are (5 to 10). */
@Composable
fun GridSizeSlider(tint: Color) {
    val columns = com.mediaviewer.util.UiToggles.landscapeColumns
    val accent = lerp(tint, Color.White, 0.35f)
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Grid Size", color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text("$columns", color = accent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        // (Drawn here rather than Material's Slider so its track and thumb
        // follow UI Shape: square with Hybrid or Squared.)
        val min = com.mediaviewer.util.UiToggles.LANDSCAPE_MIN_COLUMNS
        val max = com.mediaviewer.util.UiToggles.LANDSCAPE_MAX_COLUMNS
        val square = com.mediaviewer.util.UiToggles.squareCorners
        val trackShape = if (square) androidx.compose.ui.graphics.RectangleShape else androidx.compose.foundation.shape.RoundedCornerShape(50)
        val thumbShape = if (square) androidx.compose.ui.graphics.RectangleShape else androidx.compose.foundation.shape.RoundedCornerShape(50)
        var widthPx by remember { mutableStateOf(1) }
        fun pick(x: Float) {
            val f = (x / widthPx.coerceAtLeast(1)).coerceIn(0f, 1f)
            com.mediaviewer.util.UiToggles.updateLandscapeColumns(kotlin.math.round(min + f * (max - min)).toInt())
        }
        androidx.compose.foundation.layout.BoxWithConstraints(
            Modifier.fillMaxWidth().height(28.dp)
                .onSizeChanged { widthPx = it.width }
                .pointerInput(Unit) { androidx.compose.foundation.gestures.detectTapGestures { pick(it.x) } }
                .pointerInput(Unit) { detectHorizontalDragGestures { change, _ -> change.consume(); pick(change.position.x) } },
            contentAlignment = Alignment.CenterStart
        ) {
            val fraction = ((columns - min).toFloat() / (max - min).coerceAtLeast(1)).coerceIn(0f, 1f)
            val thumbW = 6.dp
            Box(Modifier.fillMaxWidth().height(10.dp).clip(trackShape).background(Color.White.copy(alpha = 0.15f)))
            Box(Modifier.fillMaxWidth(fraction).height(10.dp).clip(trackShape).background(accent))
            Box(
                Modifier.padding(start = ((maxWidth - thumbW) * fraction).coerceAtLeast(0.dp))
                    .size(width = thumbW, height = 24.dp).clip(thumbShape).background(Color.White)
            )
        }
    }
}

/**
 * A side list's rows that can be put in a new order: press and hold one,
 * drag it up or down (the others make way, with a haptic tick each time it
 * takes a new place) and let go. [onMove] gets where it was and where it
 * went, as indexes of [items]. Rows keep their own taps.
 *
 * [onHeld] hears which row is held (null when let go), [onPointer] where
 * the finger is (root coordinates) while it is, and [onDropOutside] gets a
 * last say when it's let go: true means it was dropped on something else
 * (a Remove bubble, say), so it isn't moved.
 */
@Composable
fun <T> ReorderableSideColumn(
    items: List<T>,
    key: (T) -> Any,
    onMove: (from: Int, to: Int) -> Unit,
    modifier: Modifier = Modifier,
    spacing: Dp = 5.dp,
    onHeld: (T?) -> Unit = {},
    onPointer: (androidx.compose.ui.geometry.Offset) -> Unit = {},
    onDropOutside: (T) -> Boolean = { false },
    /** Whether the held row may trade places with this neighbour (feeds
     *  stay within the pinned or the unpinned ones). */
    canPass: (held: T, other: T) -> Boolean = { _, _ -> true },
    row: @Composable (T) -> Unit
) {
    // Its own copy of the order, so the rows move as the held one passes
    // them and a drop doesn't jump while the saved order catches up.
    var order by remember(items) { mutableStateOf(items) }
    val latestOrder by androidx.compose.runtime.rememberUpdatedState(order)
    var held by remember { mutableStateOf<Any?>(null) }
    var heldFrom by remember { mutableStateOf(-1) }
    var offsetY by remember { mutableStateOf(0f) }
    val heights = remember { HashMap<Any, Int>() }
    val coords = remember { HashMap<Any, androidx.compose.ui.layout.LayoutCoordinates>() }
    val view = com.mediaviewer.ui.compat.rememberPlatformView()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val gapPx = with(density) { spacing.toPx() }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(spacing)) {
        order.forEach { item ->
            val k = key(item)
            androidx.compose.runtime.key(k) {
                val isHeld = held == k
                val lift by androidx.compose.animation.core.animateFloatAsState(if (isHeld) 1f else 0f, label = "rowLift")
                Box(
                    Modifier.fillMaxWidth()
                        .onSizeChanged { heights[k] = it.height }
                        .zIndex(if (isHeld) 2f else 0f)
                        // (The held row follows the finger, lifted a little.)
                        .graphicsLayer {
                            translationY = if (isHeld) offsetY else 0f
                            val sc = 1f + 0.03f * lift
                            scaleX = sc; scaleY = sc
                            shadowElevation = 12f * lift
                            shape = RoundedCornerShape(10.dp)
                        }
                        .onGloballyPositioned { coords[k] = it }
                        // (Restarted when the list itself changes — a feed pinned,
                        // say — so the gesture works on the new order.)
                        .pointerInput(k, items) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    held = k; offsetY = 0f
                                    heldFrom = latestOrder.indexOfFirst { key(it) == k }
                                    onHeld(item)
                                    view.performHapticFeedback(com.mediaviewer.ui.compat.HapticFeedbackConstants.LONG_PRESS)
                                },
                                onDragEnd = {
                                    val to = latestOrder.indexOfFirst { key(it) == k }
                                    val from = heldFrom
                                    held = null; offsetY = 0f; heldFrom = -1
                                    view.performHapticFeedback(com.mediaviewer.ui.compat.HapticFeedbackConstants.VIRTUAL_KEY)
                                    val handled = onDropOutside(item)
                                    onHeld(null)
                                    if (handled) order = items
                                    else if (from >= 0 && to >= 0 && from != to) {
                                        val original = items.indexOfFirst { key(it) == k }
                                        val target = to.coerceIn(0, items.lastIndex)
                                        if (original >= 0) onMove(original, target)
                                    }
                                },
                                onDragCancel = { order = items; held = null; offsetY = 0f; heldFrom = -1; onHeld(null) }
                            ) { change, amount ->
                                change.consume()
                                offsetY += amount.y
                                coords[k]?.takeIf { it.isAttached }?.let { onPointer(it.localToRoot(change.position)) }
                                // Past half of a neighbour: swap with it, keeping the
                                // row under the finger.
                                val list = latestOrder
                                val i = list.indexOfFirst { key(it) == k }
                                if (i < 0) return@detectDragGesturesAfterLongPress
                                val below = list.getOrNull(i + 1)
                                val above = list.getOrNull(i - 1)
                                if (below != null && canPass(item, below) && offsetY > (heights[key(below)] ?: 0) / 2f + gapPx) {
                                    order = list.toMutableList().apply { add(i + 1, removeAt(i)) }
                                    offsetY -= (heights[key(below)] ?: 0) + gapPx
                                    view.performHapticFeedback(com.mediaviewer.ui.compat.HapticFeedbackConstants.CLOCK_TICK)
                                } else if (above != null && canPass(item, above) && offsetY < -((heights[key(above)] ?: 0) / 2f + gapPx)) {
                                    order = list.toMutableList().apply { add(i - 1, removeAt(i)) }
                                    offsetY += (heights[key(above)] ?: 0) + gapPx
                                    view.performHapticFeedback(com.mediaviewer.ui.compat.HapticFeedbackConstants.CLOCK_TICK)
                                }
                            }
                        }
                ) { row(item) }
            }
        }
    }
}
