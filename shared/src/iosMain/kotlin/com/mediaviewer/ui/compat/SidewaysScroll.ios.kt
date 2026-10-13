package com.mediaviewer.ui.compat

import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.pager.PagerState
import androidx.compose.ui.Modifier

// Touch screens: fingers already drag sideways rows.
actual fun Modifier.mouseSideways(state: ScrollableState, enabled: Boolean): Modifier = this
actual fun Modifier.mousePager(state: PagerState, enabled: Boolean): Modifier = this
