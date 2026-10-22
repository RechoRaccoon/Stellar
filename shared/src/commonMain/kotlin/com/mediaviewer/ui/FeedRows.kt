package com.mediaviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.mediaviewer.i18n.Text
import com.mediaviewer.model.AuthorInfo
import com.mediaviewer.model.MediaItem
import com.mediaviewer.ui.theme.AvatarShape
import com.mediaviewer.ui.theme.CircleShape
import com.mediaviewer.ui.theme.DimGray
import com.mediaviewer.ui.theme.RoundedCornerShape
import com.mediaviewer.util.LocalFeed
import com.mediaviewer.util.rememberHapticTap
import com.mediaviewer.viewmodel.MainViewModel

/**
 * How a Hub (or profile Home tab) row's name looks, from Hub / Home
 * Settings: where it sits ([align] 0 left, 1 center, 2 right); small
 * between lines in the row's color ([divider], the Hub's own look) or a
 * big heading in the profile color; and — big — [lines] running out from
 * it to the page's edges.
 */
data class RowHeaderStyle(val align: Int = 1, val divider: Boolean = true, val lines: Boolean = false) {
    companion object {
        /** The Hub's, from its settings (this device's). */
        val hub: RowHeaderStyle
            get() = com.mediaviewer.util.UiToggles.let { RowHeaderStyle(it.hubHeaderAlign, it.hubDividerHeaders, it.hubHeaderLines) }
    }
}

/**
 * A row's name in [style] (see [RowHeaderStyle]). [edge] is how far in from
 * the page's sides it starts and ends (lined up with the rest of the page).
 * [onEdit] puts a small edit button after it.
 */
@Composable
fun FeedRowTitle(label: String, tint: Color, style: RowHeaderStyle, onEdit: (() -> Unit)? = null, edge: Dp = 16.dp) {
    val tap = rememberHapticTap()
    val lineColor = tint.copy(alpha = 0.6f)
    @Composable
    fun Line(m: Modifier) = HorizontalDivider(modifier = m, color = lineColor)
    if (style.divider) {
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = edge), verticalAlignment = Alignment.CenterVertically) {
            if (style.align != 0) Line(Modifier.weight(1f))
            Text(
                label, color = lerp(tint, Color.White, 0.35f), fontSize = 12.sp, lineHeight = 12.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = if (style.align == 0) 0.dp else 10.dp, end = if (style.align == 2 && onEdit == null) 0.dp else 10.dp).widthIn(max = 220.dp)
            )
            if (onEdit != null) Box(
                Modifier.padding(end = 8.dp).size(24.dp).clip(CircleShape).clickable { tap(); onEdit() },
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Default.Edit, contentDescription = "Edit", tint = lerp(tint, Color.White, 0.35f), modifier = Modifier.size(13.dp)) }
            if (style.align != 2) Line(Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
    } else {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = edge).padding(top = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = when (style.align) {
                0 -> Arrangement.Start; 2 -> Arrangement.End; else -> Arrangement.Center
            }
        ) {
            // Lines (or nothing) on whichever sides have room.
            if (style.align != 0) { if (style.lines) Line(Modifier.weight(1f).padding(end = 12.dp)) }
            Text(
                label, color = headerColorFor(tint), fontSize = 19.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                // (With lines, they take what's left on either side.)
                modifier = if (style.lines) Modifier else Modifier.weight(1f, fill = false)
            )
            if (onEdit != null) Box(
                Modifier.padding(start = 4.dp).size(32.dp).clip(CircleShape).clickable { tap(); onEdit() },
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Default.Edit, contentDescription = "Edit", tint = lerp(tint, Color.White, 0.35f), modifier = Modifier.size(16.dp)) }
            if (style.align != 2) { if (style.lines) Line(Modifier.weight(1f).padding(start = 12.dp)) }
        }
    }
}

/** How tall one row of a built feed's tiles is, by its style — the size
 *  the profile's own tiles are on a phone (its 3-across grids). */
private fun styleHeight(style: String): Dp = when (style) {
    "tiktok" -> 196.dp
    "youtube" -> 172.dp
    "square" -> 120.dp
    else -> 150.dp
}

/** The gap between tiles, as on the profile's own layout of that kind. */
private fun styleSpacing(style: String): Dp = when (style) {
    "square" -> if (LocalOverlays.roundedGridTiles) 4.dp else 0.dp
    "tiktok" -> 4.dp
    "youtube" -> 12.dp
    else -> 6.dp
}

/**
 * A built feed (see [LocalFeed]) as a row: its posts side by side in the
 * feed's own style, [LocalFeed.rows] deep, more loading as it's scrolled to
 * its end; or, for the "avatars" style, just the accounts' pictures. The
 * posts come from [state] (MainViewModel.hubLists, filled by loadFeedRow).
 */
@Composable
fun BuiltFeedRow(
    feed: LocalFeed, title: String, state: MainViewModel.HubListState?,
    liquidGlass: Boolean, tint: Color, headerStyle: RowHeaderStyle,
    onLoad: () -> Unit, onLoadMore: () -> Unit,
    onOpenPost: (Int) -> Unit, onOpenProfile: (AuthorInfo) -> Unit,
    onEdit: (() -> Unit)? = null,
    headerEdge: Dp = 16.dp
) {
    LaunchedEffect(feed) { onLoad() }
    // A row built from tags: the shared tags are offered (or updated).
    LaunchedEffect(feed.id) { if (feed.sources.any { it.kind == "tag" }) com.mediaviewer.tagging.SharedTagDataset.onTaggedSearchOpened() }
    LaunchedEffect(state?.failed, state?.loadedAt) {
        if (state?.failed == true) { kotlinx.coroutines.delay(20_000); onLoad() }
    }
    FeedRowTitle(title, tint, headerStyle, onEdit, edge = headerEdge)
    // (A feed row's posts aren't picked, even on a profile's Home tab.)
    androidx.compose.runtime.CompositionLocalProvider(LocalPostSelectable provides false) {
        if (feed.showsPosts) BuiltFeedPosts(feed, state, liquidGlass, onLoad, onLoadMore, onOpenPost, onOpenProfile)
    }
    if (feed.blogs || feed.reviews) BuiltFeedWritings(feed, state, liquidGlass, onOpenProfile)
}

/** A built feed's blogs and reviews (see LocalFeed.blogs/reviews): a row
 *  of each under its posts, in the Hub's own blog and review cards. */
@Composable
private fun BuiltFeedWritings(feed: LocalFeed, state: MainViewModel.HubListState?, liquidGlass: Boolean, onOpenProfile: (AuthorInfo) -> Unit) {
    val blogs = state?.blogs.orEmpty()
    val reviews = state?.reviews.orEmpty()
    if (!feed.showsPosts && blogs.isEmpty() && reviews.isEmpty() && state != null && !state.loading) {
        Text(
            "Nothing here yet", color = DimGray, fontSize = 12.sp, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
        )
        return
    }
    if (blogs.isNotEmpty()) {
        Spacer(Modifier.height(10.dp))
        com.mediaviewer.ui.compat.LazyRow(
            Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top
        ) {
            items(blogs.size, key = { "blog_" + blogs[it].blog.uri }) { i ->
                val fb = blogs[i]
                val blogTint = rememberAuthorProfileTint(fb.author.did, fb.author.avatarUrl)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    HubAuthorBubble(
                        displayName = fb.author.displayName.ifBlank { fb.author.handle }, avatarUrl = fb.author.avatarUrl,
                        liquidGlass = liquidGlass, tint = blogTint, cardWidth = HUB_BLOG_CARD_WIDTH,
                        onClick = { onOpenProfile(fb.author) }
                    )
                    Spacer(Modifier.height(6.dp))
                    HubBlogCard(
                        blog = fb.blog, author = fb.author, tint = blogTint, liquidGlass = liquidGlass,
                        onOpen = { LocalOverlays.openMutualBlog?.invoke(fb) }, onOpenAuthor = { onOpenProfile(fb.author) }
                    )
                }
            }
        }
    }
    if (reviews.isNotEmpty()) {
        Spacer(Modifier.height(10.dp))
        com.mediaviewer.ui.compat.LazyRow(
            Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(reviews.size, key = { "review_" + reviews[it].review.uri }) { i ->
                MutualReviewCard(reviews[i], liquidGlass, { LocalOverlays.openMutualReview?.invoke(it) }, onOpenProfile)
            }
        }
    }
}

/** A built feed's posts, as a row in its style (see LocalFeed.style). */
@Composable
private fun BuiltFeedPosts(
    feed: LocalFeed, state: MainViewModel.HubListState?, liquidGlass: Boolean,
    onLoad: () -> Unit, onLoadMore: () -> Unit,
    onOpenPost: (Int) -> Unit, onOpenProfile: (AuthorInfo) -> Unit
) {
    val tap = rememberHapticTap()
    val loading = state == null || (state.loading && state.posts.isEmpty())
    val posts = state?.posts.orEmpty()
    if (loading) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            repeat(4) { ShimmerBox(RoundedCornerShape(14.dp), Modifier.size(width = 110.dp, height = if (feed.style == "avatars") 60.dp else styleHeight(feed.style))) }
        }
        return
    }
    if (posts.isEmpty()) {
        Text(
            if (state?.failed == true) "Couldn't load this feed · Tap to retry" else "Nothing here yet",
            color = DimGray, fontSize = 12.sp, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                .then(if (state?.failed == true) Modifier.clickable { tap(); onLoad() } else Modifier)
        )
        return
    }
    if (feed.style == "avatars") {
        val members = state?.members.orEmpty()
        com.mediaviewer.ui.compat.LazyRow(
            Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(members.size, key = { members[it].did }) { i ->
                val m = members[i]
                Column(Modifier.width(60.dp).clickable { tap(); onOpenProfile(m) }, horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(52.dp).clip(AvatarShape).background(Color.White.copy(alpha = 0.1f))) {
                        if (m.avatarUrl != null) AsyncImage(
                            model = m.avatarUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(m.displayName.ifBlank { m.handle }, color = Color.White, fontSize = 10.sp, lineHeight = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        return
    }
    val rows = feed.rows.coerceIn(1, 3)
    val tileH = styleHeight(feed.style)
    val gridState = rememberLazyGridState()
    val latestMore by rememberUpdatedState(onLoadMore)
    LaunchedEffect(gridState, posts.size, state?.loadingMore, state?.postsCursor) {
        snapshotFlow {
            val info = gridState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            (info.totalItemsCount > 0 && last >= info.totalItemsCount - 4) || !gridState.canScrollForward
        }.collect { near -> if (near && state?.postsCursor != null) latestMore() }
    }
    val spacing = styleSpacing(feed.style)
    LazyHorizontalGrid(
        rows = GridCells.Fixed(rows), state = gridState,
        modifier = Modifier.fillMaxWidth().height(tileH * rows + spacing * (rows - 1)),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(spacing), verticalArrangement = Arrangement.spacedBy(spacing)
    ) {
        items(posts.size, key = { i -> posts[i].postUri.ifBlank { posts[i].id } + "#" + i }) { i ->
            BuiltFeedTile(posts[i], feed.style, tileH, liquidGlass) { onOpenPost(i) }
        }
        if (state?.loadingMore == true) item(key = "built_more") {
            Box(Modifier.width(48.dp).height(tileH), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 1.5.dp)
            }
        }
    }
}

/**
 * One post in a built feed's row, in its [style] (see [LocalFeed.style]),
 * drawn with the profile page's own tile for that layout so they look the
 * same: "square" its square grid, "tiktok" (Portrait) its Vertical Videos,
 * "youtube" (Horizontal) its video card (picture, the author's icon, the
 * text, likes and date), "natural" its Pinterest tile.
 */
@Composable
private fun BuiltFeedTile(post: MediaItem, style: String, height: Dp, liquidGlass: Boolean, onClick: () -> Unit) {
    val tint = rememberAuthorProfileTint(post.author.did, post.author.avatarUrl)
    when (style) {
        "square" -> Box(Modifier.size(height)) {
            PostResultTile(
                item = post, filter = PostKindFilter.ALL, gridMode = 2, tint = tint, liquidGlass = liquidGlass,
                roundedGridTiles = LocalOverlays.roundedGridTiles, onSeedSubImageIndex = { _, _ -> }, onClick = onClick
            )
        }
        "tiktok" -> Box(Modifier.width(height * 9f / 16f).height(height)) {
            PostResultTile(
                item = post, filter = PostKindFilter.VERTICAL_VIDEOS, gridMode = 0, tint = tint, liquidGlass = liquidGlass,
                roundedGridTiles = LocalOverlays.roundedGridTiles, onSeedSubImageIndex = { _, _ -> }, onClick = onClick
            )
        }
        "youtube" -> Box(Modifier.width(200.dp).height(height)) {
            PostResultTile(
                item = post, filter = PostKindFilter.HORIZONTAL_VIDEOS, gridMode = 0, tint = tint, liquidGlass = liquidGlass,
                roundedGridTiles = LocalOverlays.roundedGridTiles, onSeedSubImageIndex = { _, _ -> }, videoCards = true, onClick = onClick
            )
        }
        else -> HubPostTile(post, tint, liquidGlass, height, onClick)
    }
}
