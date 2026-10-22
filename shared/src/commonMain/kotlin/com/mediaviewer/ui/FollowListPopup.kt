package com.mediaviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mediaviewer.i18n.Text
import com.mediaviewer.model.AuthorInfo
import com.mediaviewer.ui.theme.DimGray
import com.mediaviewer.ui.theme.RoundedCornerShape
import com.mediaviewer.util.rememberHapticTap
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/**
 * A profile's Followers or Following (tapping those counts): the Blocked
 * Accounts popup's look — a search bar over a compact list — each account
 * with Follow / Unfollow on the right; tapping one opens it. Pages of 100
 * load as the list is scrolled to its end; while searching, the rest of the
 * list keeps loading (up to [SEARCH_PAGES] pages) so a name can be found.
 */
@Composable
fun FollowListPopup(
    request: FollowListRequest,
    liquidGlass: Boolean, tint: Color, backdrop: GlassBackdrop?,
    loadPage: suspend (cursor: String?) -> Result<Pair<List<AuthorInfo>, String?>>,
    setFollow: suspend (AuthorInfo, Boolean) -> Result<String?>,
    selfDid: String,
    onOpen: (AuthorInfo) -> Unit,
    onClose: () -> Unit
) {
    val tap = rememberHapticTap()
    val scope = rememberCoroutineScope()
    val people = remember(request) { mutableStateListOf<AuthorInfo>() }
    var cursor by remember(request) { mutableStateOf<String?>(null) }
    var done by remember(request) { mutableStateOf(false) }
    var loading by remember(request) { mutableStateOf(false) }
    var failed by remember(request) { mutableStateOf(false) }
    var pages by remember(request) { mutableStateOf(0) }
    var query by remember(request) { mutableStateOf("") }
    val busy = remember(request) { mutableStateListOf<String>() }

    suspend fun more() {
        if (loading || done) return
        loading = true
        loadPage(cursor).onSuccess { (list, next) ->
            val seen = people.mapTo(HashSet()) { it.did }
            people.addAll(list.filter { seen.add(it.did) })
            cursor = next; done = next == null; pages++; failed = false
        }.onFailure { failed = true; done = true }
        loading = false
    }
    LaunchedEffect(request) { more() }
    // Searching: keep reading pages so the account can be found further in.
    LaunchedEffect(request, query.isNotBlank()) {
        while (query.isNotBlank() && !done && pages < SEARCH_PAGES) more()
    }

    val q = query.trim().removePrefix("@")
    val shown = if (q.isEmpty()) people else people.filter {
        it.displayName.contains(q, ignoreCase = true) || it.handle.contains(q, ignoreCase = true)
    }
    val listState = rememberLazyListState()
    LaunchedEffect(listState, request) {
        snapshotFlow {
            val info = listState.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 10
        }.distinctUntilChanged().filter { it }.collect { more() }
    }

    LocalPopup(
        title = if (request.followers) "Followers" else "Following",
        liquidGlass = liquidGlass, tint = tint, onClose = onClose, backdrop = backdrop, fixedHeight = true
    ) {
        BlockedSearchField(query, tint, placeholder = "Search") { query = it }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            when {
                shown.isEmpty() && (loading || (!done && q.isNotEmpty())) -> Box(Modifier.fillMaxWidth().padding(28.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 1.5.dp)
                }
                shown.isEmpty() -> Box(Modifier.fillMaxWidth().padding(28.dp), contentAlignment = Alignment.Center) {
                    Text(if (failed) "Couldn't load this list" else "No one found", color = DimGray, fontSize = 13.sp)
                }
                else -> LazyColumn(state = listState, contentPadding = PaddingValues(top = 2.dp, bottom = 10.dp)) {
                    items(shown, key = { it.did }) { person ->
                        FollowListRow(
                            person, tint, isSelf = person.did == selfDid, busy = person.did in busy,
                            onOpen = { tap(); onOpen(person) },
                            onFollow = {
                                tap()
                                val want = !person.isFollowing
                                busy.add(person.did)
                                scope.launch {
                                    setFollow(person, want).onSuccess { uri ->
                                        val i = people.indexOfFirst { it.did == person.did }
                                        if (i >= 0) people[i] = people[i].copy(isFollowing = want, followingUri = uri)
                                    }
                                    busy.remove(person.did)
                                }
                            }
                        )
                    }
                    if (loading) item(key = "follow_list_more") {
                        Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(16.dp), color = Color.White, strokeWidth = 1.5.dp)
                        }
                    }
                }
            }
        }
    }
}

/** How many pages (of 100) a search reads through at most. */
private const val SEARCH_PAGES = 30

@Composable
private fun FollowListRow(person: AuthorInfo, tint: Color, isSelf: Boolean, busy: Boolean, onOpen: () -> Unit, onFollow: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onOpen).padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        BlockedAvatar(person, 34.dp)
        Column(Modifier.weight(1f)) {
            Text(person.displayName, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("@${person.handle}", color = DimGray, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        // (Not for yourself.)
        if (!isSelf) Box(
            Modifier.clip(RoundedCornerShape(14.dp))
                .background(if (person.isFollowing) Color.White.copy(alpha = 0.08f) else lerp(tint, Color.Black, 0.35f).copy(alpha = 0.75f))
                .border(1.dp, lerp(tint, Color.White, 0.3f).copy(alpha = 0.8f), RoundedCornerShape(14.dp))
                .clickable(enabled = !busy, onClick = onFollow)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            // (The longer word laid out invisibly keeps the width steady.)
            Text("Unfollow", color = Color.Transparent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            if (busy) CircularProgressIndicator(Modifier.size(14.dp), color = Color.White, strokeWidth = 1.5.dp)
            else Text(if (person.isFollowing) "Unfollow" else "Follow", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}
