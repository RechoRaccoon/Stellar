package com.mediaviewer.ui

import com.mediaviewer.ui.compat.navBarSpace
import com.mediaviewer.ui.compat.LocalContext
import com.mediaviewer.ui.theme.AvatarShape

import com.mediaviewer.ui.compat.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import com.mediaviewer.ui.theme.CircleShape
import com.mediaviewer.ui.theme.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import com.mediaviewer.i18n.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.mediaviewer.model.DmConversation
import com.mediaviewer.model.MediaItem
import com.mediaviewer.ui.theme.*
import com.mediaviewer.util.rememberHapticTap

/**
 * Share To: send the current post to people in your DMs.
 *
 * Rendered in-place (never a separate Dialog window) so its glass can
 * live-blur the post behind it — the post's own UI fades away while this is
 * open (see MainFeedScreen's popupOpen), and the whole thing fades/scales in
 * and out via [FadingPopupHost] at the call site.
 *
 * Up to three people across, each with room for a two-line name and their
 * handle; the message box is one compact pill at the bottom of the sheet
 * (the emoji button, the post's thumbnail, the text, the send button),
 * and the sheet rides up on top of the keyboard when you type. The emoji
 * button and menu are Quote Repost's: the menu sits below the sheet,
 * where the keyboard was, at the keyboard's height.
 */
@Composable
fun SendDmDialog(
    target: MediaItem?,
    conversations: List<DmConversation>,
    loading: Boolean,
    selected: Set<String>,
    sending: Boolean,
    liquidGlass: Boolean,
    dominantColor: Color = NeutralGlassTint,
    backdrop: GlassBackdrop? = null,
    /** The signed-in user's avatar, for the emoji menu's accounts row. */
    selfAvatarUrl: String? = null,
    onToggleSelect: (String) -> Unit,
    onSend: (String) -> Unit,
    onDismiss: () -> Unit
) {
    if (target == null) return
    var message by remember(target.id) { mutableStateOf("") }
    val tap = rememberHapticTap()
    val thumbUrl = target.thumbUrl.ifBlank { target.mediaUrl }
    val tint = dominantColor
    // Custom emoji, as in Quote Repost: the same star button (left of the
    // thumbnail, mirroring Send) and the menu below the sheet, where the
    // keyboard was.
    val platformContext = LocalContext.current
    val emojiStore = remember { com.mediaviewer.util.EmojiStore.get(platformContext) }
    LaunchedEffect(Unit) { emojiStore.load() }
    val selfDidForEmoji = LocalOverlays.selfDid
    LaunchedEffect(selfDidForEmoji) { if (selfDidForEmoji.isNotBlank()) emojiStore.useAccount(selfDidForEmoji) }
    var emojiPanelOpen by remember(target.id) { mutableStateOf(false) }
    var emojiTabEditing by remember(target.id) { mutableStateOf(false) }
    // The field's caret-aware emoji inserter, handed up by the message box
    // (the field state lives there). The menu below the sheet inserts
    // through it, at the caret, exactly as the DM menu does.
    var insertEmojiFn by remember { mutableStateOf<((com.mediaviewer.util.EmojiEntry) -> Unit)?>(null) }
    // The menu takes the keyboard's place, so it is the keyboard's height
    // (remembered from the tallest the keyboard has been), exactly as in
    // DMs and the composer — not a fixed height.
    val density = androidx.compose.ui.platform.LocalDensity.current
    val imeBottom = WindowInsets.ime.getBottom(density)
    var shareKeyboardPx by remember(target.id) { mutableStateOf(0) }
    LaunchedEffect(imeBottom) { if (imeBottom > shareKeyboardPx) shareKeyboardPx = imeBottom }
    val emojiPanelHeight = with(density) { if (shareKeyboardPx > 0) shareKeyboardPx.toDp() else 300.dp }.coerceIn(240.dp, 480.dp)
    val shareImeDp = with(density) { imeBottom.toDp() }
    LaunchedEffect(emojiPanelOpen, selfDidForEmoji) {
        if (selfDidForEmoji.isNotBlank()) {
            if (emojiPanelOpen) com.mediaviewer.util.EmojiCloud.requestSync(emojiStore, selfDidForEmoji)
            else com.mediaviewer.util.EmojiCloud.requestSync(emojiStore, selfDidForEmoji, force = true)
        }
    }
    val focus = remember { FocusRequester() }

    BackHandler(onBack = onDismiss)

    Box(
        modifier = Modifier.fillMaxSize()
            // (No dim: the page behind is already blurred, and the sheet's
            // own panel stands out on its own.) Tapping outside closes it.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
            // Top: just under the camera notch (where the Hub's search bar
            // sits).
            .padding(top = rememberTopCutoutClearance())
            // With the menu up, no bottom insets: the menu takes the
            // keyboard's place at the screen's bottom edge, as in Quote
            // Repost. Otherwise the sheet sits right on top of the keyboard.
            .then(if (emojiPanelOpen) Modifier else Modifier.imePadding().windowInsetsPadding(WindowInsets.navBarSpace)),
        contentAlignment = Alignment.BottomCenter
    ) {
        val wideDp = rememberLandscapeWidthDp()
        // The sheet, then the menu under it: the menu is a sibling of
        // the sheet, not content inside it, so it replaces the keyboard
        // like the Quote Repost menu instead of stretching the popup.
        Column(Modifier.fillMaxSize()) {
        PopupSheetSurface(
            liquidGlass = liquidGlass, tint = tint, backdrop = backdrop,
            modifier = Modifier.fillMaxWidth().weight(1f)
                .padding(start = 12.dp, end = 12.dp, bottom = if (emojiPanelOpen) 0.dp else 12.dp)
        ) {
            Column(Modifier.fillMaxSize()) {
                PopupSheetHeader(
                    title = "Share with",
                    subtitle = if (selected.isNotEmpty()) "· ${selected.size}" else null,
                    liquidGlass = liquidGlass, tint = tint, backdrop = backdrop,
                    onClose = onDismiss
                )

                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        loading && conversations.isEmpty() ->
                            CircularProgressIndicator(Modifier.align(Alignment.Center).size(26.dp), color = Color.White, strokeWidth = 1.5.dp)
                        conversations.isEmpty() ->
                            Text("No conversations yet", color = Color.White, fontSize = 13.sp,
                                modifier = Modifier.align(Alignment.Center).popupTextShadow().padding(horizontal = 12.dp, vertical = 6.dp))
                        // (Landscape: more people a row, about 140 dp each.)
                        else -> LazyVerticalGrid(
                            columns = GridCells.Fixed(Landscape.lanes(3, wideDp, 140)),
                            contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(conversations, key = { it.member.did }) { convo ->
                                RecipientCell(
                                    convo = convo,
                                    isSelected = selected.contains(convo.member.did),
                                    tint = tint,
                                    onTap = {
                                        tap()
                                        onToggleSelect(convo.member.did)
                                    }
                                )
                            }
                        }
                    }
                }

                PopupMessageBox(
                    thumbUrl = thumbUrl,
                    value = message, onValueChange = { message = it },
                    placeholder = if (selected.isEmpty()) "Pick someone, then add a message…" else "Add a message…",
                    tint = tint,
                    canSend = selected.isNotEmpty() && !sending,
                    sending = sending,
                    onSend = { if (selected.isNotEmpty() && !sending) onSend(message.trim()) },
                    focusRequester = focus,
                    // Tagging autocomplete + custom emoji, as in Quote
                    // Repost: the field uses the same box configuration,
                    // so @handles complete with a ghost suggestion and
                    // picked emoji render inline.
                    mentionAutocomplete = true,
                    emojiStore = emojiStore,
                    selfDid = selfDidForEmoji,
                    selfAvatarUrl = selfAvatarUrl,
                    emojiPanelOpen = emojiPanelOpen,
                    onEmojiPanelChange = { emojiPanelOpen = it },
                    emojiTabEditing = emojiTabEditing,
                    onEmojiTabEditingChange = { emojiTabEditing = it },
                    onInsertEmojiAvailable = { insertEmojiFn = it },
                    modifier = Modifier.padding(start = 10.dp, end = 10.dp, bottom = 10.dp, top = 2.dp)
                )
            }
        }
        // Custom emoji menu, below the sheet where the keyboard was —
        // the DM menu's height, and riding on the keyboard while a tab
        // is being renamed. Taps on it must not dismiss the popup (the
        // sheet absorbs its own taps the same way).
        if (emojiPanelOpen) {
            Box(
                Modifier.fillMaxWidth()
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            ) {
                EmojiPanel(
                    store = emojiStore,
                    height = emojiPanelHeight,
                    compact = emojiTabEditing,
                    liquidGlass = liquidGlass,
                    tint = tint,
                    onPickEmoji = { entry -> insertEmojiFn?.invoke(entry) },
                    onEditingChange = { emojiTabEditing = it },
                    showAccounts = true, selfDid = selfDidForEmoji, selfAvatarUrl = selfAvatarUrl,
                    modifier = Modifier.fillMaxWidth()
                        .padding(bottom = if (emojiTabEditing) shareImeDp else 0.dp)
                )
            }
        }
        }
    }
}

@Composable
private fun RecipientCell(convo: DmConversation, isSelected: Boolean, tint: Color, onTap: () -> Unit) {
    val cellShape = RoundedCornerShape(18.dp)
    val pop by animateFloatAsState(
        if (isSelected) 1f else 0f,
        spring(dampingRatio = 0.55f, stiffness = 500f), label = "recipientPop"
    )
    // Their own profile color for the selection ring and check.
    val ringColor = if (isSelected && !convo.isGroup && convo.member.did.isNotBlank())
        lerp(rememberAuthorProfileTint(convo.member.did, convo.member.avatarUrl), Color.White, 0.3f)
    else lerp(tint, Color.White, 0.35f)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .clip(cellShape)
            .background(if (isSelected) ringColor.copy(alpha = 0.14f) else Color.Transparent)
            .clickable(onClick = onTap)
            .padding(horizontal = 4.dp, vertical = 8.dp)
    ) {
        Box(Modifier.size(76.dp), contentAlignment = Alignment.Center) {
            if (convo.isGroup) {
                DmConvoAvatar(convo, 66.dp)
            } else Box(Modifier.size(66.dp).clip(AvatarShape).background(Color.White.copy(0.12f))) {
                if (convo.member.avatarUrl != null) {
                    AsyncImage(model = convo.member.avatarUrl, contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().clip(AvatarShape))
                }
            }
            if (pop > 0.01f) {
                // Group chats' avatars are a cluster, not a circle — no ring
                // around them, just the check.
                if (!convo.isGroup) {
                    Box(
                        Modifier.size(74.dp)
                            .graphicsLayer { alpha = pop.coerceIn(0f, 1f); scaleX = 0.9f + 0.1f * pop; scaleY = 0.9f + 0.1f * pop }
                            .border(2.5.dp, ringColor, CircleShape)
                    )
                }
                Box(
                    Modifier.align(Alignment.BottomEnd)
                        .graphicsLayer { scaleX = pop; scaleY = pop }
                        .size(22.dp).clip(CircleShape).background(ringColor)
                        .border(1.5.dp, Color(0xFF101014), CircleShape),
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Default.Check, contentDescription = null, tint = Color(0xFF101014), modifier = Modifier.size(14.dp)) }
            }
        }
        Spacer(Modifier.height(6.dp))
        Column(
            Modifier.fillMaxWidth().popupTextShadow(RoundedCornerShape(10.dp)).padding(horizontal = 5.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                convo.member.displayName, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                lineHeight = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            val sub = if (convo.isGroup) "${convo.memberCount} people" else if (convo.member.handle.isNotBlank()) "@${convo.member.handle}" else ""
            if (sub.isNotBlank()) {
                Text(
                    sub, color = Color.White.copy(alpha = 0.9f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}
