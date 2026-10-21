package com.mediaviewer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mediaviewer.i18n.Text
import com.mediaviewer.ui.theme.CircleShape
import com.mediaviewer.ui.theme.DimGray
import com.mediaviewer.ui.theme.RoundedCornerShape
import com.mediaviewer.util.LocalFeed
import com.mediaviewer.util.ProfileHome
import com.mediaviewer.util.rememberHapticTap
import com.mediaviewer.viewmodel.MainViewModel

/** A profile's Posts → Home view (see ProfileHome), by account. */
object ProfileHomeTab {
    /** Whether Home is the one showing (missing = not chosen yet: a
     *  profile with a Home tab opens on it). */
    val showing = mutableStateMapOf<String, Boolean>()
    /** Your own Home tab while it's being rearranged. */
    val customizing = mutableStateMapOf<String, Boolean>()
}

private fun openHomeBuilder(feed: LocalFeed) {
    LocalOverlays.feedBuilderForHome = true
    LocalOverlays.feedBuilder = feed
}

/**
 * Posts → Home: the profile's own feed rows, titled on the left, each
 * filled like a Hub feed row. On your own profile each row has an edit
 * button, and Customize Home Tab (at the end) reorders them and adds new
 * ones — they're public, so they only take public folders.
 */
internal fun LazyListScope.profileHomeRows(
    did: String, feeds: List<LocalFeed>, own: Boolean,
    rows: Map<String, MainViewModel.HubListState>,
    liquidGlass: Boolean, tint: Color,
    onOpenPost: (String, Int) -> Unit
) {
    val customizing = own && ProfileHomeTab.customizing[did] == true
    if (feeds.isEmpty()) item(key = "home_empty") {
        Text(
            if (own) "Feeds you add to your Home tab show first on your profile, for everyone to see."
            else "Nothing here yet",
            color = DimGray, fontSize = 13.sp, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 24.dp)
        )
    }
    if (customizing) {
        feeds.forEachIndexed { i, feed ->
            item(key = "home_edit_${feed.id}") { HomeFeedEditRow(feed, i > 0, i < feeds.lastIndex, liquidGlass, tint) }
        }
    } else feeds.forEach { feed ->
        val key = "home:$did:${feed.id}"
        item(key = key) {
            Column(Modifier.fillMaxWidth()) {
                BuiltFeedRow(
                    feed = feed, title = feed.name.ifBlank { "Feed" }, state = rows[key],
                    liquidGlass = liquidGlass, tint = tint, leftTitle = true,
                    onLoad = { LocalOverlays.loadFeedRow?.invoke(key, feed, false) },
                    onLoadMore = { LocalOverlays.loadFeedRow?.invoke(key, feed, true) },
                    onOpenPost = { i -> onOpenPost(key, i) },
                    onOpenProfile = { a -> LocalOverlays.openProfile?.invoke(a) },
                    onEdit = if (own) ({ openHomeBuilder(feed) }) else null
                )
            }
        }
    }
    if (own) item(key = "home_customize") {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (customizing || feeds.isEmpty()) LocalPillButton(
                "Add Feed", liquidGlass, tint, { openHomeBuilder(LocalFeed()) }, Modifier.weight(1f)
            )
            if (feeds.isNotEmpty()) LocalPillButton(
                if (customizing) "Done" else "Customize Home Tab", liquidGlass, tint,
                { ProfileHomeTab.customizing[did] = !customizing }, Modifier.weight(1f)
            )
        }
    }
}

/** Customize Home Tab: one feed — its name, up / down, and edit. */
@Composable
private fun HomeFeedEditRow(feed: LocalFeed, canUp: Boolean, canDown: Boolean, liquidGlass: Boolean, tint: Color) {
    val tap = rememberHapticTap()
    val shape = RoundedCornerShape(16.dp)
    @Composable
    fun Btn(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
        Box(
            Modifier.size(38.dp).clip(CircleShape).clickable(enabled = enabled) { tap(); onClick() },
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = label, tint = Color.White.copy(alpha = if (enabled) 0.9f else 0.25f), modifier = Modifier.size(20.dp))
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            .glassPanel(liquidGlass, tint = tint.copy(alpha = 0.45f), shape = shape)
            .padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            feed.name.ifBlank { "Feed" }, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
        )
        Btn(Icons.Default.KeyboardArrowUp, "Move up", canUp) { ProfileHome.move(feed.id, -1) }
        Btn(Icons.Default.KeyboardArrowDown, "Move down", canDown) { ProfileHome.move(feed.id, 1) }
        Btn(Icons.Default.Edit, "Edit", true) { openHomeBuilder(feed) }
    }
}
