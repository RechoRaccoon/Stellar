package com.mediaviewer.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.getValue
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Gif
import androidx.compose.material.icons.automirrored.filled.Send
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.mediaviewer.ui.compat.navBarSpace
import com.mediaviewer.ui.theme.CircleShape
import com.mediaviewer.ui.theme.RoundedCornerShape
import com.mediaviewer.util.rememberHapticTap

/**
 * Selection mode on the post grids (Explore, profiles): press and hold a
 * post, or right-click it, and it's picked — washed and outlined in your
 * own profile color, with its number — and from then on a tap picks or
 * drops posts instead of opening them. The order they were picked in is
 * kept, and every action on them ([SelectionActionBar]) goes in that order.
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

/**
 * A post tile, watched for selection mode (see [PostSelection]): press and
 * hold, or a right-click, picks it; while picking, a tap picks or drops it
 * instead of opening it (the tile's own tap never sees it). Otherwise the
 * tile's own taps, swipes and holds are left alone. Draws the picked look —
 * your color over it, outlined, with its number — on top.
 */
fun Modifier.postSelectable(item: MediaItem): Modifier = composed {
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
                drawRect(tint.copy(alpha = 0.3f))
                val w = 3.dp.toPx()
                drawRect(tint, topLeft = Offset(w / 2, w / 2), size = androidx.compose.ui.geometry.Size(size.width - w, size.height - w), style = Stroke(w))
                // Its number, in a badge at the top right.
                val r = 11.dp.toPx()
                val c = Offset(size.width - r - 6.dp.toPx(), r + 6.dp.toPx())
                drawCircle(tint, radius = r, center = c)
                val label = measurer.measure(order.toString(), TextStyle(color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold))
                drawText(label, topLeft = Offset(c.x - label.size.width / 2f, c.y - label.size.height / 2f))
            }
        }
}

/**
 * Selection mode's bar, in place of the page's own: Save (into a folder,
 * chosen first), Download, Download as GIF, Share, and — when every picked
 * post is your own — Archive and Delete. Each acts on all of them, in the
 * order they were picked; the X ends selection mode.
 */
@Composable
fun SelectionActionBar(
    tint: Color, liquidGlass: Boolean, selfDid: String,
    onSave: (List<MediaItem>) -> Unit,
    onDownload: (List<MediaItem>, Boolean) -> Unit,
    onShare: (List<MediaItem>) -> Unit,
    onArchive: (List<MediaItem>) -> Unit,
    onDelete: (List<MediaItem>) -> Unit,
    modifier: Modifier = Modifier
) {
    if (!PostSelection.active) return
    com.mediaviewer.ui.compat.BackHandler(onBack = { PostSelection.clear() })
    val tap = rememberHapticTap()
    val picked = PostSelection.selected.toList()
    val allMine = selfDid.isNotBlank() && picked.all { it.author.did == selfDid && it.postUri.isNotBlank() }
    val shape = RoundedCornerShape(26.dp)
    Row(
        modifier.windowInsetsPadding(WindowInsets.navBarSpace).padding(bottom = 12.dp)
            .glassPanel(liquidGlass, tint = tint, shape = shape)
            .then(if (liquidGlass) Modifier else Modifier.border(1.dp, tint.copy(alpha = 0.5f), shape))
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        @Composable
        fun Action(icon: ImageVector, label: String, danger: Boolean = false, onClick: () -> Unit) {
            Box(
                Modifier.size(46.dp).clip(CircleShape).clickable { tap(); onClick() },
                contentAlignment = Alignment.Center
            ) { Icon(icon, contentDescription = label, tint = if (danger) Color(0xFFFF6B8A) else Color.White, modifier = Modifier.size(22.dp)) }
        }
        com.mediaviewer.i18n.Text(
            "${picked.size}", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 10.dp)
        )
        Action(Icons.Default.Bookmark, "Save") { onSave(picked) }
        Action(Icons.Default.Download, "Download") { onDownload(picked, false); PostSelection.clear() }
        Action(Icons.Default.Gif, "Download as GIF") { onDownload(picked, true); PostSelection.clear() }
        Action(Icons.AutoMirrored.Filled.Send, "Share") { onShare(picked) }
        if (allMine) {
            Action(Icons.Default.Archive, "Archive") { onArchive(picked) }
            Action(Icons.Default.Delete, "Delete", danger = true) { onDelete(picked) }
        }
        Box(Modifier.size(width = 1.dp, height = 26.dp).background(Color.White.copy(alpha = 0.2f)))
        Action(Icons.Default.Close, "Done") { PostSelection.clear() }
    }
}
