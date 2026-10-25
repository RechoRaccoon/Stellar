package com.mediaviewer.ui

import com.mediaviewer.ui.compat.navBarSpace
import com.mediaviewer.ui.compat.LocalContext

import com.mediaviewer.ui.compat.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import com.mediaviewer.ui.theme.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.mediaviewer.model.MediaItem
import com.mediaviewer.ui.theme.*
import com.mediaviewer.util.rememberHapticTap

private const val BSKY_POST_LIMIT = 300

/**
 * Quote Repost — same treatment as Share To: rendered in-place so its glass
 * live-blurs the post (whose own UI fades away meanwhile), fades/scales in
 * and out via [FadingPopupHost], sits at the bottom of the screen and rides
 * up on top of the keyboard while typing.
 */
@Composable
fun QuoteRepostDialog(
    target: MediaItem?,
    submitting: Boolean,
    liquidGlass: Boolean,
    dominantColor: Color = NeutralGlassTint,
    backdrop: GlassBackdrop? = null,
    /** The signed-in user's avatar, for the emoji menu's accounts row. */
    selfAvatarUrl: String? = null,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit
) {
    if (target == null) return
    var text by remember(target.id) { mutableStateOf("") }
    val tint = dominantColor
    val overLimit = text.length > BSKY_POST_LIMIT
    val focus = remember { FocusRequester() }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    // Opens straight into typing: focus the field and explicitly raise the
    // keyboard (requestFocus alone doesn't always show it on every IME).
    LaunchedEffect(target.id) {
        kotlinx.coroutines.delay(120)
        runCatching { focus.requestFocus() }
        keyboard?.show()
    }
    // The popup lives on top of the keyboard: once the keyboard has been up,
    // closing it (Back, the keyboard's own hide key, a swipe) closes the
    // popup too.
    val density = androidx.compose.ui.platform.LocalDensity.current
    val imeBottom = WindowInsets.ime.getBottom(density)
    var keyboardWasShown by remember(target.id) { mutableStateOf(false) }
    // Custom emoji, as in the composer and comments: the same star button
    // (left of the thumbnail, mirroring Send) and the menu below the
    // sheet, where the keyboard was.
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
    var quoteKeyboardPx by remember(target.id) { mutableStateOf(0) }
    LaunchedEffect(imeBottom) { if (imeBottom > quoteKeyboardPx) quoteKeyboardPx = imeBottom }
    val emojiPanelHeight = with(density) { if (quoteKeyboardPx > 0) quoteKeyboardPx.toDp() else 300.dp }.coerceIn(240.dp, 480.dp)
    val quoteImeDp = with(density) { imeBottom.toDp() }
    LaunchedEffect(emojiPanelOpen, selfDidForEmoji) {
        if (selfDidForEmoji.isNotBlank()) {
            if (emojiPanelOpen) com.mediaviewer.util.EmojiCloud.requestSync(emojiStore, selfDidForEmoji)
            else com.mediaviewer.util.EmojiCloud.requestSync(emojiStore, selfDidForEmoji, force = true)
        }
    }
    LaunchedEffect(imeBottom > 0) {
        if (imeBottom > 0) keyboardWasShown = true
        // (The panel hides the keyboard while it is up; that must not
        // dismiss the popup — the panel's own Back closes the panel.)
        else if (keyboardWasShown && !submitting && !emojiPanelOpen) onDismiss()
    }
    // Safety net: if the keyboard never comes up at all (hardware keyboard,
    // unusual IME), Back still closes the popup.
    BackHandler(onBack = onDismiss)

    Box(
        modifier = Modifier.fillMaxSize()
            .background(Color.Black.copy(alpha = if (liquidGlass) 0.22f else 0.6f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
            .padding(top = rememberTopCutoutClearance())
            // With the menu up, no bottom insets: the menu takes the
            // keyboard's place at the screen's bottom edge, as in DMs.
            // Otherwise the sheet sits right on top of the keyboard.
            .then(if (emojiPanelOpen) Modifier else Modifier.windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navBarSpace))),
        contentAlignment = Alignment.BottomCenter
    ) {
        // The sheet, then the menu under it: the menu is a sibling of
        // the sheet, not content inside it, so it replaces the keyboard
        // like the DM menu instead of stretching the popup.
        Column(Modifier.fillMaxWidth()) {
        PopupSheetSurface(
            liquidGlass = liquidGlass, tint = tint, backdrop = backdrop,
            // (Landscape: a popup of a sensible width, centered.)
            modifier = Modifier.landscapeReadable(560.dp).fillMaxWidth()
                .padding(start = 8.dp, end = 8.dp, bottom = if (emojiPanelOpen) 0.dp else 8.dp)
        ) {
            Column(Modifier.fillMaxWidth()) {
                PopupSheetHeader(
                    title = "Quote Repost",
                    liquidGlass = liquidGlass, tint = tint, backdrop = backdrop,
                    onClose = onDismiss,
                    // (In the header's free right end, so it takes no room
                    // of its own.)
                    counter = "${text.length}/$BSKY_POST_LIMIT",
                    counterOver = overLimit
                )
                PopupMessageBox(
                    thumbUrl = target.thumbUrl.ifBlank { target.mediaUrl },
                    value = text, onValueChange = { text = it },
                    placeholder = "Add a comment (optional)…",
                    tint = tint,
                    canSend = !overLimit && !submitting,
                    sending = submitting,
                    onSend = { if (!overLimit && !submitting) onSubmit(text.trim()) },
                    maxLines = 8,
                    focusRequester = focus,
                    // Tagging autocomplete, as in the composer: @handles
                    // complete with a ghost suggestion (swipe/Tab to accept).
                    // The completed handle becomes a real mention on submit —
                    // quote reposts already facet through richText.
                    mentionAutocomplete = true,
                    emojiStore = emojiStore,
                    selfDid = selfDidForEmoji,
                    selfAvatarUrl = selfAvatarUrl,
                    emojiPanelOpen = emojiPanelOpen,
                    onEmojiPanelChange = { emojiPanelOpen = it },
                    emojiTabEditing = emojiTabEditing,
                    onEmojiTabEditingChange = { emojiTabEditing = it },
                    onInsertEmojiAvailable = { insertEmojiFn = it },
                    liquidGlass = liquidGlass,
                    modifier = Modifier.padding(start = 10.dp, end = 10.dp, bottom = 10.dp, top = 4.dp)
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
                        .padding(bottom = if (emojiTabEditing) quoteImeDp else 0.dp)
                )
            }
        }
        }
    }
}
