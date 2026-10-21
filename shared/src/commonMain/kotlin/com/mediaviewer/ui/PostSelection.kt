package com.mediaviewer.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.getValue
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mediaviewer.model.MediaItem
import com.mediaviewer.ui.theme.CircleShape
import com.mediaviewer.ui.theme.RoundedCornerShape
import com.mediaviewer.util.rememberHapticTap

/**
 * Selection mode on the post grids (Explore, profiles): press and hold a
 * post, or right-click it, and it's picked — washed and outlined in your
 * own profile color, with its number — and from then on a tap picks or
 * drops posts instead of opening them. The order they were picked in is
 * kept, and every action on them ([SelectionBarButtons]) goes in that order.
 * Dropping the last one (or the bar's X, or Back) ends it.
 */
object PostSelection {
    /** The picked posts, in the order they were picked (Compose state). */
    val selected = mutableStateListOf<MediaItem>()

    val active: Boolean get() = selected.isNotEmpty()

    /** 1 for the first one picked, 2 for the next…; 0 = not picked. */
    fun orderOf(item: MediaItem): Int = selected.indexOfFirst { it.id == item.id } + 1

    fun toggle(item: MediaItem) {
        val i = selected.indexOfFirst { it.id == item.id }
        if (i >= 0) selected.removeAt(i) else selected.add(item)
    }

    fun clear() { selected.clear() }

    /** The touch the outermost selectable tile under it took (see postSelectable). */
    internal var claimed: androidx.compose.ui.input.pointer.PointerId? = null
}

/** Whether post tiles here can be picked: profile pages and Saved Posts
 *  only (not the Hub, Explore or Search). */
val LocalPostSelectable = androidx.compose.runtime.staticCompositionLocalOf { false }

/**
 * A post tile, watched for selection mode (see [PostSelection]): press and
 * hold, or a right-click, picks it; while picking, a tap picks or drops it
 * instead of opening it (the tile's own tap never sees it). Otherwise the
 * tile's own taps, swipes and holds are left alone. Draws the picked look —
 * your color over the whole tile, its thumbnail included, in the tile's
 * own [shape] (rounded or square, as Settings has it), outlined, with its
 * number — on top. Does nothing where tiles can't be picked
 * ([LocalPostSelectable]).
 */
fun Modifier.postSelectable(item: MediaItem, shape: Shape = RectangleShape): Modifier = composed {
    if (!LocalPostSelectable.current) return@composed this
    val view = com.mediaviewer.ui.compat.rememberPlatformView()
    val tint = lerp(rememberSelfTint(null, NeutralGlassTint), Color.White, 0.2f)
    val order = PostSelection.orderOf(item)
    val measurer = rememberTextMeasurer()
    val latest by androidx.compose.runtime.rememberUpdatedState(item)
    fun pick() {
        runCatching { view.performHapticFeedback(com.mediaviewer.ui.compat.HapticFeedbackConstants.LONG_PRESS) }
        PostSelection.toggle(latest)
    }
    this
        .pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                // (A tile inside another selectable one: only the outer one acts.)
                if (PostSelection.claimed == down.id) return@awaitEachGesture
                PostSelection.claimed = down.id
                if (currentEvent.buttons.isSecondaryPressed) {
                    currentEvent.changes.forEach { it.consume() }
                    pick()
                    return@awaitEachGesture
                }
                val selecting = PostSelection.active
                var moved = false
                var up: androidx.compose.ui.input.pointer.PointerInputChange? = null
                val held = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                    while (true) {
                        val ev = awaitPointerEvent(PointerEventPass.Initial)
                        if (ev.changes.count { it.pressed } > 1) { moved = true; break }
                        val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if ((c.position - down.position).getDistance() > viewConfiguration.touchSlop) { moved = true; break }
                        if (!c.pressed) { up = c; break }
                    }
                } == null
                if (moved) return@awaitEachGesture
                if (held) {
                    pick()
                    // The rest of this touch is selection's: the tile doesn't open.
                    while (true) {
                        val ev = awaitPointerEvent(PointerEventPass.Initial)
                        ev.changes.forEach { it.consume() }
                        if (ev.changes.none { it.pressed }) break
                    }
                } else if (selecting) {
                    up?.let { it.consume(); PostSelection.toggle(latest) }
                }
            }
        }
        .drawWithContent {
            drawContent()
            if (order > 0) {
                // Over everything the tile draws (its picture too), in its own shape.
                val outline = shape.createOutline(size, layoutDirection, this)
                drawOutline(outline, tint.copy(alpha = 0.32f))
                val w = 3.dp.toPx()
                val inner = shape.createOutline(androidx.compose.ui.geometry.Size(size.width - w, size.height - w), layoutDirection, this)
                translate(w / 2, w / 2) { drawOutline(inner, tint, style = Stroke(w)) }
                // Its number, in a badge at the top right.
                val r = 11.dp.toPx()
                val c = Offset(size.width - r - 7.dp.toPx(), r + 7.dp.toPx())
                drawCircle(tint, radius = r, center = c)
                val label = measurer.measure(order.toString(), TextStyle(color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold))
                drawText(label, topLeft = Offset(c.x - label.size.width / 2f, c.y - label.size.height / 2f))
            }
        }
}

/** What selection mode's buttons do (set by LocalOverlayHost, which has the
 *  ViewModel): each is the app's own action, run on every picked post in
 *  the order they were picked. */
class SelectionActions(
    val selfDid: String,
    val onSave: (List<MediaItem>) -> Unit,
    val onDownload: (List<MediaItem>, Boolean) -> Unit,
    val onShare: (List<MediaItem>) -> Unit,
    val onArchive: (List<MediaItem>) -> Unit,
    val onDelete: (List<MediaItem>) -> Unit
)

/**
 * Selection mode's buttons, in place of an interaction bar's own (the bar
 * itself stays — same pill, same place): Save, Download, Download as GIF and
 * Share, with the very icons the post's own bar uses, and — when every picked
 * post is yours — Archive and Delete, with the icons the post's More menu
 * uses. The count and Cancel are at the top of the page instead (see
 * [SelectionCountBubble]).
 */
@Composable
fun SelectionBarButtons(iconSize: androidx.compose.ui.unit.Dp = 20.dp) {
    val actions = LocalOverlays.selection ?: return
    com.mediaviewer.ui.compat.BackHandler(onBack = { PostSelection.clear() })
    val tap = rememberHapticTap()
    val picked = PostSelection.selected.toList()
    val allMine = actions.selfDid.isNotBlank() && picked.all { it.author.did == actions.selfDid && it.postUri.isNotBlank() }
    @Composable
    fun Btn(label: String, onClick: () -> Unit, content: @Composable () -> Unit) {
        Box(
            Modifier.size(width = 40.dp, height = 44.dp).clip(CircleShape).clickable { tap(); onClick() },
            contentAlignment = Alignment.Center
        ) { content() }
    }
    Btn("Save", { actions.onSave(picked) }) {
        Icon(Icons.Outlined.BookmarkBorder, contentDescription = "Save", tint = Color.White, modifier = Modifier.size(iconSize))
    }
    Btn("Download", { actions.onDownload(picked, false); PostSelection.clear() }) {
        Icon(Icons.Default.Download, contentDescription = "Download", tint = Color.White, modifier = Modifier.size(iconSize))
    }
    Btn("Download as GIF", { actions.onDownload(picked, true); PostSelection.clear() }) {
        com.mediaviewer.i18n.Text("GIF", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
    Btn("Share", { actions.onShare(picked) }) {
        Icon(Icons.Default.Send, contentDescription = "Share", tint = Color.White, modifier = Modifier.size(iconSize))
    }
    if (allMine) {
        Btn("Archive", { actions.onArchive(picked) }) {
            Icon(Icons.Default.Inventory2, contentDescription = "Archive", tint = Color.White, modifier = Modifier.size(iconSize))
        }
        Btn("Delete", { actions.onDelete(picked) }) {
            Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.White, modifier = Modifier.size(iconSize))
        }
    }
}

/** Selection mode's count, where the page's Follow / Edit button sits: the
 *  same pill, saying how many are picked. */
@Composable
fun SelectionCountBubble(liquidGlass: Boolean, tint: Color, backdrop: GlassBackdrop?) {
    val shape = RoundedCornerShape(50)
    val n = PostSelection.selected.size
    val content: @Composable () -> Unit = {
        Box(Modifier.height(30.dp).padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
            com.mediaviewer.i18n.Text("$n Selected", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
    if (liquidGlass) LiquidGlassSurface(modifier = Modifier.height(30.dp), shape = shape, tint = tint, backdrop = backdrop) { content() }
    else Box(Modifier.height(30.dp).clip(shape).background(Color.White.copy(0.14f))) { content() }
}

/** Selection mode's Cancel, where the page's Back button sits: the same
 *  round button, with an X. */
@Composable
fun SelectionCancelBubble(liquidGlass: Boolean, tint: Color, backdrop: GlassBackdrop?, size: androidx.compose.ui.unit.Dp = 38.dp) {
    val tap = rememberHapticTap()
    val base = Modifier.size(size).clip(CircleShape).clickable { tap(); PostSelection.clear() }
    val icon: @Composable () -> Unit = {
        Icon(Icons.Default.Close, contentDescription = "Cancel", tint = Color.White, modifier = Modifier.size(18.dp))
    }
    if (liquidGlass) LiquidGlassSurface(modifier = base, shape = CircleShape, tint = tint, backdrop = backdrop, contentAlignment = Alignment.Center) { icon() }
    else Box(base.background(Color.Black.copy(0.6f)), contentAlignment = Alignment.Center) { icon() }
}
