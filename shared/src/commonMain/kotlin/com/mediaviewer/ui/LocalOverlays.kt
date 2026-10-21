package com.mediaviewer.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mediaviewer.model.AuthorInfo
import com.mediaviewer.model.BskyFeedInfo
import com.mediaviewer.model.MediaItem

/** Launchpad page two. */
enum class LaunchApp(val label: String) {
    CALENDAR("Calendar"), NOTES("Notes"), CALCULATOR("Calculator"), TIMER("Timer")
}

/** Answers to one poll, as counted from its replies. */
data class PollTally(
    /** Votes per letter ("A" → 12). */
    val counts: Map<String, Int> = emptyMap(),
    /** The letter the signed-in account voted, if any. */
    val myVote: String? = null
) {
    val total: Int get() = counts.values.sum()
}

/** See LocalOverlays.confirm. [onConfirm] runs with a spinner on the
 *  button; the popup closes when it's done. */
class ConfirmRequest(
    val title: String,
    val message: String,
    val confirmLabel: String,
    val tint: androidx.compose.ui.graphics.Color,
    val destructive: Boolean = true,
    val onConfirm: suspend () -> Unit
) {
    var busy by mutableStateOf(false)
}

/** One floating web page (Search → Web Browser → Popout). */
class BrowserPopout(val id: Long, val state: BrowserState) {
    var x by mutableStateOf(0.06f)
    var y by mutableStateOf(0.16f)
    var w by mutableStateOf(0.88f)
    var h by mutableStateOf(0.42f)
    /** Stacking order: the window touched last is in front. */
    var z by mutableStateOf(0f)
}

/**
 * The supporter features' popups and pages, and the few actions they need
 * from the ViewModel. Plain Compose state that any screen can set — the
 * popups themselves are drawn once, by [LocalOverlayHost] in AppRoot — so
 * none of this has to be threaded through every screen's parameters.
 */
object LocalOverlays {
    /** A post whose "Edited" status was tapped: its version history. */
    var editHistoryFor by mutableStateOf<MediaItem?>(null)
    /** A just-saved post being filed into a bookmark folder. */
    var bookmarkFolderFor by mutableStateOf<MediaItem?>(null)
    /** Selection mode's Save: several posts filed at once, in the order picked. */
    var bookmarkFolderForMany by mutableStateOf<List<MediaItem>?>(null)
    /** The profile whose note is being written. */
    var profileNoteFor by mutableStateOf<AuthorInfo?>(null)
    /** Hub → the "+" after the feeds: the Feed Builder (null = closed;
     *  blank id = a new feed). */
    var feedBuilder by mutableStateOf<com.mediaviewer.util.LocalFeed?>(null)
    /** The Feed Builder was opened from the Hub (Customize Hub → Add →
     *  Create Feed, or a feed row's edit button): what it saves is a Hub row. */
    var feedBuilderForHub by mutableStateOf(false)
    /** The Feed Builder was opened from your profile's Home tab: what it
     *  saves is a public Home tab feed (see ProfileHome), not this device's. */
    var feedBuilderForHome by mutableStateOf(false)
    /** Opens someone's profile (a Home tab row's picture). */
    var openProfile: ((AuthorInfo) -> Unit)? = null
    /** Settings → rounded square-grid tiles (for tiles drawn outside the
     *  pages that are handed it, like built feed rows). */
    var roundedGridTiles by mutableStateOf(false)
    /** Selection mode's actions (see SelectionBarButtons). */
    var selection: SelectionActions? = null
    /** Opens a blog / review shown in a built feed's row (as the Hub does). */
    var openMutualBlog: ((com.mediaviewer.model.FriendLeafletBlog) -> Unit)? = null
    var openMutualReview: ((com.mediaviewer.model.FriendPopfeedReview) -> Unit)? = null
    /** Built feed rows' contents, by key (MainViewModel.hubLists), for rows
     *  drawn outside the Hub (a profile's Home tab). */
    var feedRows: kotlinx.coroutines.flow.StateFlow<Map<String, com.mediaviewer.viewmodel.MainViewModel.HubListState>>? = null
    /** Fills a built feed's row (see MainViewModel.loadFeedRow): key, feed, more. */
    var loadFeedRow: ((String, com.mediaviewer.util.LocalFeed, Boolean) -> Unit)? = null
    /** Launchpad page two: the open app. */
    var launchApp by mutableStateOf<LaunchApp?>(null)
    /** A note to open straight away when Notes opens (a widget tap). */
    var openNoteId by mutableStateOf<String?>(null)
    /** The day (yyyymmdd) the Calendar should open on, once. */
    var openCalendarDay by mutableStateOf<Int?>(null)
    /** A feed dragged onto the Hub's DMs button, waiting to be shared. */
    var shareFeed by mutableStateOf<BskyFeedInfo?>(null)

    /** A DM chat held down: the pin / unpin confirmation. */
    var pinDmFor by mutableStateOf<com.mediaviewer.model.DmConversation?>(null)

    /** More → Edit: the "Important" notice shown before the composer opens. */
    var editWarningOpen by mutableStateOf(false)

    /** An "are you sure?" asked from somewhere that can't show it over the
     *  whole app itself (the emoji menu, say): shown by LocalOverlayHost,
     *  like every other popup — over the blurred app, its glass blurring
     *  what's behind it. */
    var confirm by mutableStateOf<ConfirmRequest?>(null)

    /** One of the popups is up: the app behind it blurs. */
    val popupOpen: Boolean
        get() = editWarningOpen || editHistoryFor != null || bookmarkFolderFor != null || bookmarkFolderForMany != null || profileNoteFor != null || feedBuilder != null || pinDmFor != null ||
            confirm != null

    /** Floating web pages, drawn over everything. */
    val popouts = mutableStateListOf<BrowserPopout>()
    private var nextPopoutId = 1L
    private var topZ = 0f

    fun bringToFront(p: BrowserPopout) { topZ += 1f; p.z = topZ }

    fun popOut(url: String) {
        val n = popouts.size
        val state = BrowserState(url)
        popouts.add(BrowserPopout(nextPopoutId++, state).also {
            // Each new window opens a little lower so they don't stack exactly.
            it.y = (0.14f + 0.05f * (n % 5)).coerceAtMost(0.5f)
            it.x = 0.06f + 0.02f * (n % 3)
            bringToFront(it)
        })
    }

    fun closePopout(p: BrowserPopout) {
        popouts.remove(p)
        p.state.dispose()
    }

    /** Closes the popups (not the floating web pages — those stay until
     *  their own X is tapped). */
    fun closeAll() {
        editHistoryFor = null
        bookmarkFolderFor = null
        bookmarkFolderForMany = null
        profileNoteFor = null
        feedBuilder = null
        editWarningOpen = false
        pinDmFor = null
        launchApp = null
        shareFeed = null
    }

    // ── Actions supplied by AppRoot (ViewModel calls) ──
    var onEditCurrentPost: (() -> Unit)? = null
    /** The Tags page's hand edits on the post on screen (see
     *  MainViewModel.editCurrentPostTag): old null = add, new null = delete. */
    var editPostTag: ((oldTag: String?, newTag: String?) -> Unit)? = null
    /** Loads the current feed again from the top. */
    var reloadFeed: (() -> Unit)? = null
    /** Turns "I Hate Fun" (the NSFW blur) on or off. */
    var setHateFunBlur: ((Boolean) -> Unit)? = null
    /** Opens a web address in Search › Web Browser. */
    var openInBrowser: ((String) -> Unit)? = null
    /** Whether the post on screen's tags can be edited (Bluesky posts,
     *  where the tagger's dataset exists). */
    var canEditTags by mutableStateOf(false)
    /** Your own Bluesky DID (which comments are yours, so they can be deleted). */
    var selfDid by mutableStateOf("")
    /** Deletes one of your comments (after its confirmation). */
    var deleteComment: ((com.mediaviewer.model.CommentItem) -> Unit)? = null
    var pollTally: (suspend (postUri: String) -> PollTally?)? = null
    var pollVote: ((item: MediaItem, letter: String, onDone: (Boolean) -> Unit) -> Unit)? = null
    /** Re-counts a chat's streak from its history. */
    var scanDmStreak: ((convoId: String, onDone: () -> Unit) -> Unit)? = null
    /** A feed/list link in a DM → its card. */
    var resolveFeedCard: (suspend (actor: String, kind: String, rkey: String) -> com.mediaviewer.model.ProfileListEntry?)? = null
    var openSharedFeed: ((com.mediaviewer.model.ProfileListEntry) -> Unit)? = null
    /** Archive (supporters): archive the post on screen, open the
     *  Archived page, put an archived post back, delete one for good. */
    var archiveCurrentPost: (() -> Unit)? = null
    var openArchive: (() -> Unit)? = null
    /** A profile's public folder tapped (Collections → Folders). */
    var openPublicFolder: ((com.mediaviewer.util.PublicFolders.Folder) -> Unit)? = null
    var restoreArchivedPost: (() -> Unit)? = null
    var deleteArchivedPost: (() -> Unit)? = null
    var archiveBusy by mutableStateOf(false)
    /** Composer: start / drop the attached video's upload. */
    var prepareVideoUpload: ((video: com.mediaviewer.platform.PlatformUri, thumbnail: com.mediaviewer.platform.PlatformUri?) -> Unit)? = null
    var cancelVideoUpload: (() -> Unit)? = null
    /** A shared post tapped in a chat (the message's id). */
    var openDmSharedPost: ((messageId: String) -> Unit)? = null
    var addSharedFeed: ((com.mediaviewer.model.ProfileListEntry) -> Unit)? = null
    /** Feeds already in your feeds list (a shared feed shows "Added"). */
    var savedFeedUris by mutableStateOf<Set<String>>(emptySet())
    /** Saved Posts → the bookmark folder being shown (null = all). */
    var bookmarkFolderId by mutableStateOf<String?>(null)
    var onShowBookmarkFolder: ((String?) -> Unit)? = null
    /** The tagging status pill tapped: Search › Tagged, over the timeline. */
    var onOpenTagged: (() -> Unit)? = null
    /** The tagging page, following what's being tagged (the timeline's tagging status). */
    var onOpenTaggingQueue: (() -> Unit)? = null
    /** The tagging status's words while it's showing (see taggingStatusLabelFor),
     *  for the pages other than the Timeline. */
    var taggingStatusLabel by mutableStateOf<String?>(null)
    /** A profile's picture or banner saved (see MainViewModel.downloadProfileMedia). */
    var downloadProfileMedia: ((did: String, imageUrl: String?, videoCid: String?, kind: String) -> Unit)? = null
    /** Settings › Reduced Animations, for what's drawn outside the screens that are handed it. */
    var reducedAnimations by mutableStateOf(false)
    /** See MainViewModel.placeBookmark. */
    var onPlaceBookmark: ((com.mediaviewer.model.MediaItem, Boolean) -> Unit)? = null
}
