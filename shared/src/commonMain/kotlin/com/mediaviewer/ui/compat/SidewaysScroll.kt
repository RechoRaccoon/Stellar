package com.mediaviewer.ui.compat

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.horizontalScroll as foundationHorizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/*
 * Sideways rows (tabs, chips, Mutuals, pagers) with a mouse. Compose only
 * lets a finger drag them, and a plain wheel turn doesn't move them (only
 * Shift + wheel), so on a computer they couldn't be scrolled. On desktop
 * these add mouse dragging (the wheel stays with the page around them);
 * on phones they add nothing.
 */

/** Mouse dragging for a sideways [state] (desktop and web only). */
expect fun Modifier.mouseSideways(state: ScrollableState, enabled: Boolean = true): Modifier

/** Mouse dragging for a pager: it follows the mouse, then settles on the
 *  nearest page (desktop only). */
expect fun Modifier.mousePager(state: PagerState, enabled: Boolean = true): Modifier

/** foundation's horizontalScroll, plus [mouseSideways]. */
fun Modifier.horizontalScroll(
    state: ScrollState,
    enabled: Boolean = true,
    flingBehavior: FlingBehavior? = null,
    reverseScrolling: Boolean = false,
): Modifier = mouseSideways(state, enabled).foundationHorizontalScroll(state, enabled, flingBehavior, reverseScrolling)

/** foundation's LazyRow, plus [mouseSideways]. */
@Composable
fun LazyRow(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    reverseLayout: Boolean = false,
    horizontalArrangement: Arrangement.Horizontal = if (!reverseLayout) Arrangement.Start else Arrangement.End,
    verticalAlignment: Alignment.Vertical = Alignment.Top,
    flingBehavior: FlingBehavior = ScrollableDefaults.flingBehavior(),
    userScrollEnabled: Boolean = true,
    content: LazyListScope.() -> Unit,
) = androidx.compose.foundation.lazy.LazyRow(
    modifier = modifier.mouseSideways(state, userScrollEnabled),
    state = state,
    contentPadding = contentPadding,
    reverseLayout = reverseLayout,
    horizontalArrangement = horizontalArrangement,
    verticalAlignment = verticalAlignment,
    flingBehavior = flingBehavior,
    userScrollEnabled = userScrollEnabled,
    content = content,
)
