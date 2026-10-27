package com.mediaviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import com.mediaviewer.ui.theme.RoundedCornerShape
import com.mediaviewer.i18n.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.mediaviewer.ui.compat.BackHandler
import com.mediaviewer.util.LocalData
import com.mediaviewer.util.rememberHapticTap
import com.mediaviewer.viewmodel.MainViewModel

/**
 * The selection Tag popup's input, split into tags to add and tags to
 * remove: a space separates tags, and a leading "-" marks a removal
 * ("raccoon red_panda -bird" adds raccoon and red_panda, removes bird).
 * Blank tokens and a lone "-" are skipped; duplicates collapse.
 */
private fun parseTagTokens(text: String): Pair<List<String>, List<String>> {
    val adds = mutableListOf<String>()
    val removes = mutableListOf<String>()
    text.split(Regex("\\s+")).forEach { raw ->
        if (raw.isEmpty()) return@forEach
        if (raw.startsWith("-")) {
            val clean = com.mediaviewer.tagging.normalizeTypedTag(raw.drop(1))
            if (clean.isNotBlank() && clean !in removes) removes += clean
        } else {
            val clean = com.mediaviewer.tagging.normalizeTypedTag(raw)
            if (clean.isNotBlank() && clean !in adds) adds += clean
        }
    }
    return adds to removes
}

/**
 * Draws the supporter features' popups, the Launchpad apps and the floating
 * web pages over the rest of the app, and connects [LocalOverlays]' actions
 * to the ViewModel. Called once from AppRoot, inside its root Box (each
 * piece sets its own zIndex among AppRoot's other layers).
 */
@Composable
fun LocalOverlayHost(
    viewModel: MainViewModel,
    liquidGlass: Boolean,
    /** The signed-in account's own profile color. */
    tint: Color,
    /** The color of the post on screen (popups about that post wear it). */
    postTint: Color,
    savedFeedUris: Set<String>,
    /** The app page behind the popups, recorded live: their glass blurs it. */
    backdrop: GlassBackdrop?,
    hubShowing: Boolean
) {
    val folderId by viewModel.bookmarkFolderId.collectAsState()
    // (Read here, in the composition: read only inside SideEffect below, a
    // new folder never recomposed this, so the Saved Posts tabs kept
    // highlighting the old one.)
    val folderIdNow = folderId
    LaunchedEffect(folderIdNow) { LocalOverlays.bookmarkFolderId = folderIdNow }
    val savedSortNow by viewModel.savedSort.collectAsState()
    LaunchedEffect(savedSortNow) { LocalOverlays.savedSort = savedSortNow }
    val dmHidden by viewModel.dmHiddenBehindFeed.collectAsState()
    val myLists by viewModel.userLists.collectAsState()
    val selectedFeed by viewModel.selectedFeedUri.collectAsState()
    val archiveBusy by viewModel.archiveBusy.collectAsState()
    // (Read here, in the composition, so a change is always passed on.)
    val archiveBusyNow = archiveBusy
    LaunchedEffect(archiveBusyNow) { LocalOverlays.archiveBusy = archiveBusyNow }
    // Tags can be added by hand on any Bluesky post — no AI model needed
    // (they go into this device's own dataset).
    val tagsEditable = viewModel.taggingSupported
    LaunchedEffect(tagsEditable) { LocalOverlays.canEditTags = tagsEditable }
    val selfDidNow by viewModel.bskyDid.collectAsState()
    LaunchedEffect(selfDidNow) { LocalOverlays.selfDid = selfDidNow }

    SideEffect {
        LocalOverlays.onEditCurrentPost = viewModel::editCurrentPost
        LocalOverlays.editPostTag = viewModel::editCurrentPostTag
        LocalOverlays.deleteComment = viewModel::deleteComment
        LocalOverlays.openInBrowser = viewModel::openInAppBrowser
        LocalOverlays.setHateFunBlur = viewModel::setHateFunBlurNsfw
        LocalOverlays.reloadFeed = { viewModel.loadFeed(reset = true) }
        LocalOverlays.pollTally = { uri -> viewModel.pollTally(uri) }
        LocalOverlays.pollVote = viewModel::votePoll
        LocalOverlays.scanDmStreak = viewModel::scanDmStreak
        LocalOverlays.resolveFeedCard = { actor, kind, rkey -> viewModel.sharedFeedCard(actor, kind, rkey) }
        LocalOverlays.openSharedFeed = viewModel::openFeedFromDm
        LocalOverlays.openDmSharedPost = viewModel::openDmSharedPost
        LocalOverlays.archiveCurrentPost = viewModel::archiveCurrentPost
        LocalOverlays.openArchive = viewModel::openArchive
        LocalOverlays.restoreArchivedPost = viewModel::restoreArchivedCurrentPost
        LocalOverlays.deleteArchivedPost = viewModel::deleteArchivedCurrentPost
        LocalOverlays.prepareVideoUpload = viewModel::prepareVideoUpload
        LocalOverlays.cancelVideoUpload = viewModel::cancelVideoUpload
        LocalOverlays.addSharedFeed = viewModel::addSharedFeed
        LocalOverlays.onShowBookmarkFolder = viewModel::showBookmarkFolder
        LocalOverlays.setSavedSort = viewModel::setSavedSort
        LocalOverlays.moveSavedPost = viewModel::moveSavedPost
        LocalOverlays.openPublicFolder = viewModel::openPublicFolder
        LocalOverlays.loadFeedRow = { key, feed, more -> viewModel.loadFeedRow(key, feed, more) }
        LocalOverlays.openHomeRowExplore = { viewModel.openHomeRowExplore(it) }
        LocalOverlays.feedRows = viewModel.hubLists
        LocalOverlays.openProfile = { viewModel.openProfile(it) }
        LocalOverlays.backFromFeed = viewModel::backFromFeed
        LocalOverlays.openMutualBlog = viewModel::openMutualBlog
        LocalOverlays.openMutualReview = viewModel::openMutualReview
        LocalOverlays.onPlaceBookmark = viewModel::placeBookmark
        LocalOverlays.onOpenTagged = viewModel::openTaggedPage
        LocalOverlays.onOpenTaggingQueue = viewModel::openTaggingQueueView
        LocalOverlays.downloadProfileMedia = viewModel::downloadProfileMedia
        LocalOverlays.savedFeedUris = savedFeedUris
    }

    // The timer keeps running (and rings) wherever you are in the app.
    TimerWatcher()

    // In-app notifications (supporters): over every page.
    InAppNoticeBanner(tint, Modifier.zIndex(11.7f))

    // A tapped notification / widget / banner asking for a place in the app
    // (see AppLinks): opened once the saved sign-in has been restored.
    val pendingLink = com.mediaviewer.util.AppLinks.pending
    val appReady by viewModel.appInitialized.collectAsState()
    val signedIn by viewModel.bskyLoggedIn.collectAsState()
    LaunchedEffect(pendingLink, appReady, signedIn) {
        if (pendingLink == null || !appReady || !signedIn) return@LaunchedEffect
        val link = com.mediaviewer.util.AppLinks.take() ?: return@LaunchedEffect
        val supporter = com.mediaviewer.util.Supporter.active
        when {
            link.startsWith("dm:") -> viewModel.openDmFromLink(link.removePrefix("dm:"))
            link == "dms" -> viewModel.openDmInbox()
            link == "inbox" -> viewModel.openInbox()
            link == "calendar" -> if (supporter) LocalOverlays.launchApp = LaunchApp.CALENDAR
            link.startsWith("calendar:") -> if (supporter) {
                LocalOverlays.openCalendarDay = link.removePrefix("calendar:").toIntOrNull()
                LocalOverlays.launchApp = LaunchApp.CALENDAR
            }
            link == "notes" -> if (supporter) LocalOverlays.launchApp = LaunchApp.NOTES
            link.startsWith("note:") -> if (supporter) {
                LocalOverlays.openNoteId = link.removePrefix("note:")
                LocalOverlays.launchApp = LaunchApp.NOTES
            }
        }
    }

    // A feed dropped on the Hub's DMs button → the "Share with" popup.
    val shareFeed = LocalOverlays.shareFeed
    LaunchedEffect(shareFeed) {
        if (shareFeed != null) {
            viewModel.openShareFeed(shareFeed)
            LocalOverlays.shareFeed = null
        }
    }
    // A quote repost's quoted post, opened in front of the Timeline.
    val quotedToOpen = LocalOverlays.openQuotedPostUri
    LaunchedEffect(quotedToOpen) {
        if (quotedToOpen != null) {
            viewModel.openQuotedPost(quotedToOpen)
            LocalOverlays.openQuotedPostUri = null
        }
    }

    // A feed opened from a DM sits in front of the DMs: Back returns to them.
    // (Going to the Hub instead leaves the DMs for good.)
    val searchHidden by viewModel.searchHiddenBehindFeed.collectAsState()
    if (dmHidden || searchHidden) BackHandler { viewModel.closeProfileFeed() }
    LaunchedEffect(hubShowing, dmHidden) { if (hubShowing && dmHidden) viewModel.dropHiddenDm() }
    LaunchedEffect(hubShowing, searchHidden) { if (hubShowing && searchHidden) viewModel.dropHiddenSearch() }

    // ── Launchpad apps (full pages over the Hub) ──
    LocalOverlays.launchApp?.let { app ->
        Box(Modifier.fillMaxSize().zIndex(10.55f)) {
            val close = { LocalOverlays.launchApp = null }
            when (app) {
                LaunchApp.CALENDAR -> CalendarPage(tint, liquidGlass, close)
                LaunchApp.NOTES -> NotesPage(tint, liquidGlass, close)
                LaunchApp.CALCULATOR -> CalculatorPage(tint, liquidGlass, close)
                LaunchApp.TIMER -> TimerPage(tint, liquidGlass, close)
            }
        }
    }

    // ── Selection mode's bar (over the page whose posts are picked) ──
    val roundedTiles by viewModel.squareGridRounded.collectAsState()
    androidx.compose.runtime.SideEffect { LocalOverlays.roundedGridTiles = roundedTiles }

    // Selection mode's buttons (in the page's own interaction bar — see
    // SelectionBarButtons): the app's own Save, Download, GIF, Share,
    // Archive and Delete, on every picked post.
    LocalOverlays.selection = SelectionActions(
        selfDid = selfDidNow,
        onSave = { LocalOverlays.bookmarkFolderForMany = it },
        onDownload = { items, gif -> viewModel.downloadItems(items, gif) },
        onShare = { viewModel.openSendPopupFor(it); PostSelection.clear() },
        onTag = { items ->
            LocalOverlays.confirm = ConfirmRequest(
                "Add Tag", "", "Add", tint, destructive = false,
                input = "", inputPlaceholder = "Tag name",
                // The header and button follow the typed tags live:
                // one add -> Add Tag, several adds -> Add Tags, any
                // removal on its own -> Remove Tag(s), a mix -> Edit Tags.
                titleForText = { text ->
                    val (adds, removes) = parseTagTokens(text)
                    val plural = if (adds.size + removes.size > 1) "s" else ""
                    // A trailing bare "-" is a second removal being
                    // typed: it counts toward the plural, so "-foo -"
                    // already reads Remove Tags instead of waiting for
                    // a letter after the second "-". (parseTagTokens
                    // itself still ignores bare dashes — this is
                    // title-only, never a real removal on confirm.)
                    val pendingRemove = text.trim().split(Regex("\\s+")).lastOrNull() == "-"
                    val removePlural = if (removes.size + (if (pendingRemove) 1 else 0) > 1) "s" else ""
                    when {
                        adds.isNotEmpty() && removes.isNotEmpty() -> "Edit Tags"
                        removes.isNotEmpty() -> "Remove Tag$removePlural"
                        adds.isNotEmpty() -> "Add Tag$plural"
                        // Nothing parsed yet, but the remove marker is
                        // already typed: flip the moment "-" appears,
                        // not once a tag follows it.
                        text.trimStart().startsWith("-") -> "Remove Tag"
                        else -> "Add Tag"
                    }
                },
                labelForText = { text ->
                    val (adds, removes) = parseTagTokens(text)
                    when {
                        adds.isNotEmpty() && removes.isNotEmpty() -> "Edit"
                        removes.isNotEmpty() -> "Remove"
                        text.trimStart().startsWith("-") -> "Remove"
                        else -> "Add"
                    }
                }
            ) {
                val (adds, removes) = parseTagTokens(LocalOverlays.confirm?.text.orEmpty())
                if (adds.isNotEmpty() || removes.isNotEmpty()) {
                    viewModel.editTags(items, adds, removes)
                    PostSelection.clear()
                }
            }
        },
        onArchive = { items ->
            LocalOverlays.confirm = ConfirmRequest(
                "Archive ${items.size} Post${if (items.size == 1) "" else "s"}?",
                "Archives the selected posts and threads.",
                "Archive", tint, destructive = false
            ) { viewModel.archiveItems(items); PostSelection.clear() }
        },
        onDelete = { items ->
            LocalOverlays.confirm = ConfirmRequest(
                "Delete ${items.size} Post${if (items.size == 1) "" else "s"}?",
                "They'll be deleted from your profile for good.",
                "Delete", tint
            ) { viewModel.deleteItems(items); PostSelection.clear() }
        }
    )

    // ── Selection mode's More menu (Archive / Delete over the bar) ──
    // The bar's More button anchors it (see SelectionBarButtons); the
    // stack stays composed while selection lasts so it can animate out —
    // pass [visible], never add/remove it. No input layer on purpose,
    // like the profile's own More menu: the page stays scrollable and
    // tappable with it open. The More button toggles it, Back closes
    // just the menu, and tapping an action closes it; the picks stay
    // picked throughout.
    if (PostSelection.active) {
        var menuRootOrigin by remember { mutableStateOf(Offset.Zero) }
        val menuOpen = PostSelection.moreMenuOpen
        if (menuOpen) {
            com.mediaviewer.ui.compat.BackHandler { PostSelection.moreMenuOpen = false }
        }
        Box(
            Modifier.fillMaxSize().zIndex(10.6f)
                .onGloballyPositioned { menuRootOrigin = it.positionInRoot() }
        ) {
            PostSelection.moreAnchor?.let { anchor ->
                BubbleActionStack(
                    visible = menuOpen,
                    anchorOriginRoot = anchor.first,
                    anchorSize = anchor.second,
                    containerRootOrigin = menuRootOrigin,
                    actions = listOf(
                        BubbleAction("Archive", Icons.Default.Inventory2) {
                            LocalOverlays.selection?.onArchive?.invoke(PostSelection.selected.toList())
                        },
                        BubbleAction("Delete", Icons.Default.Delete) {
                            LocalOverlays.selection?.onDelete?.invoke(PostSelection.selected.toList())
                        }
                    ),
                    liquidGlass = liquidGlass, tint = tint, backdrop = backdrop,
                    onDismissRequest = { PostSelection.moreMenuOpen = false },
                    gapAboveAnchor = 6.dp
                )
            }
        }
    }

    // ── Popups ──
    LocalOverlays.confirm?.let { req ->
        val confirmScope = androidx.compose.runtime.rememberCoroutineScope()
        Box(Modifier.fillMaxSize().zIndex(10.65f)) {
            ConfirmPopup(
                title = req.titleForText?.invoke(req.text) ?: req.title, message = req.message, confirmLabel = req.labelForText?.invoke(req.text) ?: req.confirmLabel,
                liquidGlass = liquidGlass, tint = req.tint, backdrop = backdrop,
                destructive = req.destructive, busy = req.busy,
                input = req.input?.let { req.text }, onInputChange = { req.text = it }, inputPlaceholder = req.inputPlaceholder,
                onConfirm = {
                    req.busy = true
                    confirmScope.launch {
                        try { req.onConfirm() } finally {
                            req.busy = false
                            if (LocalOverlays.confirm === req) LocalOverlays.confirm = null
                        }
                    }
                },
                onDismiss = { if (!req.busy) LocalOverlays.confirm = null }
            )
        }
    }
    FadingPopupHost(LocalOverlays.editHistoryFor, Modifier.zIndex(10.6f)) { item ->
        EditHistoryPopup(item, liquidGlass, postTint, backdrop, onClose = { LocalOverlays.editHistoryFor = null })
    }
    FadingPopupHost(LocalOverlays.bookmarkFolderFor, Modifier.zIndex(10.6f)) { item ->
        BookmarkFolderPopup(item, liquidGlass, postTint, backdrop, onClose = { LocalOverlays.bookmarkFolderFor = null })
    }
    // (Selection mode's Save: every picked post at once.)
    FadingPopupHost(LocalOverlays.bookmarkFolderForMany, Modifier.zIndex(10.6f)) { items ->
        BookmarkFolderPopup(items, liquidGlass, tint, backdrop, onClose = { LocalOverlays.bookmarkFolderForMany = null; PostSelection.clear() })
    }
    // The shared tags' offer / download (see SharedTagDataset): asked for
    // wherever tags are used, shown over everything like the rest.
    if (com.mediaviewer.tagging.SharedTagDataset.prompt != null) Box(Modifier.fillMaxSize().zIndex(10.65f)) {
        SharedTagDatasetPopup(liquidGlass = liquidGlass, tint = tint, backdrop = backdrop)
    }
    FadingPopupHost(LocalOverlays.editFolder, Modifier.zIndex(10.6f)) { id ->
        FolderEditPopup(id, liquidGlass, tint, backdrop, onClose = { LocalOverlays.editFolder = null })
    }
    FadingPopupHost(LocalOverlays.followList, Modifier.zIndex(10.6f)) { req ->
        FollowListPopup(
            request = req, liquidGlass = liquidGlass, tint = tint, backdrop = backdrop,
            loadPage = { c -> viewModel.followListPage(req.did, req.followers, c) },
            setFollow = { a, on -> viewModel.followFromList(a, on) },
            selfDid = selfDidNow,
            // (The account opens on top of everything, the popup gone.)
            onOpen = { a -> LocalOverlays.followList = null; viewModel.openProfile(a) },
            onClose = { LocalOverlays.followList = null }
        )
    }
    FadingPopupHost(LocalOverlays.profileNoteFor, Modifier.zIndex(10.6f)) { author ->
        ProfileNotePopup(author, liquidGlass, tint, backdrop, onClose = { LocalOverlays.profileNoteFor = null })
    }
    FadingPopupHost(LocalOverlays.feedBuilder, Modifier.zIndex(10.6f)) { feed ->
        FeedBuilderPopup(
            initial = feed, myLists = myLists, liquidGlass = liquidGlass, tint = tint, backdrop = backdrop,
            onLoadLists = viewModel::loadListsForBuilder,
            onResolveList = viewModel::resolveListForBuilder,
            onResolveAccount = viewModel::resolveAccountForBuilder,
            onSuggestTags = viewModel::suggestTagsForBuilder,
            selfDid = selfDidNow,
            // From a profile's Home tab: a public feed, so public folders only.
            onlyPublicFolders = LocalOverlays.feedBuilderForHome,
            onSave = if (LocalOverlays.feedBuilderForHome) com.mediaviewer.util.ProfileHome::save else null,
            onDelete = if (LocalOverlays.feedBuilderForHome) ({ f -> com.mediaviewer.util.ProfileHome.delete(f.id) }) else null,
            title = if (LocalOverlays.feedBuilderForHome) "Home Tab Feed" else "Feed Creator",
            // A feed that's open while it's edited reloads with its new contents;
            // one made or edited from the Hub is (and stays) a Hub row.
            onSaved = { saved ->
                if (LocalOverlays.feedBuilderForHome) return@FeedBuilderPopup
                if (selectedFeed == saved.uri) viewModel.selectFeed(saved.uri)
                val asRow = com.mediaviewer.util.HubLayout.FEED_PREFIX + saved.id
                if (LocalOverlays.feedBuilderForHub || com.mediaviewer.util.HubLayout.rows.any { it.id == asRow }) {
                    com.mediaviewer.util.HubLayout.addFeed(saved.id, saved.name)
                }
            },
            onDeleted = { gone ->
                if (LocalOverlays.feedBuilderForHome) return@FeedBuilderPopup
                if (selectedFeed == gone.uri) viewModel.selectFeed(null)
                com.mediaviewer.util.HubLayout.remove(com.mediaviewer.util.HubLayout.FEED_PREFIX + gone.id)
            },
            onClose = { LocalOverlays.feedBuilder = null; LocalOverlays.feedBuilderForHub = false; LocalOverlays.feedBuilderForHome = false }
        )
    }

    // More → Edit: what editing does to a post, before the composer opens.
    FadingPopupHost(if (LocalOverlays.editWarningOpen) true else null, Modifier.zIndex(10.6f)) { _ ->
        LocalPopup(
            title = "Important", liquidGlass = liquidGlass, tint = postTint, backdrop = backdrop,
            onClose = { LocalOverlays.editWarningOpen = false }
        ) {
            Text(
                "Editing a post will retain it's place in your feed, it's comments, and it's reposts, but it's visible stats like the Likes, Reposts, and Comments counters will be reset.",
                color = Color.White.copy(alpha = 0.92f), fontSize = 14.sp, lineHeight = 20.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(horizontal = 6.dp).padding(bottom = 14.dp)
            )
            LocalPillButton(
                "Edit Post", liquidGlass, postTint,
                { LocalOverlays.editWarningOpen = false; viewModel.editCurrentPost() },
                Modifier.fillMaxWidth()
            )
        }
    }

    // Press and hold a chat in the DM list (supporters): pin / unpin.
    LocalOverlays.pinDmFor?.let { convo ->
        val isPinned = LocalData.isDmPinned(convo.convoId)
        ConfirmPopup(
            title = if (isPinned) "Unpin this chat?" else "Pin this chat?",
            message = if (isPinned) "\"${convo.member.displayName}\" goes back to the regular list."
                else "\"${convo.member.displayName}\" stays at the top of your DMs, under Pinned.",
            confirmLabel = if (isPinned) "Unpin" else "Pin",
            liquidGlass = liquidGlass, tint = tint, backdrop = backdrop,
            onConfirm = { LocalData.setDmPinned(convo.convoId, !isPinned); LocalOverlays.pinDmFor = null },
            onDismiss = { LocalOverlays.pinDmFor = null },
            preview = convo.member.avatarUrl,
            destructive = false,
            modifier = Modifier.zIndex(10.6f)
        )
    }

    // ── "Time's up" (when the Timer page itself isn't open) ──
    if (TimerEngine.ringing && LocalOverlays.launchApp != LaunchApp.TIMER) {
        val tap = rememberHapticTap()
        val shape = RoundedCornerShape(22.dp)
        Box(Modifier.fillMaxSize().zIndex(11.6f).padding(top = rememberTopCutoutClearance() + 8.dp), contentAlignment = Alignment.TopCenter) {
            Row(
                Modifier.clip(shape).background(lerp(Color(0xFF14101A), tint, 0.3f))
                    .border(1.dp, Color(0xFFFF4FA1).copy(alpha = 0.9f), shape)
                    .clickable { tap(); TimerEngine.reset() }
                    .padding(horizontal = 18.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("⏰", fontSize = 16.sp)
                Spacer(Modifier.width(8.dp))
                Text("Time's up — tap to stop", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }

    // ── Floating web pages: over everything, on every page ──
    BrowserPopoutLayer(tint, Modifier.zIndex(11.5f))
}
