package com.mediaviewer.ui

import com.mediaviewer.ui.compat.navBarSpace
import com.mediaviewer.ui.theme.AvatarShape

import com.mediaviewer.platform.PlatformUri
import com.mediaviewer.ui.compat.rememberLauncherForActivityResult
import com.mediaviewer.ui.compat.ActivityResultContracts
import com.mediaviewer.ui.compat.LocalContext
import com.mediaviewer.ui.compat.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import com.mediaviewer.ui.theme.CircleShape
import com.mediaviewer.ui.theme.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.*
import com.mediaviewer.i18n.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.key
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import kotlinx.coroutines.launch
import coil3.compose.AsyncImage
import com.mediaviewer.model.AppMode
import com.mediaviewer.model.CommentItem
import com.mediaviewer.model.MediaItem
import com.mediaviewer.ui.theme.*
import com.mediaviewer.util.rememberHapticTap

/**
 * Comments, as a layer over the post instead of a separate screen: the post
 * stays where it is (blurred, its buttons faded — see FeedView's
 * `commentsFraction`), and this sheet slides up over it, starting right
 * under the camera cutout. The list scrolls; the input box sits at the
 * bottom exactly like the DM box and rides up on top of the keyboard.
 *
 * Closing follows the finger: pull down from the top of the list (or grab
 * the header anywhere) and the sheet tracks the drag, the blur behind it
 * easing off as it goes ([onDragFractionChanged]); let go past ~20% of the
 * screen (or flick down) and it closes, otherwise it springs back.
 */
/** A reply opened from elsewhere (the Inbox): the comment to pick out
 *  when [postUri]'s comments are showing. Set by MainViewModel. */
object CommentFocus {
    var uri by mutableStateOf<String?>(null)
    var postUri by mutableStateOf<String?>(null)
}

/** [target]'s comment and the ones it sits under, outermost first. */
private fun pathTo(list: List<CommentItem>, target: String): List<CommentItem>? {
    for (c in list) {
        if (c.uri == target) return listOf(c)
        pathTo(c.replies, target)?.let { return listOf(c) + it }
    }
    return null
}

@Composable
fun CommentsSheet(
    currentItem: MediaItem?,
    comments: List<CommentItem>,
    commentsLoading: Boolean,
    appMode: AppMode,
    liquidGlass: Boolean,
    onPostComment: (String, CommentItem?, List<PlatformUri>, List<String>) -> Unit,
    onLikeComment: (CommentItem) -> Unit,
    onVoteComment: (CommentItem, Int) -> Unit,
    onSwipeDown: () -> Unit,
    onTagClick: (String) -> Unit,
    onTagAdd: (String) -> Unit,
    onTagExclude: (String) -> Unit,
    // Item 1 (Phase 3): the current post's color, so Comments' glass rims
    // reflect it the same way the in-post glass buttons do.
    dominantColor: Color = NeutralGlassTint,
    backdrop: GlassBackdrop? = null,
    reducedAnimations: Boolean = false,
    /** 0 = fully open … 1 = dragged all the way down (for the blur behind). */
    onDragFractionChanged: (Float) -> Unit = {},
    /** A commenter's picture or name tapped: their profile. */
    onOpenProfile: (com.mediaviewer.model.AuthorInfo) -> Unit = {},
    /** Landscape's side list: no pulling it down to close it (it isn't a
     *  sheet there), and a comment's media opens through [onOpenMedia]. */
    sideList: Boolean = false,
    /** Given: a comment's media opens there (the whole screen) instead of
     *  over the comments. */
    onOpenMedia: ((List<com.mediaviewer.model.CommentMedia>, Int) -> Unit)? = null,
    /** The signed-in user's picture, for the emoji menu's accounts row. */
    selfAvatarUrl: String? = null
) {
    // Comment media waits a moment after switching to another post (see
    // LocalCommentMediaReady), so a swipe stays smooth.
    var mediaReady by remember { mutableStateOf(true) }
    LaunchedEffect(currentItem?.id) {
        mediaReady = false
        kotlinx.coroutines.delay(450)
        mediaReady = true
    }
    // A comment's media opened full screen (its list and which one).
    var viewerMedia by remember(currentItem?.id) { mutableStateOf<Pair<List<com.mediaviewer.model.CommentMedia>, Int>?>(null) }
    val openProfileOf: (CommentItem) -> Unit = { c ->
        if (c.authorDid.isNotBlank()) onOpenProfile(
            com.mediaviewer.model.AuthorInfo(did = c.authorDid, handle = c.authorHandle, displayName = c.authorDisplayName, avatarUrl = c.authorAvatarUrl)
        )
    }
    val tap = rememberHapticTap()
    var threadStack by remember(currentItem?.id) { mutableStateOf(listOf<CommentItem>()) }
    var commentText by remember { mutableStateOf("") }
    // The comment field's own value (caret included): the emoji menu
    // inserts at the caret, so it lives here, not inside FieldContent.
    var field by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue("")) }
    var showTags by remember(currentItem?.id) { mutableStateOf(false) }
    // Item 20: which comment (if any) is actually being replied to.
    var replyTarget by remember(currentItem?.id) { mutableStateOf<CommentItem?>(null) }
    // Your own comment held down: the delete confirmation.
    var commentToDelete by remember(currentItem?.id) { mutableStateOf<CommentItem?>(null) }
    fun isOwn(c: CommentItem) = appMode == AppMode.BLUESKY && c.uri.isNotBlank() &&
        LocalOverlays.selfDid.isNotBlank() && c.authorDid == LocalOverlays.selfDid
    // Each reply level's scroll position (the top level keyed ""), so coming
    // back out of a comment's replies lands where the list was left.
    val listStates = remember(currentItem?.id) { mutableMapOf<String, LazyListState>() }
    // A reply opened from the Inbox: its level of the thread opens, scrolled
    // to it, and it's picked out.
    val focusUri = CommentFocus.uri?.takeIf { currentItem != null && CommentFocus.postUri == currentItem.postUri }
    LaunchedEffect(focusUri, comments) {
        val target = focusUri ?: return@LaunchedEffect
        val path = pathTo(comments, target) ?: return@LaunchedEffect
        val ancestors = path.dropLast(1)
        threadStack = ancestors
        val siblings = ancestors.lastOrNull()?.replies ?: comments
        val idx = siblings.indexOfFirst { it.uri == target }
        val state = listStates.getOrPut(ancestors.lastOrNull()?.id ?: "") { LazyListState() }
        kotlinx.coroutines.delay(80)
        if (idx >= 0) runCatching { state.animateScrollToItem(idx + if (ancestors.isEmpty()) 0 else 1) }
    }

    // Media attached to the comment being written: up to 4 pictures, or one
    // video / GIF (the same rule as Bluesky posts).
    val platformContext = LocalContext.current
    var attached by remember(currentItem?.id) { mutableStateOf(listOf<PlatformUri>()) }
    // Content warnings on the attached media (the same menu as posting).
    var adultLabel by remember(currentItem?.id) { mutableStateOf<AdultContentLabel?>(null) }
    var graphicMedia by remember(currentItem?.id) { mutableStateOf(false) }
    var labelsOpen by remember(currentItem?.id) { mutableStateOf(false) }
    // No media left: no labels either.
    LaunchedEffect(attached.isEmpty()) { if (attached.isEmpty()) { adultLabel = null; graphicMedia = false } }
    fun isMotion(uri: PlatformUri): Boolean {
        val type = com.mediaviewer.platform.MediaBridge.mimeTypeOf(platformContext, uri)?.lowercase().orEmpty()
        return type.startsWith("video/") || type == "image/gif"
    }
    val mediaPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(4)) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val motion = uris.firstOrNull { isMotion(it) }
        attached = when {
            motion != null -> listOf(motion)
            attached.any { isMotion(it) } -> uris.take(4)
            else -> (attached + uris.filterNot { it in attached }).take(4)
        }
    }

    // Custom emoji, as in DMs: the same star button and menu, right
    // after the media button, with the panel under the input row.
    val emojiStore = remember { com.mediaviewer.util.EmojiStore.get(platformContext) }
    LaunchedEffect(Unit) { emojiStore.load() }
    var emojiPanelOpen by remember { mutableStateOf(false) }
    var emojiTabEditing by remember { mutableStateOf(false) }
    val selfDidForEmoji = LocalOverlays.selfDid
    // The menu takes the keyboard's place, so it is the keyboard's height
    // (remembered from the tallest the keyboard has been), exactly as in
    // DMs and the composer — not a fixed height.
    val commentDensity = androidx.compose.ui.platform.LocalDensity.current
    val commentImeBottomPx = WindowInsets.ime.getBottom(commentDensity)
    var commentKeyboardPx by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    LaunchedEffect(commentImeBottomPx) {
        if (commentImeBottomPx > commentKeyboardPx) commentKeyboardPx = commentImeBottomPx
    }
    val emojiPanelHeight = with(commentDensity) { if (commentKeyboardPx > 0) commentKeyboardPx.toDp() else 300.dp }.coerceIn(240.dp, 480.dp)
    val commentImeDp = with(commentDensity) { commentImeBottomPx.toDp() }
    LaunchedEffect(emojiPanelOpen, selfDidForEmoji) {
        if (selfDidForEmoji.isNotBlank()) {
            if (emojiPanelOpen) com.mediaviewer.util.EmojiCloud.requestSync(emojiStore, selfDidForEmoji)
            else com.mediaviewer.util.EmojiCloud.requestSync(emojiStore, selfDidForEmoji, force = true)
        }
    }
    // Tapping an emoji in the menu drops it into the comment at the
    // caret (replacing any selection), like typing one.
    fun insertEmoji(entry: com.mediaviewer.util.EmojiEntry) {
        val token = emojiStore.charFor(entry).toString()
        val t = field
        val start = t.selection.min.coerceIn(0, t.text.length)
        val end = t.selection.max.coerceIn(start, t.text.length)
        val next = t.text.substring(0, start) + token + t.text.substring(end)
        field = androidx.compose.ui.text.input.TextFieldValue(next, androidx.compose.ui.text.TextRange(start + token.length))
        commentText = next
    }
    // The emoji button: keyboard -> menu -> back to the keyboard.
    val commentFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    fun toggleEmojiPanel() {
        if (emojiPanelOpen) {
            emojiPanelOpen = false
            emojiTabEditing = false
            commentFocus.requestFocus()
            keyboardController?.show()
        } else {
            emojiPanelOpen = true
            focusManager.clearFocus()
            keyboardController?.hide()
        }
    }

    // ── Drag-to-close ──
    val scope = rememberCoroutineScope()
    var sheetHeightPx by remember { mutableIntStateOf(1) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var closing by remember { mutableStateOf(false) }
    val onClose by rememberUpdatedState(onSwipeDown)
    LaunchedEffect(dragOffset, sheetHeightPx) {
        onDragFractionChanged((dragOffset / sheetHeightPx).coerceIn(0f, 1f))
    }
    DisposableEffect(Unit) { onDispose { onDragFractionChanged(0f) } }
    fun settle(velocityY: Float) {
        if (closing) return
        if (dragOffset > sheetHeightPx * 0.2f || (velocityY > 1600f && dragOffset > 0f)) {
            closing = true
            onClose()
        } else if (dragOffset > 0f) {
            scope.launch {
                androidx.compose.animation.core.animate(
                    dragOffset, 0f,
                    animationSpec = androidx.compose.animation.core.spring(stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow)
                ) { v, _ -> dragOffset = v }
            }
        }
    }
    val settleRef by rememberUpdatedState(::settle)
    val dragConnection = remember {
        object : NestedScrollConnection {
            // Pulled down, now pushing back up: shrink the pull before the
            // list scrolls.
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y < 0f && dragOffset > 0f) {
                    val used = maxOf(available.y, -dragOffset)
                    dragOffset += used
                    return Offset(0f, used)
                }
                return Offset.Zero
            }
            // The list is at its top and the finger keeps going down: that
            // leftover movement pulls the whole sheet down.
            @Suppress("DEPRECATION")
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (available.y > 0f && source == NestedScrollSource.Drag && !closing) {
                    dragOffset += available.y
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }
            override suspend fun onPreFling(available: Velocity): Velocity {
                if (dragOffset > 0f) {
                    settleRef(available.y)
                    return available
                }
                return Velocity.Zero
            }
        }
    }

    // Item 3 bug fix: Bluesky posts opened from the Liked search tab carry
    // the AI tagger's tags too, so the toggle isn't e621-only.
    // Bluesky posts' tags (the tagger's, on this device) can be edited by
    // hand: hold to delete, double-tap to rename, "Add Tag" at the bottom.
    val canEditTags = currentItem != null && appMode == AppMode.BLUESKY &&
        currentItem.postUri.isNotBlank() && LocalOverlays.canEditTags
    val showTagsToggle = currentItem != null && (appMode == AppMode.E621 || currentItem.tags.isNotBlank() || canEditTags)
    // Which tag popup is up: delete / rename / add.
    var tagToDelete by remember(currentItem?.id) { mutableStateOf<String?>(null) }
    var tagToRename by remember(currentItem?.id) { mutableStateOf<String?>(null) }
    var addingTag by remember(currentItem?.id) { mutableStateOf(false) }
    var tagInput by remember { mutableStateOf("") }

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { sheetHeightPx = it.height.coerceAtLeast(1) }
            .graphicsLayer { translationY = dragOffset }
            .then(if (sideList) Modifier else Modifier.nestedScroll(dragConnection))
            .blockClicksBehind()
            // (The dark wash that keeps white text readable is NOT on the
            // sheet: it's a full-screen scrim in FeedView that fades in with
            // the blur, so it doesn't slide up with the comments.)
    ) {
    androidx.compose.runtime.CompositionLocalProvider(LocalCommentMediaReady provides mediaReady) {
        // Behind a comment's media opened full screen: blurred, not dimmed.
        val viewerBlur = if (viewerMedia != null) 26.dp else 0.dp
        Column(Modifier.fillMaxSize().pageBlur(viewerBlur)) {
            // ── Header: grab it anywhere to drag the sheet ──
            Column(
                Modifier
                    .fillMaxWidth()
                    .draggable(
                        orientation = androidx.compose.foundation.gestures.Orientation.Vertical,
                        enabled = !sideList,
                        state = rememberDraggableState { d -> if (!closing) dragOffset = (dragOffset + d).coerceAtLeast(0f) },
                        onDragStopped = { v -> settle(v) }
                    )
                    .padding(top = rememberTopCutoutClearance())
            ) {
                Spacer(Modifier.height(6.dp))
                Box(Modifier.fillMaxWidth()) {
                // A grab handle between the two titles: the whole sheet drags
                // down from this header. (Not in the landscape side list,
                // which doesn't drag.)
                if (!sideList) Box(
                    Modifier.align(Alignment.Center).padding(bottom = 8.dp)
                        .size(width = 38.dp, height = 4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color.White.copy(alpha = 0.45f))
                )
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Comments", color = if (!showTags) Color.White else Color.White.copy(alpha = 0.6f),
                        fontSize = 15.sp, fontWeight = if (!showTags) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.clickable(enabled = showTagsToggle) { tap(); showTags = false }
                    )
                    if (showTagsToggle) {
                        Text(
                            "Tags", color = if (showTags) Color.White else Color.White.copy(alpha = 0.6f),
                            fontSize = 15.sp, fontWeight = if (showTags) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.clickable { tap(); showTags = true }
                        )
                    }
                }
                }
            }

            // ── Body — swipe left/right toggles Comments <-> Tags ──
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .let { base ->
                        if (showTagsToggle && currentItem != null) {
                            base.pointerInput(currentItem.id) {
                                var totalX = 0f
                                detectHorizontalDragGestures(
                                    onDragEnd = {
                                        if (totalX < -70f) showTags = true
                                        else if (totalX > 70f) showTags = false
                                        totalX = 0f
                                    },
                                    onDragCancel = { totalX = 0f }
                                ) { _, dragAmount -> totalX += dragAmount }
                            }
                        } else base
                    }
            ) {
                if (showTags && currentItem != null) {
                    // Per request: tags shown alphabetically.
                    val tags = currentItem.tags.split(" ").filter { it.isNotBlank() }.sortedBy { it.lowercase() }
                    // (The Tags page follows Gradient Buttons; the comments don't.)
                    androidx.compose.runtime.CompositionLocalProvider(LocalKeepGlass provides false) {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(top = 8.dp, bottom = 8.dp)
                    ) {
                        if (tags.isEmpty()) {
                            // Editable: just the Add Tag button, at the top.
                            if (!canEditTags) item {
                                Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                                    Text("no tags", color = DimGray, fontSize = 14.sp)
                                }
                            }
                        } else {
                            items(tags, key = { it }) { tag ->
                                TagRow(
                                    tag, liquidGlass, dominantColor, backdrop, onTagClick,
                                    onLongPress = if (canEditTags) ({ tagToDelete = tag }) else null,
                                    onDoubleTap = if (canEditTags) ({ tagInput = tag.replace('_', ' '); tagToRename = tag }) else null
                                )
                            }
                        }
                        if (canEditTags) {
                            item(key = "add_tag") {
                                AddTagRow(liquidGlass, dominantColor, backdrop) { tagInput = ""; addingTag = true }
                            }
                        }
                        // Clear of the gesture bar.
                        item { Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navBarSpace)) }
                    }
                    }
                } else {
                    // Item 16: reply-chain navigation. The AnimatedContent's
                    // target IS the stack, and each page draws from the stack
                    // it was created with — so the page sliding out keeps
                    // showing the old level instead of both pages jumping to
                    // the new one first (the "jumps, then animates" jank).
                    AnimatedContent(
                        targetState = threadStack,
                        contentKey = { stack -> stack.size to (stack.lastOrNull()?.id ?: "") },
                        transitionSpec = {
                            if (reducedAnimations) {
                                EnterTransition.None togetherWith ExitTransition.None
                            } else if (targetState.size > initialState.size) {
                                (slideInHorizontally(animationSpec = tween(260)) { w -> w } + fadeIn(tween(200)))
                                    .togetherWith(slideOutHorizontally(animationSpec = tween(260)) { w -> -w } + fadeOut(tween(180)))
                            } else {
                                (slideInHorizontally(animationSpec = tween(260)) { w -> -w } + fadeIn(tween(200)))
                                    .togetherWith(slideOutHorizontally(animationSpec = tween(260)) { w -> w } + fadeOut(tween(180)))
                            }
                        },
                        label = "thread-nav"
                    ) { stack ->
                        // (Looked up again in the live list, so a like on a
                        // reply shows straight away — the stack holds the
                        // comment as it was when it was opened.)
                        val parent = stack.lastOrNull()?.let { opened -> findComment(comments, opened.id) ?: opened }
                        val displayedComments = parent?.replies ?: comments
                        val listState = listStates.getOrPut(stack.lastOrNull()?.id ?: "") { LazyListState() }
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(top = 2.dp, bottom = 8.dp)
                        ) {
                            if (parent != null) {
                                item(key = "parent-${parent.id}") {
                                    ThreadParentHeader(
                                        parent = parent, liquidGlass = liquidGlass, dominantColor = dominantColor, backdrop = backdrop,
                                        onBack = { threadStack = stack.dropLast(1) },
                                        onOpenProfile = openProfileOf,
                                        onOpenMedia = { i -> onOpenMedia?.invoke(parent.media, i) ?: run { viewerMedia = parent.media to i } },
                                        onLongPress = if (isOwn(parent)) ({ commentToDelete = parent }) else null
                                    )
                                    HorizontalDivider(color = Color.White.copy(alpha = 0.08f), thickness = 0.5.dp,
                                        modifier = Modifier.padding(vertical = 4.dp))
                                }
                            }
                            when {
                                // Comments are fetched as soon as a post is on screen
                                // (see FeedView), so they're normally already here;
                                // if they're still arriving, show nothing rather
                                // than a spinner — they simply appear.
                                parent == null && commentsLoading && displayedComments.isEmpty() -> {}
                                displayedComments.isEmpty() -> item(key = "empty") {
                                    Box(Modifier.fillParentMaxWidth().fillParentMaxHeight(0.8f), contentAlignment = Alignment.Center) {
                                        Text("no comments", color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
                                    }
                                }
                                else -> items(displayedComments, key = { it.id }) { comment ->
                                    CommentRow(
                                        comment, appMode, liquidGlass, onLikeComment, onVoteComment,
                                        // Replying is a real threaded reply to that comment (see
                                        // MainViewModel.postComment) — no "@handle" is typed
                                        // into the message; the bar above the field says
                                        // who it's replying to.
                                        onReplyToComment = { c -> replyTarget = c },
                                        onOpenThread = { c ->
                                            if (c.replies.isNotEmpty()) {
                                                // A level opened fresh starts at its top.
                                                listStates.remove(c.id)
                                                threadStack = stack + c
                                            }
                                        },
                                        dominantColor = dominantColor, backdrop = backdrop,
                                        indented = parent != null,
                                        highlighted = focusUri != null && comment.uri == focusUri,
                                        onOpenProfile = openProfileOf,
                                        onOpenMedia = { i -> onOpenMedia?.invoke(comment.media, i) ?: run { viewerMedia = comment.media to i } },
                                        onLongPress = if (isOwn(comment)) ({ commentToDelete = comment }) else null
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ── Input — same look and keyboard behaviour as the DM box ──
            if (!showTags) {
                Column(
                    Modifier.fillMaxWidth()
                        // With the menu up, the row takes no bottom insets:
                        // it sits directly on the menu, as in DMs. (The
                        // insets plus the column's own padding were the gap
                        // under the row.)
                        .then(if (emojiPanelOpen) Modifier else Modifier.windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navBarSpace)))
                        .padding(horizontal = 10.dp, vertical = 10.dp)
                ) {
                    replyTarget?.let { target ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Replying to @${target.authorHandle}", color = Color.White, fontSize = 11.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                            )
                            Icon(
                                Icons.Default.Close, contentDescription = "Cancel reply", tint = Color.White,
                                modifier = Modifier.size(16.dp).clickable {
                                    tap()
                                    replyTarget = null
                                }
                            )
                        }
                    }
                    if (attached.isNotEmpty()) {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            attached.forEach { uri ->
                                AttachedThumb(uri, isMotion(uri), dominantColor) { attached = attached - uri }
                            }
                            // Content warnings, right after the last picture
                            // (bright once one is set).
                            Box(Modifier.height(58.dp), contentAlignment = Alignment.Center) {
                                TextToggleButton(
                                    label = "Labels",
                                    liquidGlass = liquidGlass, tint = dominantColor, backdrop = backdrop,
                                    selected = adultLabel != null || graphicMedia,
                                    onClick = { labelsOpen = true }
                                )
                            }
                        }
                    }
                    Row(
                        // (Landscape: the visualizer steps up onto the comment box.)
                        Modifier.fillMaxWidth().visualizerFloor("feed.comments", "feed", enabled = sideList),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val fieldShape = RoundedCornerShape(24.dp)
                        // While a comment with media is uploading, the box shows
                        // its progress and can't take another comment.
                        val sending = com.mediaviewer.util.CommentSend.sending
                        val canSend = (commentText.isNotBlank() || attached.isNotEmpty()) && !sending
                        fun send() {
                            if (canSend) {
                                val labels = if (attached.isEmpty()) emptyList()
                                    else listOfNotNull(adultLabel?.value, if (graphicMedia) GRAPHIC_MEDIA_LABEL else null)
                                onPostComment(commentText.trim(), replyTarget, attached, labels)
                                commentText = ""; replyTarget = null; attached = emptyList()
                            }
                        }
                        if (appMode == AppMode.BLUESKY) {
                            val mediaModifier = Modifier.size(46.dp).clickable(enabled = !sending) {
                                tap()
                                mediaPicker.launch(com.mediaviewer.ui.compat.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                            }
                            @Composable
                            fun MediaContent() {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.Image, contentDescription = "Add media", tint = Color.White, modifier = Modifier.size(20.dp))
                                }
                            }
                            if (liquidGlass) {
                                LiquidGlassSurface(mediaModifier, shape = CircleShape, tint = dominantColor, backdrop = backdrop) { MediaContent() }
                            } else {
                                Box(mediaModifier.clip(CircleShape).background(Color.White.copy(0.14f))) { MediaContent() }
                            }
                        }
                        if (appMode == AppMode.BLUESKY) {
                            // Custom emoji, as in DMs: the same star
                            // button, right after the media button.
                            val emojiModifier = Modifier.size(46.dp).clickable { tap(); toggleEmojiPanel() }
                            @Composable
                            fun EmojiContent() {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.Star, contentDescription = "Emoji", tint = Color.White, modifier = Modifier.size(20.dp))
                                }
                            }
                            if (liquidGlass) {
                                LiquidGlassSurface(emojiModifier, shape = CircleShape, tint = dominantColor, backdrop = backdrop) { EmojiContent() }
                            } else {
                                Box(emojiModifier.clip(CircleShape).background(Color.White.copy(0.14f))) { EmojiContent() }
                            }
                        }
                        @Composable
                        fun FieldContent() {
                            if (sending) {
                                Box(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                                    Text(
                                        com.mediaviewer.util.VideoUpload.label.ifBlank { "Uploading…" },
                                        color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp, maxLines = 1
                                    )
                                }
                            } else Box(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
                                // @mention autocomplete, as in the composer: the rest
                                // of the likely handle shows dimmed after the caret;
                                // Tab, a swipe right across the box, or the Send
                                // key's first press fills it in.
                                if (field.text != commentText) field = androidx.compose.ui.text.input.TextFieldValue(commentText, androidx.compose.ui.text.TextRange(commentText.length))
                                var focused by remember { mutableStateOf(false) }
                                var fieldLayout by remember { mutableStateOf<androidx.compose.ui.text.TextLayoutResult?>(null) }
                                val mention = remember(field.text, field.selection) { mentionTokenAt(field) }
                                var guess by remember { mutableStateOf<String?>(null) }
                                LaunchedEffect(mention?.second, focused) {
                                    val typed = mention?.second
                                    if (typed == null || !focused) { guess = null; return@LaunchedEffect }
                                    if (guess?.startsWith(typed.lowercase()) != true) guess = null
                                    kotlinx.coroutines.delay(120)
                                    guess = com.mediaviewer.util.MentionSuggest.complete(typed)
                                }
                                val ghost = mention?.let { m -> guess?.takeIf { it.startsWith(m.second.lowercase()) && it.length > m.second.length }?.drop(m.second.length) }
                                fun acceptGhost(): Boolean {
                                    val m = mentionTokenAt(field) ?: return false
                                    val g = guess?.takeIf { it.startsWith(m.second.lowercase()) } ?: return false
                                    val start = m.first + 1
                                    val tail = field.text.substring(field.selection.end)
                                    val insert = if (tail.firstOrNull()?.isWhitespace() == true) g else "$g "
                                    val text = field.text.substring(0, start) + insert + tail
                                    guess = null
                                    field = androidx.compose.ui.text.input.TextFieldValue(text, androidx.compose.ui.text.TextRange(start + insert.length + if (insert == g) 1 else 0))
                                    commentText = text
                                    return true
                                }
                                // Custom emoji, as in the composer: each
                                // token shows as a blank slot with its
                                // picture overlaid (below); the mention
                                // ghost wraps that transformation, so the
                                // two compose instead of fighting. (While
                                // a ghost shows, the overlay bails — its
                                // length check fails — and the pictures
                                // come back when the ghost clears.)
                                val emojiTransformation = remember(emojiStore, emojiStore.state) { emojiVisualTransformation(emojiStore) }
                                val transformation = if (ghost == null) emojiTransformation
                                    else remember(ghost, field.selection.end, emojiTransformation) { ghostTransformation(emojiTransformation, field.selection.end, ghost) }
                                Box(Modifier.fillMaxWidth()) {
                                BasicTextField(
                                    value = field, onValueChange = { field = it; commentText = it.text },
                                    visualTransformation = transformation,
                                    singleLine = true,
                                    textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 14.sp, lineHeight = 20.sp, fontFamily = androidx.compose.material3.LocalTextStyle.current.fontFamily),
                                    cursorBrush = androidx.compose.ui.graphics.SolidColor(Color.White),
                                    onTextLayout = { fieldLayout = it },
                                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                        imeAction = androidx.compose.ui.text.input.ImeAction.Send
                                    ),
                                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSend = { if (ghost == null || !acceptGhost()) send() }),
                                    // (The whole bubble is the field: a touch anywhere on it
                                    // keeps the keyboard up, and the swipe can start anywhere.)
                                    modifier = Modifier.fillMaxWidth()
                                        .focusRequester(commentFocus)
                                        .onFocusChanged {
                                            focused = it.isFocused
                                            // Tapping back into the field
                                            // closes the menu (the keyboard
                                            // takes its place again).
                                            if (it.isFocused && emojiPanelOpen) { emojiPanelOpen = false; emojiTabEditing = false }
                                        }
                                        .onPreviewKeyEvent { e ->
                                            e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown && e.key == androidx.compose.ui.input.key.Key.Tab && ghost != null && acceptGhost()
                                        }
                                        .pointerInput(ghost != null) {
                                            if (ghost == null) return@pointerInput
                                            // A swipe right fills the handle in. Watched before the
                                            // field's own gestures (they'd take the drag for
                                            // moving the caret), and only taken once it's clearly
                                            // a sideways swipe.
                                            awaitEachGesture {
                                                val down = awaitFirstDown(
                                                    requireUnconsumed = false, pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial
                                                )
                                                while (true) {
                                                    val ev = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                                                    val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                                                    if (!c.pressed) break
                                                    val dx = c.position.x - down.position.x
                                                    val dy = c.position.y - down.position.y
                                                    if (dx > 24.dp.toPx() && kotlin.math.abs(dy) < dx) {
                                                        if (acceptGhost()) ev.changes.forEach { it.consume() }
                                                        break
                                                    }
                                                }
                                            }
                                        }
                                )
                                EmojiFieldOverlay(field.text, fieldLayout, emojiStore)
                                }
                                if (commentText.isEmpty()) {
                                    // One line, always: the placeholder shrinks until it
                                    // fits the bubble instead of wrapping to two lines —
                                    // in landscape the comments side panel can be too
                                    // narrow for it at full size. (The same step-down
                                    // shrink the settings rows use, keyed on the words
                                    // so each placeholder sizes itself.)
                                    val placeholder = if (replyTarget != null) "Reply…" else "Add a comment…"
                                    var placeholderSize by remember(placeholder) { mutableStateOf(14f) }
                                    Text(
                                        placeholder, color = Color.White, fontSize = placeholderSize.sp,
                                        maxLines = 1, softWrap = false, overflow = TextOverflow.Clip,
                                        onTextLayout = { result ->
                                            if (result.didOverflowWidth && placeholderSize > 6f) {
                                                placeholderSize = (placeholderSize - 0.5f).coerceAtLeast(6f)
                                            }
                                        }
                                    )
                                }
                            }
                        }
                        if (liquidGlass) {
                            LiquidGlassSurface(Modifier.weight(1f).height(46.dp).keyboardRegion(0.dp), shape = fieldShape, tint = dominantColor, backdrop = backdrop) { FieldContent() }
                        } else {
                            Box(Modifier.weight(1f).height(46.dp).keyboardRegion(0.dp).clip(fieldShape).background(Color.White.copy(0.08f))) { FieldContent() }
                        }
                        val sendModifier = Modifier.size(46.dp).clickable(enabled = canSend) { tap(); send() }
                        @Composable
                        fun SendContent() {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.Send, contentDescription = "Send",
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        if (liquidGlass) {
                            LiquidGlassSurface(sendModifier, shape = CircleShape, tint = dominantColor, backdrop = backdrop) { SendContent() }
                        } else {
                            Box(sendModifier.clip(CircleShape).background(Color.White.copy(0.14f))) { SendContent() }
                        }
                    }
                    // Back closes the menu first, not the sheet.
                    if (emojiPanelOpen) BackHandler { emojiPanelOpen = false; emojiTabEditing = false }
                }
                // The emoji menu, under the input row but outside its padded
                // column, so it runs edge to edge like the posting page's
                // (the column's 10dp sides left gaps at the menu's edges).
                // No nav-bar padding of its own: like the DM menu, it runs
                // to the screen's bottom edge.
                Column(
                    Modifier.fillMaxWidth()
                ) {
                    // Custom emoji menu, under the input row, where the
                    // keyboard was — the DM menu's height, and riding on
                    // the keyboard while a tab is being renamed.
                    if (emojiPanelOpen) {
                        EmojiPanel(
                            store = emojiStore,
                            height = emojiPanelHeight,
                            compact = emojiTabEditing,
                            liquidGlass = liquidGlass,
                            tint = dominantColor,
                            onPickEmoji = { insertEmoji(it) },
                            onEditingChange = { emojiTabEditing = it },
                            // The accounts row, as in DMs: your own picture first,
                            // then saved accounts, with Import at its far right
                            // (not wedged into the folders row, where it
                            // overlapped the last tab).
                            showAccounts = true, selfDid = selfDidForEmoji, selfAvatarUrl = selfAvatarUrl,
                            modifier = Modifier.fillMaxWidth()
                                .padding(bottom = if (emojiTabEditing) commentImeDp else 0.dp)
                        )
                    }
                    // (The first time the emoji menu opens: custom emoji are public.)
                    if (emojiPanelOpen) PublicDataNotice(PublicDataKind.EMOJI, liquidGlass = liquidGlass, tint = dominantColor, backdrop = backdrop)
                }
            }
        }
        // A comment's picture / video / GIF, full screen.
        viewerMedia?.let { (list, start) ->
            CommentMediaViewer(list, start, dominantColor, liquidGlass, backdrop) { viewerMedia = null }
        }

        if (labelsOpen) {
            ContentLabelsPopup(
                adult = adultLabel, graphic = graphicMedia,
                liquidGlass = liquidGlass, tint = dominantColor,
                // One Adult Content option at a time (tap again to clear);
                // Graphic Media is separate — as when posting.
                onAdultChange = { picked -> adultLabel = if (adultLabel == picked) null else picked },
                onGraphicChange = { graphicMedia = it },
                onDismiss = { labelsOpen = false }
            )
        }

        commentToDelete?.let { c ->
            ConfirmPopup(
                title = "Delete Comment?",
                message = "This will permanently delete your comment.",
                confirmLabel = "Delete",
                liquidGlass = liquidGlass, tint = dominantColor, backdrop = backdrop,
                onConfirm = {
                    commentToDelete = null
                    if (replyTarget?.id == c.id) replyTarget = null
                    // Its replies page was open: back out of it.
                    if (threadStack.any { it.id == c.id }) threadStack = threadStack.takeWhile { it.id != c.id }
                    LocalOverlays.deleteComment?.invoke(c)
                },
                onDismiss = { commentToDelete = null }
            )
        }

        // ── Tag popups (hold = delete, double-tap = rename, Add Tag) ──
        tagToDelete?.let { tag ->
            ConfirmPopup(
                title = "Delete Tag?",
                message = "Remove \"${tag.replace('_', ' ')}\" from this post?",
                confirmLabel = "Delete",
                liquidGlass = liquidGlass, tint = dominantColor, backdrop = backdrop,
                onConfirm = { tagToDelete = null; LocalOverlays.editPostTag?.invoke(tag, null) },
                onDismiss = { tagToDelete = null }
            )
        }
        tagToRename?.let { tag ->
            ConfirmPopup(
                title = "Rename Tag",
                message = "",
                confirmLabel = "Rename",
                liquidGlass = liquidGlass, tint = dominantColor, backdrop = backdrop,
                destructive = false,
                input = tagInput, onInputChange = { tagInput = it }, inputPlaceholder = "Tag name",
                onConfirm = {
                    val typed = com.mediaviewer.tagging.normalizeTypedTag(tagInput)
                    if (typed.isNotBlank()) {
                        tagToRename = null
                        if (typed != tag) LocalOverlays.editPostTag?.invoke(tag, typed)
                    }
                },
                onDismiss = { tagToRename = null }
            )
        }
        if (addingTag) {
            ConfirmPopup(
                title = "Add Tag",
                message = "",
                confirmLabel = "Add",
                liquidGlass = liquidGlass, tint = dominantColor, backdrop = backdrop,
                destructive = false,
                input = tagInput, onInputChange = { tagInput = it }, inputPlaceholder = "Tag name",
                onConfirm = {
                    val typed = com.mediaviewer.tagging.normalizeTypedTag(tagInput)
                    if (typed.isNotBlank()) {
                        addingTag = false
                        LocalOverlays.editPostTag?.invoke(null, typed)
                    }
                },
                onDismiss = { addingTag = false }
            )
        }
        }
}
}

/** A picture / video attached to the comment being written, with an X to
 *  take it off. */
@Composable
private fun AttachedThumb(uri: PlatformUri, motion: Boolean, tint: Color, onRemove: () -> Unit) {
    val tap = rememberHapticTap()
    val shape = RoundedCornerShape(10.dp)
    Box(Modifier.size(58.dp).clip(shape).background(Color.White.copy(alpha = 0.10f))) {
        AsyncImage(model = uri, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        if (motion) {
            Icon(
                Icons.Default.PlayArrow, contentDescription = null, tint = Color.White,
                modifier = Modifier.align(Alignment.Center).size(22.dp)
            )
        }
        Box(
            Modifier.align(Alignment.TopEnd).padding(3.dp).size(18.dp).clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.6f)).clickable { tap(); onRemove() },
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Close, contentDescription = "Remove", tint = Color.White, modifier = Modifier.size(12.dp))
        }
    }
}

/** The Tags page's last row: adds a tag typed by hand. */
@Composable
private fun AddTagRow(liquidGlass: Boolean, dominantColor: Color, backdrop: GlassBackdrop?, onClick: () -> Unit) {
    val tap = rememberHapticTap()
    val shape = RoundedCornerShape(14.dp)
    val m = Modifier
        .fillMaxWidth()
        .padding(horizontal = 12.dp, vertical = 6.dp)
        .height(36.dp)
        .clip(shape)
        .clickable { tap(); onClick() }
    val label: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Add, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Add Tag", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
    if (liquidGlass) {
        LiquidGlassSurface(m, shape = shape, tint = dominantColor, backdrop = backdrop, contentAlignment = Alignment.Center) { label() }
    } else {
        Box(m.background(Color.White.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) { label() }
    }
}

/** One tag on the Tags page: its own short, compact blurred bubble,
 *  edge to edge, with the tag centered. Tap searches that tag. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun TagRow(
    tag: String, liquidGlass: Boolean, dominantColor: Color, backdrop: GlassBackdrop?,
    onTagClick: (String) -> Unit,
    /** Hold: delete (with a confirm popup). */
    onLongPress: (() -> Unit)? = null,
    /** Double-tap: rename. */
    onDoubleTap: (() -> Unit)? = null
) {
    val tap = rememberHapticTap()
    val shape = RoundedCornerShape(14.dp)
    val m = Modifier
        .fillMaxWidth()
        .padding(horizontal = 12.dp, vertical = 3.dp)
        .height(34.dp)
        .clip(shape)
        .then(
            if (onLongPress == null && onDoubleTap == null) Modifier.clickable { tap(); onTagClick(tag) }
            else Modifier.combinedClickable(
                onClick = { tap(); onTagClick(tag) },
                onLongClick = onLongPress,
                onDoubleClick = onDoubleTap?.let { f -> { tap(); f() } }
            )
        )
    val label: @Composable () -> Unit = {
        Text(
            tag.replace('_', ' '), color = Color.White, fontSize = 13.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 14.dp)
        )
    }
    if (liquidGlass) {
        LiquidGlassSurface(m, shape = shape, tint = dominantColor.copy(alpha = 0.5f), backdrop = backdrop, contentAlignment = Alignment.Center) { label() }
    } else {
        Box(m.background(Color.White.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) { label() }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun CommentRow(
    comment: CommentItem,
    appMode: AppMode,
    liquidGlass: Boolean,
    onLike: (CommentItem) -> Unit,
    onVote: (CommentItem, Int) -> Unit,
    onReplyToComment: (CommentItem) -> Unit,
    onOpenThread: (CommentItem) -> Unit = {},
    dominantColor: Color = NeutralGlassTint,
    backdrop: GlassBackdrop? = null,
    indented: Boolean = false,
    /** The reply that was opened (see [CommentFocus]): picked out. */
    highlighted: Boolean = false,
    onOpenProfile: (CommentItem) -> Unit = {},
    onOpenMedia: (Int) -> Unit = {},
    /** Your own comment held down (anywhere on it, its media too): delete. */
    onLongPress: (() -> Unit)? = null
) {
    @Composable
    fun RowContent() {
        val tap = rememberHapticTap()
        Row(
            modifier = Modifier.fillMaxWidth()
                .padding(start = if (indented) 20.dp else 0.dp)
                .then(if (highlighted) Modifier.clip(RoundedCornerShape(12.dp)).background(dominantColor.copy(alpha = 0.22f)) else Modifier)
                .then(
                    if (onLongPress != null) Modifier.combinedClickable(
                        onClick = { if (comment.replies.isNotEmpty()) { tap(); onOpenThread(comment) } },
                        onLongClick = { tap(); onLongPress() }
                    ) else Modifier.clickable(enabled = comment.replies.isNotEmpty()) { tap(); onOpenThread(comment) }
                )
                .padding(horizontal = 12.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
        // Their picture and name open their profile.
        val toProfile = Modifier.clickable(enabled = comment.authorDid.isNotBlank()) { tap(); onOpenProfile(comment) }
        if (comment.authorAvatarUrl != null) {
            AsyncImage(model = comment.authorAvatarUrl, contentDescription = "${comment.authorDisplayName}'s profile", contentScale = ContentScale.Crop,
                modifier = Modifier.size(30.dp).clip(AvatarShape).then(toProfile))
        } else {
            Box(modifier = Modifier.size(30.dp).clip(AvatarShape).background(Color.White.copy(0.1f)).then(toProfile))
        }

        Column(modifier = Modifier.weight(1f)) {
            // Item 8: display name gets first claim on the row's width and shrinks
            // with an ellipsis before wrapping; the handle only gets whatever room
            // is left over and also ellipsizes — so long names/handles never wrap
            // onto a second line and break the row's height.
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically, modifier = toProfile) {
                Text(
                    comment.authorDisplayName, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 12.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Text(
                    "@${comment.authorHandle}", color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(2.dp))
            if (comment.body.isNotBlank()) DmMessageText(comment.body, comment.facets, color = Color.White.copy(0.88f), fontSize = 13.sp, lineHeight = 17.sp)
            if (comment.media.isNotEmpty()) CommentMediaBlock(comment.media, dominantColor, onOpen = onOpenMedia, onLongPress = onLongPress)
            Spacer(Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (appMode == AppMode.BLUESKY) {
                    // Item 16: no more raw like-count number next to the heart —
                    // just the heart itself, then a "replies: N" label (only shown
                    // when this comment actually has replies).
                    Icon(
                        imageVector = if (comment.isLiked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                        contentDescription = "Like", tint = if (comment.isLiked) LikeRed else Color.White,
                        modifier = Modifier.size(14.dp).clickable { tap(); onLike(comment) }
                    )
                    if (comment.replyCount > 0) {
                        Text("replies: ${comment.replyCount}", color = Color.White, fontSize = 11.sp)
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        "Reply", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable { tap(); onReplyToComment(comment) }
                    )
                } else {
                    Icon(Icons.Default.ArrowUpward, contentDescription = "Upvote",
                        tint = if (comment.e621UserVote == 1) VoteGreen else Color.White,
                        modifier = Modifier.size(14.dp).clickable { tap(); onVote(comment, 1) })
                    Text(comment.likeCount.toString(), color = Color.White, fontSize = 11.sp)
                    Icon(Icons.Default.ArrowDownward, contentDescription = "Downvote",
                        tint = if (comment.e621UserVote == -1) VoteRed else Color.White,
                        modifier = Modifier.size(14.dp).clickable { tap(); onVote(comment, -1) })
                    if (comment.replyCount > 0) {
                        Spacer(Modifier.width(4.dp))
                        Text("replies: ${comment.replyCount}", color = Color.White, fontSize = 11.sp)
                    }
                }
            }
        }
    }
    }
    if (liquidGlass) {
        LiquidGlassSurface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            shape = RoundedCornerShape(14.dp), tint = dominantColor.copy(alpha = 0.5f), backdrop = backdrop
        ) { RowContent() }
    } else {
        RowContent()
    }
}

// ─── Reply-chain: pinned parent header ─────────────────────────────────────────
// Occupies the top of the list whenever the user has drilled into a reply chain —
// the tapped comment "becomes" the header, with a separate curved Back bubble to
// its left. Tapping Back repeatedly walks back out one level at a time.
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ThreadParentHeader(
    parent: CommentItem,
    liquidGlass: Boolean,
    dominantColor: Color,
    backdrop: GlassBackdrop?,
    onBack: () -> Unit,
    onOpenProfile: (CommentItem) -> Unit = {},
    onOpenMedia: (Int) -> Unit = {},
    onLongPress: (() -> Unit)? = null
) {
    val tap = rememberHapticTap()
    val toProfile = Modifier.clickable(enabled = parent.authorDid.isNotBlank()) { tap(); onOpenProfile(parent) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        @Composable
        fun BackIconContent() {
            Box(Modifier.fillMaxSize().clickable(onClick = { tap(); onBack() }), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White, modifier = Modifier.size(18.dp))
            }
        }
        if (liquidGlass) {
            LiquidGlassSurface(modifier = Modifier.size(40.dp), shape = CircleShape, tint = dominantColor, backdrop = backdrop) { BackIconContent() }
        } else {
            Box(Modifier.size(40.dp).clip(CircleShape).background(Color.White.copy(0.08f))) { BackIconContent() }
        }

        @Composable
        fun ParentPillContent() {
            Row(
                Modifier.fillMaxWidth()
                    .then(if (onLongPress != null) Modifier.combinedClickable(onClick = {}, onLongClick = { tap(); onLongPress() }) else Modifier)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Item 21: the opened (top) comment in a thread was missing its
                // author's avatar, unlike every regular CommentRow below it.
                if (parent.authorAvatarUrl != null) {
                    AsyncImage(model = parent.authorAvatarUrl, contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.size(30.dp).clip(AvatarShape).then(toProfile))
                } else {
                    Box(Modifier.size(30.dp).clip(AvatarShape).background(Color.White.copy(0.1f)).then(toProfile))
                }
                Column(Modifier.weight(1f)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically, modifier = toProfile) {
                        Text(parent.authorDisplayName, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 12.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        Text("@${parent.authorHandle}", color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.height(3.dp))
                    if (parent.body.isNotBlank()) DmMessageText(parent.body, parent.facets, color = Color.White, fontSize = 13.sp, lineHeight = 17.sp)
                    if (parent.media.isNotEmpty()) CommentMediaBlock(parent.media, dominantColor, onOpen = onOpenMedia, onLongPress = onLongPress)
                }
            }
        }
        if (liquidGlass) {
            LiquidGlassSurface(modifier = Modifier.weight(1f), shape = RoundedCornerShape(16.dp), tint = dominantColor, backdrop = backdrop) { ParentPillContent() }
        } else {
            Box(Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(Color.White.copy(0.06f))) { ParentPillContent() }
        }
    }
}

/** [id] anywhere in [list], replies included. */
private fun findComment(list: List<CommentItem>, id: String): CommentItem? {
    for (c in list) {
        if (c.id == id) return c
        findComment(c.replies, id)?.let { return it }
    }
    return null
}
