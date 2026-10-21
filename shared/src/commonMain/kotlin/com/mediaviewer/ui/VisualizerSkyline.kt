package com.mediaviewer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import com.mediaviewer.ui.compat.LocalContext
import com.mediaviewer.ui.compat.navBarSpace
import com.mediaviewer.util.AudioVisualizerEngine

/**
 * Landscape's audio visualizer: one row of bars along the whole bottom of
 * the window. Where something sits on the bottom edge (the post's text
 * bubble and interaction bar, the comment box, the Hub's bottom bar) the
 * bars step up and rest on top of it instead, like a skyline; everywhere
 * else they stand on the bottom edge itself.
 *
 * Those things say where they are with [visualizerFloor], each under the
 * screen it belongs to; only the screen that's showing ([screen]) counts.
 */
object VisualizerFloors {
    internal val floors = mutableStateMapOf<String, Pair<String, Rect>>()
}

/** This element's top is where the landscape visualizer's bars stand,
 *  across its width, while [screen] is showing. */
fun Modifier.visualizerFloor(key: String, screen: String, enabled: Boolean = true): Modifier = composed {
    DisposableEffect(key, enabled) {
        if (!enabled) VisualizerFloors.floors.remove(key)
        onDispose { VisualizerFloors.floors.remove(key) }
    }
    if (!enabled) this else this.onGloballyPositioned { VisualizerFloors.floors[key] = screen to it.boundsInRoot() }
}

/** The landscape visualizer (see [VisualizerFloors]); draws nothing for a
 *  null [screen]. Touches pass straight through it. */
@Composable
fun AudioVisualizerSkyline(screen: String?, color: Color, modifier: Modifier = Modifier) {
    if (screen == null) return
    val context = LocalContext.current
    DisposableEffect(Unit) {
        AudioVisualizerEngine.acquire(context)
        onDispose { AudioVisualizerEngine.release() }
    }
    val barColor = remember(color) { lerp(color, Color.White, 0.35f) }
    val bottomInset = androidx.compose.foundation.layout.WindowInsets.navBarSpace
    val density = androidx.compose.ui.platform.LocalDensity.current
    val insetPx = bottomInset.getBottom(density)
    Canvas(modifier.fillMaxSize()) {
        val levels = AudioVisualizerEngine.levels
        val n = levels.size
        if (n == 0) return@Canvas
        val side = 10.dp.toPx()
        val gap = 3.dp.toPx()
        // About as wide as the Timeline's bars on a phone.
        val target = 11.dp.toPx()
        val usable = size.width - side * 2
        val count = ((usable + gap) / (target + gap)).toInt().coerceAtLeast(n)
        val barW = ((usable - gap * (count - 1)) / count).coerceAtLeast(1f)
        val maxH = 34.dp.toPx()
        val lift = 2.dp.toPx()
        val floor = size.height - insetPx - 4.dp.toPx()
        // (Only what's on screen, in its lower part: a post scrolled away
        // above or below doesn't count.)
        val tops = VisualizerFloors.floors.values.filter { it.first == screen }.map { it.second }
            .filter { it.bottom > 0f && it.top < size.height && it.top > size.height * 0.3f }
        val radius = CornerRadius(barW / 2f, barW / 2f)
        // (The first and last places stay empty: at the far left the first
        // bar stood alone beside the post's interaction bar, and the right
        // end drops one too so both sides match.)
        for (i in 1 until count - 1) {
            // Across the window, each bar takes the band at its place.
            val f = i.toFloat() * (n - 1) / (count - 1).coerceAtLeast(1)
            val lo = f.toInt().coerceIn(0, n - 1)
            val hi = (lo + 1).coerceAtMost(n - 1)
            val v = levels[lo] + (levels[hi] - levels[lo]) * (f - lo)
            if (v <= 0.01f) continue
            val x = side + i * (barW + gap)
            val cx = x + barW / 2f
            // Standing on whatever is under this bar (the highest one), or
            // on the bottom edge.
            var base = floor
            for (r in tops) if (cx >= r.left && cx <= r.right && r.top - lift < base) base = r.top - lift
            val h = (maxH * v).coerceAtLeast(barW)
            drawRoundRect(
                color = barColor.copy(alpha = 0.55f + 0.4f * v),
                topLeft = Offset(x, base - h),
                size = Size(barW, h),
                cornerRadius = radius
            )
        }
    }
}
