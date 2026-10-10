package com.mediaviewer.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mediaviewer.platform.PlatformContext
import com.mediaviewer.platform.SharedPreferences
import com.mediaviewer.platform.sharedPreferences
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// ─────────────────────────────────────────────────────────────────────────────
// Tips: the first-time walkthroughs.
//
// Not popups — the whole screen dims, the parts being talked about stay lit,
// and plain text sits over the dimmed page, with a line in the profile color
// running from the text to what it's about (only ever straight across or
// straight up/down, turning sharply where it has to) and ending in a dot.
// Some steps play a small gesture animation instead. Tap anywhere moves on.
//
// Each walkthrough (a "tour", see TipTours.kt) shows once per phone, the
// first time its screen is opened; Dev Tools › Reset Tips brings them all
// back.
//
// Anything on screen can be pointed at: give it Modifier.tipAnchor("id")
// and use that id in a tour.
// ─────────────────────────────────────────────────────────────────────────────

/** Where a note's text goes. Fractions are of the screen (0…1). */
sealed class TipPlace {
    /** At ([x], [y]) on screen; [pin] says which part of the text sits on
     *  that point across: 0 = its left edge, 0.5 = its middle, 1 = its right edge. */
    data class Screen(val x: Float, val y: Float, val pin: Float = 0.5f) : TipPlace()
    /** Above [anchor], [gap] clear of it. [pin] lines the text up with the
     *  anchor: 0 = left edges, 0.5 = centres, 1 = right edges. */
    data class Above(val anchor: String, val gap: Dp = 56.dp, val pin: Float = 0.5f, val shift: Dp = 0.dp) : TipPlace()
    /** Below [anchor] (same rules as [Above]). */
    data class Below(val anchor: String, val gap: Dp = 56.dp, val pin: Float = 0.5f, val shift: Dp = 0.dp) : TipPlace()
}

/**
 * One piece of text. [anchors] are lit up and each gets a line from the
 * text. **Words in double asterisks** are drawn bold in the profile color.
 */
data class TipNote(
    val text: String,
    val place: TipPlace,
    val title: String? = null,
    val anchors: List<String> = emptyList(),
    val width: Dp = 280.dp,
    /** Null: follows the place (left / centred / right). */
    val align: TextAlign? = null
)

/** The little looping gesture pictures. */
enum class TipAnim { PINCH_EXPLORE, DOUBLE_TAP_LIKE, HOLD_WHEEL, ZOOM, THREE_FINGERS, SWIPE_SIDEWAYS, SWIPE_VERTICAL, TAP, DRAG_HOLD }

data class TipAnimSpec(
    val anim: TipAnim,
    val x: Float, val y: Float,
    val size: Dp = 120.dp,
    val caption: String? = null
)

/** One screenful of a walkthrough. */
data class TipStep(
    val notes: List<TipNote> = emptyList(),
    /** Lit up without a line (the notes' own anchors are lit anyway). */
    val highlights: List<String> = emptyList(),
    val anims: List<TipAnimSpec> = emptyList(),
    /** Where "Tap to continue." goes, as a fraction of the screen's height. */
    val continueY: Float = 0.93f
)

class TipTour(val id: String, val steps: List<TipStep>)

/** Where the things tours point at are on screen. */
object TipAnchors {
    private val all = HashMap<String, LinkedHashMap<Any, Rect>>()
    private val live = mutableStateMapOf<String, Rect>()
    /** Only kept in Compose state while a tour is up, so scrolling a page
     *  full of anchors costs nothing the rest of the time. */
    private var publishing = false

    internal fun report(id: String, token: Any, rect: Rect) {
        val m = all.getOrPut(id) { LinkedHashMap() }
        if (m[token] == rect) return
        m.remove(token); m[token] = rect
        if (publishing) resolve(id)
    }

    internal fun drop(id: String, token: Any) {
        all[id]?.remove(token)
        if (publishing) resolve(id)
    }

    private fun resolve(id: String) {
        val r = all[id]?.values?.lastOrNull { it.width > 2f && it.height > 2f }
        if (r == null) live.remove(id) else if (live[id] != r) live[id] = r
    }

    internal fun setPublishing(on: Boolean) {
        if (on == publishing) return
        publishing = on
        if (on) all.keys.toList().forEach { resolve(it) } else live.clear()
    }

    /**
     * [id]'s bounds in root coordinates, or null when it isn't on screen.
     * "base@l,t,r,b" is a part of anchor "base", given as fractions of its
     * width and height (for a row of fixed-size buttons that isn't worth
     * anchoring one by one).
     */
    fun rect(id: String): Rect? {
        val at = id.indexOf('@')
        if (at < 0) return live[id]
        val base = live[id.substring(0, at)] ?: return null
        val f = id.substring(at + 1).split(',').mapNotNull { it.trim().toFloatOrNull() }
        if (f.size != 4) return base
        return Rect(
            base.left + base.width * f[0], base.top + base.height * f[1],
            base.left + base.width * f[2], base.top + base.height * f[3]
        )
    }
}

/** Lets tours point at this element as [id] (null: nothing). */
fun Modifier.tipAnchor(id: String?): Modifier = if (id == null) this else composed {
    val token = remember { Any() }
    DisposableEffect(id) { onDispose { TipAnchors.drop(id, token) } }
    onGloballyPositioned { TipAnchors.report(id, token, it.boundsInRoot()) }
}

/** Which walkthroughs have been seen, and the one showing now. */
object Tips {
    private var prefs: SharedPreferences? = null
    private var seen by mutableStateOf<Set<String>>(emptySet())

    var tour by mutableStateOf<TipTour?>(null)
        private set
    var step by mutableStateOf(0)
        private set
    /** Something else (the welcome popups) is up: tours wait. */
    var blocked by mutableStateOf(false)
    /** The Hub is on its main page (not its Settings page). */
    var hubMainShowing by mutableStateOf(true)

    fun init(context: PlatformContext) {
        if (prefs != null) return
        val p = context.sharedPreferences("stellar_tips")
        prefs = p
        seen = p.getStringSet("seen", null)?.toSet() ?: emptySet()
    }

    fun isSeen(id: String): Boolean = prefs == null || id in seen

    /** Starts [id]'s walkthrough if it hasn't been seen and nothing else is
     *  showing. True if it started. */
    fun request(id: String): Boolean {
        if (prefs == null || blocked || tour != null || id in seen) return false
        val t = TipTours.byId(id) ?: return false
        TipAnchors.setPublishing(true)
        step = 0
        tour = t
        return true
    }

    fun next() {
        val t = tour ?: return
        if (step < t.steps.size - 1) step++ else finish()
    }

    /** Closes the current walkthrough for good. */
    fun finish() {
        val t = tour ?: return
        seen = seen + t.id
        prefs?.edit()?.putStringSet("seen", seen)?.apply()
        tour = null
        // (The anchors stop being tracked once the overlay has faded out.)
    }

    /** Dev Tools › Reset Tips: every walkthrough shows again. */
    fun reset() {
        seen = emptySet()
        prefs?.edit()?.putStringSet("seen", emptySet())?.apply()
    }
}

private fun emphasize(text: String, accent: Color): AnnotatedString = buildAnnotatedString {
    val parts = text.split("**")
    parts.forEachIndexed { i, part ->
        if (i % 2 == 1) withStyle(SpanStyle(color = accent, fontWeight = FontWeight.Bold)) { append(part) }
        else append(part)
    }
}

/**
 * Draws the walkthrough that's up, over everything. Put it last in the app's
 * root Box. [tint] is the signed-in account's profile color.
 */
@Composable
fun TipOverlay(tint: Color, modifier: Modifier = Modifier) {
    val tour = Tips.tour
    // Kept while fading out after the last tap.
    val shownTour = remember { arrayOfNulls<TipTour>(1) }
    val shownStep = remember { intArrayOf(0) }
    if (tour != null) { shownTour[0] = tour; shownStep[0] = Tips.step }
    val visible = remember { Animatable(0f) }
    LaunchedEffect(tour != null) {
        visible.animateTo(if (tour != null) 1f else 0f, tween(if (tour != null) 380 else 260))
        if (Tips.tour == null) TipAnchors.setPublishing(false)
    }
    val t = shownTour[0] ?: return
    if (tour == null && visible.value <= 0.001f) return
    val stepIndex = shownStep[0].coerceIn(0, t.steps.size - 1)
    val step = t.steps[stepIndex]

    val density = LocalDensity.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(Size.Zero) }
    val accent = lerp(tint, Color.White, 0.35f)
    val lineColor = lerp(tint, Color.White, 0.2f)

    // Taps right after a step appears are ignored, so a quick double tap
    // can't skip text unread.
    var shownAt by remember { mutableStateOf(0L) }
    LaunchedEffect(t, stepIndex) { shownAt = com.mediaviewer.platform.currentTimeMillis() }
    com.mediaviewer.ui.compat.BackHandler(enabled = tour != null) { Tips.next() }

    // The step's lit-up parts fade between steps.
    val holeFade = remember { Animatable(1f) }
    val lastHoles = remember { mutableStateOf<List<String>>(emptyList()) }
    val holes = remember(t, stepIndex) { (step.highlights + step.notes.flatMap { it.anchors }).distinct() }
    var previousHoles by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(holes) {
        previousHoles = lastHoles.value
        lastHoles.value = holes
        holeFade.snapTo(0f)
        holeFade.animateTo(1f, tween(360, easing = FastOutSlowInEasing))
    }

    Box(
        modifier.fillMaxSize()
            .graphicsLayer { alpha = visible.value }
            .onGloballyPositioned { origin = it.positionInRoot(); size = Size(it.size.width.toFloat(), it.size.height.toFloat()) }
            // Every touch stays here: a tap moves on, nothing reaches the page.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val down = awaitPointerEvent()
                        down.changes.forEach { it.consume() }
                        val first = down.changes.firstOrNull() ?: continue
                        if (!first.pressed) continue
                        val start = first.position
                        var moved = false
                        while (true) {
                            val e = awaitPointerEvent()
                            e.changes.forEach { it.consume() }
                            val c = e.changes.firstOrNull { it.id == first.id } ?: break
                            if ((c.position - start).getDistance() > viewConfiguration.touchSlop * 2) moved = true
                            if (!c.pressed) break
                        }
                        if (!moved && com.mediaviewer.platform.currentTimeMillis() - shownAt > 450) Tips.next()
                    }
                }
            }
    ) {
        // ── Dim, with the lit parts cut out ──
        val padPx = with(density) { 6.dp.toPx() }
        val maxCorner = with(density) { 22.dp.toPx() }
        val ringPx = with(density) { 1.5.dp.toPx() }
        fun holeRect(id: String): Rect? = TipAnchors.rect(id)?.let {
            Rect(it.left - origin.x - padPx, it.top - origin.y - padPx, it.right - origin.x + padPx, it.bottom - origin.y + padPx)
        }
        Canvas(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
            drawRect(Color.Black.copy(alpha = 0.78f))
            fun hole(id: String, amount: Float) {
                val r = holeRect(id) ?: return
                val corner = min(maxCorner, min(r.width, r.height) / 2f)
                drawRoundRect(
                    Color.Black.copy(alpha = amount.coerceIn(0f, 1f)), topLeft = r.topLeft, size = r.size,
                    cornerRadius = CornerRadius(corner), blendMode = BlendMode.DstOut
                )
            }
            val f = holeFade.value
            previousHoles.filter { it !in holes }.forEach { hole(it, 1f - f) }
            holes.forEach { id -> hole(id, if (id in previousHoles) 1f else f) }
        }
        Canvas(Modifier.fillMaxSize()) {
            val f = holeFade.value
            holes.forEach { id ->
                val r = holeRect(id) ?: return@forEach
                val corner = min(maxCorner, min(r.width, r.height) / 2f)
                drawRoundRect(
                    lineColor.copy(alpha = 0.75f * (if (id in previousHoles) 1f else f)), topLeft = r.topLeft, size = r.size,
                    cornerRadius = CornerRadius(corner), style = Stroke(ringPx)
                )
            }
        }

        // ── This step's text, pictures and lines ──
        androidx.compose.animation.AnimatedContent(
            targetState = stepIndex,
            transitionSpec = {
                androidx.compose.animation.fadeIn(tween(320, delayMillis = 90))
                    .togetherWith(androidx.compose.animation.fadeOut(tween(180)))
            },
            label = "tipStep"
        ) { index ->
            val s = t.steps.getOrNull(index) ?: return@AnimatedContent
            StepContent(s, origin, size, accent, lineColor, isCurrent = { Tips.tour === t && Tips.step == index })
        }

        // ── Tap to continue ──
        val pulse = rememberInfiniteTransition(label = "tipContinue")
        val glow by pulse.animateFloat(0.45f, 0.8f, infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "glow")
        Text(
            "Tap to continue.",
            color = Color.White.copy(alpha = glow), fontSize = 13.sp, letterSpacing = 0.4.sp,
            modifier = Modifier.layout { m, c ->
                val p = m.measure(Constraints())
                layout(c.maxWidth, c.maxHeight) {
                    p.place((c.maxWidth - p.width) / 2, (c.maxHeight * step.continueY - p.height / 2f).toInt())
                }
            }
        )
    }
}

@Composable
private fun StepContent(step: TipStep, origin: Offset, screen: Size, accent: Color, lineColor: Color, isCurrent: () -> Boolean) {
    val density = LocalDensity.current
    val noteRects = remember(step) { mutableStateMapOf<Int, Rect>() }
    val draw = remember(step) { Animatable(0f) }
    LaunchedEffect(step) {
        kotlinx.coroutines.delay(260)
        draw.animateTo(1f, tween(520, easing = FastOutSlowInEasing))
    }
    val margin = with(density) { 20.dp.toPx() }
    fun anchorRect(id: String): Rect? = TipAnchors.rect(id)?.let { it.translate(-origin.x, -origin.y) }

    // A note pointing at things that aren't on screen right now is left out.
    fun shown(note: TipNote) = note.anchors.isEmpty() || note.anchors.any { TipAnchors.rect(it) != null }
    // Nothing of this step can be shown (say, the row it's about is
    // scrolled away): straight on to the next.
    LaunchedEffect(step) {
        kotlinx.coroutines.delay(350)
        if (isCurrent() && step.anims.isEmpty() && step.notes.none { shown(it) }) Tips.next()
    }

    Box(Modifier.fillMaxSize()) {
        // Lines first, under the text.
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 2.dp.toPx()
            val dot = 5.dp.toPx()
            val gap = 6.dp.toPx() + 6.dp.toPx() // the lit area's padding + a little air
            step.notes.forEachIndexed { i, note ->
                if (!shown(note)) return@forEachIndexed
                val box = noteRects[i] ?: return@forEachIndexed
                note.anchors.forEach { id ->
                    val target = anchorRect(id) ?: return@forEach
                    val path = elbowPath(box, target, gap, 12.dp.toPx()) ?: return@forEach
                    drawPartialPath(path, draw.value, lineColor, stroke, dot)
                }
            }
        }
        step.notes.forEachIndexed { i, note ->
            if (shown(note)) NoteText(note, i, origin, screen, margin, accent) { r -> noteRects[i] = r }
        }
        step.anims.forEach { a ->
            TipAnimation(a, accent)
        }
    }
}

/** Draws the first [progress] of [path], with the end dot once it's there. */
private fun DrawScope.drawPartialPath(path: Path, progress: Float, color: Color, stroke: Float, dot: Float) {
    if (progress <= 0f) return
    val measure = PathMeasure()
    measure.setPath(path, false)
    val length = measure.length
    if (length <= 0f) return
    val part = Path()
    measure.getSegment(0f, length * progress, part, true)
    drawPath(part, color, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Miter))
    if (progress >= 0.98f) {
        val end = measure.getPosition(length)
        drawCircle(color, radius = dot, center = end)
        drawCircle(Color.Black.copy(alpha = 0.35f), radius = dot * 0.45f, center = end)
    }
}

/**
 * A line from the text box [box] to [target] using only straight-across and
 * straight-up/down runs, stopping [gap] short of the target's edge.
 */
private fun elbowPath(box: Rect, target: Rect, gap: Float, inset: Float): Path? {
    if (box.overlaps(target)) return null
    val p = Path()
    val tcx = target.center.x
    val tcy = target.center.y
    val below = target.top >= box.bottom
    val above = target.bottom <= box.top
    when {
        below || above -> {
            val startY = if (below) box.bottom + 4f else box.top - 4f
            val endY = if (below) target.top - gap else target.bottom + gap
            if (tcx in (box.left + inset)..(box.right - inset)) {
                // Straight down/up.
                p.moveTo(tcx, startY); p.lineTo(tcx, endY)
            } else {
                // Down/up, across, down/up again.
                val startX = (if (tcx < box.left) box.left + inset else box.right - inset)
                val midY = (startY + endY) / 2f
                p.moveTo(startX, startY); p.lineTo(startX, midY); p.lineTo(tcx, midY); p.lineTo(tcx, endY)
            }
        }
        else -> {
            // Beside it: across, then up/down if the heights don't meet.
            val right = target.left >= box.right
            val startX = if (right) box.right + 6f else box.left - 6f
            if (tcy in (box.top + inset / 2)..(box.bottom - inset / 2)) {
                val endX = if (right) target.left - gap else target.right + gap
                p.moveTo(startX, tcy); p.lineTo(endX, tcy)
            } else {
                val startYm = box.center.y
                val endY = if (tcy > startYm) target.top - gap else target.bottom + gap
                p.moveTo(startX, startYm); p.lineTo(tcx, startYm); p.lineTo(tcx, endY)
            }
        }
    }
    return p
}

@Composable
private fun NoteText(
    note: TipNote, index: Int, origin: Offset, screen: Size, margin: Float, accent: Color, onPlaced: (Rect) -> Unit
) {
    val density = LocalDensity.current
    val pin = when (val pl = note.place) {
        is TipPlace.Screen -> pl.pin
        is TipPlace.Above -> pl.pin
        is TipPlace.Below -> pl.pin
    }
    val align = note.align ?: when {
        pin < 0.25f -> TextAlign.Start
        pin > 0.75f -> TextAlign.End
        else -> TextAlign.Center
    }
    val shadow = Shadow(Color.Black.copy(alpha = 0.9f), Offset(0f, 1f), 10f)
    Column(
        Modifier.layout { m, c ->
            val maxW = min(note.width.roundToPx(), (c.maxWidth - 2 * margin).toInt().coerceAtLeast(40))
            val p = m.measure(Constraints(maxWidth = maxW))
            val sw = c.maxWidth.toFloat(); val sh = c.maxHeight.toFloat()
            fun anchorRect(id: String): Rect? = TipAnchors.rect(id)?.translate(-origin.x, -origin.y)
            var x: Float
            var y: Float
            when (val pl = note.place) {
                is TipPlace.Screen -> {
                    x = sw * pl.x - p.width * pl.pin
                    y = sh * pl.y - p.height / 2f
                }
                is TipPlace.Above, is TipPlace.Below -> {
                    val id = if (pl is TipPlace.Above) pl.anchor else (pl as TipPlace.Below).anchor
                    val gap = (if (pl is TipPlace.Above) pl.gap else (pl as TipPlace.Below).gap).toPx()
                    val shift = (if (pl is TipPlace.Above) pl.shift else (pl as TipPlace.Below).shift).toPx()
                    val r = anchorRect(id)
                    if (r == null) {
                        x = sw / 2f - p.width / 2f; y = sh * 0.45f - p.height / 2f
                    } else {
                        x = when {
                            pin < 0.25f -> r.left + shift
                            pin > 0.75f -> r.right - p.width + shift
                            else -> r.center.x - p.width / 2f + shift
                        }
                        val wantAbove = pl is TipPlace.Above
                        val aboveY = r.top - gap - p.height
                        val belowY = r.bottom + gap
                        y = if (wantAbove) {
                            if (aboveY >= margin * 2) aboveY else belowY
                        } else {
                            if (belowY + p.height <= sh - margin * 3) belowY else aboveY
                        }
                    }
                }
            }
            x = x.coerceIn(margin, max(margin, sw - margin - p.width))
            y = y.coerceIn(margin * 2, max(margin * 2, sh - margin * 3 - p.height))
            layout(c.maxWidth, c.maxHeight) { p.place(IntOffset(x.toInt(), y.toInt())) }
        }
    ) {
        Column(Modifier.onGloballyPositioned { coords ->
            val r = coords.boundsInRoot()
            onPlaced(r.translate(-origin.x, -origin.y))
        }) {
            val horizontal = when (align) {
                TextAlign.Start -> Alignment.Start
                TextAlign.End -> Alignment.End
                else -> Alignment.CenterHorizontally
            }
            Column(horizontalAlignment = horizontal) {
                if (note.title != null) {
                    Text(
                        note.title, color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold, textAlign = align,
                        style = TextStyle(shadow = shadow), lineHeight = 28.sp
                    )
                    Spacer(Modifier.height(6.dp))
                }
                Text(
                    emphasize(note.text, accent), color = Color.White.copy(alpha = 0.94f), fontSize = 15.sp,
                    lineHeight = 21.sp, textAlign = align, style = TextStyle(shadow = shadow)
                )
            }
        }
    }
}

// ── The gesture pictures ────────────────────────────────────────────────────

@Composable
private fun TipAnimation(spec: TipAnimSpec, accent: Color) {
    val clock = rememberInfiniteTransition(label = "tipAnim")
    val period = when (spec.anim) {
        TipAnim.PINCH_EXPLORE -> 2600
        TipAnim.HOLD_WHEEL -> 2800
        TipAnim.THREE_FINGERS -> 2600
        else -> 2200
    }
    val time by clock.animateFloat(0f, 1f, infiniteRepeatable(tween(period, easing = LinearEasing)), label = "t")
    val shadow = Shadow(Color.Black.copy(alpha = 0.9f), Offset(0f, 1f), 10f)
    Column(
        Modifier.layout { m, c ->
            val p = m.measure(Constraints(maxWidth = c.maxWidth))
            layout(c.maxWidth, c.maxHeight) {
                val sizePx = spec.size.roundToPx()
                val x = (c.maxWidth * spec.x - p.width / 2f).toInt().coerceIn(0, max(0, c.maxWidth - p.width))
                val y = (c.maxHeight * spec.y - sizePx / 2f).toInt().coerceIn(0, max(0, c.maxHeight - p.height))
                p.place(x, y)
            }
        },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Canvas(Modifier.size(spec.size)) { drawGesture(spec.anim, time, accent) }
        if (spec.caption != null) {
            Spacer(Modifier.height(10.dp))
            Text(
                emphasize(spec.caption, accent), color = Color.White.copy(alpha = 0.94f), fontSize = 14.sp, lineHeight = 19.sp,
                textAlign = TextAlign.Center, style = TextStyle(shadow = shadow),
                modifier = Modifier.layout { m, c ->
                    val p = m.measure(Constraints(maxWidth = min(c.maxWidth, (spec.size + 60.dp).roundToPx())))
                    layout(p.width, p.height) { p.place(0, 0) }
                }
            )
        }
    }
}

/** 0 before [a], 1 after [b], eased between. */
private fun phase(t: Float, a: Float, b: Float): Float {
    if (t <= a) return 0f
    if (t >= b) return 1f
    val x = (t - a) / (b - a)
    return x * x * (3 - 2 * x)
}

private fun DrawScope.finger(at: Offset, pressed: Float, accent: Color) {
    val r = size.minDimension * 0.085f
    drawCircle(Color.White.copy(alpha = 0.18f + 0.2f * pressed), radius = r * (1.6f - 0.3f * pressed), center = at)
    drawCircle(Color.White.copy(alpha = 0.92f), radius = r * (1f - 0.15f * pressed), center = at)
    drawCircle(accent, radius = r * (1f - 0.15f * pressed), center = at, style = Stroke(r * 0.22f))
}

private fun DrawScope.ripple(at: Offset, t: Float, accent: Color) {
    if (t <= 0f || t >= 1f) return
    drawCircle(accent.copy(alpha = (1f - t) * 0.8f), radius = size.minDimension * (0.08f + 0.22f * t), center = at, style = Stroke(2.dp.toPx()))
}

private fun DrawScope.card(center: Offset, w: Float, h: Float, accent: Color, alpha: Float = 1f) {
    drawRoundRect(
        accent.copy(alpha = 0.22f * alpha), topLeft = Offset(center.x - w / 2, center.y - h / 2), size = Size(w, h),
        cornerRadius = CornerRadius(min(w, h) * 0.12f)
    )
    drawRoundRect(
        Color.White.copy(alpha = 0.75f * alpha), topLeft = Offset(center.x - w / 2, center.y - h / 2), size = Size(w, h),
        cornerRadius = CornerRadius(min(w, h) * 0.12f), style = Stroke(1.5.dp.toPx())
    )
}

private fun DrawScope.heart(center: Offset, s: Float, color: Color) {
    val p = Path()
    p.moveTo(center.x, center.y + s * 0.35f)
    p.cubicTo(center.x - s * 0.9f, center.y - s * 0.15f, center.x - s * 0.45f, center.y - s * 0.85f, center.x, center.y - s * 0.35f)
    p.cubicTo(center.x + s * 0.45f, center.y - s * 0.85f, center.x + s * 0.9f, center.y - s * 0.15f, center.x, center.y + s * 0.35f)
    p.close()
    drawPath(p, color)
}

private fun DrawScope.drawGesture(anim: TipAnim, t: Float, accent: Color) {
    val w = size.width; val h = size.height
    val c = Offset(w / 2, h / 2)
    when (anim) {
        TipAnim.PINCH_EXPLORE -> {
            // One post splitting into a grid as two fingers pinch in.
            val pinch = phase(t, 0.12f, 0.62f)
            val fade = 1f - phase(t, 0.85f, 1f)
            val big = w * 0.62f
            val gap = w * 0.05f
            val small = (big - gap) / 2f
            // The single post fades out as the four tiles fade in.
            card(c, big, big, accent, (1f - pinch) * fade + (1f - fade))
            if (pinch > 0f) {
                val spread = small / 2f + gap / 2f
                for (i in 0..1) for (j in 0..1) {
                    val cx = c.x + (if (i == 0) -1 else 1) * spread
                    val cy = c.y + (if (j == 0) -1 else 1) * spread
                    val s = small * (0.8f + 0.2f * pinch)
                    card(Offset(cx, cy), s, s, accent, pinch * fade)
                }
            }
            val reach = w * (0.42f - 0.26f * pinch)
            val press = phase(t, 0.05f, 0.12f) * (1f - phase(t, 0.66f, 0.74f))
            if (t < 0.78f) {
                finger(Offset(c.x - reach * 0.7071f, c.y - reach * 0.7071f), press, accent)
                finger(Offset(c.x + reach * 0.7071f, c.y + reach * 0.7071f), press, accent)
            }
        }
        TipAnim.DOUBLE_TAP_LIKE -> {
            card(c, w * 0.62f, h * 0.74f, accent)
            val tap1 = phase(t, 0.10f, 0.16f) * (1f - phase(t, 0.18f, 0.22f))
            val tap2 = phase(t, 0.26f, 0.32f) * (1f - phase(t, 0.34f, 0.38f))
            ripple(c, (t - 0.16f) / 0.3f, accent)
            ripple(c, (t - 0.32f) / 0.3f, accent)
            val pop = phase(t, 0.36f, 0.48f)
            val gone = phase(t, 0.78f, 0.95f)
            if (pop > 0f) heart(Offset(c.x, c.y - h * 0.02f - h * 0.08f * gone), w * 0.2f * (0.6f + 0.55f * pop - 0.15f * pop * pop), accent.copy(alpha = 1f - gone))
            if (t < 0.5f) finger(Offset(c.x + w * 0.12f, c.y + h * 0.16f), max(tap1, tap2), accent)
        }
        TipAnim.HOLD_WHEEL -> {
            // Press and hold: a ring fills, then the shortcut wheel opens and
            // the finger slides to one of them.
            val press = phase(t, 0.05f, 0.12f) * (1f - phase(t, 0.86f, 0.92f))
            val fill = phase(t, 0.12f, 0.40f)
            val open = phase(t, 0.40f, 0.52f) * (1f - phase(t, 0.88f, 0.96f))
            val slide = phase(t, 0.56f, 0.74f)
            val radius = w * 0.3f
            if (fill > 0f && open < 0.5f) {
                drawArc(
                    accent, -90f, 360f * fill, false, topLeft = Offset(c.x - w * 0.15f, c.y - w * 0.15f),
                    size = Size(w * 0.3f, w * 0.3f), style = Stroke(3.dp.toPx(), cap = StrokeCap.Round)
                )
            }
            if (open > 0f) {
                for (k in 0 until 8) {
                    val a = (k * 45f - 90f) * (PI.toFloat() / 180f)
                    val at = Offset(c.x + cos(a) * radius * open, c.y + sin(a) * radius * open)
                    val chosen = k == 1 && slide > 0.95f
                    drawCircle(if (chosen) accent else Color.White.copy(alpha = 0.25f * open), radius = w * 0.075f * open, center = at)
                    drawCircle(Color.White.copy(alpha = 0.8f * open), radius = w * 0.075f * open, center = at, style = Stroke(1.2.dp.toPx()))
                }
            }
            val a = (45f - 90f) * (PI.toFloat() / 180f)
            val at = Offset(c.x + cos(a) * radius * slide, c.y + sin(a) * radius * slide)
            if (t < 0.92f) finger(at, press, accent)
        }
        TipAnim.ZOOM -> {
            // Two fingers spreading apart: the picture grows.
            val spread = phase(t, 0.12f, 0.55f) * (1f - phase(t, 0.75f, 0.92f))
            val press = phase(t, 0.04f, 0.12f) * (1f - phase(t, 0.58f, 0.66f))
            val s = w * (0.4f + 0.36f * spread)
            card(c, s, s, accent)
            val reach = w * (0.12f + 0.24f * phase(t, 0.12f, 0.55f))
            if (t < 0.7f) {
                finger(Offset(c.x - reach * 0.7071f, c.y + reach * 0.7071f), press, accent)
                finger(Offset(c.x + reach * 0.7071f, c.y - reach * 0.7071f), press, accent)
            }
        }
        TipAnim.THREE_FINGERS -> {
            // Three fingers spread: the buttons around the post slip away.
            val spread = phase(t, 0.15f, 0.5f)
            val back = phase(t, 0.78f, 0.95f)
            val chrome = (1f - spread) + back
            card(c, w * 0.6f, h * 0.84f, accent)
            val barW = w * 0.46f
            drawRoundRect(Color.White.copy(alpha = 0.6f * chrome.coerceIn(0f, 1f)), topLeft = Offset(c.x - barW / 2, h * 0.14f), size = Size(barW, h * 0.07f), cornerRadius = CornerRadius(h * 0.035f))
            drawRoundRect(Color.White.copy(alpha = 0.6f * chrome.coerceIn(0f, 1f)), topLeft = Offset(c.x - barW / 2, h * 0.79f), size = Size(barW, h * 0.07f), cornerRadius = CornerRadius(h * 0.035f))
            val press = phase(t, 0.06f, 0.14f) * (1f - phase(t, 0.55f, 0.62f))
            if (t < 0.66f) {
                val reach = w * (0.08f + 0.22f * spread)
                for (k in 0 until 3) {
                    val a = (k * 120f - 90f) * (PI.toFloat() / 180f)
                    finger(Offset(c.x + cos(a) * reach, c.y + sin(a) * reach), press, accent)
                }
            }
        }
        TipAnim.SWIPE_SIDEWAYS, TipAnim.SWIPE_VERTICAL -> {
            val vertical = anim == TipAnim.SWIPE_VERTICAL
            // Back and forth, one direction per half.
            val half = if (t < 0.5f) 0 else 1
            val local = (t - half * 0.5f) / 0.5f
            val move = phase(local, 0.15f, 0.7f)
            val press = phase(local, 0.05f, 0.15f) * (1f - phase(local, 0.72f, 0.82f))
            val dir = if (half == 0) -1f else 1f
            val span = (if (vertical) h else w) * 0.3f
            val from = -dir * span
            val pos = from + (dir * span - from) * move
            val trail = Offset(if (vertical) c.x else c.x + from, if (vertical) c.y + from else c.y)
            val at = if (vertical) Offset(c.x, c.y + pos) else Offset(c.x + pos, c.y)
            if (press > 0.05f) drawLine(accent.copy(alpha = 0.55f * press), trail, at, strokeWidth = w * 0.07f, cap = StrokeCap.Round)
            if (local < 0.85f) finger(at, press, accent)
        }
        TipAnim.TAP -> {
            val press = phase(t, 0.15f, 0.22f) * (1f - phase(t, 0.28f, 0.34f))
            ripple(c, (t - 0.25f) / 0.45f, accent)
            finger(c, press, accent)
        }
        TipAnim.DRAG_HOLD -> {
            // Hold, then drag somewhere else.
            val press = phase(t, 0.05f, 0.15f) * (1f - phase(t, 0.78f, 0.86f))
            val lift = phase(t, 0.15f, 0.32f)
            val move = phase(t, 0.32f, 0.72f)
            val from = Offset(c.x - w * 0.24f, c.y + h * 0.12f)
            val to = Offset(c.x + w * 0.24f, c.y - h * 0.12f)
            val at = Offset(from.x + (to.x - from.x) * move, from.y + (to.y - from.y) * move)
            val s = w * 0.3f * (1f + 0.12f * lift)
            card(at, s, s * 0.7f, accent)
            if (t < 0.88f) finger(Offset(at.x + s * 0.2f, at.y + s * 0.2f), press, accent)
        }
    }
}
