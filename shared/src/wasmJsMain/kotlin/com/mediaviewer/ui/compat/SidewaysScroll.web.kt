package com.mediaviewer.ui.compat

import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.pager.PagerState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs

actual fun Modifier.mouseSideways(state: ScrollableState, enabled: Boolean): Modifier =
    if (!enabled) this else this
        // (The mouse wheel is left to the page around it: rows are
        // scrolled by dragging with the mouse — or a trackpad's sideways
        // swipe / Shift + wheel, which the row itself takes.)
        .pointerInput(state) {
            awaitPointerEventScope {
                while (true) {
                    dragSideways(onStart = {}, onMove = { state.dispatchRawDelta(-it) }, onEnd = {})
                }
            }
        }

actual fun Modifier.mousePager(state: PagerState, enabled: Boolean): Modifier =
    if (!enabled) this else this.pointerInput(state) {
        coroutineScope {
            awaitPointerEventScope {
                while (true) {
                    var startPage = state.currentPage
                    dragSideways(
                        onStart = { startPage = state.currentPage },
                        onMove = { state.dispatchRawDelta(-it) },
                        onEnd = { total ->
                            // A fifth of the width turns the page; less springs back.
                            val turn = size.width * 0.2f
                            val target = when {
                                total < -turn -> startPage + 1
                                total > turn -> startPage - 1
                                else -> startPage
                            }.coerceIn(0, (state.pageCount - 1).coerceAtLeast(0))
                            launch { state.animateScrollToPage(target) }
                        },
                    )
                }
            }
        }
    }

/**
 * One left-button mouse drag, watched before the row's contents see it
 * (so once it's a drag, the button under the mouse doesn't also click).
 * Mostly-sideways movement past the touch slop is a drag; [onMove] gets
 * each step's x movement, [onEnd] the total.
 */
private suspend fun AwaitPointerEventScope.dragSideways(
    onStart: () -> Unit,
    onMove: (Float) -> Unit,
    onEnd: (Float) -> Unit,
) {
    val down: PointerEvent = awaitPointerEvent(PointerEventPass.Initial)
    val first = down.changes.firstOrNull() ?: return
    if (down.type != PointerEventType.Press || first.type != PointerType.Mouse || !down.buttons.isPrimaryPressed) return
    var totalX = 0f
    var totalY = 0f
    var dragging = false
    while (true) {
        val event = awaitPointerEvent(PointerEventPass.Initial)
        val change: PointerInputChange = event.changes.firstOrNull { it.id == first.id } ?: break
        if (!change.pressed) {
            if (dragging) {
                change.consume()
                onEnd(totalX)
            }
            break
        }
        val dx = change.position.x - change.previousPosition.x
        totalX += dx
        totalY += change.position.y - change.previousPosition.y
        if (!dragging && abs(totalX) > viewConfiguration.touchSlop && abs(totalX) > abs(totalY)) {
            dragging = true
            onStart()
        }
        if (dragging) {
            onMove(dx)
            change.consume()
        }
    }
}
