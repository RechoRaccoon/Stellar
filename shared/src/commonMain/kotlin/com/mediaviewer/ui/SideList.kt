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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex
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

/** The width a side list's content is laid out at; narrower, it all
 *  shrinks evenly to fit (see shrinkToFit) instead of being squeezed. */
val SIDE_LIST_LAYOUT_WIDTH: Dp = 340.dp

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
    val shape = RoundedCornerShape(if (small) 10.dp else 12.dp)
    Row(
        modifier.fillMaxWidth().heightIn(min = if (small) 32.dp else 38.dp)
            .then(
                if (liquidGlass) Modifier.glassPanel(true, tint = if (selected) tint else tint.copy(alpha = 0.4f), shape = shape)
                else Modifier.clip(shape).background(if (selected) Color.White.copy(0.15f) else Color.White.copy(0.06f))
            )
            .clickable { tap(); onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (iconUrl != null) {
            AsyncImage(
                model = iconUrl, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.size(if (small) 16.dp else 18.dp).clip(CircleShape)
            )
        } else if (icon != null) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(if (small) 15.dp else 17.dp))
        }
        Text(
            label, color = if (selected || locked) Color.White else DimGray,
            fontSize = if (small) 12.sp else 13.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
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
        modifier.width(rememberSideListWidth()).fillMaxHeight().shrinkToFit(SIDE_LIST_LAYOUT_WIDTH)
            .padding(start = 16.dp, end = 16.dp, top = rememberTopCutoutClearance(), bottom = 12.dp)
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
            Text("Grid Size", color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text("$columns", color = accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Slider(
            value = columns.toFloat(),
            onValueChange = { com.mediaviewer.util.UiToggles.updateLandscapeColumns(kotlin.math.round(it).toInt()) },
            valueRange = com.mediaviewer.util.UiToggles.LANDSCAPE_MIN_COLUMNS.toFloat()..com.mediaviewer.util.UiToggles.LANDSCAPE_MAX_COLUMNS.toFloat(),
            steps = com.mediaviewer.util.UiToggles.LANDSCAPE_MAX_COLUMNS - com.mediaviewer.util.UiToggles.LANDSCAPE_MIN_COLUMNS - 1,
            colors = SliderDefaults.colors(
                thumbColor = Color.White, activeTrackColor = accent, inactiveTrackColor = Color.White.copy(alpha = 0.15f),
                activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent
            ),
            modifier = Modifier.fillMaxWidth().height(28.dp)
        )
    }
}
