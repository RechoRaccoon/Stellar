package com.mediaviewer.ui

import androidx.compose.foundation.background
import com.mediaviewer.ui.theme.AvatarShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import com.mediaviewer.ui.theme.CircleShape
import com.mediaviewer.ui.theme.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import com.mediaviewer.i18n.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.drawBehind
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mediaviewer.model.AppMode
import com.mediaviewer.model.PROFILE_FEED_HANDLE_PREFIX
import com.mediaviewer.model.isProfileFeed
import com.mediaviewer.model.isSpecialFeed
import com.mediaviewer.model.BskyFeedInfo
import com.mediaviewer.model.MediaItem
import com.mediaviewer.ui.theme.*
import com.mediaviewer.util.rememberHapticTap
import com.mediaviewer.viewmodel.MainViewModel
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/**
 * The feed's Explore mode (the grid — pinch in on a post, or the Hub's
 * Explore button). Laid out like a profile page:
 * the feeds as a row of profile-style main tabs, the content-type sub-tabs
 * under them, the posts in the profile's own layouts (masonry / square
 * grid / text list / video lists), and the profile's interaction bar with
 * Refresh + Grid layout. Everything wears the signed-in user's own profile
 * color. Pinch out anywhere to go back to the post you were on.
 */
/**
 * Where the grid was, kept across the grid being closed (pinch out / opening
 * a post) and reopened — like a profile remembers its scroll. Saved as "which
 * post sat in the middle of the screen, and how far off-centre", so it lands
 * exactly back in place, whatever the layout.
 */
private object GridScrollMemory {
    /** The grid's own scroll state, kept alive while the grid is closed.
     *  A staggered grid only knows which column each post sat in while
     *  its state lives; recreating it and jumping to a post re-deals the
     *  columns from that post onward, which is what flipped a post you'd
     *  tapped from one side of the grid to the other on the way back. */
    var state: LazyStaggeredGridState? = null
    var stateScope: String? = null
    var scope: String? = null
    var anchorId: String? = null
    var anchorDelta: Int = 0
    /** The feed post the grid was left for; if the feed has since moved to a
     *  different post, the grid centres that one instead. */
    var feedIndex: Int = -1
}

private const val POST_KEY_PREFIX = "p:"
private const val GRID_HEADER_KEY = "grid_header"
/** Full-line items above the first post (the feed/content-type header). */
private const val GRID_HEADER_ITEMS = 1

/** The post nearest the viewport's middle, and how far its centre is from it. */
private fun middleAnchor(state: LazyStaggeredGridState): Pair<String, Int>? {
    val info = state.layoutInfo
    val mid = (info.viewportStartOffset + info.viewportEndOffset) / 2
    val best = info.visibleItemsInfo
        .filter { (it.key as? String)?.startsWith(POST_KEY_PREFIX) == true }
        .minByOrNull { kotlin.math.abs(it.offset.y + it.size.height / 2 - mid) } ?: return null
    return (best.key as String).removePrefix(POST_KEY_PREFIX) to (best.offset.y + best.size.height / 2 - mid)
}

/** Scrolls so post [index] sits [delta] px off the viewport's middle. */
private suspend fun centreOn(state: LazyStaggeredGridState, index: Int, delta: Int) {
    state.scrollToItem(index)
    val info = state.layoutInfo
    val it = info.visibleItemsInfo.firstOrNull { v -> v.index == index } ?: return
    val mid = (info.viewportStartOffset + info.viewportEndOffset) / 2
    val shift = (it.offset.y + it.size.height / 2 - mid) - delta
    if (shift != 0) state.scrollBy(shift.toFloat())
}

/**
 * The feed's Explore mode (the grid — pinch in on a post, or the Hub's
 * Explore button). Laid out like a profile page:
 * the feeds as a row of profile-style main tabs, the content-type sub-tabs
 * under them, the posts in the profile's own layouts (masonry / square
 * grid / text list / video lists), and the profile's interaction bar with
 * Refresh + Grid layout. Everything wears the signed-in user's own profile
 * color. Pinch out anywhere to go back to the post you were on.
 *
 * The posts are a LazyVerticalStaggeredGrid, so only on-screen tiles exist
 * (the profile's LazyColumn masonry composes every loaded tile at once,
 * which crawled with a whole feed loaded).
 */
@Composable
fun GridScreen(
    items: List<MediaItem>,
    currentIndex: Int,
    appMode: AppMode,
    availableFeeds: List<BskyFeedInfo>,
    selectedFeedUri: String?,
    authorFeedState: MainViewModel.AuthorFeedSavedState?,
    e621SearchTags: String,
    liquidGlass: Boolean = false,
    /** A Home row opened in Explore lays out by the feed creator's picked
     *  style (see openHomeRowExplore); null keeps the grid's own layout. */
    exploreFeedStyle: String? = null,
    /** The grid's Layout button drops the forced style (see onGrid below). */
    onClearExploreFeedStyle: () -> Unit = {},
    // onItemClick also reports which sub-image within that post's
    // mediaGroup was tapped, so the pager opens on it. -1 means "leave the
    // sub-image index as-is" (the pinch-out-to-return gesture).
    onItemClick: (postIndex: Int, subImageIndex: Int) -> Unit,
    onLoadMore: () -> Unit,
    onSelectFeed: (String?) -> Unit,
    onSearchE621: (String) -> Unit,
    onRefresh: () -> Unit,
    /** The signed-in user's avatar — the grid's UI wears its color. */
    selfAvatarUrl: String? = null,
    isLoading: Boolean = false,
    roundedGridTiles: Boolean = false,
    reducedAnimations: Boolean = false,
    /** Pulled down while already scrolled to the very top. */
    onSwipeDown: () -> Unit = {},
    /** Pinched in (fingers closing): leaves a feed opened from a profile. */
    onPinchIn: () -> Unit = {},
    /** The Back button at the top left (the same one profiles have). */
    onBack: (() -> Unit)? = null
) {
    val tap = rememberHapticTap()
    var localTags  by remember(e621SearchTags) { mutableStateOf(e621SearchTags) }
    val tint = rememberSelfTint(selfAvatarUrl, NeutralGlassTint)
    var kind by remember { mutableStateOf(PostKindFilter.ALL) }
    val gridScreen = "feed_grid"
    // Landscape: the slider's column count, and two layouts instead of three
    // (see Landscape and effectiveGridMode).
    val wideDp = rememberLandscapeWidthDp()
    // A folder laid out as tall (vertical) or wide (horizontal) videos: its
    // posts are drawn that way, whatever the sub-tab (see Folder Layout).
    val folderLayout = run {
        val a = authorFeedState?.author
        val rkey = a?.takeIf { it.isProfileFeed() }?.handle?.substringAfter("stellar-folder://", "")?.takeIf { it.isNotBlank() }
        val openId = LocalOverlays.bookmarkFolderId
        when {
            openId != null -> com.mediaviewer.util.LocalData.bookmarkFolders.firstOrNull { it.id == openId }?.layout
            a != null && rkey != null -> com.mediaviewer.util.PublicFolders.byAccount[a.did]?.firstOrNull { it.rkey == rkey }?.layout
            else -> null
        }
    }
    // A Home row opened in Explore lays out by the feed creator's picked
    // style, the way a folder lays out by its own layout above; anything
    // else keeps the grid's own layout.
    val exploreLayout = when (exploreFeedStyle) {
        "tiktok" -> PostKindFilter.VERTICAL_VIDEOS to null
        "youtube" -> PostKindFilter.HORIZONTAL_VIDEOS to null
        "square" -> PostKindFilter.ALL to 2
        "natural" -> PostKindFilter.ALL to 0
        else -> null
    }
    val layoutKind = when (folderLayout) {
        "vertical" -> PostKindFilter.VERTICAL_VIDEOS
        "horizontal" -> PostKindFilter.HORIZONTAL_VIDEOS
        else -> exploreLayout?.first ?: kind
    }
    val gridMode = effectiveGridMode(layoutKind, exploreLayout?.second ?: resultsGridMode(gridScreen, layoutKind), wideDp > 0)
    val spec = resultsLayoutSpec(layoutKind, gridMode, roundedGridTiles, wideDp)
    // Saved Posts / From Friends / History all use your own DID, so the
    // name is part of the key too — switching between them starts fresh.
    val memoryScope = "$appMode|${authorFeedState?.author?.let { it.did + "|" + it.handle + "|" + it.displayName } ?: selectedFeedUri}"
    val freshGridState = rememberLazyStaggeredGridState()
    val keptState = remember(memoryScope) {
        GridScrollMemory.state?.takeIf { GridScrollMemory.stateScope == memoryScope } != null
    }
    val gridState = remember(memoryScope) {
        GridScrollMemory.state?.takeIf { GridScrollMemory.stateScope == memoryScope }
            ?: freshGridState.also { GridScrollMemory.state = it; GridScrollMemory.stateScope = memoryScope }
    }
    val gridScope = androidx.compose.runtime.rememberCoroutineScope()
    // Shows as soon as the feed + content-type rows (the header item) have
    // scrolled off the top.
    val showScrollTop by remember(gridState) { androidx.compose.runtime.derivedStateOf { gridState.firstVisibleItemIndex >= GRID_HEADER_ITEMS } }
    // Pull-down-at-top → Hub. Tracked straight off the finger (see the
    // pointerInput on the grid below) rather than from nested-scroll
    // leftovers, which the grid's own overscroll stretch could swallow:
    // once the grid is at the very top, pulling down a further ~88dp — or
    // a quick flick down of ~36dp — opens the Hub. One trigger per gesture.
    val latestOnSwipeDown by rememberUpdatedState(onSwipeDown)
    val latestOnPinchIn by rememberUpdatedState(onPinchIn)
    val density = androidx.compose.ui.platform.LocalDensity.current
    val pullThresholdPx = with(density) { 88.dp.toPx() }
    val flickThresholdPx = with(density) { 36.dp.toPx() }
    // A swipeable multi-image tile reports which image it's showing just
    // before its click lands; carried into onItemClick as the sub-image.
    val pendingSeed = remember { arrayOfNulls<Pair<String, Int>>(1) }
    val latestItems by rememberUpdatedState(items)
    val latestCurrentIndex by rememberUpdatedState(currentIndex)

    // Posts shown under the current sub-tab. Deduped by id: lazy layouts
    // crash on duplicate keys, and feeds can repeat a post.
    val matched = remember(items, kind) { items.filter { kind.matches(it) }.distinctBy { it.id } }
    val indexById = remember(matched) { HashMap<String, Int>(matched.size * 2).also { m -> matched.forEachIndexed { i, it -> m[it.id] = i } } }

    // ── Keeping its place ─────────────────────────────────────────────────
    // (a) across the grid closing and reopening (see GridScrollMemory);
    // (b) across a Grid-layout or sub-tab switch: the post in the middle
    //     before the switch is put back in the middle after it.
    var exitFeedIndex by remember { mutableIntStateOf(currentIndex) }
    var pendingAnchor by remember { mutableStateOf<Pair<String, Int>?>(null) }
    var restored by remember { mutableStateOf(false) }
    LaunchedEffect(matched.isNotEmpty()) {
        if (restored || matched.isEmpty()) return@LaunchedEffect
        restored = true
        val mem = GridScrollMemory
        val sameSpot = mem.scope == memoryScope && mem.feedIndex == currentIndex && mem.anchorId != null
        // Same grid, same post: the kept state is still exactly where it was.
        if (sameSpot && keptState) return@LaunchedEffect
        if (sameSpot) {
            val idx = indexById[mem.anchorId!!]
            if (idx != null) { centreOn(gridState, idx + GRID_HEADER_ITEMS, mem.anchorDelta); return@LaunchedEffect }
        }
        val current = items.getOrNull(currentIndex)?.id?.let { indexById[it] }
        // The first post of a feed: stay at the very top, header showing.
        if (current != null && current > 0) centreOn(gridState, current + GRID_HEADER_ITEMS, 0)
    }
    LaunchedEffect(kind, gridMode) {
        val a = pendingAnchor ?: return@LaunchedEffect
        pendingAnchor = null
        val idx = indexById[a.first]
        if (idx != null) centreOn(gridState, idx + GRID_HEADER_ITEMS, a.second) else gridState.scrollToItem(0)
    }
    // Picked posts don't outlive the page they were picked on.
    DisposableEffect(Unit) { onDispose { PostSelection.clear() } }
    // A profile's own posts (opened from it), Saved Posts, and the Explore
    // feeds can be picked; From Friends and History can't.
    val savedPostsPage = appMode == AppMode.BLUESKY && authorFeedState?.author?.let { it.isSpecialFeed() && it.displayName == "Saved Posts" } == true
    // One of your own folders, opened from your profile: it works like
    // that folder in Saved Posts (sort, edit, pick and drag its posts).
    val ownFolderPage = appMode == AppMode.BLUESKY && LocalOverlays.bookmarkFolderId != null &&
        authorFeedState?.author?.let { it.isProfileFeed() && it.handle.contains("stellar-folder://") } == true
    val folderTools = savedPostsPage || ownFolderPage
    // (Anyone's folder opened from their profile's Collections too.)
    val folderPage = authorFeedState?.author?.let { it.isProfileFeed() && it.handle.contains("stellar-folder://") } == true
    // Explore is the feed view with no author page (authorFeedState null);
    // Saved Posts, From Friends, History, and the archive all ride on
    // pseudo-authors, so they keep their own rules above.
    val selectablePage = folderTools || (appMode == AppMode.BLUESKY && (folderPage || authorFeedState == null || authorFeedState?.author?.let { !it.isSpecialFeed() } == true))
    DisposableEffect(savedPostsPage) {
        LocalOverlays.savedPostsShowing = savedPostsPage
        onDispose { LocalOverlays.savedPostsShowing = false }
    }
    var savedSortOpen by remember { mutableStateOf(false) }
    var savedSortBounds by remember { mutableStateOf<Pair<androidx.compose.ui.geometry.Offset, androidx.compose.ui.unit.IntSize>?>(null) }
    var savedSortRoot by remember { mutableStateOf<androidx.compose.ui.geometry.Offset?>(null) }
    // Saved Posts, a folder open, in its own order: a post held and moved
    // is dragged onto the place of whichever post it's over (see
    // PostSelection.reorder).
    val gridOrigin = remember { arrayOf(androidx.compose.ui.geometry.Offset.Zero) }
    val reorderable = folderTools && LocalOverlays.bookmarkFolderId != null && LocalOverlays.savedSort == 4
    androidx.compose.runtime.DisposableEffect(reorderable) {
        PostSelection.reorder = if (!reorderable) null else { item, root ->
            val p = root - gridOrigin[0]
            val over = gridState.layoutInfo.visibleItemsInfo.firstOrNull { info ->
                val o = info.offset; val sz = info.size
                p.x >= o.x && p.x < o.x + sz.width && p.y >= o.y && p.y < o.y + sz.height
            }
            val targetId = (over?.key as? String)?.removePrefix(POST_KEY_PREFIX)
            val target = targetId?.let { id -> latestItems.firstOrNull { it.id == id } }
            if (target != null && target.id != item.id) LocalOverlays.moveSavedPost?.invoke(item, target)
        }
        onDispose { PostSelection.reorder = null }
    }
    DisposableEffect(memoryScope) {
        onDispose {
            val a = middleAnchor(gridState)
            GridScrollMemory.scope = memoryScope
            GridScrollMemory.anchorId = a?.first
            GridScrollMemory.anchorDelta = a?.second ?: 0
            GridScrollMemory.feedIndex = exitFeedIndex
        }
    }

    // Paging: ask for more once the last dozen tiles come into view (the
    // old masonry asked on every composition, so it kept loading forever).
    LaunchedEffect(gridState, isLoading, matched.size) {
        if (isLoading) return@LaunchedEffect
        // A sub-tab with nothing matching on the loaded pages yet: keep paging.
        if (matched.isEmpty() && items.isNotEmpty()) { onLoadMore(); return@LaunchedEffect }
        snapshotFlow {
            val info = gridState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            info.totalItemsCount > 0 && last >= info.totalItemsCount - 30
        }.distinctUntilChanged().filter { it }.collect { onLoadMore() }
    }

    // Live glass backdrop: this page's background AND its scrolling posts,
    // recorded so the interaction bar blurs whatever is really behind it.
    // The bar itself sits outside the recorded box (a glass panel must never
    // draw inside the layer it samples).
    val backdropLayer = androidx.compose.ui.graphics.rememberGraphicsLayer()
    var backdropOrigin by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    val backdrop = remember(liquidGlass, backdropLayer) {
        if (liquidGlass) GlassBackdrop(backdropLayer) { backdropOrigin } else null
    }

    Box(Modifier.fillMaxSize()) {
    Box(
        Modifier.fillMaxSize()
            .onGloballyPositioned { backdropOrigin = it.positionInRoot() }
            .drawWithContent {
                if (liquidGlass && GradientGlass.recording) backdropLayer.record { this@drawWithContent.drawContent() }
                drawContent()
            }
            .then(if (liquidGlass) Modifier else Modifier.background(OledBlack))
    ) {
        if (liquidGlass) SpaceSky(tint, Modifier.matchParentSize())
        // The feed/content-type rows are the grid's first item now, so they
        // scroll away with the posts instead of staying pinned in place.
        val topClearance = rememberTopCutoutClearance()
        // The feed row and the post-type filters: the grid's first item in
        // portrait; landscape's side list instead (see SideList).
        val sideways = wideDp > 0
        // Saved Posts, From Friends, a folder…: the page's name sits level
        // with the Back button (centered on it) instead of on a row below it.
        val titleBesideBack = onBack != null && !sideways &&
            authorFeedState?.author?.let { appMode == AppMode.BLUESKY && it.isSpecialFeed() } == true
        // An opened feed (not a folder, list or special page) that isn't in
        // your feeds yet can be added from its own page.
        val openedFeedUri = authorFeedState?.author?.takeIf { appMode == AppMode.BLUESKY && it.isProfileFeed() }
            ?.handle?.removePrefix(PROFILE_FEED_HANDLE_PREFIX)
            ?.takeIf { it.contains("/app.bsky.feed.generator/") && availableFeeds.none { f -> f.uri == it } }
        val openedFeedName = authorFeedState?.author?.displayName.orEmpty().ifBlank { "Feed" }
        @Composable
        fun HeaderRows() {
            // ── Feeds (profile-style main tabs) / e621 tag search ─────────────
            val specialFeed = authorFeedState?.author?.takeIf { appMode == AppMode.BLUESKY && it.isSpecialFeed() }
            if (specialFeed != null) {
                // Saved Posts / From Friends: their name, big and
                // centered, in place of the feed selector.
                if (titleBesideBack) {
                    Box(Modifier.fillMaxWidth().height(BACK_BUBBLE_SIZE).padding(horizontal = 60.dp), contentAlignment = Alignment.Center) {
                        Text(
                            specialFeed.displayName,
                            color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                } else {
                    if (!LocalSideList.current) HorizontalDivider(color = Color.White.copy(alpha = 0.08f), thickness = 0.5.dp)
                    Text(
                        specialFeed.displayName,
                        color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)
                    )
                }
                // Saved Posts (supporters): Bookmarks (Bluesky's own,
                // always first) and a bubble per bookmark folder, like the
                // feed row — tap one to see just that folder.
                val folders = com.mediaviewer.util.LocalData.bookmarkFolders
                if (specialFeed.displayName == "Saved Posts" && com.mediaviewer.util.Supporter.active && folders.isNotEmpty()) {
                    val openFolder = LocalOverlays.bookmarkFolderId
                    if (!LocalSideList.current) HorizontalDivider(color = Color.White.copy(alpha = 0.08f), thickness = 0.5.dp)
                    ProfileStyleTabRow(
                        labels = listOf("Bookmarks") + folders.map { it.name },
                        selectedIndex = folders.indexOfFirst { it.id == openFolder } + 1,
                        liquidGlass = liquidGlass, tint = tint,
                        // Each folder's lock (private, encrypted) or world
                        // (public, on your profile), shown on its tab; it's
                        // changed (with its name, cover…) from the bar's Edit.
                        trailingIcons = listOf<androidx.compose.ui.graphics.vector.ImageVector?>(null) + folders.map {
                            if (it.public) Icons.Default.Public else Icons.Default.Lock
                        }
                    ) { i ->
                        LocalOverlays.onShowBookmarkFolder?.invoke(if (i == 0) null else folders.getOrNull(i - 1)?.id)
                    }
                }
            } else if (appMode == AppMode.BLUESKY) {
                val saved = authorFeedState
                val labels = buildList {
                    if (saved != null) add(saved.author.displayName.ifBlank { "@" + saved.author.handle })
                    availableFeeds.forEach { add(it.displayName) }
                }
                val offset = if (saved != null) 1 else 0
                val selected = if (saved != null) 0 else availableFeeds.indexOfFirst { it.uri == selectedFeedUri }.let { if (it >= 0) it + offset else -1 }
                if (!LocalSideList.current) HorizontalDivider(color = Color.White.copy(alpha = 0.08f), thickness = 0.5.dp)
                Box(Modifier.fillMaxWidth().tipAnchor("ex.feeds")) {
                // Each feed's own picture before its name, like the Hub's feed row.
                val icons = buildList {
                    if (saved != null) add(saved.author.avatarUrl)
                    availableFeeds.forEach { add(it.avatarUrl) }
                }
                ProfileStyleTabRow(labels = labels, selectedIndex = selected, liquidGlass = liquidGlass, tint = tint, icons = icons) { i ->
                    if (i >= offset) availableFeeds.getOrNull(i - offset)?.let { onSelectFeed(it.uri) }
                }
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = localTags, onValueChange = { localTags = it },
                        placeholder = { Text("Search tags…", color = DimGray, fontSize = 13.sp) },
                        singleLine = true,
                        textStyle = LocalTextStyle.current.copy(fontSize = 13.sp, color = Color.White),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = tint.copy(0.6f), unfocusedBorderColor = Color.White.copy(0.1f),
                            cursorColor = Color.White, focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                            focusedTextColor = Color.White, unfocusedTextColor = Color.White
                        ),
                        modifier = Modifier.weight(1f).height(54.dp)
                    )
                    Button(
                        onClick = { tap(); onSearchE621(localTags) },
                        colors = ButtonDefaults.buttonColors(containerColor = tint.copy(0.35f), contentColor = Color.White),
                        contentPadding = PaddingValues(horizontal = 14.dp),
                        modifier = Modifier.height(54.dp)
                    ) { Text("Go", fontSize = 13.sp) }
                }
            }
            if (LocalSideList.current) SideListDivider(tint) else HorizontalDivider(color = Color.White.copy(alpha = 0.08f), thickness = 0.5.dp)
            // ── Content-type sub-tabs ──────────────────────────────────────
            if (items.isNotEmpty()) {
                Box(Modifier.fillMaxWidth().tipAnchor("ex.kinds")) {
                PostKindSubTabRow(items, kind, liquidGlass, tint) { newKind ->
                    if (newKind != kind) {
                        pendingAnchor = middleAnchor(gridState)
                        kind = newKind
                    }
                }
                }
            }
            // Landscape: Add Feed, under the sub filters past another divider.
            if (LocalSideList.current && openedFeedUri != null) {
                SideListDivider(tint)
                SideListButton("Add Feed", selected = false, liquidGlass = liquidGlass, tint = tint, small = true) {
                    LocalOverlays.addOpenedFeed?.invoke(openedFeedUri, openedFeedName)
                }
            }
        }
        Row(Modifier.fillMaxSize()) {
        if (sideways) {
            LandscapeSideList(
                tint = tint, liquidGlass = liquidGlass, backLabel = "Back", onBack = onSwipeDown,
                footer = { GridSizeSlider(tint) }
            ) { HeaderRows() }
            SideListEdge(tint)
        }
        LazyVerticalStaggeredGrid(
            columns = StaggeredGridCells.Fixed(spec.lanes),
            state = gridState,
            contentPadding = PaddingValues(start = spec.horizontalPadding, end = spec.horizontalPadding, top = 0.dp, bottom = 96.dp),
            horizontalArrangement = Arrangement.spacedBy(spec.spacing),
            verticalItemSpacing = spec.spacing,
            modifier = Modifier
                .onGloballyPositioned { gridOrigin[0] = it.positionInRoot() }
                .weight(1f).fillMaxHeight()
                // Pinch OUT (fingers spreading apart) — the opposite of
                // the pinch-in that opened the grid — goes back to the
                // post you were on. Watched at the Initial pass and only
                // consumed once it fires, so one-finger scrolling is
                // untouched.
                .pointerInput(gridState) {
                    awaitEachGesture {
                        var startDist = -1f
                        // Pull-to-Hub: where the finger was when the grid
                        // was (or became) scrolled all the way up.
                        var pullBaseY = Float.NaN
                        var lastY = 0f
                        var lastT = 0L
                        var velocity = 0f   // px per ms, + = downward
                        var multiTouch = false
                        var pullFired = false
                        // Same rule as the Hub's swipe into the feed: only a
                        // swipe that STARTS with the grid already scrolled
                        // all the way up goes back to the Hub — scrolling up
                        // to the top never overshoots into it; it takes a
                        // second swipe.
                        var armed: Boolean? = null
                        // A sideways swipe (the folder or feed row scrolling)
                        // never closes the grid, whatever it drifts down by.
                        var startX = Float.NaN
                        var startY = Float.NaN
                        var sideways = false
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (armed == null) armed = !gridState.canScrollBackward
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.isEmpty()) {
                                // Released: a quick flick down at the top counts too.
                                if (armed == true && !pullFired && !multiTouch && !pullBaseY.isNaN() &&
                                    lastY - pullBaseY >= flickThresholdPx && velocity > 0.9f
                                ) {
                                    latestOnSwipeDown()
                                }
                                break
                            }
                            if (pressed.size == 1 && !multiTouch) {
                                val ch = pressed[0]
                                val y = ch.position.y
                                if (startX.isNaN()) { startX = ch.position.x; startY = y }
                                val dx = kotlin.math.abs(ch.position.x - startX)
                                val dy = kotlin.math.abs(y - startY)
                                if (!sideways && dx > viewConfiguration.touchSlop && dx > dy) sideways = true
                                if (sideways) armed = false
                                val t = ch.uptimeMillis
                                if (lastT != 0L && t > lastT) {
                                    val v = (y - lastY) / (t - lastT)
                                    velocity = velocity * 0.4f + v * 0.6f
                                }
                                lastY = y; lastT = t
                                if (armed == true && !gridState.canScrollBackward) {
                                    if (pullBaseY.isNaN() || y < pullBaseY) pullBaseY = y
                                    if (!pullFired && y - pullBaseY >= pullThresholdPx) {
                                        pullFired = true
                                        latestOnSwipeDown()
                                    }
                                } else {
                                    pullBaseY = Float.NaN
                                }
                            }
                            if (pressed.size < 2) { startDist = -1f; continue }
                            multiTouch = true
                            val dist = (pressed[0].position - pressed[1].position).getDistance()
                            if (startDist < 0f) { startDist = dist; continue }
                            pressed.forEach { it.consume() }
                            if (dist > startDist * 1.4f) {
                                exitFeedIndex = latestCurrentIndex
                                onItemClick(latestCurrentIndex, -1)
                                break
                            }
                            if (dist < startDist * 0.7f) {
                                latestOnPinchIn()
                                break
                            }
                        }
                    }
                }
        ) {
            item(key = GRID_HEADER_KEY, span = StaggeredGridItemSpan.FullLine, contentType = "grid_header") {
                // Undo the grid's side padding so the tab rows run edge to edge.
                Column(Modifier.fillMaxWidth().layout { measurable, constraints ->
                    val extra = (spec.horizontalPadding * 2).roundToPx()
                    val placeable = measurable.measure(constraints.copy(
                        minWidth = constraints.maxWidth + extra, maxWidth = constraints.maxWidth + extra
                    ))
                    layout(constraints.maxWidth, placeable.height) { placeable.place(-extra / 2, 0) }
                }) {
                    // (Room for the fixed Back button above the first row.)
                    Spacer(Modifier.height(topClearance + if (onBack != null && !titleBesideBack && wideDp <= 0) 44.dp else 0.dp))
                    if (!sideways) HeaderRows()
                    Spacer(Modifier.height(4.dp))
                }
            }
            if (items.isEmpty()) {
                if (isLoading) {
                    // A feed that's still loading (e.g. one just switched to)
                    // shows placeholder tiles instead of the previous
                    // feed's posts.
                    items(count = 18, key = { i -> "grid_placeholder_$i" }, contentType = { "grid_placeholder" }) { i ->
                        GridPlaceholderTile(i, spec.lanes, tint, roundedGridTiles || gridMode != 2)
                    }
                } else {
                    item(key = "grid_empty", span = StaggeredGridItemSpan.FullLine) {
                        Box(Modifier.fillMaxWidth().padding(vertical = 160.dp), contentAlignment = Alignment.Center) {
                            Text("Nothing here yet", color = DimGray, fontSize = 13.sp)
                        }
                    }
                }
            }
            items(
                count = matched.size,
                key = { i -> POST_KEY_PREFIX + matched[i].id },
                contentType = { gridMode * 10 + layoutKind.ordinal }
            ) { i ->
                val item = matched[i]
                val tile: @Composable () -> Unit = {
                    // (Only profile pages, Saved Posts, and Explore can be picked.)
                    androidx.compose.runtime.CompositionLocalProvider(LocalPostSelectable provides selectablePage) {
                    PostResultTile(
                        item = item, filter = layoutKind, gridMode = gridMode, tint = tint,
                        liquidGlass = liquidGlass, roundedGridTiles = roundedGridTiles,
                        onSeedSubImageIndex = { id, page -> pendingSeed[0] = id to page },
                        videoCards = wideDp > 0,
                        onClick = {
                            // (Picking posts: a tap anywhere else on a tile picks it too.)
                            if (PostSelection.active) { PostSelection.toggle(item); return@PostResultTile }
                            val idx = latestItems.indexOfFirst { it.id == item.id }
                            if (idx >= 0) {
                                val seed = pendingSeed[0]?.takeIf { it.first == item.id }?.second ?: 0
                                pendingSeed[0] = null
                                exitFeedIndex = idx
                                onItemClick(idx, seed)
                            }
                        }
                    )
                    }
                }
                // From Friends: who sent it, and what they said, on the tile.
                // A quote repost shows its quoter and their words the same way.
                val sender = item.sentByAuthor?.takeIf { !item.sentByIsRepost || item.sentByMessage.isNotBlank() }
                if (sender != null) SentByTileOverlay(sender, item.sentByMessage, tint, liquidGlass, tile) else tile()
            }
            if (isLoading && items.isNotEmpty()) {
                item(key = "grid_loading_more", span = StaggeredGridItemSpan.FullLine) {
                    Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 1.5.dp)
                    }
                }
            }
        }
        } // Row (side list + grid)
    }
        // (Picking posts: the same bar shows selection's buttons.)
        ResultsInteractionBar(
            liquidGlass = liquidGlass, tint = tint, backdrop = backdrop,
            refreshing = isLoading, animateRefresh = !reducedAnimations,
            onRefresh = onRefresh,
            // (A folder laid out as videos has no other layouts to switch to.)
            filter = layoutKind, gridMode = gridMode,
            onGrid = {
                pendingAnchor = middleAnchor(gridState)
                // (A forced style would hide the cycle otherwise: drop it
                // first, so the button visibly changes the layout.)
                onClearExploreFeedStyle()
                cycleResultsGridMode(gridScreen, layoutKind, landscape = wideDp > 0)
            },
            modifier = Modifier.align(Alignment.BottomCenter).padding(start = if (wideDp > 0) rememberSideListWidth() else 0.dp),
            refreshAnchor = "ex.refresh", gridAnchor = "ex.layout",
            // Saved Posts (or your folder): Sort (as on profiles), then — with
            // a folder open — Edit, for its name, privacy, cover and delete.
            trailing = if (!folderTools) null else ({
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(44.dp).onGloballyPositioned { savedSortBounds = it.positionInRoot() to it.size }
                            .clip(CircleShape).clickable { tap(); savedSortOpen = !savedSortOpen },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            if (savedSortOpen) Icons.Default.Close else Icons.Filled.SwapVert,
                            contentDescription = if (savedSortOpen) "Close" else "Sort",
                            tint = if (LocalOverlays.savedSort != 4 && !savedSortOpen) vividAccent(tint) else Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    val openFolder = com.mediaviewer.util.LocalData.bookmarkFolders.firstOrNull { it.id == LocalOverlays.bookmarkFolderId }
                    if (openFolder != null) Box(
                        Modifier.size(44.dp).clip(CircleShape).clickable { tap(); LocalOverlays.editFolder = openFolder.id },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = "Edit folder", tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                }
            })
        )
        // Saved Posts › Sort: the profile's own options, in its bubbles.
        if (folderTools) Box(Modifier.fillMaxSize().onGloballyPositioned { savedSortRoot = it.positionInRoot() }) {
            val bounds = savedSortBounds
            val origin = savedSortRoot
            if (bounds != null && origin != null) {
                val accent = vividAccent(tint)
                val options = listOf(
                    "Most liked" to Icons.Filled.Favorite,
                    "Most reposted" to Icons.Filled.Repeat,
                    "Most saved" to Icons.Filled.Bookmark,
                    "Most comments" to Icons.Filled.ChatBubble,
                    "Newest" to Icons.Filled.Schedule,
                    "Oldest first" to Icons.Filled.History
                )
                BubbleActionStack(
                    visible = savedSortOpen,
                    anchorOriginRoot = bounds.first,
                    anchorSize = bounds.second,
                    containerRootOrigin = origin,
                    actions = options.mapIndexed { i, (label, icon) ->
                        BubbleAction(label, icon = icon, iconTint = if (i == LocalOverlays.savedSort) accent else null) {
                            if (i != LocalOverlays.savedSort) {
                                LocalOverlays.setSavedSort?.invoke(i)
                                gridScope.launch { gridState.scrollToItem(0) }
                            }
                        }
                    },
                    liquidGlass = liquidGlass, tint = tint, backdrop = backdrop,
                    onDismissRequest = { savedSortOpen = false },
                    gapAboveAnchor = 6.dp
                )
            }
        }
        // Back, fixed at the top left: the very button profiles have. While
        // picking posts it's Cancel (an X), and the count sits at the right
        // where a profile's Follow / Edit is.
        // (Landscape: Back is the side list's own, so there's only the one.)
        val floatingBack = onBack != null && wideDp <= 0
        if (floatingBack || PostSelection.active) Row(
            Modifier.align(Alignment.TopStart).fillMaxWidth()
                .padding(start = if (wideDp > 0) rememberSideListWidth() else 0.dp, top = rememberTopCutoutClearance())
                .padding(start = 12.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (PostSelection.active) SelectionCancelBubble(liquidGlass = liquidGlass, tint = tint, backdrop = backdrop)
            else if (floatingBack) ProfileBackBubble(liquidGlass = liquidGlass, tint = tint, backdrop = backdrop, onClick = onBack!!)
            Spacer(Modifier.weight(1f))
            if (PostSelection.active) SelectionCountBubble(liquidGlass = liquidGlass, tint = tint, backdrop = backdrop)
            else if (floatingBack && openedFeedUri != null) ProfileAddBubble(liquidGlass = liquidGlass, tint = tint, backdrop = backdrop) {
                LocalOverlays.addOpenedFeed?.invoke(openedFeedUri, openedFeedName)
            }
        }
        // Back-to-top arrow, same as on profiles (glass that blurs the grid).
        androidx.compose.animation.AnimatedVisibility(
            visible = showScrollTop,
            modifier = Modifier.align(Alignment.TopCenter).padding(start = if (wideDp > 0) rememberSideListWidth() else 0.dp, top = rememberTopCutoutClearance() + 8.dp),
            enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.scaleIn(initialScale = 0.8f),
            exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.scaleOut(targetScale = 0.8f)
        ) {
            ScrollToTopGlassBubble(liquidGlass = liquidGlass, tint = tint, backdrop = backdrop) {
                gridScope.launch {
                    if (reducedAnimations) gridState.scrollToItem(0) else gridState.animateScrollToItem(0)
                }
            }
        }
    }
}

/** A grey, softly pulsing stand-in tile while a feed loads. Heights vary a
 *  little in the masonry layouts so it reads like the real grid. */
@Composable
private fun GridPlaceholderTile(index: Int, lanes: Int, tint: Color, rounded: Boolean) {
    val pulse = androidx.compose.animation.core.rememberInfiniteTransition(label = "gridPlaceholder")
    val a by pulse.animateFloat(
        initialValue = 0.05f, targetValue = 0.12f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.tween(900, delayMillis = (index % lanes) * 120),
            androidx.compose.animation.core.RepeatMode.Reverse
        ),
        label = "gridPlaceholderAlpha"
    )
    val ratio = if (lanes >= 3) 1f else listOf(0.8f, 1.15f, 0.66f, 1f, 0.75f, 1.3f)[index % 6]
    val shape = if (rounded) RoundedCornerShape(14.dp) else RoundedCornerShape(0.dp)
    Box(
        Modifier.fillMaxWidth().aspectRatio(ratio).clip(shape)
            .drawBehind { drawRect(androidx.compose.ui.graphics.lerp(Color.White, tint, 0.35f).copy(alpha = a)) }
    )
}

/**
 * From Friends grid: a small glass bubble over the tile with the sender's
 * avatar and their message. The tile is recorded into its own layer so the
 * bubble can blur the media right behind it (real blur on Android 12+).
 */
@Composable
internal fun SentByTileOverlay(
    sender: com.mediaviewer.model.AuthorInfo,
    message: String,
    tint: Color,
    liquidGlass: Boolean,
    tile: @Composable () -> Unit
) {
    val layer = androidx.compose.ui.graphics.rememberGraphicsLayer()
    var origin by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    val backdrop = remember(layer) { GlassBackdrop(layer) { origin } }
    Box(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .onGloballyPositioned { origin = it.positionInRoot() }
                .drawWithContent {
                    if (LiteGlass.LIVE) { layer.record { this@drawWithContent.drawContent() }; drawLayer(layer) } else drawContent()
                }
        ) { tile() }
        val shape = RoundedCornerShape(12.dp)
        val label = message.trim().ifBlank { sender.displayName.ifBlank { "@" + sender.handle } }
        val content: @Composable BoxScope.() -> Unit = {
            Row(
                // A dark wash under the text, so it stays readable over
                // bright media.
                Modifier.clip(shape).background(Color.Black.copy(alpha = 0.38f))
                    .padding(start = 3.dp, end = 8.dp, top = 3.dp, bottom = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                coil3.compose.AsyncImage(
                    model = sender.avatarUrl, contentDescription = sender.displayName,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.size(18.dp).clip(AvatarShape).background(Color.White.copy(0.12f))
                )
                Text(
                    label, color = Color.White, fontSize = 10.sp, lineHeight = 12.sp,
                    fontWeight = FontWeight.Medium, maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
        }
        val bubbleModifier = Modifier.align(Alignment.TopStart).padding(6.dp).widthIn(max = 220.dp)
        if (liquidGlass) {
            LiquidGlassSurface(bubbleModifier, shape = shape, tint = tint, backdrop = backdrop, contentAlignment = Alignment.CenterStart, content = content)
        } else {
            Box(bubbleModifier.clip(shape).background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.CenterStart, content = content)
        }
    }
}
