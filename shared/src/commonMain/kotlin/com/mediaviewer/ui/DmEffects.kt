package com.mediaviewer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import com.mediaviewer.ui.compat.rememberPlatformView
import com.mediaviewer.ui.compat.HapticFeedbackConstants

/** A little celebration a DM can set off. */
enum class DmEffect(val label: String) {
    /** Confetti on its own (a supporter profile's default). */
    CONFETTI("Confetti"),
    /** "Happy birthday": confetti with balloons flying up. */
    BIRTHDAY("Balloons"),
    SNOW("Snow"),
    FIREWORKS("Fireworks"),
    BATS("Bats"),
    /** "I love you": slowly turning hearts in the sender's colors. */
    HEARTS("Hearts"),
    RAIN("Rain"),
    /** Bubbles that float about, pop when tapped, then pop by themselves. */
    BUBBLES("Bubbles")
}

private val RAIN_WORD = Regex("""\brain(ing)?\b""")
private val BUBBLES_WORD = Regex("""\bbubbles\b""")

/** The effect [text] sets off, if it says one of the magic phrases
 *  (any capitalisation). */
fun dmEffectFor(text: String): DmEffect? {
    val t = text.lowercase()
    return when {
        t.contains("happy birthday") -> DmEffect.BIRTHDAY
        t.contains("merry christmas") -> DmEffect.SNOW
        t.contains("happy new year") -> DmEffect.FIREWORKS
        t.contains("happy halloween") -> DmEffect.BATS
        t.contains("i love you") -> DmEffect.HEARTS
        BUBBLES_WORD.containsMatchIn(t) -> DmEffect.BUBBLES
        RAIN_WORD.containsMatchIn(t) -> DmEffect.RAIN
        else -> null
    }
}

/** How long the longest effect can run, for anything that follows one. */
const val DM_EFFECT_MAX_SECONDS = 18f

/**
 * Plays [effect] once over the chat for each new [playKey]. Everything is
 * drawn from scratch with shapes and a fresh random seed each time — no
 * images, no borrowed artwork. Touches pass straight through (except onto
 * a bubble, which pops).
 *
 * [colors] tints the effects that take the sender's profile colors
 * (hearts); [backdrop] is the page behind, which bubbles blur.
 */
@Composable
fun DmEffectLayer(
    effect: DmEffect,
    playKey: Int,
    modifier: Modifier = Modifier,
    colors: List<Color> = emptyList(),
    backdrop: GlassBackdrop? = null
) {
    if (effect == DmEffect.CONFETTI) {
        // The same confetti as a supporter's profile.
        SupporterConfetti(playKey = playKey, modifier = modifier.fillMaxSize())
        return
    }
    if (effect == DmEffect.BUBBLES) {
        BubblesEffect(playKey, modifier, backdrop)
        return
    }
    // A birthday is the confetti with balloons behind it.
    if (effect == DmEffect.BIRTHDAY) SupporterConfetti(playKey = playKey, modifier = modifier.fillMaxSize())
    val duration = when (effect) {
        DmEffect.SNOW -> 11f
        DmEffect.FIREWORKS -> 5.6f
        DmEffect.BIRTHDAY -> 7.5f
        DmEffect.HEARTS -> 9f
        DmEffect.RAIN -> 8f
        else -> 5.4f
    }
    var time by remember(playKey) { mutableFloatStateOf(0f) }
    var done by remember(playKey) { androidx.compose.runtime.mutableStateOf(false) }
    val seed = remember(playKey) { com.mediaviewer.platform.nanoTime() xor (playKey.toLong() shl 20) }
    LaunchedEffect(playKey) {
        val start = withFrameNanos { it }
        while (time < duration) {
            withFrameNanos { now -> time = (now - start) / 1_000_000_000f }
        }
        done = true
    }
    if (done) return
    Canvas(modifier.fillMaxSize()) {
        when (effect) {
            DmEffect.SNOW -> drawSnow(time, seed)
            DmEffect.FIREWORKS -> drawFireworks(time, seed)
            DmEffect.BIRTHDAY -> drawBalloons(time, seed)
            DmEffect.HEARTS -> drawHearts(time, seed, colors, duration)
            DmEffect.RAIN -> drawRain(time, seed, duration)
            else -> drawBats(time, seed)
        }
    }
}

private val PARTY_PALETTE = listOf(
    Color(0xFFFF4FA1), Color(0xFFFFD166), Color(0xFF4FC3F7), Color(0xFF7CFFB2), Color(0xFFB388FF), Color(0xFFFF8A65)
)

/** Balloons: a loose bunch rises from below the screen and off the top,
 *  each swaying on its own string. */
private fun DrawScope.drawBalloons(t: Float, seed: Long) {
    val rnd = Random(seed)
    val unit = size.width / 400f
    repeat(16) {
        val x0 = (0.06f + rnd.nextFloat() * 0.88f) * size.width
        val delay = rnd.nextFloat() * 2.4f
        val rise = 3.6f + rnd.nextFloat() * 1.6f
        val sway = (10f + rnd.nextFloat() * 20f) * unit
        val freq = 0.8f + rnd.nextFloat() * 1.1f
        val phase = rnd.nextFloat() * 6.28f
        val r = (17f + rnd.nextFloat() * 11f) * unit
        val color = PARTY_PALETTE[rnd.nextInt(PARTY_PALETTE.size)]
        val p = (t - delay) / rise
        if (p < 0f || p > 1f) return@repeat
        val tall = r * 1.22f
        val stringLength = r * 3.2f
        val x = x0 + sin(t * freq + phase) * sway
        val y = size.height + tall + p * -(size.height + tall * 2f + stringLength)
        // Leans the way it's drifting.
        val lean = cos(t * freq + phase) * 0.12f
        val knotY = y + tall
        // The string trails below, wavering.
        val string = Path().apply {
            moveTo(x, knotY + 3f * unit)
            val steps = 6
            for (i in 1..steps) {
                val f = i / steps.toFloat()
                lineTo(x - lean * stringLength * f + sin(t * 3f + phase + f * 5f) * 2.4f * unit * f, knotY + 3f * unit + stringLength * f)
            }
        }
        drawPath(string, Color.White.copy(alpha = 0.55f), style = Stroke(width = 1f * unit))
        // The knot.
        val knot = Path().apply {
            moveTo(x, knotY - 1f * unit)
            lineTo(x - 3.2f * unit, knotY + 4f * unit)
            lineTo(x + 3.2f * unit, knotY + 4f * unit)
            close()
        }
        drawPath(knot, lerp(color, Color.Black, 0.2f))
        // The balloon: lit from the top left, darker at the bottom right.
        drawOval(
            brush = Brush.radialGradient(
                listOf(lerp(color, Color.White, 0.55f), color, lerp(color, Color.Black, 0.35f)),
                center = Offset(x - r * 0.35f, y - tall * 0.4f), radius = r * 1.9f
            ),
            topLeft = Offset(x - r, y - tall), size = Size(r * 2f, tall * 2f)
        )
        // Its shine.
        drawOval(
            Color.White.copy(alpha = 0.5f),
            topLeft = Offset(x - r * 0.62f, y - tall * 0.72f), size = Size(r * 0.34f, tall * 0.5f)
        )
    }
}

/** A heart, point down, centred on the origin and about 2 units wide. */
private fun heartPath(): Path = Path().apply {
    moveTo(0f, 0.95f)
    cubicTo(-0.25f, 0.7f, -1.05f, 0.2f, -1.05f, -0.35f)
    cubicTo(-1.05f, -0.85f, -0.35f, -1.05f, 0f, -0.5f)
    cubicTo(0.35f, -1.05f, 1.05f, -0.85f, 1.05f, -0.35f)
    cubicTo(1.05f, 0.2f, 0.25f, 0.7f, 0f, 0.95f)
    close()
}

/** Hearts: they float up slowly, each turning about its upright axis — it
 *  narrows to its edge, shows its darker back, and comes round again —
 *  in the sender's own profile colors. */
private fun DrawScope.drawHearts(t: Float, seed: Long, colors: List<Color>, duration: Float) {
    val rnd = Random(seed)
    val unit = size.width / 400f
    val palette = colors.filter { it.alpha > 0f }.ifEmpty { listOf(Color(0xFFFF4FA1), Color(0xFFFF7EB9)) }
    val heart = heartPath()
    repeat(18) { i ->
        val x0 = (0.07f + rnd.nextFloat() * 0.86f) * size.width
        val delay = rnd.nextFloat() * (duration - 5.6f).coerceAtLeast(0.5f)
        val rise = 4.6f + rnd.nextFloat() * 1.0f
        val sway = (8f + rnd.nextFloat() * 16f) * unit
        val freq = 0.5f + rnd.nextFloat() * 0.7f
        val phase = rnd.nextFloat() * 6.28f
        val s = (13f + rnd.nextFloat() * 13f) * unit
        val spinSpeed = (0.9f + rnd.nextFloat() * 0.8f) * (if (rnd.nextBoolean()) 1f else -1f)
        val base = palette[i % palette.size]
        val p = (t - delay) / rise
        if (p < 0f || p > 1f) return@repeat
        val x = x0 + sin(t * freq + phase) * sway
        val y = size.height + s * 1.2f - p * (size.height + s * 2.4f)
        val angle = t * spinSpeed + phase
        val facing = cos(angle)
        // Edge-on it's a sliver, never nothing; the back is the darker side.
        val width = (kotlin.math.abs(facing)).coerceAtLeast(0.06f)
        val front = facing >= 0f
        val lit = lerp(base, Color.White, 0.18f + 0.22f * kotlin.math.abs(facing))
        val body = if (front) lit else lerp(base, Color.Black, 0.38f)
        val fade = (p * 8f).coerceAtMost(1f) * ((1f - p) * 5f).coerceAtMost(1f)
        withTransform({
            translate(x, y)
            scale(s * width, s, pivot = Offset.Zero)
        }) {
            // The rim that gives it thickness as it turns.
            drawPath(heart, lerp(base, Color.Black, 0.5f).copy(alpha = fade), style = Stroke(width = 0.16f))
            drawPath(
                heart,
                Brush.linearGradient(
                    listOf(lerp(body, Color.White, if (front) 0.35f else 0.05f), body, lerp(body, Color.Black, 0.3f)),
                    start = Offset(-1f, -1f), end = Offset(1f, 1f)
                ),
                alpha = fade
            )
            // A soft shine on the front's upper left lobe.
            if (front) drawOval(
                Color.White.copy(alpha = 0.42f * fade * facing),
                topLeft = Offset(-0.72f, -0.72f), size = Size(0.42f, 0.3f)
            )
        }
    }
}

/** Rain: streaks slanting down from the top right to the bottom left,
 *  with a small splash where each one lands. */
private fun DrawScope.drawRain(t: Float, seed: Long, duration: Float) {
    val rnd = Random(seed)
    val unit = size.width / 400f
    // Builds, holds, then eases off.
    val strength = (t / 1.2f).coerceAtMost(1f) * ((duration - t) / 1.6f).coerceIn(0f, 1f)
    // How far left a drop travels for each unit it falls.
    val slant = 0.42f
    val reachX = size.height * slant
    repeat(150) {
        val start = rnd.nextFloat()
        val fall = 0.55f + rnd.nextFloat() * 0.4f
        val offset = rnd.nextFloat() * fall
        val length = (16f + rnd.nextFloat() * 22f) * unit
        val depth = 0.35f + rnd.nextFloat() * 0.65f
        val landing = 0.82f + rnd.nextFloat() * 0.18f
        // Each drop falls over and over, starting somewhere along the top
        // (and beyond the right edge, so the left side is rained on too).
        val p = ((t + offset) % fall) / fall
        val topX = start * (size.width + reachX)
        val y = p * size.height * landing
        val x = topX - y * slant
        val alpha = (0.2f + 0.5f * depth) * strength
        if (alpha <= 0.01f) return@repeat
        if (p < 0.94f) {
            drawLine(
                Color(0xFFCFE8FF).copy(alpha = alpha),
                Offset(x + length * slant, y - length), Offset(x, y),
                strokeWidth = (0.8f + 1.1f * depth) * unit, cap = StrokeCap.Round
            )
        } else {
            // Landed: a tiny ring opens and fades.
            val q = (p - 0.94f) / 0.06f
            val ly = size.height * landing
            val lx = topX - ly * slant
            drawOval(
                Color(0xFFCFE8FF).copy(alpha = alpha * (1f - q)),
                topLeft = Offset(lx - 7f * unit * q, ly - 2f * unit * q), size = Size(14f * unit * q, 4f * unit * q),
                style = Stroke(width = 0.9f * unit)
            )
        }
    }
}

// ── Bubbles ─────────────────────────────────────────────────────────────

private class Bubble(
    val id: Int,
    val x0: Float, val restY: Float, val radius: Float,
    val delay: Float, val rise: Float,
    val swayX: Float, val swayY: Float, val freq: Float, val phase: Float,
    /** When it pops by itself, if nobody pops it first. */
    val popsAt: Float
) {
    var poppedAt by androidx.compose.runtime.mutableStateOf(-1f)
}

/** How long a pop takes to play. */
private const val POP_SECONDS = 0.32f

/**
 * Bubbles: they rise slowly from the bottom, float about for a while, and
 * then pop one at a time. Each is a see-through bubble that blurs what's
 * behind it (when there's a [backdrop] to blur), and tapping one pops it
 * with a firm tap you can feel. Everywhere that isn't a bubble, touches
 * pass through to the chat.
 */
@Composable
private fun BubblesEffect(playKey: Int, modifier: Modifier, backdrop: GlassBackdrop?) {
    val timeState = remember(playKey) { mutableFloatStateOf(0f) }
    val doneState = remember(playKey) { androidx.compose.runtime.mutableStateOf(false) }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        if (w > 0f && h > 0f && !doneState.value) BubbleField(playKey, w, h, backdrop, timeState, doneState)
    }
}

/** All the bubbles of one run, in a [w]×[h] pixel area. */
@Composable
private fun BubbleField(
    playKey: Int, w: Float, h: Float, backdrop: GlassBackdrop?,
    timeState: androidx.compose.runtime.MutableFloatState,
    doneState: androidx.compose.runtime.MutableState<Boolean>
) {
    val view = rememberPlatformView()
    var time by timeState
    var done by doneState
    val bubbles = remember(playKey, w, h) {
        val rnd = Random(com.mediaviewer.platform.nanoTime() xor (playKey.toLong() shl 20))
        val unit = w / 400f
        val count = 14
        // They pop in a shuffled order, spaced out, after floating a while.
        val order = (0 until count).shuffled(rnd)
        List(count) { i ->
            Bubble(
                id = i,
                x0 = (0.1f + rnd.nextFloat() * 0.8f) * w,
                restY = (0.14f + rnd.nextFloat() * 0.6f) * h,
                radius = (20f + rnd.nextFloat() * 24f) * unit,
                delay = rnd.nextFloat() * 2.6f,
                rise = 4.2f + rnd.nextFloat() * 2.2f,
                swayX = (10f + rnd.nextFloat() * 18f) * unit,
                swayY = (6f + rnd.nextFloat() * 10f) * unit,
                freq = 0.35f + rnd.nextFloat() * 0.45f,
                phase = rnd.nextFloat() * 6.28f,
                popsAt = 10.5f + order.indexOf(i) * 0.45f
            )
        }
    }
    LaunchedEffect(playKey, bubbles) {
        val start = withFrameNanos { it }
        while (true) {
            withFrameNanos { now -> time = (now - start) / 1_000_000_000f }
            var left = false
            for (b in bubbles) {
                if (b.poppedAt < 0f && time >= b.popsAt) {
                    b.poppedAt = time
                    runCatching { view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
                }
                if (b.poppedAt < 0f || time - b.poppedAt < POP_SECONDS) left = true
            }
            if (!left) break
        }
        done = true
    }
    for (b in bubbles) {
        androidx.compose.runtime.key(b.id) { OneBubble(b, h, backdrop, timeState) }
    }
}

@Composable
private fun OneBubble(b: Bubble, h: Float, backdrop: GlassBackdrop?, timeState: androidx.compose.runtime.MutableFloatState) {
    val view = rememberPlatformView()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val time by timeState
    val popped = b.poppedAt
    // Gone once its pop has played.
    if (popped >= 0f && time - popped >= POP_SECONDS) return
    if (time < b.delay) return
    val sizeDp = with(density) { (b.radius * 2f).toDp() }
    val place = Modifier
        .offset {
            val t = time
            // Rises, easing to a stop where it will float…
            val p = ((t - b.delay) / b.rise).coerceIn(0f, 1f)
            val e = 1f - (1f - p) * (1f - p) * (1f - p)
            val fromY = h + b.radius * 2f
            // …then drifts about that spot.
            val x = b.x0 + sin(t * b.freq + b.phase) * b.swayX
            val y = fromY + (b.restY - fromY) * e + cos(t * b.freq * 0.8f + b.phase) * b.swayY * e
            IntOffset((x - b.radius).roundToInt(), (y - b.radius).roundToInt())
        }
        .size(sizeDp)
        .graphicsLayer {
            if (popped >= 0f) {
                // The pop: it swells a little and is gone.
                val q = ((time - popped) / POP_SECONDS).coerceIn(0f, 1f)
                val s = 1f + 0.35f * q
                scaleX = s; scaleY = s; alpha = (1f - q) * (1f - q)
            } else {
                // A gentle wobble, like a real one.
                val wob = sin(time * 2.2f + b.phase) * 0.035f
                scaleX = 1f + wob; scaleY = 1f - wob
            }
        }
        .then(
            if (popped < 0f) Modifier.pointerInput(b.id) {
                detectTapGestures {
                    if (b.poppedAt < 0f) {
                        b.poppedAt = time
                        runCatching { view.crunchHaptic() }
                    }
                }
            } else Modifier
        )
    if (backdrop != null && popped < 0f) {
        LiquidGlassSurface(place, shape = CircleShape, tint = Color.White, backdrop = backdrop) {
            Canvas(Modifier.matchParentSize()) { drawBubble(glass = true, popping = 0f) }
        }
    } else {
        Canvas(place) {
            drawBubble(glass = false, popping = if (popped >= 0f) ((time - popped) / POP_SECONDS).coerceIn(0f, 1f) else 0f)
        }
    }
}

/** One soap bubble filling the canvas: a thin bright skin with a hint of
 *  colour around the rim and two highlights. Over glass it's only the
 *  skin; without glass behind, a faint fill stands in for it. */
private fun DrawScope.drawBubble(glass: Boolean, popping: Float) {
    val r = size.minDimension / 2f
    val c = Offset(size.width / 2f, size.height / 2f)
    if (popping > 0f) {
        // Droplets fly out from where the skin was.
        repeat(9) { i ->
            val a = i / 9f * 6.2832f + 0.4f
            val d = r * (0.9f + 0.5f * popping)
            drawCircle(
                Color.White.copy(alpha = 0.8f * (1f - popping)), radius = r * 0.06f,
                center = Offset(c.x + cos(a) * d, c.y + sin(a) * d)
            )
        }
    }
    if (!glass) drawCircle(
        Brush.radialGradient(
            listOf(Color.White.copy(alpha = 0.03f), Color.White.copy(alpha = 0.07f), Color(0xFFBFE6FF).copy(alpha = 0.22f)),
            center = c, radius = r
        ),
        radius = r, center = c
    )
    // The skin: the colours of a soap film, round the edge.
    drawCircle(
        Brush.sweepGradient(
            listOf(
                Color(0xFFFFB3E6).copy(alpha = 0.75f), Color(0xFFB3E5FF).copy(alpha = 0.75f), Color(0xFFCFFFE0).copy(alpha = 0.75f),
                Color(0xFFFFF3B0).copy(alpha = 0.75f), Color(0xFFD6C2FF).copy(alpha = 0.75f), Color(0xFFFFB3E6).copy(alpha = 0.75f)
            ),
            center = c
        ),
        radius = r - r * 0.035f, center = c, style = Stroke(width = r * 0.07f)
    )
    // Inner glow just inside the skin.
    drawCircle(
        Brush.radialGradient(0.72f to Color.Transparent, 1f to Color.White.copy(alpha = 0.22f), center = c, radius = r),
        radius = r, center = c
    )
    // The window-like highlight, upper left, and its small echo opposite.
    drawArc(
        Color.White.copy(alpha = 0.75f), startAngle = 200f, sweepAngle = 55f, useCenter = false,
        topLeft = Offset(c.x - r * 0.72f, c.y - r * 0.72f), size = Size(r * 1.44f, r * 1.44f),
        style = Stroke(width = r * 0.09f, cap = StrokeCap.Round)
    )
    drawCircle(Color.White.copy(alpha = 0.7f), radius = r * 0.07f, center = Offset(c.x - r * 0.42f, c.y - r * 0.5f))
    drawArc(
        Color.White.copy(alpha = 0.3f), startAngle = 25f, sweepAngle = 40f, useCenter = false,
        topLeft = Offset(c.x - r * 0.78f, c.y - r * 0.78f), size = Size(r * 1.56f, r * 1.56f),
        style = Stroke(width = r * 0.05f, cap = StrokeCap.Round)
    )
}

/** Snow: flakes drift down slowly, a few at first, then thinning out. */
private fun DrawScope.drawSnow(t: Float, seed: Long) {
    val rnd = Random(seed)
    val unit = size.width / 400f
    repeat(80) {
        val x0 = rnd.nextFloat()
        val delay = rnd.nextFloat() * 4.2f
        val fall = 4.5f + rnd.nextFloat() * 2.3f
        val sway = (8f + rnd.nextFloat() * 22f) * unit
        val freq = 0.5f + rnd.nextFloat() * 1.1f
        val phase = rnd.nextFloat() * 6.28f
        val r = (1.4f + rnd.nextFloat() * 2.6f) * unit
        val p = (t - delay) / fall
        if (p in 0f..1f) {
            val x = x0 * size.width + sin(t * freq + phase) * sway
            val y = -10f * unit + p * (size.height + 20f * unit)
            // Fades in at the top and out near the bottom.
            val a = (p * 6f).coerceAtMost(1f) * ((1f - p) * 5f).coerceAtMost(1f)
            drawCircle(Color.White.copy(alpha = 0.85f * a), radius = r, center = Offset(x, y))
            drawCircle(Color.White.copy(alpha = 0.18f * a), radius = r * 2.2f, center = Offset(x, y))
        }
    }
}

/** Fireworks: rockets climb from the bottom and burst into falling sparks. */
private fun DrawScope.drawFireworks(t: Float, seed: Long) {
    val rnd = Random(seed)
    val unit = size.width / 400f
    val palette = listOf(
        Color(0xFFFF4FA1), Color(0xFFFFD166), Color(0xFF4FC3F7), Color(0xFF7CFFB2), Color(0xFFB388FF), Color(0xFFFF8A65)
    )
    repeat(7) { b ->
        val launch = b * 0.5f + rnd.nextFloat() * 0.3f
        val x = (0.14f + rnd.nextFloat() * 0.72f) * size.width
        val peak = (0.16f + rnd.nextFloat() * 0.3f) * size.height
        val color = palette[rnd.nextInt(palette.size)]
        val second = palette[rnd.nextInt(palette.size)]
        val rise = 0.75f
        val power = (95f + rnd.nextFloat() * 70f) * unit
        val sparks = 34
        // (Every spark's direction is drawn from the seed even while the
        // rocket is still climbing, so the sequence stays the same each frame.)
        val angles = FloatArray(sparks) { rnd.nextFloat() * 6.2832f }
        val speeds = FloatArray(sparks) { 0.45f + rnd.nextFloat() * 0.55f }
        val local = t - launch
        if (local < 0f) return@repeat
        if (local < rise) {
            // Climbing: eases out as it nears the top, with a short tail.
            val p = local / rise
            val e = 1f - (1f - p) * (1f - p)
            val y = size.height + (peak - size.height) * e
            drawLine(
                Color.White.copy(alpha = 0.55f), Offset(x, y + 26f * unit * (1f - p)), Offset(x, y),
                strokeWidth = 2.2f * unit
            )
            drawCircle(Color.White, radius = 2.4f * unit, center = Offset(x, y))
        } else {
            val tau = local - rise
            val life = 1.7f
            if (tau < life) {
                val fade = (1f - tau / life)
                // Fast at first, slowing with drag; gravity pulls them down.
                val reach = (1f - exp(-3.2f * tau)) / 3.2f * 3.2f
                val drop = 46f * unit * tau * tau
                for (i in 0 until sparks) {
                    val d = power * speeds[i] * reach
                    val px = x + cos(angles[i]) * d
                    val py = peak + sin(angles[i]) * d + drop
                    val c = if (i % 3 == 0) second else color
                    drawCircle(c.copy(alpha = fade), radius = (1.2f + 1.6f * fade) * unit, center = Offset(px, py))
                }
                // The flash at the moment it bursts.
                if (tau < 0.18f) drawCircle(Color.White.copy(alpha = (1f - tau / 0.18f) * 0.7f), radius = 30f * unit * (tau / 0.18f + 0.3f), center = Offset(x, peak))
            }
        }
    }
}

/** Bats: a small flock flaps across the screen, each on its own wavy path. */
private fun DrawScope.drawBats(t: Float, seed: Long) {
    val rnd = Random(seed)
    val unit = size.width / 400f
    repeat(10) {
        val delay = rnd.nextFloat() * 1.7f
        val cross = 2.3f + rnd.nextFloat() * 1.3f
        val leftToRight = rnd.nextBoolean()
        val y0 = (0.1f + rnd.nextFloat() * 0.6f) * size.height
        val wobble = (14f + rnd.nextFloat() * 26f) * unit
        val wobbleFreq = 2f + rnd.nextFloat() * 2.5f
        val scale = (0.7f + rnd.nextFloat() * 0.9f) * unit
        val flapFreq = 9f + rnd.nextFloat() * 5f
        val phase = rnd.nextFloat() * 6.28f
        val p = (t - delay) / cross
        if (p in 0f..1f) {
            val span = size.width + 120f * unit
            val x = if (leftToRight) -60f * unit + p * span else size.width + 60f * unit - p * span
            val y = y0 + sin(t * wobbleFreq + phase) * wobble - p * 30f * unit
            // Wings beat up and down.
            val flap = sin(t * flapFreq + phase)
            drawBat(Offset(x, y), scale, flap, if (leftToRight) 1f else -1f)
        }
    }
}

private fun DrawScope.drawBat(c: Offset, s: Float, flap: Float, dir: Float) {
    val body = Color(0xFF1B1026)
    val edge = Color(0xFFB388FF).copy(alpha = 0.55f)
    val tipY = -9f * s * flap          // wing tips rise and fall
    val path = Path().apply {
        // Head and ears.
        moveTo(c.x - 3f * s, c.y - 5f * s)
        lineTo(c.x - 2.2f * s, c.y - 9f * s)
        lineTo(c.x, c.y - 6f * s)
        lineTo(c.x + 2.2f * s, c.y - 9f * s)
        lineTo(c.x + 3f * s, c.y - 5f * s)
        // Right wing: out to the tip, then the scalloped trailing edge back in.
        quadraticTo(c.x + 12f * s, c.y - 8f * s + tipY * 0.6f, c.x + 22f * s, c.y - 2f * s + tipY)
        quadraticTo(c.x + 17f * s, c.y + 1f * s + tipY * 0.6f, c.x + 14f * s, c.y + 5f * s + tipY * 0.5f)
        quadraticTo(c.x + 10f * s, c.y + 1f * s + tipY * 0.3f, c.x + 7f * s, c.y + 5f * s + tipY * 0.2f)
        quadraticTo(c.x + 4f * s, c.y + 2f * s, c.x + 2.5f * s, c.y + 6f * s)
        // Tail.
        lineTo(c.x, c.y + 8f * s)
        lineTo(c.x - 2.5f * s, c.y + 6f * s)
        // Left wing, mirrored.
        quadraticTo(c.x - 4f * s, c.y + 2f * s, c.x - 7f * s, c.y + 5f * s + tipY * 0.2f)
        quadraticTo(c.x - 10f * s, c.y + 1f * s + tipY * 0.3f, c.x - 14f * s, c.y + 5f * s + tipY * 0.5f)
        quadraticTo(c.x - 17f * s, c.y + 1f * s + tipY * 0.6f, c.x - 22f * s, c.y - 2f * s + tipY)
        quadraticTo(c.x - 12f * s, c.y - 8f * s + tipY * 0.6f, c.x - 3f * s, c.y - 5f * s)
        close()
    }
    drawPath(path, body)
    drawPath(path, edge, style = Stroke(width = 0.9f * s))
    // Two tiny eyes, looking the way it's flying.
    drawCircle(Color(0xFFFFD166), radius = 0.7f * s, center = Offset(c.x - 1.2f * s + dir * 0.4f * s, c.y - 4.6f * s))
    drawCircle(Color(0xFFFFD166), radius = 0.7f * s, center = Offset(c.x + 1.2f * s + dir * 0.4f * s, c.y - 4.6f * s))
}
