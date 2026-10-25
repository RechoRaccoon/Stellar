package com.mediaviewer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mediaviewer.i18n.Text
import com.mediaviewer.ui.theme.DimGray
import com.mediaviewer.util.LocalFeed
import com.mediaviewer.util.ProfileHome
import com.mediaviewer.viewmodel.MainViewModel
import kotlinx.coroutines.launch

/** A profile's Posts → Home view (see ProfileHome), by account. */
object ProfileHomeTab {
    /** Whether Home is the one showing (missing = not chosen yet: a
     *  profile with a Home tab opens on it). */
    val showing = mutableStateMapOf<String, Boolean>()
    /** Your own Home tab while it's being customized (Customize ↔ Done,
     *  beside the Home filter). */
    val customizing = mutableStateMapOf<String, Boolean>()
}

private fun openHomeBuilder(feed: LocalFeed) {
    LocalOverlays.feedBuilderForHome = true
    LocalOverlays.feedBuilder = feed
}

/** A Home tab's row names, as its Home Settings say. */
internal fun homeHeaderStyle(did: String): RowHeaderStyle =
    (ProfileHome.headers[did] ?: ProfileHome.Header()).let { RowHeaderStyle(it.align, it.divider, it.lines) }

/**
 * Posts → Home: the profile's own feed rows, each filled like a Hub feed
 * row, their names as the profile's Home Settings say. Your own, while
 * customizing, is the same editor as Customize Hub: Home Layout (drag the
 * rows, edit them, Create) and Home Settings. Every change is saved as
 * it's made — they're public records, so only public folders can be a
 * row's source.
 */
internal fun LazyListScope.profileHomeRows(
    did: String, feeds: List<LocalFeed>, own: Boolean,
    rows: Map<String, MainViewModel.HubListState>,
    liquidGlass: Boolean, tint: Color,
    onOpenPost: (String, Int) -> Unit,
    onSeeAll: (String) -> Unit = {},
    // Landscape only, like the Hub: the rows are drawn at the Home Size.
    rowScaled: Boolean = false
) {
    val customizing = own && ProfileHomeTab.customizing[did] == true
    if (customizing) {
        item(key = "home_customize") {
            // (Edged like the profile's tabs and bio.)
            Column(Modifier.fillMaxWidth().padding(horizontal = PROFILE_EDGE)) {
                LayoutSectionDivider("Home Layout", tint)
                LayoutRowsList(
                    items = feeds.map { feed -> LayoutRowItem(feed.id, feed.name.ifBlank { "Feed" }, onEdit = { openHomeBuilder(feed) }) },
                    liveIds = { ProfileHome.byAccount[did].orEmpty().map { it.id } },
                    onMove = { from, to -> ProfileHome.moveTo(from, to) },
                    liquidGlass = liquidGlass, tint = tint, backdrop = null
                )
                Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.Center) {
                    LayoutPillButton("Create", liquidGlass, tint, null, { openHomeBuilder(LocalFeed()) })
                }
                LayoutSectionDivider("Home Settings", tint)
                HeaderStyleSettings(
                    style = homeHeaderStyle(did),
                    onChange = { s -> ProfileHome.setHeader(ProfileHome.Header(s.align, s.divider, s.lines)) },
                    liquidGlass = liquidGlass, tint = tint, backdrop = null
                )
            }
        }
        return
    }
    if (feeds.isEmpty() && ProfileHome.failed[did] == true) {
        item(key = "home_failed") {
            val scope = androidx.compose.runtime.rememberCoroutineScope()
            Column(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Couldn't load the Home tab", color = DimGray, fontSize = 13.sp)
                // (Why, while this is being tracked down.)
                ProfileHome.errors[did]?.takeIf { it.isNotBlank() }?.let { why ->
                    Text(why, color = DimGray.copy(alpha = 0.7f), fontSize = 10.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
                }
                Box(Modifier.padding(top = 10.dp)) {
                    LayoutPillButton("Try Again", liquidGlass, tint, null, { scope.launch { ProfileHome.load(did, fresh = true) } })
                }
            }
        }
        return
    }
    if (feeds.isEmpty()) item(key = "home_empty") {
        // (Your own empty Home tab is just its Create button.)
        // (As far below the filters as they are below the tabs.)
        if (own) Box(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 18.dp), contentAlignment = Alignment.Center) {
            LayoutPillButton("Create", liquidGlass, tint, null, { openHomeBuilder(LocalFeed()) })
        } else Text(
            "Nothing here yet",
            color = DimGray, fontSize = 13.sp, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 24.dp)
        )
    }
    val style = homeHeaderStyle(did)
    feeds.forEach { feed ->
        val key = "home:$did:${feed.id}"
        item(key = key) {
            Column(
                Modifier.fillMaxWidth().then(
                    if (rowScaled) Modifier.drawnAtScaleWrapped { com.mediaviewer.util.UiToggles.homeScale }
                    else Modifier
                )
            ) {
                BuiltFeedRow(
                    feed = feed, title = feed.name.ifBlank { "Feed" }, state = rows[key],
                    liquidGlass = liquidGlass, tint = tint, headerStyle = style,
                    onLoad = { LocalOverlays.loadFeedRow?.invoke(key, feed, false) },
                    onLoadMore = { LocalOverlays.loadFeedRow?.invoke(key, feed, true) },
                    onOpenPost = { i -> onOpenPost(key, i) },
                    onSeeAll = { onSeeAll(key) },
                    onOpenProfile = { a -> LocalOverlays.openProfile?.invoke(a) },
                    // (Lined up with the profile's tabs and bio.)
                    headerEdge = PROFILE_EDGE,
                    listEdge = PROFILE_EDGE
                )
            }
        }
    }
}
