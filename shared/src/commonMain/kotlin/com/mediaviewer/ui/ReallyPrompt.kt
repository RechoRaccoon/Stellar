package com.mediaviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mediaviewer.i18n.Text
import com.mediaviewer.ui.theme.RoundedCornerShape
import com.mediaviewer.util.rememberHapticTap

/**
 * Two-tap deletes: the first tap turns the button into "Really?", the
 * second does it. Only one is ever waiting, app-wide, and a tap anywhere
 * else puts it back (see [disarmReallyOnTapElsewhere], on the app's root).
 */
object ReallyPrompt {
    /** What's waiting on its second tap (any key the button picks). */
    var armed by mutableStateOf<Any?>(null)
        private set
    /** Where each two-tap button on screen is, in root coordinates (so a
     *  tap on the waiting one isn't "elsewhere"). */
    internal val places = HashMap<Any, Rect>()

    fun isArmed(key: Any): Boolean = armed == key
    fun arm(key: Any) { armed = key }
    fun disarm() { armed = null }

    /** First tap arms [key]; a second (while armed) runs [action]. */
    fun tap(key: Any, action: () -> Unit) {
        if (armed == key) { disarm(); action() } else arm(key)
    }
}

/** Marks where the button for [key] is, so tapping it again isn't "elsewhere". */
fun Modifier.reallyTarget(key: Any): Modifier = composed {
    DisposableEffect(key) { onDispose { ReallyPrompt.places.remove(key) } }
    onGloballyPositioned { ReallyPrompt.places[key] = it.boundsInRoot() }
}

/** On the app's root: any touch outside the waiting button puts it back.
 *  (Watched before anything handles the touch, and never consumed.) */
fun Modifier.disarmReallyOnTapElsewhere(): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val key = ReallyPrompt.armed
        if (key != null) {
            val b = ReallyPrompt.places[key]
            if (b == null || !b.contains(down.position)) ReallyPrompt.disarm()
        }
    }
}

/**
 * A popup header's delete button (top right, mirroring the close X): the
 * trash icon, then "Really?" — in the popup's own colors, not red.
 */
@Composable
fun PopupDeleteBubble(key: Any, onDelete: () -> Unit, modifier: Modifier = Modifier) {
    val tap = rememberHapticTap()
    val armed = ReallyPrompt.isArmed(key)
    val shape = RoundedCornerShape(17.dp)
    Box(
        modifier.height(34.dp).widthIn(min = 34.dp).reallyTarget(key).clip(shape)
            .background(Color.Black.copy(alpha = 0.34f))
            .border(1.dp, Color.White.copy(alpha = 0.16f), shape)
            .clickable { tap(); ReallyPrompt.tap(key, onDelete) }
            .padding(horizontal = if (armed) 12.dp else 0.dp),
        contentAlignment = Alignment.Center
    ) {
        if (armed) Text("Really?", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        else Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.White, modifier = Modifier.size(18.dp))
    }
}
