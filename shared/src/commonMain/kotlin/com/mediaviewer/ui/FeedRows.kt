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
 * A Hub (or profile Home tab) row's name: centered between two lines in the
 * row's color, or — [left] (Customize Hub's "Row Names on the Left", and
 * every Home tab) — bigger, at the left, like Settings' headings. [onEdit]
 * puts a small edit button at its end.
 */
@Composable
fun FeedRowTitle(label: String, tint: Color, left: Boolean, onEdit: (() -> Unit)? = null) {
    val tap = rememberHapticTap()
    val labelColor = lerp(tint, Color.White, 0.35f)
    if (left) {
        Row(
            Modifier.fillMaxWidth().padding(start = 18.dp, end = 10.dp, top = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                label, color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
            )
            if (onEdit != null) Box(
                Modifier.size(32.dp).clip(CircleShape).clickable { tap(); onEdit() },
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Default.Edit, contentDescription = "Edit", tint = labelColor, modifier = Modifier.size(16.dp)) }
        }
    } else {
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            HorizontalDivider(modifier = Modifier.weight(1f), color = tint.copy(alpha = 0.6f))
            Text(
                label, color = labelColor, fontSize = 12.sp, lineHeight = 12.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 10.dp).widthIn(max = 220.dp)
            )
            if (onEdit != null) Box(
                Modifier.padding(end = 8.dp).size(24.dp).clip(CircleShape).clickable { tap(); onEdit() },
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Default.Edit, contentDescription = "Edit", tint = labelColor, modifier = Modifier.size(13.dp)) }
            HorizontalDivider(modifier = Modifier.weight(1f), color = tint.copy(alpha = 0.6f))
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** How tall one row of a built feed's tiles is, by its style. */
private fun styleHeight(style: String): Dp = when (style) {
    "tiktok" -> 230.dp
    "youtube" -> 190.dp
    "square" -> 140.dp
    else -> 150.dp
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
    liquidGlass: Boolean, tint: Color, leftTitle: Boolean,
    onLoad: () -> Unit, onLoadMore: () -> Unit,
    onOpenPost: (Int) -> Unit, onOpenProfile: (AuthorInfo) -> Unit,
    onEdit: (() -> Unit)? = null
) {
    LaunchedEffect(feed) { onLoad() }
    LaunchedEffect(state?.failed, state?.loadedAt) {
        if (state?.failed == true) { kotlinx.coroutines.delay(20_000); onLoad() }
    }
    FeedRowTitle(title, tint, leftTitle, onEdit)
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
    val spacing = 10.dp
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

/** One post in a built feed's row, in its [style] (see [LocalFeed.style]). */
@Composable
private fun BuiltFeedTile(post: MediaItem, style: String, height: Dp, liquidGlass: Boolean, onClick: () -> Unit) {
    val tap = rememberHapticTap()
    val tint = rememberAuthorProfileTint(post.author.did, post.author.avatarUrl)
    if (style == "natural") { HubPostTile(post, tint, liquidGlass, height, onClick); return }
    val shape = RoundedCornerShape(14.dp)
    val thumb = post.thumbUrl.ifBlank { post.mediaUrl.orEmpty() }
    @Composable
    fun Picture(m: Modifier) {
        Box(m.clip(shape).background(lerp(tint, Color.Black, 0.6f))) {
            if (!post.isTextOnly && thumb.isNotBlank()) {
                AsyncImage(model = thumb, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                if (post.isVideo) Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.align(Alignment.Center).size(28.dp))
            } else Text(
                post.text, color = Color.White, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 8,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(10.dp)
            )
        }
    }
    when (style) {
        "youtube" -> {
            val w = height * 1.2f
            Column(Modifier.width(w).height(height).clip(shape).clickable { tap(); onClick() }) {
                Picture(Modifier.fillMaxWidth().height(w * 9f / 16f))
                Spacer(Modifier.height(6.dp))
                Text(
                    post.text.ifBlank { post.author.displayName }, color = Color.White, fontSize = 12.sp, lineHeight = 15.sp,
                    fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis
                )
                Text(
                    post.author.displayName.ifBlank { post.author.handle }, color = DimGray, fontSize = 11.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        }
        "tiktok" -> Box(Modifier.width(height * 9f / 16f).height(height).clickable { tap(); onClick() }) {
            Picture(Modifier.fillMaxSize())
        }
        else -> Box(Modifier.size(height).clickable { tap(); onClick() }) { Picture(Modifier.fillMaxSize()) }
    }
}
