package com.mediaviewer.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import com.mediaviewer.ui.theme.CircleShape
import com.mediaviewer.ui.theme.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Icon
import com.mediaviewer.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mediaviewer.ui.theme.OffBlack
import com.mediaviewer.util.rememberHapticTap

/**
 * Hosts one of the post popups (Share To, Quote Repost, Add To): fades and
 * gently scales in over the post while the post's own UI fades away (see
 * MainFeedScreen's popupOpen), and plays the same thing in reverse when it
 * closes — the last [target] is kept on screen while it fades out.
 */
@OptIn(androidx.compose.animation.ExperimentalAnimationApi::class)
@Composable
fun <T : Any> FadingPopupHost(
    target: T?,
    modifier: Modifier = Modifier,
    content: @Composable (T) -> Unit
) {
    // Plain (non-state) holder: remembers what to keep drawing while the
    // popup fades out after its target has already gone null.
    val holder = remember { arrayOfNulls<Any>(1) }
    if (target != null) holder[0] = target
    // The whole popup (its full-screen dimming too) only fades; the sheet
    // itself scales and slides in (see PopupSheetSurface). Scaling and
    // sliding everything showed the dimming as a big square moving in on a
    // wide window.
    AnimatedVisibility(
        visible = target != null,
        enter = fadeIn(tween(240)),
        exit = fadeOut(tween(190)),
        modifier = modifier.fillMaxSize()
    ) {
        val progress = transition.animateFloat(
            transitionSpec = {
                if (targetState == androidx.compose.animation.EnterExitState.Visible) tween(300, easing = FastOutSlowInEasing)
                else tween(220, easing = FastOutSlowInEasing)
            },
            label = "popupSheet"
        ) { if (it == androidx.compose.animation.EnterExitState.Visible) 1f else 0f }
        @Suppress("UNCHECKED_CAST")
        val shown = (target ?: holder[0]) as T?
        if (shown != null) {
            androidx.compose.runtime.CompositionLocalProvider(LocalPopupProgress provides { progress.value }) { content(shown) }
        }
    }
}

/** How far a popup sheet is in (0 = away, 1 = all the way): read while
 *  drawing, so only the sheet moves (see FadingPopupHost). */
val LocalPopupProgress = androidx.compose.runtime.staticCompositionLocalOf<() -> Float> { { 1f } }

/**
 * The one rule every popup follows. Over the Timeline (a post on screen,
 * nothing else open) a popup is live-blurred glass in the post's own color,
 * blurring the post under it. Everywhere else (the Hub, profiles, Explore,
 * Saved Posts, Search…) it's a filled panel: the "New chat" look, a soft
 * gradient of its color with a gradient rim, and its buttons a brighter
 * fill of the same color. Neither dims the screen: the app behind is
 * already blurred while a popup's open.
 */
object PopupStyle {
    /** True while the Timeline is what's on screen (set by AppRoot). */
    var timeline by androidx.compose.runtime.mutableStateOf(false)

    /** Whether a popup is drawn as glass right now. */
    fun glass(liquidGlass: Boolean): Boolean = liquidGlass && timeline

    /** The filled panel's base color for [tint]. */
    fun panel(tint: Color): Color = lerp(Color(0xFF101014), tint, 0.16f)
}

/** The filled popup panel (see [PopupStyle]): "New chat"'s gradient and rim. */
fun Modifier.popupPanel(tint: Color, shape: Shape): Modifier {
    val panel = PopupStyle.panel(tint)
    return this.clip(shape)
        .background(Brush.verticalGradient(listOf(lerp(panel, tint, 0.12f).copy(alpha = 0.97f), panel.copy(alpha = 0.97f))))
        .border(1.2.dp, Brush.linearGradient(listOf(tint.copy(alpha = 0.9f), Color.White.copy(alpha = 0.25f), tint.copy(alpha = 0.6f))), shape)
}

/** A button on a filled popup: a brighter fill of the popup's color, a
 *  little lighter at the top, with a soft rim ([strong] = the main action). */
fun Modifier.popupButtonFill(tint: Color, shape: Shape, strong: Boolean = false): Modifier {
    val base = lerp(PopupStyle.panel(tint), tint, if (strong) 0.55f else 0.32f)
    return this.clip(shape)
        .background(Brush.verticalGradient(listOf(lerp(base, Color.White, 0.08f), base)))
        .border(1.dp, lerp(tint, Color.White, 0.35f).copy(alpha = if (strong) 0.85f else 0.5f), shape)
}

/** A popup sheet's surface: glass over the Timeline, the filled panel
 *  everywhere else (see [PopupStyle]). */
@Composable
fun PopupSheetSurface(
    liquidGlass: Boolean,
    tint: Color,
    backdrop: GlassBackdrop?,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(26.dp),
    content: @Composable BoxScope.() -> Unit
) {
    val absorb = Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
    // Scales up a little and rises into place as its popup opens.
    val progress = LocalPopupProgress.current
    val moving = Modifier.graphicsLayer {
        val p = progress()
        val s = 0.94f + 0.06f * p
        scaleX = s; scaleY = s
        translationY = (1f - p) * size.height / 14f
    }
    val sheet = moving.then(modifier)
    if (PopupStyle.glass(liquidGlass)) {
        LiquidGlassSurface(modifier = sheet.then(absorb), shape = shape, tint = tint, backdrop = backdrop, content = content)
    } else {
        Box(sheet.popupPanel(tint, shape).then(absorb), content = content)
    }
}

/** A soft, rounded dark backing behind text sitting on a popup's glass, so
 *  titles, names and handles stay readable over any post color. */
fun Modifier.popupTextShadow(shape: Shape = RoundedCornerShape(12.dp)): Modifier =
    this.clip(shape).background(Color.Black.copy(alpha = 0.32f))

/** The popup's title row: a plain round X on the left, the title centered
 *  on its own dimmed backing. */
@Composable
fun PopupSheetHeader(
    title: String,
    liquidGlass: Boolean,
    tint: Color,
    backdrop: GlassBackdrop?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    /** A character counter at the right end of the row (e.g. "12/300"),
     *  over the message field's right edge. */
    counter: String? = null,
    counterOver: Boolean = false
) {
    Box(modifier.fillMaxWidth().padding(start = 10.dp, end = 16.dp, top = 10.dp, bottom = 4.dp)) {
        if (counter != null) {
            Text(
                counter, color = if (counterOver) Color(0xFFFF6B8A) else Color.White.copy(alpha = 0.55f),
                fontSize = 11.sp, maxLines = 1, modifier = Modifier.align(Alignment.CenterEnd).padding(end = 6.dp)
            )
        }
        Row(
            Modifier.align(Alignment.Center).padding(horizontal = 44.dp)
                .popupTextShadow().padding(horizontal = 14.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center, maxLines = 1
            )
            if (subtitle != null) {
                Spacer(Modifier.size(6.dp))
                Text(subtitle, color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            }
        }
        PopupCloseBubble(liquidGlass, tint, backdrop, onClose, Modifier.align(Alignment.CenterStart))
    }
}

/** Round X that closes a popup — a plain dark bubble with a white X (not
 *  tinted), the same everywhere. */
@Composable
fun PopupCloseBubble(
    liquidGlass: Boolean,
    tint: Color,
    backdrop: GlassBackdrop?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tap = rememberHapticTap()
    Box(
        modifier.size(34.dp).clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.34f))
            .border(1.dp, Color.White.copy(alpha = 0.16f), CircleShape)
            .clickable { tap(); onClose() },
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White, modifier = Modifier.size(18.dp))
    }
}

/**
 * The message box shared by Share with and Quote Repost: the post's
 * thumbnail, a text field that grows as you type (up to [maxLines]), an
 * optional character counter inside on the right, and the send button.
 */
@Composable
fun PopupMessageBox(
    thumbUrl: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    tint: Color,
    canSend: Boolean,
    sending: Boolean,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
    counter: String? = null,
    counterOver: Boolean = false,
    maxLines: Int = 5,
    focusRequester: androidx.compose.ui.focus.FocusRequester? = null,
    /** A picture to show instead of [thumbUrl] (e.g. a local file). */
    thumbModel: Any? = null,
    sendIcon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Default.Send,
    sendDescription: String = "Send"
) {
    val tap = rememberHapticTap()
    // The counter sits above the send button, its right edge level with the
    // field's, so the text gets the whole width and never re-wraps as the
    // count grows a digit.
    Column(modifier.fillMaxWidth()) {
    if (counter != null) {
        Text(
            counter, color = if (counterOver) Color(0xFFFF6B8A) else Color.White.copy(alpha = 0.55f),
            fontSize = 11.sp, maxLines = 1,
            modifier = Modifier.align(Alignment.End).padding(end = 12.dp, bottom = 4.dp)
        )
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 50.dp)
            .popupFieldWell(tint, RoundedCornerShape(25.dp))
            .padding(start = 7.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        if (thumbModel != null || thumbUrl.isNotBlank()) {
            coil3.compose.AsyncImage(
                model = thumbModel ?: thumbUrl, contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.size(38.dp).clip(RoundedCornerShape(11.dp))
            )
            Spacer(Modifier.width(10.dp))
        } else Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f).heightIn(min = 38.dp), contentAlignment = Alignment.CenterStart) {
            BasicTextField(
                value = value, onValueChange = onValueChange,
                textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 14.sp, lineHeight = 19.sp),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(Color.White),
                maxLines = maxLines,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences
                ),
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                    .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            )
            if (value.isEmpty()) Text(
                placeholder, color = Color.White.copy(alpha = 0.5f), fontSize = 14.sp, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(lerp(tint, Color.White, 0.12f).copy(alpha = if (canSend) 0.95f else 0.3f))
                .clickable(enabled = canSend && !sending) { tap(); onSend() },
            contentAlignment = Alignment.Center
        ) {
            if (sending) androidx.compose.material3.CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
            else Icon(sendIcon, contentDescription = sendDescription, tint = Color.White.copy(alpha = if (canSend) 1f else 0.5f), modifier = Modifier.size(19.dp))
        }
    }
    }
}

/** A text field's rounded well inside a popup, in the same style as the
 *  app's other inputs (dark well, colored rim). */
fun Modifier.popupFieldWell(tint: Color, shape: Shape = RoundedCornerShape(22.dp)): Modifier =
    this.clip(shape)
        .background(Color.Black.copy(alpha = 0.28f))
        .border(1.dp, lerp(tint, Color.White, 0.25f).copy(alpha = 0.7f), shape)
