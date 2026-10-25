package com.mediaviewer.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowOutward
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.mediaviewer.model.BskyProfileLinkView
import com.mediaviewer.model.MAX_PROFILE_LINKS
import com.mediaviewer.model.ProfileLinkDraft
import com.mediaviewer.ui.compat.BackHandler
import com.mediaviewer.util.rememberHapticTap
import kotlin.math.roundToInt

// ── Profile links ──────────────────────────────────────────────────────────
// Bluesky's profile links (app.bsky.actor.link records, listed in order by
// the profile record's `links` strong refs), rendered the way Bluesky's own
// app renders them — a small pill with the site's favicon and the title (or
// the domain) — but in Stellar's profile colors. The row scrolls
// horizontally when there are too many pills to fit, and a long press opens
// a popup with Open link and Copy link. The editor row below reuses the
// same pills with drag handles, and the Hub's plus bubble adds a link.

// ── Validation (mirrors the official client's providers.ts) ────────────────

/** The host a pill shows: the domain without www, or the URL itself. */
fun linkHost(url: String): String {
    val host = url.substringAfter("://").substringBefore("/").substringBefore(":").lowercase()
    return host.removePrefix("www.").ifBlank { url }
}

/**
 * Normalizes typed input into a canonical https URL, or null when it isn't
 * a usable web link. A missing scheme is read as https.
 */
fun normalizeProfileLinkUrl(input: String): String? {
    val trimmed = input.trim()
    if (trimmed.isEmpty() || trimmed.contains(" ")) return null
    val withScheme = if (trimmed.contains("://")) trimmed else "https://$trimmed"
    if (!withScheme.startsWith("https://")) return null
    val rest = withScheme.removePrefix("https://")
    val host = rest.substringBefore("/").substringBefore(":").lowercase()
    if (host.isBlank() || !host.contains(".")) return null
    val tail = rest.substringAfter("/", "").let { if (it.isEmpty()) "" else "/$it" }
    return "https://$host$tail"
}

/**
 * General-purpose URL shorteners, which hide where a link goes (the official
 * client's list, verbatim). A brand's own short links (youtu.be, amzn.to)
 * only lead to that brand, so they aren't listed.
 */
private val SHORTENER_DOMAINS = listOf(
    "aceimg.in", "acel.me", "adf.ly", "bit.ly", "bitly.com", "bl.ink",
    "buff.ly", "clck.ru", "cutt.ly", "fc-lc.xyz", "goo.gl", "goto.now",
    "is.gd", "link2.fm", "ln.run", "lnk.ua", "lnkd.in", "n9.cl",
    "onlylinks.cc", "ow.ly", "psee.io", "rb.gy", "rebrand.ly", "rg.by",
    "s.id", "short.do", "short.gy", "shorten.as", "shorten.ee",
    "shorten.is", "shorten.so", "shorten.tv", "shorten.world",
    "shorter.me", "shortn.icu", "shorturl.at", "shorturl.lt",
    "shorturl.ly", "slicedrve.in", "slnt.to", "smarturl.lt",
    "smarturl.my", "sprl.me", "sw.run", "t.co", "t.ly", "tiny.cc",
    "tinyurl.com", "urlr.me", "v.gd", "vdey.in", "videy.at", "videy.lt",
    "videy.my", "x.gd", "zig.ht"
)

private fun isShortenerUrl(url: String): Boolean {
    val host = url.substringAfter("://").substringBefore("/").substringBefore(":").lowercase()
    return SHORTENER_DOMAINS.any { host == it || host.endsWith(".$it") }
}

/**
 * What's wrong with typed link input, or null when it saves cleanly. The
 * insecure case is flagged right away (typing more can't fix an http
 * scheme); the rest only surface when Save is pressed.
 */
fun profileLinkInputError(input: String, existingUrls: List<String>): String? {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return null
    if (trimmed.startsWith("http://", ignoreCase = true)) {
        return "Only secure links can be added. Use a link that starts with https://."
    }
    val url = normalizeProfileLinkUrl(trimmed)
        ?: return "That doesn't look like a link. Try something like yoursite.com."
    if (isShortenerUrl(url)) return "Short links can't be added. Use the full link instead."
    if (existingUrls.any { normalizeProfileLinkUrl(it) == url }) return "You've already added this link."
    return null
}

// ── Display pill + row ─────────────────────────────────────────────────────

/**
 * One link pill: the site's favicon (or a globe when there is none), the
 * title or domain on one line, and the outbound arrow. A tap opens the
 * link; a long press opens the preview popup.
 */
@Composable
fun ProfileLinkPill(
    link: BskyProfileLinkView,
    tint: Color,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
    liquidGlass: Boolean = false
) {
    val tap = rememberHapticTap()
    val haptic = LocalHapticFeedback.current
    val pillShape = RoundedCornerShape(50)
    Row(
        modifier
            .height(FILTER_CHIP_HEIGHT)
            .then(
                if (liquidGlass) Modifier.glassPanel(true, tint = tint.copy(alpha = 0.7f), shape = pillShape)
                else Modifier.clip(pillShape).background(Color.White.copy(alpha = 0.08f))
            )
            .border(1.dp, Color.White.copy(alpha = 0.22f), pillShape)
            .clip(pillShape)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { tap(); onOpen() },
                onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onLongPress() }
            )
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LinkPillContent(url = link.url, title = link.title, iconUrl = link.icon, tint = tint) {
            androidx.compose.foundation.layout.Spacer(Modifier.size(2.dp))
            androidx.compose.material3.Icon(
                Icons.Default.ArrowOutward, contentDescription = null,
                tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(10.dp)
            )
        }
    }
}

/** The pill's shared inside: favicon (or globe) + title-or-domain text. */
@Composable
private fun LinkPillContent(
    url: String,
    title: String?,
    iconUrl: String?,
    tint: Color,
    trailing: @Composable () -> Unit
) {
    var iconFailed by remember(iconUrl) { mutableStateOf(false) }
    if (!iconUrl.isNullOrBlank() && !iconFailed) {
        AsyncImage(
            model = iconUrl, contentDescription = null, contentScale = ContentScale.Crop,
            onError = { iconFailed = true },
            modifier = Modifier.size(14.dp).clip(RoundedCornerShape(3.dp))
        )
    } else {
        androidx.compose.material3.Icon(
            Icons.Default.Public, contentDescription = null,
            tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(14.dp)
        )
    }
    androidx.compose.foundation.layout.Spacer(Modifier.size(6.dp))
    Text(
        (title?.takeIf { it.isNotBlank() } ?: linkHost(url)).take(30),
        color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium,
        maxLines = 1, overflow = TextOverflow.Ellipsis
    )
    trailing()
}

/**
 * The row of link pills under the bio, starting at the profile's left edge
 * and scrolling horizontally when there are too many to fit. The caller
 * supplies the edge padding (PROFILE_EDGE) so the first pill lines up with
 * the bio and the post tabs.
 */
@Composable
fun ProfileLinksRow(
    links: List<BskyProfileLinkView>,
    tint: Color,
    onOpenLink: (BskyProfileLinkView) -> Unit,
    onLongPressLink: (BskyProfileLinkView) -> Unit,
    modifier: Modifier = Modifier,
    liquidGlass: Boolean = false
) {
    Row(
        modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        links.forEach { link ->
            ProfileLinkPill(
                link = link, tint = tint,
                onOpen = { onOpenLink(link) },
                onLongPress = { onLongPressLink(link) },
                liquidGlass = liquidGlass
            )
        }
    }
}

// ── Long-press popup ───────────────────────────────────────────────────────

/**
 * The long-press popup for a profile link, on the app's confirmation popup
 * system (the same card as "Save Emoji?"): a "Profile Link" header, the
 * full URL as the body text, and two side-by-side buttons — Copy link on
 * the left, Open link on the right. Tapping outside or Back dismisses it.
 * Layout values are ConfirmPopup's, verbatim.
 * The official sheet also offers Report on other people's profiles; this
 * one deliberately has only the two actions asked for.
 */
@Composable
fun LinkPreviewPopup(
    link: BskyProfileLinkView,
    tint: Color,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onCopy: () -> Unit,
    liquidGlass: Boolean = false,
    backdrop: GlassBackdrop? = null
) {
    val tap = rememberHapticTap()
    val view = com.mediaviewer.ui.compat.rememberPlatformView()
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        view.performHapticFeedback(com.mediaviewer.ui.compat.HapticFeedbackConstants.CONTEXT_CLICK)
        appear.animateTo(1f, spring(dampingRatio = 0.72f, stiffness = 520f))
    }
    BackHandler(onBack = onDismiss)
    val shape = RoundedCornerShape(22.dp)
    val glass = PopupStyle.glass(liquidGlass)
    Box(
        Modifier.fillMaxSize()
            .graphicsLayer { alpha = appear.value.coerceIn(0f, 1f) }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onDismiss() },
        contentAlignment = Alignment.Center
    ) {
        val cardModifier = Modifier
            .padding(horizontal = 36.dp).widthIn(max = 360.dp).fillMaxWidth()
            .graphicsLayer {
                val sc = 0.88f + 0.12f * appear.value
                scaleX = sc; scaleY = sc
            }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
        val content: @Composable () -> Unit = {
            Column(Modifier.fillMaxWidth().padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "Profile Link",
                    color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    link.url, color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp, lineHeight = 18.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val pill = RoundedCornerShape(19.dp)
                    val copyMod = Modifier.weight(1f).height(38.dp).clip(pill).clickable { tap(); onCopy() }
                    val openMod = Modifier.weight(1f).height(38.dp).clip(pill).clickable {
                        view.performHapticFeedback(com.mediaviewer.ui.compat.HapticFeedbackConstants.LONG_PRESS)
                        onOpen()
                    }
                    if (glass) {
                        LiquidGlassSurface(copyMod, shape = pill, tint = tint, contentAlignment = Alignment.Center) {
                            Text("Copy link", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        }
                        LiquidGlassSurface(openMod, shape = pill, tint = tint, contentAlignment = Alignment.Center) {
                            Text("Open link", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        }
                    } else {
                        Box(copyMod.popupButtonFill(tint, pill), contentAlignment = Alignment.Center) {
                            Text("Copy link", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Box(openMod.popupButtonFill(tint, pill, strong = true), contentAlignment = Alignment.Center) {
                            Text("Open link", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
        if (glass) {
            LiquidGlassSurface(cardModifier, shape = shape, tint = tint, backdrop = backdrop) {
                Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.3f)))
                content()
            }
        } else {
            Box(cardModifier.popupPanel(tint, shape)) { content() }
        }
    }
}

// ── Editor row: drag to reorder, plus bubble to add ────────────────────────

/**
 * Drag state for the editor's link pills. This is the Hub feed row's
 * HubFeedDragState system, minus the Hub-only drop targets (Remove / Pin /
 * share): the row keeps its own order copy, the other pills shift visually
 * while one is held, and the reorder commits once at drop. The first
 * version reordered the list live on every drag event, which moved every
 * pill's coordinates mid-gesture and fought the drag — that is why it
 * never worked.
 */
class LinkDragState {
    var active by mutableStateOf(false)
    var link by mutableStateOf<ProfileLinkDraft?>(null)
    var fromIndex by mutableIntStateOf(-1)
    var targetIndex by mutableIntStateOf(-1)
    /** Finger position, root coordinates. */
    var pointer by mutableStateOf(Offset.Zero)
    /** Where on the pill it was grabbed. */
    var grab by mutableStateOf(Offset.Zero)
    var chipWidthPx by mutableIntStateOf(0)
    var rootOrigin: Offset = Offset.Zero

    fun reset() {
        active = false; link = null; fromIndex = -1; targetIndex = -1
    }
}

/**
 * The Links row inside Edit Profile: the same pills with drag handles
 * instead of arrows, reordered with the Hub feed row's drag system
 * (long-press, then drag — a lifted ghost follows the finger while the
 * other pills shift around it), and the Hub's plus bubble at the end (at
 * the beginning when there are no links yet), at the pills' own height.
 * Tapping a pill edits it; tapping the plus adds one. Changes stay local
 * until the profile is saved. The ghost renders from EditProfileDialog's
 * root, above the panel, from this same [drag] state.
 */
@Composable
fun LinksEditorRow(
    links: List<ProfileLinkDraft>,
    tint: Color,
    liquidGlass: Boolean,
    enabled: Boolean,
    onChange: (List<ProfileLinkDraft>) -> Unit,
    /** The tapped pill's index, or null for the plus bubble (add). */
    onEdit: (Int?) -> Unit,
    modifier: Modifier = Modifier,
    drag: LinkDragState
) {
    val tap = rememberHapticTap()
    val haptic = LocalHapticFeedback.current
    // The row's own copy of the order, so a drop re-lays it out in the same
    // frame the drag ends (no one-frame jump while the parent catches up).
    var order by remember(links) { mutableStateOf(links) }
    val latestOrder by rememberUpdatedState(order)
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    val spacingPx = with(density) { 6.dp.toPx() }
    val edgePx = with(density) { 56.dp.toPx() }
    val coords = remember { HashMap<String, LayoutCoordinates>() }
    var rowCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val keyOf: (ProfileLinkDraft) -> String = { it.uri ?: "new:${it.url}" }

    fun recomputeTarget() {
        val d = drag.link ?: return
        val x = drag.pointer.x
        val others = latestOrder.filter { keyOf(it) != keyOf(d) }
        val t = others.count { o ->
            val c = coords[keyOf(o)]?.takeIf { it.isAttached } ?: return@count false
            c.localToRoot(Offset(c.size.width / 2f, 0f)).x < x
        }
        if (t != drag.targetIndex) {
            if (drag.targetIndex >= 0) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            drag.targetIndex = t
        }
    }

    fun finish(commit: Boolean) {
        val d = drag.link
        val from = drag.fromIndex
        val to = drag.targetIndex
        if (commit && d != null && from >= 0 && to >= 0 && to != from) {
            order = latestOrder.toMutableList().apply { add(to, removeAt(from)) }
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onChange(order)
        } else if (commit) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
        drag.reset()
    }

    // Holding a pill near either edge scrolls the row. (HubFeedRow.)
    LaunchedEffect(drag.active) {
        while (drag.active) {
            withFrameNanos { }
            val rc = rowCoords?.takeIf { it.isAttached } ?: continue
            val left = rc.localToRoot(Offset.Zero).x
            val right = left + rc.size.width
            val x = drag.pointer.x
            val delta = when {
                x < left + edgePx -> -(1f - (x - left).coerceAtLeast(0f) / edgePx) * 18f
                x > right - edgePx -> (1f - (right - x).coerceAtLeast(0f) / edgePx) * 18f
                else -> 0f
            }
            if (delta != 0f && scroll.dispatchRawDelta(delta) != 0f) recomputeTarget()
        }
    }

    Row(
        modifier.onGloballyPositioned { rowCoords = it }.horizontalScroll(scroll),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // The plus bubble leads when there is nothing yet, else trails —
        // at the pills' own height, like the Hub's button is its bar's.
        if (order.isEmpty()) {
            HubUploadBubble(liquidGlass = liquidGlass, tint = tint, size = FILTER_CHIP_HEIGHT, onOpenComposePost = { onEdit(null) })
        }
        order.forEachIndexed { index, draft ->
            val draftKey = keyOf(draft)
            key(draftKey) {
                val isHeld = drag.active && drag.link?.let { keyOf(it) == draftKey } == true
                val shiftTarget = if (!drag.active || isHeld) 0f else {
                    val from = drag.fromIndex
                    val t = drag.targetIndex
                    val w = drag.chipWidthPx + spacingPx
                    when {
                        from < t && index in (from + 1)..t -> -w
                        from > t && index in t until from -> w
                        else -> 0f
                    }
                }
                val shift by animateFloatAsState(
                    targetValue = shiftTarget,
                    animationSpec = if (drag.active) spring(dampingRatio = 0.75f, stiffness = 420f) else snap(),
                    label = "linkShift"
                )
                // Dropped pills settle in with a small bounce.
                val settle = remember { Animatable(1f) }
                LaunchedEffect(isHeld) {
                    if (!isHeld && settle.value != 1f) settle.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 500f))
                    if (isHeld) settle.snapTo(0.85f)
                }
                Box(
                    Modifier
                        .onGloballyPositioned { coords[draftKey] = it }
                        .pointerInput(draftKey, order, enabled) {
                            if (!enabled) return@pointerInput
                            detectTapGestures(onTap = {
                                val i = latestOrder.indexOfFirst { keyOf(it) == draftKey }
                                if (i >= 0) { tap(); onEdit(i) }
                            })
                        }
                        .pointerInput(draftKey, order, enabled) {
                            if (!enabled) return@pointerInput
                            detectDragGesturesAfterLongPress(
                                onDragStart = { off ->
                                    val c = coords[draftKey] ?: return@detectDragGesturesAfterLongPress
                                    drag.link = draft
                                    drag.fromIndex = latestOrder.indexOfFirst { keyOf(it) == draftKey }
                                    drag.targetIndex = drag.fromIndex
                                    drag.grab = off
                                    drag.chipWidthPx = c.size.width
                                    drag.pointer = c.localToRoot(off)
                                    drag.active = true
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                },
                                onDrag = { change, _ ->
                                    change.consume()
                                    val c = coords[draftKey]?.takeIf { it.isAttached } ?: return@detectDragGesturesAfterLongPress
                                    drag.pointer = c.localToRoot(change.position)
                                    recomputeTarget()
                                },
                                onDragEnd = { finish(commit = true) },
                                onDragCancel = { finish(commit = false) }
                            )
                        }
                ) {
                    LinkEditorPill(
                        draft, tint, liquidGlass,
                        Modifier.graphicsLayer {
                            translationX = shift
                            alpha = if (isHeld) 0f else 1f
                            scaleX = settle.value; scaleY = settle.value
                        }
                    )
                }
            }
        }
        if (order.isNotEmpty() && order.size < MAX_PROFILE_LINKS) {
            HubUploadBubble(liquidGlass = liquidGlass, tint = tint, size = FILTER_CHIP_HEIGHT, onOpenComposePost = { onEdit(null) })
        }
    }
}

/** One editor pill: the display pill's look with a drag handle, no arrow. */
@Composable
private fun LinkEditorPill(
    draft: ProfileLinkDraft,
    tint: Color,
    liquidGlass: Boolean,
    modifier: Modifier = Modifier
) {
    val pillShape = RoundedCornerShape(50)
    Box(
        modifier
            .height(FILTER_CHIP_HEIGHT)
            .then(
                if (liquidGlass) Modifier.glassPanel(true, tint = tint.copy(alpha = 0.7f), shape = pillShape)
                else Modifier.clip(pillShape).background(Color.White.copy(alpha = 0.08f))
            )
            .border(1.dp, Color.White.copy(alpha = 0.22f), pillShape)
            .clip(pillShape),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 10.dp, end = 8.dp)
        ) {
            LinkPillContent(
                url = draft.url, title = draft.title, iconUrl = draft.iconUrl, tint = tint
            ) {
                androidx.compose.foundation.layout.Spacer(Modifier.size(4.dp))
                androidx.compose.material3.Icon(
                    Icons.Default.DragHandle, contentDescription = null,
                    tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(12.dp)
                )
            }
        }
    }
}

/** The picked-up pill: lifted (bigger, with a shadow) and following the
 *  finger. Rendered by EditProfileDialog's root, above the panel. */
@Composable
fun LinkDragGhost(
    drag: LinkDragState,
    draft: ProfileLinkDraft,
    tint: Color,
    liquidGlass: Boolean
) {
    if (!drag.active) return
    val lift = remember(keyOfGhost(draft)) { Animatable(1f) }
    LaunchedEffect(keyOfGhost(draft)) { lift.animateTo(1.14f, spring(dampingRatio = 0.5f, stiffness = 600f)) }
    Box(
        Modifier
            .offset {
                IntOffset(
                    (drag.pointer.x - drag.grab.x - drag.rootOrigin.x).roundToInt(),
                    (drag.pointer.y - drag.grab.y - drag.rootOrigin.y).roundToInt()
                )
            }
            .graphicsLayer {
                val sc = lift.value
                scaleX = sc; scaleY = sc
                transformOrigin = TransformOrigin(
                    (drag.grab.x / drag.chipWidthPx.coerceAtLeast(1)).coerceIn(0f, 1f), 0.5f
                )
                shadowElevation = 14.dp.toPx()
                shape = RoundedCornerShape(50)
                clip = false
            }
    ) {
        LinkEditorPill(draft, tint, liquidGlass)
    }
}

private fun keyOfGhost(draft: ProfileLinkDraft): String = draft.uri ?: "new:${draft.url}"

// ── Add / edit form ────────────────────────────────────────────────────────

/**
 * The add/edit link form, on the Feed Creator's system ([LocalPopup]):
 * the X at the top left closes it, the trash at the top right removes
 * the link (two taps: trash, then "Really?"), and a tap outside never
 * closes it. The fields stay Edit Profile's EditFields — the form lives
 * in that dialog. Titles stay manual — Stellar has no page-title service
 * to suggest one from, the way Bluesky's card service does — so a blank
 * title shows the domain on the pill.
 */
@Composable
fun LinkFormPopup(
    editing: ProfileLinkDraft?,
    existingUrls: List<String>,
    tint: Color,
    onDismiss: () -> Unit,
    onSave: (ProfileLinkDraft) -> Unit,
    onRemove: (() -> Unit)?,
    liquidGlass: Boolean = false,
    backdrop: GlassBackdrop? = null
) {
    var input by remember { mutableStateOf(editing?.url?.removePrefix("https://")?.removePrefix("http://")?.removePrefix("www.") ?: "") }
    var title by remember { mutableStateOf(editing?.title ?: "") }
    var error by remember { mutableStateOf<String?>(null) }
    val isEditing = editing != null
    LocalPopup(
        title = if (isEditing) "Edit Link" else "Add Link",
        liquidGlass = liquidGlass, tint = tint, onClose = onDismiss,
        maxWidth = 360.dp, backdrop = backdrop,
        dismissOnOutsideTap = false,
        onDelete = if (isEditing) onRemove else null
    ) {
        EditField(
            value = input,
            onValue = {
                input = it
                error = null
            },
            label = "Link", tint = tint,
            keyboardType = KeyboardType.Uri
        )
        // An http scheme can't be fixed by typing more, so it errors
        // right away; everything else errors on Save.
        val shownError = if (input.trim().startsWith("http://", ignoreCase = true)) {
            "Only secure links can be added. Use a link that starts with https://."
        } else error
        if (shownError != null) {
            Spacer(Modifier.height(6.dp))
            Text(shownError, color = Color(0xFFFF8A80), fontSize = 12.sp)
        }
        Spacer(Modifier.height(10.dp))
        EditField(
            value = title, onValue = { title = it.take(30) }, label = "Title", tint = tint,
            counter = if (title.length >= 20) "${title.length}/30" else null
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LocalPillButton(
                if (isEditing) "Save changes" else "Add link", liquidGlass, tint,
                {
                    val url = normalizeProfileLinkUrl(input)
                    val err = profileLinkInputError(input, existingUrls)
                    if (url == null || err != null) {
                        error = err ?: "That doesn't look like a link. Try something like yoursite.com."
                    } else {
                        onSave(ProfileLinkDraft(url = url, title = title.trim().ifBlank { null }, uri = editing?.uri, iconUrl = editing?.iconUrl))
                    }
                },
                Modifier.weight(1f)
            )
        }
    }
}

