package com.mediaviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowOutward
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.mediaviewer.model.BskyProfileLinkView
import com.mediaviewer.model.MAX_PROFILE_LINKS
import com.mediaviewer.model.ProfileLinkDraft
import com.mediaviewer.util.rememberHapticTap

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
    modifier: Modifier = Modifier
) {
    val tap = rememberHapticTap()
    val haptic = LocalHapticFeedback.current
    val pillShape = RoundedCornerShape(50)
    Row(
        modifier
            .clip(pillShape)
            .background(tint.copy(alpha = 0.15f))
            .border(1.dp, tint.copy(alpha = 0.4f), pillShape)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { tap(); onOpen() },
                onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onLongPress() }
            )
            .padding(start = 10.dp, top = 6.dp, end = 10.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LinkPillContent(url = link.url, title = link.title, iconUrl = link.icon, tint = tint) {
            androidx.compose.foundation.layout.Spacer(Modifier.size(2.dp))
            androidx.compose.material3.Icon(
                Icons.Default.ArrowOutward, contentDescription = null,
                tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(12.dp)
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
            modifier = Modifier.size(16.dp).clip(RoundedCornerShape(3.dp))
        )
    } else {
        androidx.compose.material3.Icon(
            Icons.Default.Public, contentDescription = null,
            tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(16.dp)
        )
    }
    androidx.compose.foundation.layout.Spacer(Modifier.size(6.dp))
    Text(
        (title?.takeIf { it.isNotBlank() } ?: linkHost(url)).take(30),
        color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium,
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
    modifier: Modifier = Modifier
) {
    Row(
        modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        links.forEach { link ->
            ProfileLinkPill(
                link = link, tint = tint,
                onOpen = { onOpenLink(link) },
                onLongPress = { onLongPressLink(link) }
            )
        }
    }
}

// ── Long-press popup ───────────────────────────────────────────────────────

/**
 * The long-press popup, in Stellar's popup language (a centered panel, the
 * same shape Edit Profile uses): the full destination, then Open link and
 * Copy link. The official sheet also offers Report on other people's
 * profiles; this one deliberately has only the two actions asked for.
 */
@Composable
fun LinkPreviewPopup(
    link: BskyProfileLinkView,
    tint: Color,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onCopy: () -> Unit
) {
    val tap = rememberHapticTap()
    Box(
        Modifier.fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onDismiss() },
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier.padding(horizontal = 18.dp).widthIn(max = 440.dp).fillMaxWidth()
                .popupPanel(tint, RoundedCornerShape(24.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {}
                .padding(16.dp)
        ) {
            Text(
                link.url, color = Color.White.copy(alpha = 0.9f), fontSize = 13.sp,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(14.dp))
            Box(
                Modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(23.dp))
                    .background(lerp(tint, Color.Black, 0.12f))
                    .clickable { tap(); onOpen() },
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Open link", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.size(6.dp))
                    androidx.compose.material3.Icon(
                        Icons.Default.ArrowOutward, contentDescription = null,
                        tint = Color.White, modifier = Modifier.size(14.dp)
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(23.dp))
                    .border(1.dp, tint.copy(0.6f), RoundedCornerShape(23.dp))
                    .clickable { tap(); onCopy() },
                contentAlignment = Alignment.Center
            ) {
                Text("Copy link", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

// ── Editor row: drag to reorder, plus bubble to add ────────────────────────

/**
 * The Links row inside Edit Profile: the same pills with drag handles
 * instead of arrows, draggable to reorder (long-press, then drag — the held
 * pill follows the finger while the others shift around it), and the Hub's
 * plus bubble at the end (at the beginning when there are no links yet).
 * Tapping a pill edits it; tapping the plus adds one. Changes stay local
 * until the profile is saved.
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
    modifier: Modifier = Modifier
) {
    val tap = rememberHapticTap()
    val haptic = LocalHapticFeedback.current
    val scroll = rememberScrollState()
    // Root coordinates of each pill, for working out drag targets.
    val pillCoords = remember { mutableStateMapOf<String, LayoutCoordinates>() }
    var draggedKey by remember { mutableStateOf<String?>(null) }
    var fingerRootX by remember { mutableStateOf(0f) }
    var grabX by remember { mutableStateOf(0f) }
    val keyOf: (ProfileLinkDraft) -> String = { it.uri ?: "new:${it.url}" }

    Row(
        modifier.horizontalScroll(scroll, enabled = draggedKey == null),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // The plus bubble leads when there is nothing yet, else trails.
        if (links.isEmpty()) {
            // The Hub's plus bubble, reused as the add-link button.
            HubUploadBubble(liquidGlass = liquidGlass, tint = tint, onOpenComposePost = { onEdit(null) })
        }
        links.forEachIndexed { index, draft ->
            val draftKey = keyOf(draft)
            key(draftKey) {
                val isDragged = draggedKey == draftKey
                val coords = pillCoords[draftKey]
                val dragTx = if (isDragged && coords != null) {
                    fingerRootX - coords.positionInRoot().x - grabX
                } else 0f
                val pillShape = RoundedCornerShape(50)
                Box(
                    Modifier
                        .onGloballyPositioned { pillCoords[draftKey] = it }
                        .graphicsLayer {
                            translationX = dragTx
                            scaleX = if (isDragged) 1.05f else 1f
                            scaleY = if (isDragged) 1.05f else 1f
                        }
                        .clip(pillShape)
                        .background(tint.copy(alpha = if (isDragged) 0.3f else 0.15f))
                        .border(1.dp, tint.copy(alpha = 0.4f), pillShape)
                        .pointerInput(draftKey, enabled) {
                            if (!enabled) return@pointerInput
                            detectDragGesturesAfterLongPress(
                                onDragStart = { offset ->
                                    val c = pillCoords[draftKey] ?: return@detectDragGesturesAfterLongPress
                                    draggedKey = draftKey
                                    grabX = offset.x
                                    fingerRootX = c.positionInRoot().x + offset.x
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                },
                                onDragEnd = { draggedKey = null },
                                onDragCancel = { draggedKey = null },
                                onDrag = { change, _ ->
                                    val c = pillCoords[draftKey] ?: return@detectDragGesturesAfterLongPress
                                    fingerRootX = c.positionInRoot().x + change.position.x
                                    val current = links.indexOfFirst { keyOf(it) == draftKey }
                                    if (current < 0) return@detectDragGesturesAfterLongPress
                                    val others = links.filterIndexed { i, _ -> i != current }
                                    val target = others.count { other ->
                                        val oc = pillCoords[keyOf(other)] ?: return@count false
                                        oc.positionInRoot().x + oc.size.width / 2f < fingerRootX
                                    }
                                    if (target != current) {
                                        val next = links.toMutableList()
                                        val item = next.removeAt(current)
                                        next.add(target.coerceIn(0, next.size), item)
                                        onChange(next)
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    }
                                }
                            )
                        }
                        .clickable(enabled = enabled) { tap(); onEdit(index) }
                        .padding(start = 10.dp, top = 6.dp, end = 8.dp, bottom = 6.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LinkPillContent(
                            url = draft.url, title = draft.title, iconUrl = draft.iconUrl, tint = tint
                        ) {
                            androidx.compose.foundation.layout.Spacer(Modifier.size(4.dp))
                            androidx.compose.material3.Icon(
                                Icons.Default.DragHandle, contentDescription = null,
                                tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }
            }
        }
        if (links.isNotEmpty() && links.size < MAX_PROFILE_LINKS) {
            // The Hub's plus bubble, reused as the add-link button.
            HubUploadBubble(liquidGlass = liquidGlass, tint = tint, onOpenComposePost = { onEdit(null) })
        }
    }
}

// ── Add / edit form ────────────────────────────────────────────────────────

/**
 * The add/edit link form, in Stellar's popup language (a centered panel like
 * Edit Profile's): a Link field, an optional Title field (30 characters,
 * like Bluesky's), and Save, with Remove for an edit. Titles stay manual —
 * Stellar has no page-title service to suggest one from, the way Bluesky's
 * card service does — so a blank title shows the domain on the pill.
 */
@Composable
fun LinkFormPopup(
    editing: ProfileLinkDraft?,
    existingUrls: List<String>,
    tint: Color,
    onDismiss: () -> Unit,
    onSave: (ProfileLinkDraft) -> Unit,
    onRemove: (() -> Unit)?
) {
    val tap = rememberHapticTap()
    var input by remember { mutableStateOf(editing?.url?.removePrefix("https://")?.removePrefix("http://")?.removePrefix("www.") ?: "") }
    var title by remember { mutableStateOf(editing?.title ?: "") }
    var error by remember { mutableStateOf<String?>(null) }
    val isEditing = editing != null

    Box(
        Modifier.fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onDismiss() },
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier.padding(horizontal = 18.dp).widthIn(max = 440.dp).fillMaxWidth()
                .popupPanel(tint, RoundedCornerShape(24.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {}
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Text(
                if (isEditing) "Edit link" else "Add a link",
                color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(14.dp))
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
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier.weight(1f).height(46.dp).clip(RoundedCornerShape(23.dp))
                        .border(1.dp, tint.copy(0.6f), RoundedCornerShape(23.dp))
                        .clickable { tap(); onDismiss() },
                    contentAlignment = Alignment.Center
                ) { Text("Close", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
                Box(
                    Modifier.weight(1f).height(46.dp).clip(RoundedCornerShape(23.dp))
                        .background(lerp(tint, Color.Black, 0.12f))
                        .clickable {
                            tap()
                            val url = normalizeProfileLinkUrl(input)
                            val err = profileLinkInputError(input, existingUrls)
                            if (url == null || err != null) { error = err ?: "That doesn't look like a link. Try something like yoursite.com."; return@clickable }
                            onSave(ProfileLinkDraft(url = url, title = title.trim().ifBlank { null }, uri = editing?.uri, iconUrl = editing?.iconUrl))
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (isEditing) "Save changes" else "Add link",
                        color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold
                    )
                }
            }
            if (isEditing && onRemove != null) {
                Spacer(Modifier.height(10.dp))
                Box(
                    Modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(23.dp))
                        .border(1.dp, Color(0xFFFF8A80).copy(0.6f), RoundedCornerShape(23.dp))
                        .clickable { tap(); onRemove() },
                    contentAlignment = Alignment.Center
                ) {
                    Text("Remove link", color = Color(0xFFFF8A80), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
