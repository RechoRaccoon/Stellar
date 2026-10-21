package com.mediaviewer.ui

import androidx.compose.animation.AnimatedVisibility
import com.mediaviewer.ui.theme.AvatarShape
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import com.mediaviewer.ui.theme.CircleShape
import com.mediaviewer.ui.theme.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import coil3.compose.AsyncImage
import com.mediaviewer.i18n.Text
import com.mediaviewer.ui.compat.ActivityResultContracts
import com.mediaviewer.ui.compat.PickVisualMediaRequest
import com.mediaviewer.ui.compat.horizontalScroll
import com.mediaviewer.ui.compat.navBarSpace
import com.mediaviewer.ui.compat.rememberLauncherForActivityResult
import com.mediaviewer.ui.compat.showPlatformToast
import com.mediaviewer.ui.theme.DimGray
import com.mediaviewer.util.EMOJI_DEFAULT_FOLDER_NAME
import com.mediaviewer.util.EmojiAccounts
import com.mediaviewer.util.EmojiCloud
import com.mediaviewer.util.EmojiEntry
import com.mediaviewer.util.EmojiFolder
import com.mediaviewer.util.EmojiStore
import com.mediaviewer.util.rememberHapticTap
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Height of the emoji panel while a tab is being renamed (just the tab row,
 *  riding on top of the keyboard). */
internal val EmojiPanelCompactHeight = 60.dp

/** Something held in the emoji menu: one of your emoji, one of your
 *  folders, or a saved account. */
private sealed interface EmojiHeld {
    data class Emoji(val entry: EmojiEntry) : EmojiHeld
    data class Folder(val folder: EmojiFolder) : EmojiHeld
    data class Account(val account: EmojiAccounts.Account) : EmojiHeld
}

/** What a confirmation popup is up for. */
private sealed interface EmojiAsk {
    data class DeleteEmoji(val entry: EmojiEntry) : EmojiAsk
    data class DeleteFolder(val folder: EmojiFolder) : EmojiAsk
    data object DeleteOrphans : EmojiAsk
    data class SaveOthers(val owner: String, val emoji: EmojiCloud.RemoteEmoji) : EmojiAsk
    /** Someone's folder held down ([folder] null: their "All"). */
    data class SaveFolder(val owner: String, val folder: String?, val emoji: List<EmojiCloud.RemoteEmoji>) : EmojiAsk
}

/**
 * The custom emoji menu (Textshot mode and DMs), Discord-style:
 *
 *  - accounts row ([showAccounts], DMs): your own picture first (selected
 *    to begin with: your emoji and folders), then the accounts you've
 *    saved, then the person you're messaging if they aren't saved yet. Pick
 *    one to see their emoji instead. At the far right, Import (yours) or
 *    Save (someone else's: keeps their picture in this row).
 *  - folders row: "All", one tab per folder, and (+) to make a folder.
 *    Tap the selected folder to rename it.
 *  - the grid: tap to put an emoji in, Discord-sized cells. Animated emoji
 *    move. Someone else's emoji: tap or hold to save it to your own.
 *
 * Press, hold and drag (your own things): an emoji to a new place, or onto
 * a folder tab to file it there ("All" takes it out of the folder); a
 * folder or a saved account to a new place; any of them onto Delete, which
 * appears at the bottom while something is held.
 *
 * [floating]: a card above the message field (landscape) instead of the
 * keyboard's place.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun EmojiPanel(
    store: EmojiStore,
    height: Dp,
    compact: Boolean,
    liquidGlass: Boolean,
    tint: Color,
    onPickEmoji: (EmojiEntry) -> Unit,
    onEditingChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    showAccounts: Boolean = false,
    selfDid: String = "",
    selfAvatarUrl: String? = null,
    /** The person being messaged (offered at the end of the accounts row). */
    partner: com.mediaviewer.model.AuthorInfo? = null,
    floating: Boolean = false,
    /** Floating, but as wide as the space it's given (the status editor). */
    fillWidth: Boolean = false
) {
    val context = com.mediaviewer.ui.compat.LocalContext.current
    val scope = rememberCoroutineScope()
    val tap = rememberHapticTap()
    val view = com.mediaviewer.ui.compat.rememberPlatformView()
    val keyboard = LocalSoftwareKeyboardController.current
    val density = LocalDensity.current

    val folders = store.state.index.folders
    // null = the "All" tab.
    var selectedFolderId by remember { mutableStateOf<Int?>(null) }
    if (selectedFolderId != null && folders.none { it.id == selectedFolderId }) selectedFolderId = null

    // Whose emoji are showing: null = your own.
    // (Starts over for each chat and each signed-in account, so another
    // conversation never shows the last person's emoji.)
    var viewing by remember(selfDid, partner?.did) { mutableStateOf<EmojiAccounts.Account?>(null) }
    var viewingFolder by remember(selfDid, partner?.did) { mutableStateOf<String?>(null) }
    val saved = if (showAccounts) EmojiAccounts.saved(selfDid) else emptyList()
    val partnerAccount = partner?.takeIf { showAccounts && it.did.isNotBlank() && it.did != selfDid }?.let {
        EmojiAccounts.Account(it.did, it.handle, it.displayName, it.avatarUrl)
    }
    // Saved accounts keep their pictures up to date from the chat partner.
    LaunchedEffect(partnerAccount) {
        val p = partnerAccount ?: return@LaunchedEffect
        val s = EmojiAccounts.saved(selfDid).firstOrNull { it.did == p.did }
        if (s != null && s != p) EmojiAccounts.save(selfDid, p)
    }
    val others = viewing
    // Their library: what was read before shows at once, then a fresh read
    // (they may have added some since). [remoteFailed]: it couldn't be read.
    var remoteFailed by remember(others?.did) { mutableStateOf(false) }
    var remoteRetry by remember(others?.did) { mutableIntStateOf(0) }
    val remote by produceState<List<EmojiCloud.RemoteEmoji>?>(null, others?.did, remoteRetry) {
        value = null
        remoteFailed = false
        val did = others?.did ?: return@produceState
        EmojiCloud.libraryOf(did)?.let { value = it }
        val fresh = EmojiCloud.libraryOf(did, fresh = true)
        if (fresh != null) value = fresh else if (value == null) remoteFailed = true
    }

    var editingId by remember { mutableStateOf<Int?>(null) }
    var editValue by remember { mutableStateOf(TextFieldValue("")) }
    var importing by remember { mutableStateOf(false) }
    var ask by remember { mutableStateOf<EmojiAsk?>(null) }
    var saving by remember { mutableStateOf(false) }

    fun startEditing(id: Int, current: String) {
        editingId = id
        editValue = TextFieldValue(current, TextRange(0, current.length))
        onEditingChange(true)
    }

    fun finishEditing() {
        val id = editingId ?: return
        editingId = null
        store.renameFolder(id, editValue.text)
        onEditingChange(false)
        keyboard?.hide()
    }

    fun runImport(block: suspend () -> Int) {
        importing = true
        scope.launch {
            val n = runCatching { block() }.getOrDefault(0)
            importing = false
            showPlatformToast(if (n > 0) "Imported $n emoji" else "No emoji imported")
        }
    }

    // Photos and videos, as many as you like (a video or animated GIF
    // becomes an animated emoji). They land in the selected tab.
    val mediaPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { uris ->
        if (uris.isNotEmpty()) runImport { store.importImages(uris, selectedFolderId) }
    }

    // ── Press, hold and drag ──
    var held by remember { mutableStateOf<EmojiHeld?>(null) }
    var pointer by remember { mutableStateOf(Offset.Zero) }
    var grab by remember { mutableStateOf(Offset.Zero) }
    var heldSize by remember { mutableStateOf(IntSize.Zero) }
    var panelOrigin by remember { mutableStateOf(Offset.Zero) }
    val emojiBounds = remember { mutableStateMapOf<Int, Rect>() }
    val folderBounds = remember { mutableStateMapOf<Int, Rect>() } // -1 = All
    val accountBounds = remember { mutableStateMapOf<String, Rect>() }
    var deleteBounds by remember { mutableStateOf<Rect?>(null) }
    fun near(r: Rect?, slop: Float = 12f) = r != null && Rect(r.left - slop, r.top - slop, r.right + slop, r.bottom + slop).contains(pointer)
    val overDelete = held != null && near(deleteBounds, 20f)

    fun startHold(item: EmojiHeld, bounds: Rect, at: Offset) {
        held = item
        grab = at - bounds.topLeft
        heldSize = IntSize(bounds.width.roundToInt(), bounds.height.roundToInt())
        pointer = at
        view.performHapticFeedback(com.mediaviewer.ui.compat.HapticFeedbackConstants.LONG_PRESS)
    }

    fun drop() {
        val item = held ?: return
        held = null
        val index = store.state.index
        if (near(deleteBounds, 20f)) {
            view.performHapticFeedback(com.mediaviewer.ui.compat.HapticFeedbackConstants.LONG_PRESS)
            when (item) {
                is EmojiHeld.Emoji -> ask = EmojiAsk.DeleteEmoji(item.entry)
                is EmojiHeld.Folder -> ask = EmojiAsk.DeleteFolder(item.folder)
                is EmojiHeld.Account -> {
                    EmojiAccounts.remove(selfDid, item.account.did)
                    if (viewing?.did == item.account.did) viewing = null
                }
            }
            return
        }
        when (item) {
            is EmojiHeld.Emoji -> {
                val id = item.entry.id
                // Onto a folder tab: filed there (out of the folder it was in).
                val tab = folderBounds.entries.firstOrNull { near(it.value) }?.key
                if (tab != null) {
                    val from = selectedFolderId
                    val to = if (tab == -1) null else tab
                    if (to == from) return
                    store.setIndex(index.copy(folders = index.folders.map { f ->
                        when (f.id) {
                            to -> if (id in f.emojiIds) f else f.copy(emojiIds = f.emojiIds + id)
                            from -> f.copy(emojiIds = f.emojiIds - id)
                            else -> f
                        }
                    }))
                    view.performHapticFeedback(com.mediaviewer.ui.compat.HapticFeedbackConstants.CONFIRM)
                    return
                }
                // (Among the other emoji it already moved while held.)
            }
            // (Folders and accounts were put in their new places while held.)
            is EmojiHeld.Folder, is EmojiHeld.Account -> {}
        }
    }

    /** [id] takes [target]'s place in what's showing (All, or the open folder). */
    fun moveEmoji(id: Int, target: Int): Boolean {
        val index = store.state.index
        val folderId = selectedFolderId
        if (folderId == null) {
            val list = index.emojis.toMutableList()
            val fromI = list.indexOfFirst { it.id == id }
            val toI = list.indexOfFirst { it.id == target }
            if (fromI < 0 || toI < 0) return false
            list.add(toI, list.removeAt(fromI))
            store.setIndex(index.copy(emojis = list))
        } else {
            var moved = false
            store.setIndex(index.copy(folders = index.folders.map { f ->
                if (f.id != folderId) f else {
                    val ids = f.emojiIds.toMutableList()
                    val fromI = ids.indexOf(id); val toI = ids.indexOf(target)
                    if (fromI < 0 || toI < 0) f else { moved = true; f.copy(emojiIds = ids.apply { add(toI, removeAt(fromI)) }) }
                }
            }))
            return moved
        }
        return true
    }

    // Like dragging feeds: what's held trades places with whatever it's
    // dragged over as it goes, the others making way (a tick each time).
    // The one it just traded with is left alone until the finger leaves
    // it, so the two don't swap straight back before they've moved.
    var swapLock by remember { mutableStateOf<Any?>(null) }
    fun liveReorder() {
        val item = held ?: return
        fun <K> step(bounds: Map<K, Rect>, self: K, skip: (K) -> Boolean = { false }, move: (K) -> Boolean) {
            swapLock?.let { l -> if (bounds.entries.none { it.key == l && it.value.contains(pointer) }) swapLock = null }
            val target = bounds.entries.firstOrNull { it.key != self && it.key != swapLock && !skip(it.key) && it.value.contains(pointer) }?.key ?: return
            if (move(target)) {
                swapLock = target
                view.performHapticFeedback(com.mediaviewer.ui.compat.HapticFeedbackConstants.CLOCK_TICK)
            }
        }
        when (item) {
            is EmojiHeld.Emoji -> step(emojiBounds, item.entry.id) { moveEmoji(item.entry.id, it) }
            is EmojiHeld.Folder -> step(folderBounds, item.folder.id, skip = { it == -1 }) { target ->
                val index = store.state.index
                val list = index.folders.toMutableList()
                val fromI = list.indexOfFirst { it.id == item.folder.id }
                val toI = list.indexOfFirst { it.id == target }
                if (fromI < 0 || toI < 0) false else { list.add(toI, list.removeAt(fromI)); store.setIndex(index.copy(folders = list)); true }
            }
            is EmojiHeld.Account -> step(accountBounds, item.account.did) { target ->
                val list = EmojiAccounts.saved(selfDid)
                val fromI = list.indexOfFirst { it.did == item.account.did }
                val toI = list.indexOfFirst { it.did == target }
                if (fromI >= 0 && toI >= 0) { EmojiAccounts.move(selfDid, fromI, toI); true } else false
            }
        }
    }

    /** Press, hold and drag for one of your own things. */
    fun Modifier.holdToDrag(key: Any, item: () -> EmojiHeld?, bounds: () -> Rect?): Modifier =
        if (compact) this else this.pointerInput(key) {
            detectDragGesturesAfterLongPress(
                onDragStart = { off ->
                    val b = bounds() ?: return@detectDragGesturesAfterLongPress
                    val it = item() ?: return@detectDragGesturesAfterLongPress
                    startHold(it, b, b.topLeft + off)
                },
                onDrag = { change, amount -> if (held != null) { change.consume(); pointer += amount; liveReorder() } },
                onDragEnd = { swapLock = null; drop() },
                onDragCancel = { swapLock = null; held = null }
            )
        }

    val panelBg = Color(red = tint.red * 0.16f, green = tint.green * 0.16f, blue = tint.blue * 0.16f, alpha = 1f)
    val tabShape = RoundedCornerShape(16.dp)
    val panelShape = RoundedCornerShape(18.dp)

    Box(
        modifier
            .then(if (floating && !fillWidth) Modifier.widthIn(max = 440.dp).fillMaxWidth() else Modifier.fillMaxWidth())
            .height(if (compact) EmojiPanelCompactHeight else height)
            .then(
                if (floating) Modifier.clip(panelShape).background(panelBg.copy(alpha = 0.96f), panelShape)
                    .border(1.dp, Color.White.copy(alpha = 0.12f), panelShape)
                else Modifier.background(panelBg)
            )
            .onGloballyPositioned { panelOrigin = it.positionInRoot() }
            // Swallow taps so they never fall through to what's behind.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .then(if (compact || floating) Modifier else Modifier.windowInsetsPadding(WindowInsets.navBarSpace))
    ) {
    Column(Modifier.fillMaxSize()) {
        if (!floating) Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.08f)))

        @Composable
        fun ImportOrSave() {
            val acct = others
            if (acct == null) {
                // Straight to your photos and videos: pick as many as you
                // like and they're all added (a video or GIF becomes an
                // animated emoji).
                PanelPill(
                    liquidGlass = liquidGlass, tint = tint, shape = tabShape,
                    onClick = {
                        if (!importing) mediaPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                    }
                ) { PillLabel(if (importing) "Importing…" else "Import", selected = !importing) }
            } else {
                // Someone else's: Save keeps them in the accounts row; Unsave
                // takes them out again.
                val isSaved = EmojiAccounts.isSaved(selfDid, acct.did)
                PanelPill(
                    liquidGlass = liquidGlass, tint = tint, shape = tabShape,
                    onClick = {
                        if (isSaved) { EmojiAccounts.remove(selfDid, acct.did); showPlatformToast("Unsaved") }
                        else { EmojiAccounts.save(selfDid, acct); showPlatformToast("Saved") }
                    }
                ) { PillLabel(if (isSaved) "Unsave" else "Save", selected = true) }
            }
        }

        // ── Accounts row: you · saved accounts · the person you're messaging   Import/Save ──
        if (showAccounts) {
            Row(
                Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AccountBubble(selfAvatarUrl, "You", selected = others == null, tint = tint) {
                        finishEditing(); viewing = null
                    }
                    saved.forEach { a ->
                        androidx.compose.runtime.key(a.did) {
                            val latest by rememberUpdatedState(a)
                            val isHeld = (held as? EmojiHeld.Account)?.account?.did == a.did
                            AccountBubble(
                                a.avatarUrl, a.displayName.ifBlank { a.handle }, selected = others?.did == a.did, tint = tint,
                                modifier = Modifier
                                    .graphicsLayer { alpha = if (isHeld) 0.3f else 1f }
                                    .onGloballyPositioned { accountBounds[a.did] = it.boundsInRoot() },
                                drag = Modifier.holdToDrag("acct:" + a.did, { EmojiHeld.Account(latest) }, { accountBounds[a.did] })
                            ) { finishEditing(); viewing = latest; viewingFolder = null }
                        }
                    }
                    // The person you're messaging, last, until they're saved.
                    if (partnerAccount != null && saved.none { it.did == partnerAccount.did }) {
                        AccountBubble(
                            partnerAccount.avatarUrl, partnerAccount.displayName.ifBlank { partnerAccount.handle },
                            selected = others?.did == partnerAccount.did, tint = tint, dashed = true
                        ) { finishEditing(); viewing = partnerAccount; viewingFolder = null }
                    }
                }
                Spacer(Modifier.width(8.dp))
                ImportOrSave()
            }
        }

        // ── Folders row: All · folders · (+)   (Import, without the accounts row) ──
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (others != null) {
                    // Their folders (from their emoji's folder names).
                    val names = remote.orEmpty().mapNotNull { it.folder?.takeIf { f -> f.isNotBlank() } }.distinct()
                    // (Press and hold one: save it — and its emoji — to your own.
                    // "All": every one of theirs, into your All.)
                    val theirs = remote.orEmpty()
                    PanelPill(
                        liquidGlass = liquidGlass, tint = tint, shape = tabShape, onClick = { viewingFolder = null },
                        onLongClick = { if (theirs.isNotEmpty()) { tap(); ask = EmojiAsk.SaveFolder(others.did, null, theirs) } }
                    ) {
                        PillLabel("All", selected = viewingFolder == null)
                    }
                    names.forEach { n ->
                        PanelPill(
                            liquidGlass = liquidGlass, tint = tint, shape = tabShape, onClick = { viewingFolder = n },
                            onLongClick = { tap(); ask = EmojiAsk.SaveFolder(others.did, n, theirs.filter { it.folder == n }) }
                        ) {
                            PillLabel(n, selected = viewingFolder == n)
                        }
                    }
                } else {
                    val dropOnAll = held is EmojiHeld.Emoji && near(folderBounds[-1])
                    PanelPill(
                        liquidGlass = liquidGlass, tint = tint, shape = tabShape,
                        highlighted = dropOnAll,
                        modifier = Modifier.onGloballyPositioned { folderBounds[-1] = it.boundsInRoot() },
                        onClick = {
                            finishEditing()
                            selectedFolderId = null
                        },
                        onLongClick = {
                            if (store.orphanEmojiCount() > 0) ask = EmojiAsk.DeleteOrphans
                            else showPlatformToast("No untagged emoji to delete")
                        }
                    ) { PillLabel("All", selected = selectedFolderId == null) }

                    folders.forEach { folder ->
                        androidx.compose.runtime.key(folder.id) {
                            val isSelected = folder.id == selectedFolderId
                            val latest by rememberUpdatedState(folder)
                            val isHeld = (held as? EmojiHeld.Folder)?.folder?.id == folder.id
                            val dropHere = held != null && held !is EmojiHeld.Account && !isHeld && near(folderBounds[folder.id])
                            val m = Modifier
                                .graphicsLayer { alpha = if (isHeld) 0.3f else 1f }
                                .onGloballyPositioned { folderBounds[folder.id] = it.boundsInRoot() }
                            if (editingId == folder.id) {
                                PanelPill(liquidGlass = liquidGlass, tint = tint, shape = tabShape, modifier = m, onClick = null) {
                                    EditableTabName(
                                        value = editValue,
                                        onValueChange = { editValue = it },
                                        onDone = { finishEditing() }
                                    )
                                }
                            } else {
                                PanelPill(
                                    liquidGlass = liquidGlass, tint = tint, shape = tabShape, highlighted = dropHere,
                                    modifier = m,
                                    drag = Modifier.holdToDrag("folder:" + folder.id, { EmojiHeld.Folder(latest) }, { folderBounds[folder.id] }),
                                    onClick = {
                                        if (isSelected) {
                                            // Tapping the already-selected folder renames it.
                                            startEditing(folder.id, folder.name)
                                        } else {
                                            finishEditing()
                                            selectedFolderId = folder.id
                                        }
                                    }
                                ) { PillLabel(folder.name, selected = isSelected) }
                            }
                        }
                    }

                    // (+) — always the last thing in the row, after every folder.
                    PanelPill(
                        liquidGlass = liquidGlass, tint = tint, shape = CircleShape,
                        modifier = Modifier.width(32.dp),
                        onClick = {
                            finishEditing()
                            val id = store.createFolder(EMOJI_DEFAULT_FOLDER_NAME)
                            selectedFolderId = id
                            startEditing(id, EMOJI_DEFAULT_FOLDER_NAME)
                        }
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "New emoji folder", tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.size(16.dp))
                    }
                }
            }
            if (!showAccounts) {
                Spacer(Modifier.width(8.dp))
                ImportOrSave()
            }
        }

        // ── Emoji grid (hidden while renaming a tab, when only the row shows) ─
        if (!compact) {
            val cells = GridCells.Adaptive(minSize = 40.dp)
            val gridPadding = PaddingValues(start = 6.dp, end = 6.dp, top = 2.dp, bottom = 56.dp)
            if (others != null) {
                val list = remote
                when {
                    list == null && remoteFailed -> Box(
                        Modifier.weight(1f).fillMaxWidth().padding(24.dp).clickable { remoteRetry++ },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Couldn't load their emoji. Tap to try again.", color = DimGray, fontSize = 13.sp, textAlign = TextAlign.Center)
                    }
                    list == null -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 1.5.dp)
                    }
                    list.isEmpty() -> Box(Modifier.weight(1f).fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        Text("No custom emoji yet.", color = DimGray, fontSize = 13.sp, textAlign = TextAlign.Center)
                    }
                    else -> {
                        val shown = list.filter { viewingFolder == null || it.folder == viewingFolder }
                        LazyVerticalGrid(columns = cells, modifier = Modifier.weight(1f).fillMaxWidth(), contentPadding = gridPadding) {
                            items(shown, key = { it.rkey }) { e ->
                                Box(
                                    Modifier.aspectRatio(1f).padding(2.dp).clip(RoundedCornerShape(6.dp))
                                        .combinedClickable(
                                            onClick = { tap(); ask = EmojiAsk.SaveOthers(others.did, e) },
                                            onLongClick = { tap(); ask = EmojiAsk.SaveOthers(others.did, e) }
                                        ),
                                    contentAlignment = Alignment.Center
                                ) { RemoteEmojiPicture(others.did, e.cid, e.animated, e.name, Modifier.fillMaxSize().padding(1.dp)) }
                            }
                        }
                    }
                }
            } else {
                val emoji = store.emojiFor(selectedFolderId)
                if (emoji.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        Text(
                            if (selectedFolderId == null) "No emoji yet. Tap Import to add some."
                            else "This folder is empty. Tap Import to add emoji to it.",
                            color = DimGray, fontSize = 13.sp, textAlign = TextAlign.Center
                        )
                    }
                } else {
                    LazyVerticalGrid(columns = cells, modifier = Modifier.weight(1f).fillMaxWidth(), contentPadding = gridPadding) {
                        items(emoji, key = { it.id }) { e ->
                            val latest by rememberUpdatedState(e)
                            val isHeld = (held as? EmojiHeld.Emoji)?.entry?.id == e.id
                            val target = held is EmojiHeld.Emoji && !isHeld && emojiBounds[e.id]?.contains(pointer) == true
                            Box(
                                Modifier.aspectRatio(1f).padding(2.dp)
                                    .onGloballyPositioned { emojiBounds[e.id] = it.boundsInRoot() }
                                    .graphicsLayer { alpha = if (isHeld) 0.25f else 1f }
                                    .clip(RoundedCornerShape(6.dp))
                                    .then(if (target) Modifier.border(1.5.dp, Color.White.copy(alpha = 0.8f), RoundedCornerShape(6.dp)) else Modifier)
                                    .combinedClickable(onClick = { tap(); onPickEmoji(latest) }, onLongClick = {})
                                    .holdToDrag("emoji:" + e.id, { EmojiHeld.Emoji(latest) }, { emojiBounds[e.id] }),
                                contentAlignment = Alignment.Center
                            ) { EmojiPicture(store, e, Modifier.fillMaxSize().padding(1.dp)) }
                        }
                    }
                }
            }
        }
    }

        // Delete: at the bottom, centred, while something is held.
        AnimatedVisibility(
            visible = held != null,
            enter = fadeIn(tween(160)) + scaleIn(initialScale = 0.6f, animationSpec = androidx.compose.animation.core.spring(dampingRatio = 0.55f, stiffness = 500f)),
            exit = fadeOut(tween(140)) + scaleOut(targetScale = 0.7f, animationSpec = tween(140)),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp)
        ) {
            HubDropBubble(
                Icons.Default.Delete, "Delete", hovered = overDelete, liquidGlass = liquidGlass, tint = tint,
                modifier = Modifier.onGloballyPositioned { deleteBounds = it.boundsInRoot() }
            )
        }

        // The held thing, following the finger.
        held?.let { h ->
            Box(
                Modifier
                    .offset { IntOffset((pointer.x - grab.x - panelOrigin.x).roundToInt(), (pointer.y - grab.y - panelOrigin.y).roundToInt()) }
                    .size(with(density) { heldSize.width.toDp() }, with(density) { heldSize.height.toDp() })
                    .graphicsLayer { scaleX = 1.12f; scaleY = 1.12f; shadowElevation = 12.dp.toPx(); alpha = if (overDelete) 0.6f else 1f },
                contentAlignment = Alignment.Center
            ) {
                when (h) {
                    is EmojiHeld.Emoji -> EmojiPicture(store, h.entry, Modifier.fillMaxSize().padding(1.dp))
                    is EmojiHeld.Folder -> PanelPill(liquidGlass = liquidGlass, tint = tint, shape = tabShape, onClick = null) { PillLabel(h.folder.name, selected = true) }
                    is EmojiHeld.Account -> AccountBubble(h.account.avatarUrl, h.account.displayName, selected = true, tint = tint) {}
                }
            }
        }
    }

    // ── Confirmations ──
    // (Shown over the whole app by LocalOverlayHost, like every other
    // popup: the app blurs behind, and the popup's glass blurs it too.)
    LaunchedEffect(ask) {
        val a = ask ?: return@LaunchedEffect
        ask = null
        LocalOverlays.confirm = when (a) {
            is EmojiAsk.DeleteEmoji -> ConfirmRequest("Delete Emoji?", "Deletes \":${a.entry.name}:\" everywhere.", "Delete", tint) {
                store.deleteEmoji(a.entry.id)
            }
            is EmojiAsk.DeleteFolder -> {
                val n = store.emojiCountIn(a.folder.id)
                ConfirmRequest(
                    "Delete Folder?",
                    if (n == 0) "Deletes \"${a.folder.name}\". It's empty." else "Deletes \"${a.folder.name}\" and its $n emoji.",
                    "Delete", tint
                ) {
                    store.deleteFolder(a.folder.id)
                    if (selectedFolderId == a.folder.id) selectedFolderId = null
                }
            }
            EmojiAsk.DeleteOrphans -> ConfirmRequest("Delete Emoji?", "Deletes ${store.orphanEmojiCount()} emoji not in any folder.", "Delete", tint) {
                store.deleteOrphanEmoji()
            }
            is EmojiAsk.SaveOthers -> ConfirmRequest("Save Emoji?", "Adds \":${a.emoji.name}:\" to your own emoji.", "Save", tint, destructive = false) {
                val added = EmojiCloud.saveOthers(store, selfDid, a.owner, a.emoji.cid, a.emoji.name)
                showPlatformToast(if (added != null) "Saved to your emoji" else "Couldn't save that emoji")
            }
            is EmojiAsk.SaveFolder -> ConfirmRequest(
                if (a.folder == null) "Save All Emoji?" else "Save Folder?",
                if (a.folder == null) "Adds all ${a.emoji.size} of their emoji to your own emoji."
                    else "Adds \"${a.folder}\" and its ${a.emoji.size} emoji to your own emoji.",
                "Save", tint, destructive = false
            ) {
                val n = EmojiCloud.saveOthersFolder(store, selfDid, a.owner, a.emoji, a.folder)
                showPlatformToast(if (n > 0) "Saved $n emoji" else "Nothing new to save")
            }
        }
    }
}

/** One of your own emoji's picture (moving, if it's animated). */
@Composable
internal fun EmojiPicture(store: EmojiStore, entry: EmojiEntry, modifier: Modifier = Modifier) {
    if (entry.animated) {
        AnimatedGifBytes(
            key = "emoji:${entry.id}:${entry.file}", load = { store.pngBytes(entry) },
            contentDescription = entry.name, modifier = modifier, contentScale = ContentScale.Fit
        )
    } else {
        val bmp = remember(entry.id, entry.file) { store.imageBitmapForChar(store.charFor(entry)) }
        if (bmp != null) Image(bitmap = bmp, contentDescription = entry.name, contentScale = ContentScale.Fit, modifier = modifier)
    }
}

/** Someone's emoji picture, from their PDS (moving, if it's animated). */
@Composable
internal fun RemoteEmojiPicture(did: String, cid: String, animated: Boolean, name: String, modifier: Modifier = Modifier) {
    // (Keyed, so a different emoji in this spot — a new status, say — never
    // shows the last one's picture while its own is looked up.)
    var url by remember(did, cid) { mutableStateOf<String?>(null) }
    LaunchedEffect(did, cid) {
        url = runCatching { com.mediaviewer.worker.BlueskyBlobResolver.resolveBlobUrl(did, cid) }.getOrNull()
    }
    val u = url ?: return
    if (animated) {
        AnimatedGifBytes(
            key = "blob:$cid",
            load = { com.mediaviewer.network.PlainHttp.get(u).takeIf { it.code in 200..299 }?.body },
            contentDescription = name, modifier = modifier, contentScale = ContentScale.Fit
        )
    } else {
        AsyncImage(model = u, contentDescription = name, contentScale = ContentScale.Fit, modifier = modifier)
    }
}

/** A round account picture in the accounts row. [dashed]: not saved yet. */
@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun AccountBubble(
    avatarUrl: String?, name: String, selected: Boolean, tint: Color,
    modifier: Modifier = Modifier, dashed: Boolean = false, drag: Modifier? = null, onClick: () -> Unit
) {
    val tap = rememberHapticTap()
    val ring = if (selected) Color.White else Color.White.copy(alpha = if (dashed) 0.22f else 0.35f)
    Box(
        modifier.size(34.dp).clip(AvatarShape)
            .background(tint.copy(alpha = 0.35f))
            .border(if (selected) 2.dp else 1.dp, ring, AvatarShape)
            .combinedClickable(onClick = { tap(); onClick() }, onLongClick = if (drag != null) ({}) else null)
            .then(drag ?: Modifier),
        contentAlignment = Alignment.Center
    ) {
        if (avatarUrl != null) AsyncImage(
            model = avatarUrl, contentDescription = name, contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().padding(if (selected) 2.dp else 1.dp).clip(AvatarShape)
                .graphicsLayer { alpha = if (selected || !dashed) 1f else 0.7f }
        ) else Text(name.take(1).uppercase(), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

/** A glass pill (or circle) in the panel's rows. [onClick] null = not tappable. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PanelPill(
    liquidGlass: Boolean, tint: Color, shape: Shape,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    onClick: (() -> Unit)?,
    onLongClick: (() -> Unit)? = null,
    /** Press-hold-drag, inside the tap handling (so a hold that's let go
     *  isn't also a tap). */
    drag: Modifier? = null,
    content: @Composable BoxScope.() -> Unit
) {
    val tap = rememberHapticTap()
    val clickMod = modifier.height(32.dp).clip(shape)
        .then(if (highlighted) Modifier.border(1.5.dp, Color.White.copy(alpha = 0.85f), shape) else Modifier)
        .then(
            if (onClick != null) Modifier.combinedClickable(
                onClick = { tap(); onClick() },
                onLongClick = onLongClick?.let { { tap(); it() } } ?: if (drag != null) ({}) else null
            ) else Modifier
        )
        .then(drag ?: Modifier)
    if (liquidGlass) {
        LiquidGlassSurface(clickMod, shape = shape, tint = tint, contentAlignment = Alignment.Center, content = content)
    } else {
        Box(clickMod.background(Color.White.copy(alpha = 0.10f)), contentAlignment = Alignment.Center, content = content)
    }
}

/** Same dim-unless-selected treatment as the composer's Blog/Textshot toggles. */
@Composable
private fun PillLabel(text: String, selected: Boolean) {
    Text(
        text, color = Color.White.copy(alpha = if (selected) 1f else 0.45f),
        fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
        modifier = Modifier.padding(horizontal = 14.dp)
    )
}

/** The tab's name as a live text field: focused right away, everything
 *  selected so typing replaces it; Done (or tapping elsewhere) commits. */
@Composable
private fun EditableTabName(value: TextFieldValue, onValueChange: (TextFieldValue) -> Unit, onDone: () -> Unit) {
    val focus = remember { FocusRequester() }
    var hadFocus by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    BasicTextField(
        value = value,
        onValueChange = { if (it.text.length <= 24) onValueChange(it) },
        singleLine = true,
        textStyle = TextStyle(color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
        cursorBrush = SolidColor(Color.White),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, capitalization = KeyboardCapitalization.Words),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = Modifier
            .padding(horizontal = 14.dp)
            .widthIn(min = 56.dp)
            .width(IntrinsicSize.Max)
            .focusRequester(focus)
            .onFocusChanged {
                if (it.isFocused) hadFocus = true else if (hadFocus) onDone()
            }
    )
}

