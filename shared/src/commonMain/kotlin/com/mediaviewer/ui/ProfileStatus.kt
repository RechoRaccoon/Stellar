package com.mediaviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mediaviewer.i18n.Text
import com.mediaviewer.ui.compat.LocalContext
import com.mediaviewer.util.ProfileStatus
import com.mediaviewer.util.rememberHapticTap

/*
 * Profiles' extras kept in the same record as the icon shape and effect
 * (see util/ProfileStyles): a temporary status, in a little speech bubble
 * pointing at the profile picture, and a looping video banner.
 */

/** The longest a status can be. */
const val STATUS_MAX_LENGTH = 100

/** The biggest video a banner can be (it's kept on your own PDS). */
const val BANNER_VIDEO_MAX_BYTES = 50_000_000L

/** How tall the bubble's little pointer is. */
private val TAIL = 7.dp

/** A rounded bubble with a small pointer out of its bottom-left corner. */
private class StatusBubbleShape(private val radius: Float, private val tail: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val bodyH = (size.height - tail).coerceAtLeast(1f)
        val r = radius.coerceAtMost(bodyH / 2)
        val path = Path().apply {
            addRoundRect(RoundRect(0f, 0f, size.width, bodyH, r, r))
            // The pointer: from the body's bottom edge down to the corner.
            moveTo(r * 0.6f, bodyH - 1f)
            lineTo(r * 0.6f + tail * 1.6f, bodyH - 1f)
            lineTo(0f, size.height)
            close()
        }
        return Outline.Generic(path)
    }
}

/**
 * The status bubble by a profile picture: its bottom-left pointer touches
 * the picture's top-right. Empty on your own profile, it's a small "+"
 * that opens [ProfileStatusEditor]; on other people's, it's only shown
 * while they have a status.
 */
@Composable
fun ProfileStatusBubble(status: ProfileStatus?, isOwn: Boolean, tint: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    if (status == null && !isOwn) return
    val tap = rememberHapticTap()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val shape = remember(density) { with(density) { StatusBubbleShape(12.dp.toPx(), TAIL.toPx()) } }
    val fill = lerp(tint, Color.Black, 0.35f).copy(alpha = 0.92f)
    Box(
        modifier
            .clip(shape)
            .background(fill, shape)
            .border(1.dp, lerp(tint, Color.White, 0.35f).copy(alpha = 0.8f), shape)
            .then(if (isOwn) Modifier.clickable { tap(); onClick() } else Modifier)
            .padding(bottom = TAIL)
    ) {
        if (status == null) {
            Icon(Icons.Default.Add, contentDescription = "Set a status", tint = Color.White, modifier = Modifier.padding(5.dp).size(14.dp))
        } else {
            Text(
                status.text, color = Color.White, fontSize = 12.sp, lineHeight = 15.sp,
                maxLines = 3, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 220.dp).padding(horizontal = 10.dp, vertical = 5.dp)
            )
        }
    }
}

private enum class StatusUnit(val minutes: Long, val short: String, val label: String) {
    MINUTES(1, "m", "Minutes"), HOURS(60, "h", "Hours"), DAYS(60 * 24, "d", "Days")
}

/**
 * Setting your status: a box like the comment box, with how long it lasts
 * on the left where the comment box has its media button. 24 hours unless
 * changed; 0 means it never clears by itself.
 */
@Composable
fun ProfileStatusEditor(
    current: ProfileStatus?, tint: Color, liquidGlass: Boolean,
    onPost: (text: String, minutes: Long) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    val tap = rememberHapticTap()
    var text by remember { mutableStateOf(current?.text.orEmpty()) }
    var amount by remember { mutableStateOf("24") }
    var unit by remember { mutableStateOf(StatusUnit.HOURS) }
    var durationOpen by remember { mutableStateOf(false) }
    val amountValue = amount.toLongOrNull()?.coerceAtLeast(0) ?: 0L
    val minutes = amountValue * unit.minutes
    val accent = lerp(tint, Color.White, 0.35f)
    com.mediaviewer.ui.compat.BackHandler(onBack = onDismiss)
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onDismiss() }
            .imePadding(),
        contentAlignment = Alignment.BottomCenter
    ) {
        Column(
            Modifier.landscapeReadable().fillMaxWidth().padding(horizontal = 12.dp, vertical = 14.dp)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val panelShape = RoundedCornerShape(18.dp)
            @Composable
            fun Panel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
                Box(
                    modifier.then(
                        Modifier.opaqueFill(panelShape, tint, liquidGlass)
                    )
                ) { content() }
            }
            if (current != null && current.isActive()) {
                Panel(Modifier.clickable { tap(); onClear() }) {
                    Text("Clear Status", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
                }
            }
            if (durationOpen) {
                Panel(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Clear After", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val fieldShape = RoundedCornerShape(12.dp)
                            Box(
                                Modifier.width(64.dp).height(36.dp).clip(fieldShape).background(Color.White.copy(alpha = 0.1f)).padding(horizontal = 10.dp),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                BasicTextField(
                                    value = amount, onValueChange = { v -> amount = v.filter { it.isDigit() }.take(4) },
                                    singleLine = true,
                                    textStyle = TextStyle(color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                                    cursorBrush = SolidColor(Color.White),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            StatusUnit.entries.forEach { u ->
                                val selected = u == unit
                                Box(
                                    Modifier.height(32.dp).clip(RoundedCornerShape(16.dp))
                                        .background(if (selected) accent.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.08f))
                                        .clickable { tap(); unit = u }
                                        .padding(horizontal = 12.dp),
                                    contentAlignment = Alignment.Center
                                ) { Text(u.label, color = Color.White, fontSize = 12.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal) }
                            }
                        }
                        Text("0 = never clear", color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                fun post() {
                    val t = text.trim()
                    if (t.isNotEmpty()) onPost(t, minutes)
                }
                // How long it lasts — where the comment box has its media button.
                @Composable
                fun Circle(modifier: Modifier, content: @Composable () -> Unit) {
                    Box(
                        modifier.size(46.dp).then(
                            Modifier.opaqueFill(CircleShape, tint, liquidGlass)
                        ),
                        contentAlignment = Alignment.Center
                    ) { content() }
                }
                Circle(Modifier.clickable { tap(); durationOpen = !durationOpen }) {
                    Text(
                        if (minutes == 0L) "∞" else "$amountValue${unit.short}",
                        color = Color.White, fontSize = if (minutes == 0L) 18.sp else 12.sp, fontWeight = FontWeight.Bold, maxLines = 1
                    )
                }
                val fieldShape = RoundedCornerShape(24.dp)
                Box(
                    Modifier.weight(1f).height(46.dp).then(
                        Modifier.opaqueFill(fieldShape, tint, liquidGlass)
                    ).padding(horizontal = 16.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
                    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
                    BasicTextField(
                        value = text, onValueChange = { text = it.replace('\n', ' ').take(STATUS_MAX_LENGTH) },
                        singleLine = true,
                        textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
                        cursorBrush = SolidColor(Color.White),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { post() }),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus)
                    )
                    if (text.isEmpty()) Text("What's on your mind?", color = Color.White.copy(alpha = 0.55f), fontSize = 14.sp)
                }
                Circle(Modifier.clickable(enabled = text.isNotBlank()) { tap(); post() }) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Post status", tint = if (text.isNotBlank()) Color.White else Color.White.copy(0.4f), modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

/** The status box's parts: solid (never see-through), in the profile's
 *  color; with Liquid Glass on, a glass-like rim around them. */
private fun Modifier.opaqueFill(shape: Shape, tint: Color, liquidGlass: Boolean): Modifier =
    this.clip(shape).background(lerp(Color(0xFF16161B), tint, 0.3f), shape)
        .then(if (liquidGlass) Modifier.border(1.dp, lerp(tint, Color.White, 0.35f).copy(alpha = 0.7f), shape) else Modifier)

/** A new status lasting [minutes] (0 = until it's changed). */
fun newProfileStatus(text: String, minutes: Long): ProfileStatus {
    val now = com.mediaviewer.platform.currentTimeMillis()
    return ProfileStatus(
        text = text.take(STATUS_MAX_LENGTH),
        createdAt = com.mediaviewer.util.isoStringOf(now),
        expiresAt = if (minutes <= 0L) null else com.mediaviewer.util.isoStringOf(now + minutes * 60_000L)
    )
}

/**
 * A profile's video banner, muted and looping over the banner picture
 * (which stays underneath until the first frame is up). Played straight
 * from the blob on the owner's PDS.
 */
@Composable
fun ProfileBannerVideo(did: String, cid: String, modifier: Modifier = Modifier) {
    var url by remember(did, cid) { mutableStateOf<String?>(null) }
    LaunchedEffect(did, cid) {
        url = runCatching { com.mediaviewer.worker.BlueskyBlobResolver.resolveBlobUrl(did, cid) }.getOrNull()
    }
    val u = url ?: return
    val context = LocalContext.current
    val player = remember(u) { FeedVideos.acquire(context, u) }
    DisposableEffect(player) {
        player.setVolumeMuted(true)
        player.play()
        onDispose {
            player.setVolumeMuted(false)
            FeedVideos.recycle(u, player)
        }
    }
    FeedVideoView(player, modifier)
}
