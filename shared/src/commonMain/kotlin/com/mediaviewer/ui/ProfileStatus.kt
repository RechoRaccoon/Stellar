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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import com.mediaviewer.ui.theme.CircleShape
import com.mediaviewer.ui.theme.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
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
import androidx.compose.material.icons.filled.Star
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
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
const val STATUS_MAX_LENGTH = 300

/** The biggest video a banner can be (it's kept on your own PDS). */
const val BANNER_VIDEO_MAX_BYTES = 50_000_000L

/** How tall the bubble's little pointer is. */
private val TAIL = 7.dp

/**
 * A rounded bubble whose bottom-left corner is square and grows a small
 * triangle pointing down and to the left, out of that corner (toward the
 * profile picture). The bubble's body is inset by [tail] on the left and
 * bottom to leave room for it.
 */
private class StatusBubbleShape(private val radius: Float, private val tail: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val bodyH = (size.height - tail).coerceAtLeast(1f)
        val r = radius.coerceAtMost(bodyH / 2)
        val body = Path().apply {
            addRoundRect(
                RoundRect(
                    left = tail, top = 0f, right = size.width, bottom = bodyH,
                    topLeftCornerRadius = CornerRadius(r), topRightCornerRadius = CornerRadius(r),
                    bottomRightCornerRadius = CornerRadius(r), bottomLeftCornerRadius = CornerRadius.Zero
                )
            )
        }
        val side = tail * 1.5f
        val arrow = Path().apply {
            moveTo(tail, bodyH - side)
            lineTo(tail + side, bodyH)
            lineTo(0f, size.height)
            close()
        }
        return Outline.Generic(Path().apply { op(body, arrow, PathOperation.Union) })
    }
}

/**
 * The status bubble by a profile picture: its bottom-left pointer touches
 * the picture's top-right. Empty on your own profile, it says "Status" (greyed)
 * and opens [ProfileStatusEditor]; on other people's, it's only shown
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
            .padding(start = TAIL, bottom = TAIL)
    ) {
        if (status == null) {
            Text("Status", color = Color.White.copy(alpha = 0.45f), fontSize = 12.sp, lineHeight = 15.sp, maxLines = 1, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp))
        } else {
            // As wide and tall as the room by the picture allows (see the
            // banner layout), so a longer status gets a bigger bubble — and
            // the words shrink until every one of them fits.
            FittedStatusText(status, Modifier.padding(horizontal = 10.dp, vertical = 5.dp))
        }
    }
}

/** A status's words with its custom emoji drawn in place. */
@Composable
fun StatusRichText(
    text: String, emoji: List<com.mediaviewer.util.StatusEmoji>, color: Color,
    fontSize: androidx.compose.ui.unit.TextUnit, lineHeight: androidx.compose.ui.unit.TextUnit,
    modifier: Modifier = Modifier
) {
    val (annotated, inline, _) = rememberStatusAnnotated(text, emoji)
    androidx.compose.material3.Text(annotated, color = color, fontSize = fontSize, lineHeight = lineHeight, inlineContent = inline, modifier = modifier)
}

/** The size of an emoji in a status, in ems. */
private const val STATUS_EMOJI_EM = 1.5f

/** A status's words built for drawing: the text, its emoji pictures, and
 *  where in the text each one sits (for measuring). */
private data class StatusAnnotated(
    val text: androidx.compose.ui.text.AnnotatedString,
    val inline: Map<String, androidx.compose.foundation.text.InlineTextContent>,
    val spots: List<IntRange>
)

@Composable
private fun rememberStatusAnnotated(text: String, emoji: List<com.mediaviewer.util.StatusEmoji>): StatusAnnotated =
    remember(text, emoji) {
        val at = emoji.filter { it.at in text.indices }.associateBy { it.at }
        val spots = ArrayList<IntRange>()
        val annotated = androidx.compose.ui.text.buildAnnotatedString {
            text.forEachIndexed { i, c ->
                val e = at[i]
                if (e != null) { spots += length until length + 1; appendInlineContent("se$i", c.toString()) }
                else if (c.toString() != com.mediaviewer.util.EmojiCloud.HIDDEN) append(c)
            }
        }
        val inline = at.map { (i, e) ->
            "se$i" to androidx.compose.foundation.text.InlineTextContent(
                androidx.compose.ui.text.Placeholder(
                    androidx.compose.ui.unit.TextUnit(STATUS_EMOJI_EM, androidx.compose.ui.unit.TextUnitType.Em),
                    androidx.compose.ui.unit.TextUnit(STATUS_EMOJI_EM, androidx.compose.ui.unit.TextUnitType.Em),
                    androidx.compose.ui.text.PlaceholderVerticalAlign.TextCenter
                )
            ) { RemoteEmojiPicture(e.did, e.cid, e.animated, e.name, Modifier.fillMaxSize()) }
        }.toMap()
        StatusAnnotated(annotated, inline, spots)
    }

/** The status in its bubble: as big as 12sp, shrinking (down to 5sp)
 *  until all of it fits in the room the bubble has. */
@Composable
private fun FittedStatusText(status: ProfileStatus, modifier: Modifier = Modifier) {
    val (annotated, inline, spots) = rememberStatusAnnotated(status.text, status.emoji)
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val density = androidx.compose.ui.platform.LocalDensity.current
    androidx.compose.foundation.layout.BoxWithConstraints(modifier) {
        val maxW = constraints.maxWidth
        val maxH = constraints.maxHeight
        val size = remember(annotated, maxW, maxH, density) {
            val bounded = maxW in 1 until androidx.compose.ui.unit.Constraints.Infinity && maxH in 1 until androidx.compose.ui.unit.Constraints.Infinity
            if (!bounded) return@remember 12f
            fun fits(sz: Float): Boolean {
                val placeholders = spots.map { r ->
                    androidx.compose.ui.text.AnnotatedString.Range(
                        androidx.compose.ui.text.Placeholder((sz * STATUS_EMOJI_EM).sp, (sz * STATUS_EMOJI_EM).sp, androidx.compose.ui.text.PlaceholderVerticalAlign.TextCenter),
                        r.first, r.last + 1
                    )
                }
                val r = measurer.measure(
                    annotated, TextStyle(fontSize = sz.sp, lineHeight = (sz * 1.25f).sp), placeholders = placeholders,
                    constraints = androidx.compose.ui.unit.Constraints(maxWidth = maxW), density = density
                )
                return r.size.height <= maxH
            }
            if (fits(12f)) return@remember 12f
            var lo = 5f; var hi = 12f
            repeat(8) { val mid = (lo + hi) / 2f; if (fits(mid)) lo = mid else hi = mid }
            lo
        }
        androidx.compose.material3.Text(
            annotated, color = Color.White, fontSize = size.sp, lineHeight = (size * 1.25f).sp,
            inlineContent = inline, overflow = TextOverflow.Ellipsis
        )
    }
}

private enum class StatusUnit(val minutes: Long, val short: String, val label: String, val default: Int) {
    MINUTES(1, "m", "Minutes", 30), HOURS(60, "h", "Hours", 24), DAYS(60 * 24, "d", "Days", 7),
    /** Never clears by itself. */
    NEVER(0, "", "Never", 0)
}

/**
 * Setting your status: a box like the comment box, with how long it lasts
 * on the left where the comment box has its media button: Minutes, Hours,
 * Days (each remembering its own number) or Never. Left of that, your
 * status history (kept on this device only): tap one to put its words in
 * the box, press and hold to delete it. While the box holds the status
 * that's up now, the send button clears it instead.
 */
@Composable
fun ProfileStatusEditor(
    did: String,
    current: ProfileStatus?, tint: Color, liquidGlass: Boolean,
    onPost: (text: String, minutes: Long) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    val tap = rememberHapticTap()
    // Custom emoji: typed as tokens (drawn over the field), like DMs.
    val context = LocalContext.current
    val emojiStore = remember { com.mediaviewer.util.EmojiStore.get(context) }
    fun editable(t: String, e: List<com.mediaviewer.util.StatusEmoji>) = com.mediaviewer.util.EmojiCloud.editableStatus(emojiStore, did, t, e)
    var field by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(editable(current?.text.orEmpty(), current?.emoji.orEmpty()))) }
    val text = field.text
    fun setText(t: String) { field = androidx.compose.ui.text.input.TextFieldValue(t, androidx.compose.ui.text.TextRange(t.length)) }
    var touched by remember { mutableStateOf(false) }
    // (The library loads first, so the status's own emoji come back as emoji.)
    LaunchedEffect(Unit) { emojiStore.load(); if (!touched) setText(editable(current?.text.orEmpty(), current?.emoji.orEmpty())) }
    val currentEditable = remember(current, emojiStore.state) { current?.let { editable(it.text, it.emoji) } }
    var emojiOpen by remember { mutableStateOf(false) }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val imeBottom = androidx.compose.foundation.layout.WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current)
    var lastIme by remember { mutableStateOf(0) }
    LaunchedEffect(imeBottom) { if (imeBottom > lastIme && emojiOpen) emojiOpen = false; lastIme = imeBottom }
    var unit by remember { mutableStateOf(StatusUnit.entries.firstOrNull { it.name == com.mediaviewer.util.UiToggles.statusUnit() } ?: StatusUnit.HOURS) }
    fun savedAmount(u: StatusUnit) = if (u == StatusUnit.NEVER) "0" else com.mediaviewer.util.UiToggles.statusAmount(u.name, u.default).toString()
    var amount by remember { mutableStateOf(savedAmount(unit)) }
    var durationOpen by remember { mutableStateOf(false) }
    var historyOpen by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<com.mediaviewer.util.StatusHistory.Entry?>(null) }
    LaunchedEffect(did) { com.mediaviewer.util.StatusHistory.load(did) }
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
        // Full height so the history can grow up to just under the camera;
        // taps between the parts still reach the dimmed background.
        Column(
            Modifier.landscapeReadable().fillMaxWidth().fillMaxHeight()
                .padding(top = rememberTopCutoutClearance()).padding(horizontal = 12.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Bottom)
        ) {
            val swallow = Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            val panelShape = RoundedCornerShape(18.dp)
            @Composable
            fun Panel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
                Box(
                    modifier.then(
                        Modifier.opaqueFill(panelShape, tint, liquidGlass)
                    ).then(swallow)
                ) { content() }
            }
            if (historyOpen) {
                Panel(Modifier.fillMaxWidth().weight(1f, fill = false)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Status History", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        val history = com.mediaviewer.util.StatusHistory.entries
                        if (history.isEmpty()) {
                            Text("No statuses yet", color = Color.White.copy(alpha = 0.55f), fontSize = 13.sp, modifier = Modifier.padding(vertical = 18.dp).align(Alignment.CenterHorizontally))
                        } else {
                            androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid(
                                columns = androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells.Fixed(2),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalItemSpacing = 8.dp,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                items(count = history.size, key = { i -> "status_" + history[i].createdAt + "_" + i }) { i ->
                                    val entry = history[i]
                                    StatusHistoryTile(
                                        entry, tint, liquidGlass = false,
                                        onClick = { touched = true; setText(editable(entry.text, entry.emoji).take(STATUS_MAX_LENGTH)); historyOpen = false },
                                        onLongPress = { pendingDelete = entry }
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (durationOpen) {
                Panel(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Clear After", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            val fieldShape = RoundedCornerShape(12.dp)
                            val never = unit == StatusUnit.NEVER
                            Box(
                                Modifier.width(64.dp).height(36.dp).clip(fieldShape).background(Color.White.copy(alpha = if (never) 0.05f else 0.1f)).padding(horizontal = 10.dp),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                BasicTextField(
                                    value = amount, enabled = !never,
                                    onValueChange = { v ->
                                        amount = v.filter { it.isDigit() }.take(4)
                                        com.mediaviewer.util.UiToggles.saveStatusDuration(unit.name, amount.toIntOrNull() ?: 0)
                                    },
                                    singleLine = true,
                                    textStyle = TextStyle(color = Color.White.copy(alpha = if (never) 0.35f else 1f), fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                                    cursorBrush = SolidColor(Color.White),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            StatusUnit.entries.forEach { u ->
                                val selected = u == unit
                                Box(
                                    Modifier.weight(1f).height(32.dp).clip(RoundedCornerShape(16.dp))
                                        .background(if (selected) accent.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.08f))
                                        .clickable {
                                            tap(); unit = u; amount = savedAmount(u)
                                            com.mediaviewer.util.UiToggles.saveStatusDuration(u.name, null)
                                        }
                                        .padding(horizontal = 4.dp),
                                    contentAlignment = Alignment.Center
                                ) { Text(u.label, color = Color.White, fontSize = 12.sp, maxLines = 1, softWrap = false, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal) }
                            }
                        }
                    }
                }
            }
            if (emojiOpen) {
                Panel(Modifier.fillMaxWidth()) {
                    EmojiPanel(
                        store = emojiStore, height = 280.dp, compact = false, liquidGlass = liquidGlass, tint = tint,
                        onPickEmoji = { entry ->
                            touched = true
                            val t = field
                            val a = t.selection.min.coerceIn(0, t.text.length)
                            val b = t.selection.max.coerceIn(a, t.text.length)
                            val token = emojiStore.charFor(entry).toString()
                            val next = (t.text.substring(0, a) + token + t.text.substring(b))
                            if (next.length <= STATUS_MAX_LENGTH) field = androidx.compose.ui.text.input.TextFieldValue(next, androidx.compose.ui.text.TextRange(a + token.length))
                        },
                        onEditingChange = {}, floating = true, fillWidth = true
                    )
                }
            }
            // The box holds the status that's up now: send clears it.
            val isCurrent = current != null && current.isActive() && currentEditable != null && text.trim() == currentEditable.trim()
            fun post() {
                val t = text.trim()
                if (isCurrent) onClear() else if (t.isNotEmpty()) onPost(t, minutes)
            }
            @Composable
            fun Circle(modifier: Modifier, content: @Composable () -> Unit) {
                Box(
                    modifier.size(46.dp).then(
                        Modifier.opaqueFill(CircleShape, tint, liquidGlass)
                    ),
                    contentAlignment = Alignment.Center
                ) { content() }
            }
            // How long it lasts · custom emoji · your past statuses.
            Row(Modifier.then(swallow), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Circle(Modifier.clickable { tap(); durationOpen = !durationOpen; historyOpen = false; emojiOpen = false }) {
                    Text(
                        if (minutes == 0L) "∞" else "$amountValue${unit.short}",
                        color = Color.White, fontSize = if (minutes == 0L) 18.sp else 12.sp, fontWeight = FontWeight.Bold, maxLines = 1
                    )
                }
                Circle(Modifier.clickable {
                    tap()
                    emojiOpen = !emojiOpen; durationOpen = false; historyOpen = false
                    if (emojiOpen) keyboard?.hide() else keyboard?.show()
                }) {
                    Icon(androidx.compose.material.icons.Icons.Default.Star, contentDescription = "Emoji", tint = if (emojiOpen) Color.White else Color.White.copy(alpha = 0.85f), modifier = Modifier.size(22.dp))
                }
                Circle(Modifier.clickable { tap(); historyOpen = !historyOpen; durationOpen = false; emojiOpen = false }) {
                    Icon(
                        Icons.Default.History, contentDescription = "Status history",
                        tint = if (historyOpen) Color.White else Color.White.copy(alpha = 0.85f), modifier = Modifier.size(20.dp)
                    )
                }
            }
            Row(Modifier.fillMaxWidth().then(swallow), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val fieldShape = RoundedCornerShape(24.dp)
                Box(
                    Modifier.weight(1f).heightIn(min = 46.dp, max = 112.dp).verticalScroll(rememberScrollState()).then(
                        Modifier.opaqueFill(fieldShape, tint, liquidGlass)
                    ).padding(horizontal = 16.dp, vertical = 13.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
                    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
                    var layout by remember { mutableStateOf<androidx.compose.ui.text.TextLayoutResult?>(null) }
                    val emojiState = emojiStore.state
                    val transformation = remember(emojiStore, emojiState) { emojiVisualTransformation(emojiStore) }
                    Box(Modifier.fillMaxWidth()) {
                        BasicTextField(
                            value = field,
                            onValueChange = { v ->
                                touched = true
                                field = v.copy(text = v.text.replace('\n', ' ').take(STATUS_MAX_LENGTH))
                            },
                            textStyle = TextStyle(color = Color.White, fontSize = 14.sp, lineHeight = 20.sp),
                            cursorBrush = SolidColor(Color.White),
                            visualTransformation = transformation,
                            onTextLayout = { layout = it },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = { post() }),
                            modifier = Modifier.fillMaxWidth().focusRequester(focus)
                        )
                        EmojiFieldOverlay(text, layout, emojiStore)
                    }
                    if (text.isEmpty()) Text("What's on your mind?", color = Color.White.copy(alpha = 0.55f), fontSize = 14.sp)
                }
                Circle(Modifier.clickable(enabled = isCurrent || text.isNotBlank()) { tap(); post() }) {
                    if (isCurrent) {
                        Icon(Icons.Default.Close, contentDescription = "Clear status", tint = Color.White, modifier = Modifier.size(20.dp))
                    } else {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Post status", tint = if (text.isNotBlank()) Color.White else Color.White.copy(0.4f), modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
        pendingDelete?.let { entry ->
            ConfirmPopup(
                title = "Delete this status?",
                message = "\"${entry.text.take(80)}\" will be deleted from your status history.",
                confirmLabel = "Delete",
                liquidGlass = liquidGlass, tint = tint, backdrop = null,
                onConfirm = { pendingDelete = null; com.mediaviewer.util.StatusHistory.delete(did, entry) },
                onDismiss = { pendingDelete = null }
            )
        }
        // (The first time: statuses are public.)
        PublicDataNotice(PublicDataKind.STATUS, liquidGlass = liquidGlass, tint = tint)
    }
}

/** The status box's parts: solid (never see-through), in the profile's
 *  color; with Liquid Glass on, a glass-like rim around them. */
private fun Modifier.opaqueFill(shape: Shape, tint: Color, liquidGlass: Boolean): Modifier =
    this.clip(shape).background(lerp(Color(0xFF16161B), tint, 0.3f), shape)
        .then(if (liquidGlass) Modifier.border(1.dp, lerp(tint, Color.White, 0.35f).copy(alpha = 0.7f), shape) else Modifier)

/** A new status lasting [minutes] (0 = until it's changed). */
fun newProfileStatus(text: String, minutes: Long, emoji: List<com.mediaviewer.util.StatusEmoji> = emptyList()): ProfileStatus {
    val now = com.mediaviewer.platform.currentTimeMillis()
    val kept = text.take(STATUS_MAX_LENGTH)
    return ProfileStatus(
        text = kept,
        emoji = emoji.filter { it.at < kept.length },
        createdAt = com.mediaviewer.util.isoStringOf(now),
        expiresAt = if (minutes <= 0L) null else com.mediaviewer.util.isoStringOf(now + minutes * 60_000L)
    )
}

/**
 * A profile's video banner (or icon), muted and looping over the banner
 * picture. Until the video's first frame is showing, [poster] (the regular
 * picture) is drawn on top of it, so it never shows black while loading.
 * Played straight from the blob on the owner's PDS.
 */
@Composable
fun ProfileBannerVideo(
    did: String, cid: String, modifier: Modifier = Modifier, cornerPercent: Int = 0,
    poster: (@Composable () -> Unit)? = null
) {
    var url by remember(did, cid) { mutableStateOf<String?>(null) }
    LaunchedEffect(did, cid) {
        url = runCatching { com.mediaviewer.worker.BlueskyBlobResolver.resolveBlobUrl(did, cid) }.getOrNull()
    }
    val u = url ?: return
    val context = LocalContext.current
    val player = remember(u) { FeedVideos.acquire(context, u) }
    var firstFrame by remember(u) { mutableStateOf(false) }
    DisposableEffect(player) {
        val l = object : FeedVideoListener { override fun onFirstFrame() { firstFrame = true } }
        player.addListener(l)
        player.setVolumeMuted(true)
        player.play()
        onDispose {
            player.removeListener(l)
            player.setVolumeMuted(false)
            FeedVideos.recycle(u, player)
        }
    }
    // (Some players don't report the first frame: playing past the start counts.)
    LaunchedEffect(player) {
        while (!firstFrame) {
            if (player.isPlaying && player.currentPosition > 150L) firstFrame = true
            kotlinx.coroutines.delay(200)
        }
    }
    Box(modifier) {
        FeedVideoView(player, Modifier.matchParentSize(), fill = true, cornerPercent = cornerPercent)
        if (!firstFrame && poster != null) Box(Modifier.matchParentSize()) { poster() }
    }
}
