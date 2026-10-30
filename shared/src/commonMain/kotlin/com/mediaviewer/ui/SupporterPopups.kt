package com.mediaviewer.ui

import androidx.compose.foundation.background
import com.mediaviewer.platform.ioDispatcher
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import com.mediaviewer.ui.compat.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import com.mediaviewer.ui.theme.CircleShape
import com.mediaviewer.ui.theme.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.Icon
import com.mediaviewer.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.mediaviewer.model.AuthorInfo
import com.mediaviewer.model.BskyList
import com.mediaviewer.model.MediaItem
import com.mediaviewer.platform.LocalPlatform
import com.mediaviewer.ui.compat.ActivityResultContracts
import com.mediaviewer.ui.compat.LocalContext
import com.mediaviewer.ui.compat.PickVisualMediaRequest
import com.mediaviewer.ui.compat.rememberLauncherForActivityResult
import com.mediaviewer.util.BookmarkFolder
import com.mediaviewer.util.DateText
import com.mediaviewer.util.LocalData
import com.mediaviewer.util.LocalFeed
import com.mediaviewer.util.LocalFeedSource
import com.mediaviewer.util.PostEditVersion
import com.mediaviewer.util.rememberHapticTap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun isoToText(iso: String): String {
    if (iso.isBlank()) return ""
    val ms = runCatching { com.mediaviewer.platform.parseIsoInstantMillis(iso) }.getOrNull()
        ?: runCatching { com.mediaviewer.platform.parseIsoOffsetDateTimeMillis(iso) }.getOrNull()
        ?: return ""
    return DateText.format(ms, "MMM d, yyyy · h:mm a")
}

// ── Edited posts: version history ───────────────────────────────────────

/** Tapping a post's "Edited" status: the current version on top, then
 *  every earlier version, newest first. */
@Composable
fun EditHistoryPopup(item: MediaItem, liquidGlass: Boolean, tint: Color, backdrop: GlassBackdrop?, onClose: () -> Unit) {
    val versions: List<PostEditVersion> = remember(item) { (item.editHistory ?: emptyList()).reversed() }
    LocalPopup(title = "Edit History", liquidGlass = liquidGlass, tint = tint, onClose = onClose, backdrop = backdrop) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val current = remember(item) {
                if (item.isVideo) emptyList() else item.mediaGroup.map { it.thumbUrl.ifBlank { it.mediaUrl } }.filter { it.isNotBlank() }
            }
            VersionCard("Current version", isoToText(item.editedAt.orEmpty()).let { if (it.isBlank()) "" else "Edited $it" }, item.text, tint, current = true, pictures = current)
            versions.forEachIndexed { i, v ->
                val original = i == versions.lastIndex
                VersionCard(
                    if (original) "Original" else "Earlier version",
                    isoToText(v.at) + if (v.images > 0) " · ${v.images} image${if (v.images == 1) "" else "s"}" else "",
                    v.text, tint, current = false, pictures = v.pictures(item.author.did),
                    did = item.author.did, cids = v.imageBlobs.map { it.ref.link }
                )
            }
            if (versions.isEmpty()) Text(
                "This post was edited, but its earlier versions aren't available.",
                color = Color.White.copy(alpha = 0.65f), fontSize = 12.sp, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
            )
        }
    }
}

@Composable
private fun VersionCard(
    label: String, sub: String, text: String, tint: Color, current: Boolean, pictures: List<String> = emptyList(),
    /** The pictures' blobs, when known: one the CDN won't serve (it's no
     *  longer in the post itself) is fetched from the PDS instead. */
    did: String = "", cids: List<String> = emptyList()
) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape)
            .background(Color.Black.copy(alpha = if (current) 0.34f else 0.24f))
            .border(1.dp, lerp(tint, Color.White, 0.25f).copy(alpha = if (current) 0.8f else 0.35f), shape)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            if (sub.isNotBlank()) {
                Spacer(Modifier.width(8.dp))
                Text(sub, color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.height(5.dp))
        Text(
            text.ifBlank { "(no text)" }, color = Color.White.copy(alpha = if (current) 0.96f else 0.82f),
            fontSize = 14.sp, lineHeight = 19.sp
        )
        // That version's pictures, in order.
        if (pictures.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                pictures.forEachIndexed { i, url ->
                    val cid = cids.getOrNull(i)?.takeIf { it.isNotBlank() && did.isNotBlank() }
                    var model by remember(url) { mutableStateOf(url) }
                    val scope = rememberCoroutineScope()
                    coil3.compose.AsyncImage(
                        model = model, contentDescription = null, contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        onError = {
                            if (cid != null && model == url) scope.launch {
                                runCatching { com.mediaviewer.worker.BlueskyBlobResolver.resolveBlobUrl(did, cid) }.getOrNull()?.let { model = it }
                            }
                        },
                        modifier = Modifier.size(72.dp).clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = 0.06f))
                    )
                }
            }
        }
    }
}

// ── Bookmark folders ────────────────────────────────────────────────────

/** A folder's picture: its cover, or a folder icon on the app color. */
@Composable
internal fun FolderCover(folder: BookmarkFolder?, tint: Color, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(11.dp)
    Box(
        modifier.clip(shape).background(Brush.linearGradient(listOf(lerp(tint, Color.White, 0.15f), lerp(tint, Color.Black, 0.45f)))),
        contentAlignment = Alignment.Center
    ) {
        val cover = folder?.cover
        if (!cover.isNullOrBlank()) {
            AsyncImage(
                model = if (cover.startsWith("http")) cover else LocalPlatform.parseUri(cover),
                contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clip(shape)
            )
        } else Icon(Icons.Default.Folder, contentDescription = null, tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.size(20.dp))
    }
}

/**
 * "Add To", for a saved post: Bookmarks (Bluesky's own, always first) and
 * your bookmark folders, each tappable to put the post in or take it out,
 * and a row to make a new folder with a name and a cover. A post is in
 * Bookmarks or in folders, not both (see MainViewModel.placeBookmark).
 * Folders are kept by Private Sync, encrypted, on your PDS.
 */
@Composable
fun BookmarkFolderPopup(item: MediaItem, liquidGlass: Boolean, tint: Color, backdrop: GlassBackdrop?, onClose: () -> Unit) =
    BookmarkFolderPopup(listOf(item), liquidGlass, tint, backdrop, onClose)

/** The same for several posts at once (selection mode): a folder (or
 *  Bookmarks) shows as picked when every one of them is in it; tapping it
 *  puts them all in, in the order they were picked — or, when they all
 *  already are, takes them all out. */
@Composable
fun BookmarkFolderPopup(items: List<MediaItem>, liquidGlass: Boolean, tint: Color, backdrop: GlassBackdrop?, onClose: () -> Unit) {
    if (items.isEmpty()) return
    val item = items.first()
    val context = LocalContext.current
    val tap = rememberHapticTap()
    val scope = rememberCoroutineScope()
    val folders = LocalData.bookmarkFolders
    var creating by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var cover by remember { mutableStateOf<String?>(null) }
    // New folders are private (the lock) unless switched to public.
    var makePublic by remember { mutableStateOf(false) }
    var layout by remember { mutableStateOf("grid") }
    val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            cover = withContext(ioDispatcher) { LocalPlatform.importMedia(context, uri, "covers")?.toString() }
        }
    }
    fun thumbOf(m: MediaItem) = m.thumbUrl.ifBlank { m.mediaUrl }
    // (Read from the folders themselves, so it follows every tap here.)
    val filed = items.all { m -> folders.any { m.postUri in it.posts } }
    var inBookmarks by remember(items.map { it.postUri }) {
        mutableStateOf(items.all { it.isBookmarked && !LocalData.isInAnyBookmarkFolder(it.postUri) })
    }
    fun placed(toBookmarks: Boolean) {
        inBookmarks = toBookmarks
        items.forEach { LocalOverlays.onPlaceBookmark?.invoke(it, toBookmarks) }
    }
    /** Every one of [items] into [folderId] — or all out, when they're all in it. */
    fun toggleAll(folderId: String, allIn: Boolean) {
        for (m in items) {
            val isIn = LocalData.bookmarkFolders.firstOrNull { it.id == folderId }?.posts?.contains(m.postUri) == true
            if (isIn == allIn) LocalData.toggleInBookmarkFolder(folderId, m.postUri, thumbOf(m))
        }
    }

    LocalPopup(title = "Add To", liquidGlass = liquidGlass, tint = tint, onClose = onClose, subtitle = "Folders", backdrop = backdrop) {
        Column(
            Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Bookmarks: where a saved post goes unless it's filed in a folder.
            run {
                val shape = RoundedCornerShape(16.dp)
                val on = inBookmarks && !filed
                Row(
                    Modifier.fillMaxWidth().clip(shape)
                        .background(Color.Black.copy(alpha = 0.26f))
                        .border(1.dp, lerp(tint, Color.White, 0.25f).copy(alpha = if (on) 0.9f else 0.3f), shape)
                        .clickable {
                            tap()
                            if (!on) placed(true)
                            else { inBookmarks = false; items.forEach { LocalOverlays.onPlaceBookmark?.invoke(it, false) } }
                        }
                        .padding(horizontal = 8.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.size(40.dp).clip(RoundedCornerShape(11.dp))
                            .background(Brush.linearGradient(listOf(lerp(tint, Color.White, 0.15f), lerp(tint, Color.Black, 0.45f)))),
                        contentAlignment = Alignment.Center
                    ) { Icon(Icons.Default.Bookmark, contentDescription = null, tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.size(20.dp)) }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Bookmarks", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text("Default Atmosphere Folder", color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
                    }
                    Box(
                        Modifier.size(26.dp).clip(CircleShape)
                            .background(if (on) lerp(tint, Color.White, 0.2f) else Color.White.copy(alpha = 0.10f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(if (on) Icons.Default.Check else Icons.Default.Add, contentDescription = if (on) "In Bookmarks" else "Add", tint = Color.White, modifier = Modifier.size(15.dp))
                    }
                }
            }
            folders.forEach { folder ->
                val inFolder = items.all { it.postUri in folder.posts }
                val shape = RoundedCornerShape(16.dp)
                Row(
                    Modifier.fillMaxWidth().clip(shape)
                        .background(Color.Black.copy(alpha = 0.26f))
                        .border(1.dp, lerp(tint, Color.White, 0.25f).copy(alpha = if (inFolder) 0.9f else 0.3f), shape)
                        .clickable { tap(); toggleAll(folder.id, inFolder); placed(false) }
                        .padding(horizontal = 8.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FolderCover(folder, tint, Modifier.size(40.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                folder.name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)
                            )
                            // Private (the lock) or public (the world), right after its name.
                            Icon(
                                if (folder.public) Icons.Default.Public else Icons.Default.Lock,
                                contentDescription = if (folder.public) "Public" else "Private",
                                tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.padding(start = 6.dp).size(13.dp)
                            )
                        }
                        Text(
                            "${folder.posts.size} post${if (folder.posts.size == 1) "" else "s"}",
                            color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp
                        )
                    }
                    Box(
                        Modifier.size(26.dp).clip(CircleShape)
                            .background(if (inFolder) lerp(tint, Color.White, 0.2f) else Color.White.copy(alpha = 0.10f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            if (inFolder) Icons.Default.Check else Icons.Default.Add, contentDescription = if (inFolder) "In folder" else "Add",
                            tint = Color.White, modifier = Modifier.size(15.dp)
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        if (creating) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // The new folder's cover: tap to pick a picture.
                Box(
                    Modifier.size(44.dp).clip(RoundedCornerShape(11.dp))
                        .background(Color.Black.copy(alpha = 0.3f))
                        .border(1.dp, lerp(tint, Color.White, 0.25f).copy(alpha = 0.6f), RoundedCornerShape(11.dp))
                        .clickable { tap(); coverPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    contentAlignment = Alignment.Center
                ) {
                    val c = cover
                    if (c != null) AsyncImage(
                        model = LocalPlatform.parseUri(c), contentDescription = "Cover", contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    ) else Icon(Icons.Default.Image, contentDescription = "Pick a cover", tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(8.dp))
                LocalTextField(name, { name = it.take(60) }, "Folder name", tint, Modifier.weight(1f), capitalization = KeyboardCapitalization.Words)
                Spacer(Modifier.width(6.dp))
                FolderPrivacyToggle(makePublic, tint) { makePublic = !makePublic }
            }
            Spacer(Modifier.height(10.dp))
            FolderLayoutChoice(layout) { layout = it }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LocalPillButton("Cancel", liquidGlass, tint, { creating = false; name = ""; cover = null; makePublic = false; layout = "grid" }, Modifier.weight(1f))
                LocalPillButton(
                    "Create", liquidGlass, tint,
                    {
                        val made = LocalData.createBookmarkFolder(name, cover, public = makePublic, layout = layout)
                        toggleAll(made.id, false)
                        placed(false)
                        creating = false; name = ""; cover = null; makePublic = false; layout = "grid"
                    },
                    Modifier.weight(1f), enabled = name.isNotBlank()
                )
            }
        } else {
            LocalPillButton("New Folder", liquidGlass, tint, { creating = true }, Modifier.fillMaxWidth())
        }
    }
}

/**
 * A bookmark folder's settings (Saved Posts or your profile → a folder →
 * Edit): its cover, its name, whether it's private or public, and — at the
 * top right — delete. Cover and privacy take effect as they're picked; the
 * name when the popup closes (one write, not one per letter).
 */
@Composable
fun FolderEditPopup(id: String, liquidGlass: Boolean, tint: Color, backdrop: GlassBackdrop?, onClose: () -> Unit) {
    val folder = LocalData.bookmarkFolders.firstOrNull { it.id == id }
    // (Deleted, or gone from another device meanwhile.)
    if (folder == null) { LaunchedEffect(Unit) { onClose() }; return }
    val context = LocalContext.current
    val tap = rememberHapticTap()
    val scope = rememberCoroutineScope()
    var name by remember(id) { mutableStateOf(folder.name) }
    val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val picked = withContext(ioDispatcher) { LocalPlatform.importMedia(context, uri, "covers")?.toString() }
            if (picked != null) {
                // (The old picture, if it was one picked here, goes.)
                folder.cover?.takeIf { !it.startsWith("http") }?.let { LocalPlatform.deleteMedia(context, it) }
                LocalData.updateBookmarkFolder(id, cover = picked)
            }
        }
    }
    fun close() {
        if (name.isNotBlank() && name.trim() != folder.name) LocalData.updateBookmarkFolder(id, name = name)
        onClose()
    }
    LocalPopup(
        title = "Edit Folder", liquidGlass = liquidGlass, tint = tint, onClose = ::close, backdrop = backdrop,
        onDelete = {
            folder.cover?.takeIf { !it.startsWith("http") }?.let { LocalPlatform.deleteMedia(context, it) }
            LocalData.deleteBookmarkFolder(id)
            // Showing it now: back to Bookmarks (or off the folder's page).
            if (LocalOverlays.bookmarkFolderId == id) {
                if (LocalOverlays.savedPostsShowing) LocalOverlays.onShowBookmarkFolder?.invoke(null)
                else LocalOverlays.backFromFeed?.invoke()
            }
            onClose()
        }
    ) {
        // (Laid out like making a folder: cover, name and privacy in one
        // row, then its layout.)
        Row(verticalAlignment = Alignment.CenterVertically) {
            // The cover: tap to pick a picture. A picked one has a small X
            // to go back to the folder's own.
            Box(Modifier.size(44.dp)) {
                Box(
                    Modifier.fillMaxSize().clip(RoundedCornerShape(11.dp))
                        .background(Color.Black.copy(alpha = 0.3f))
                        .border(1.dp, lerp(tint, Color.White, 0.25f).copy(alpha = 0.6f), RoundedCornerShape(11.dp))
                        .clickable { tap(); coverPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                ) { FolderCover(folder, tint, Modifier.fillMaxSize()) }
                if (folder.hasCustomCover) Box(
                    Modifier.align(Alignment.TopEnd).offset(x = 6.dp, y = (-6).dp).size(20.dp).clip(CircleShape)
                        .background(lerp(tint, Color.Black, 0.2f))
                        .border(1.dp, lerp(tint, Color.White, 0.4f), CircleShape)
                        .clickable {
                            tap()
                            LocalOverlays.confirm = ConfirmRequest("Remove Custom Cover?", "", "Remove", tint, destructive = false) {
                                folder.cover?.takeIf { !it.startsWith("http") }?.let { LocalPlatform.deleteMedia(context, it) }
                                LocalData.updateBookmarkFolder(id, clearCover = true)
                            }
                        },
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Default.Close, contentDescription = "Remove custom cover", tint = Color.White, modifier = Modifier.size(12.dp)) }
            }
            Spacer(Modifier.width(8.dp))
            LocalTextField(name, { name = it.take(60) }, "Folder name", tint, Modifier.weight(1f), capitalization = KeyboardCapitalization.Words)
            Spacer(Modifier.width(6.dp))
            FolderPrivacyToggle(folder.public, tint) { LocalData.setBookmarkFolderPublic(id, !folder.public) }
        }
        Spacer(Modifier.height(10.dp))
        FolderLayoutChoice(folder.layout) { LocalData.setBookmarkFolderLayout(id, it) }
        Spacer(Modifier.height(10.dp))
        LocalPillButton("Done", liquidGlass, tint, ::close, Modifier.fillMaxWidth())
    }
}

// ── Profile notes ───────────────────────────────────────────────────────

/** A private note about a profile (More → the note icon). Kept only on
 *  this device; shown above the bio on that profile. */
@Composable
fun ProfileNotePopup(author: AuthorInfo, liquidGlass: Boolean, tint: Color, backdrop: GlassBackdrop?, onClose: () -> Unit) {
    val existing = remember(author.did) { LocalData.profileNote(author.did) }
    var text by remember(author.did) { mutableStateOf(existing) }
    val label = when {
        existing.isBlank() -> "Add"
        text.isBlank() -> "Remove Note"
        else -> "Save"
    }
    LocalPopup(title = "Profile Note", liquidGlass = liquidGlass, tint = tint, onClose = onClose, backdrop = backdrop) {
        Text(
            "Only you can see this (it syncs to your other devices encrypted).",
            color = Color.White.copy(alpha = 0.65f), fontSize = 12.sp, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
        )
        Box(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            LocalTextField(
                text, { text = it }, "Write a note about @${author.handle}…", tint,
                singleLine = false, minHeight = 130.dp
            )
        }
        Spacer(Modifier.height(10.dp))
        LocalPillButton(
            label, liquidGlass, tint,
            { LocalData.setProfileNote(author.did, text); onClose() },
            Modifier.fillMaxWidth(),
            enabled = existing.isNotBlank() || text.isNotBlank(),
            destructive = existing.isNotBlank() && text.isBlank()
        )
    }
}

// ── Feed Builder ────────────────────────────────────────────────────────

/**
 * The Feed Builder: a feed (and, from the Hub or a profile's Home tab, a
 * row) made from any mix of sources — your lists, any list by its link,
 * accounts, hashtags, tags from the tag database and bookmark folders —
 * then which kinds of posts it shows, their order, and how its row looks.
 * Laid out as cards, top to bottom in the order it's built: name, sources,
 * what to show, order, layout.
 *
 * [onlyPublicFolders] (a profile's Home tab): only public folders can be
 * added, since the Home tab is public. [onSave] saves somewhere other than
 * this device's feeds (the Home tab's own records); [onDelete] likewise.
 */
@Composable
fun FeedBuilderPopup(
    initial: LocalFeed,
    myLists: List<BskyList>,
    liquidGlass: Boolean,
    tint: Color,
    backdrop: GlassBackdrop?,
    onLoadLists: () -> Unit,
    onResolveList: (String, (LocalFeedSource?, String?) -> Unit) -> Unit,
    onResolveAccount: (String, (LocalFeedSource?, String?) -> Unit) -> Unit,
    onSaved: (LocalFeed) -> Unit,
    onDeleted: (LocalFeed) -> Unit,
    onClose: () -> Unit,
    onSuggestTags: (String, (List<String>) -> Unit) -> Unit = { _, done -> done(emptyList()) },
    selfDid: String = "",
    onlyPublicFolders: Boolean = false,
    onSave: ((LocalFeed) -> LocalFeed)? = null,
    onDelete: ((LocalFeed) -> Unit)? = null,
    title: String = "Feed Creator"
) {
    val tap = rememberHapticTap()
    val editing = initial.id.isNotBlank()
    var name by remember { mutableStateOf(initial.name) }
    var sources by remember { mutableStateOf(initial.sources) }
    var textPosts by remember { mutableStateOf(initial.textPosts) }
    var images by remember { mutableStateOf(initial.images) }
    var hVideos by remember { mutableStateOf(initial.horizontalVideos) }
    var vVideos by remember { mutableStateOf(initial.verticalVideos) }
    var polls by remember { mutableStateOf(initial.polls) }
    var sort by remember { mutableStateOf(initial.sort) }
    var style by remember { mutableStateOf(initial.style) }
    var rowCount by remember { mutableStateOf(initial.rows.coerceIn(1, 3)) }
    var blogs by remember { mutableStateOf(initial.blogs) }
    var reviews by remember { mutableStateOf(initial.reviews) }
    var reposts by remember { mutableStateOf(initial.reposts) }
    var livestreams by remember { mutableStateOf(initial.livestreams) }
    // Which "add" panel is open.
    var adding by remember { mutableStateOf("folders") }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var tagIdeas by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(Unit) { onLoadLists() }
    // Tags: what's typed so far, completed from the tag database as it's typed.
    LaunchedEffect(adding, input) {
        if (adding != "tag" || input.isBlank()) { tagIdeas = emptyList(); return@LaunchedEffect }
        kotlinx.coroutines.delay(150)
        onSuggestTags(input.trim()) { tagIdeas = it.take(8) }
    }

    fun add(source: LocalFeedSource) {
        if (sources.none { it.kind == source.kind && it.value == source.value }) sources = sources + source
    }
    fun submitInput() {
        val t = input.trim()
        if (t.isEmpty() || busy) return
        error = null
        when (adding) {
            "hashtag" -> {
                val tag = t.removePrefix("#").filter { it.isLetterOrDigit() || it == '_' }
                if (tag.isNotEmpty()) { add(LocalFeedSource("hashtag", tag, "#$tag")); input = "" }
            }
            "tag" -> { val tag = t.lowercase().replace(' ', '_'); add(LocalFeedSource("tag", tag, tag.replace('_', ' '))); input = "" }
            "link" -> { busy = true; onResolveList(t) { src, err -> busy = false; if (src != null) { add(src); input = "" } else error = err } }
            "account" -> { busy = true; onResolveAccount(t) { src, err -> busy = false; if (src != null) { add(src); input = "" } else error = err } }
        }
    }
    val accent = lerp(tint, Color.White, 0.45f)

    @Composable
    fun Card(heading: String, sub: String? = null, content: @Composable () -> Unit) {
        val shape = RoundedCornerShape(18.dp)
        Column(
            Modifier.fillMaxWidth().clip(shape).background(Color.Black.copy(alpha = 0.22f))
                .border(1.dp, lerp(tint, Color.White, 0.2f).copy(alpha = 0.25f), shape)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(heading, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            if (sub != null) Text(sub, color = Color.White.copy(alpha = 0.55f), fontSize = 11.sp, lineHeight = 14.sp)
            content()
        }
    }
    @Composable
    fun Chips(content: @Composable () -> Unit) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { content() }
    }

    // (Only the X closes it: a stray tap outside would lose the feed.)
    // (One height whatever's in it, so adding or removing a source doesn't
    // make it jump up and down as it re-centers.)
    // Deleting a feed: the trash at the top right (then "Really?").
    LocalPopup(
        title = title, liquidGlass = liquidGlass, tint = tint, onClose = onClose, maxHeight = 680.dp, backdrop = backdrop,
        dismissOnOutsideTap = false, fixedHeight = true,
        onDelete = if (!editing) null else ({
            if (onDelete != null) onDelete(initial) else LocalData.deleteLocalFeed(initial.id)
            onDeleted(initial); onClose()
        })
    ) {
        Column(
            Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            LocalTextField(name, { name = it.take(40) }, "Name it", tint, capitalization = KeyboardCapitalization.Words)

            // ── Sources ──
            Card("Posts From") {
                sources.forEach { src ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.06f))
                            .padding(start = 10.dp, end = 4.dp, top = 5.dp, bottom = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            when (src.kind) {
                                "list" -> "List"; "account" -> "Account"; "hashtag" -> "Hashtag"; "tag" -> "Tag"
                                else -> "Folder"
                            },
                            color = accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(62.dp)
                        )
                        Text(
                            src.label.ifBlank { src.value }, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                        )
                        Box(
                            Modifier.size(28.dp).clip(CircleShape).clickable { tap(); sources = sources - src },
                            contentAlignment = Alignment.Center
                        ) { Icon(Icons.Default.Close, contentDescription = "Remove", tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(14.dp)) }
                    }
                }
                Chips {
                    LocalChip("Folders", adding == "folders", tint, { adding = "folders"; error = null })
                    LocalChip("My Lists", adding == "lists", tint, { adding = "lists"; error = null })
                    LocalChip("Accounts", adding == "account", tint, { adding = "account"; input = ""; error = null })
                    LocalChip("Hashtags", adding == "hashtag", tint, { adding = "hashtag"; input = ""; error = null })
                    LocalChip("Tags", adding == "tag", tint, { adding = "tag"; input = ""; error = null })
                    LocalChip("List Link", adding == "link", tint, { adding = "link"; input = ""; error = null })
                }
                when (adding) {
                    "lists" -> {
                        if (myLists.isEmpty()) Text("You don't have any lists yet.", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
                        myLists.forEach { list ->
                            val added = sources.any { it.kind == "list" && it.value == list.uri }
                            PickRow(list.name, added, tint) {
                                sources = if (added) sources.filterNot { it.kind == "list" && it.value == list.uri }
                                else sources + LocalFeedSource("list", list.uri, list.name)
                            }
                        }
                    }
                    "folders" -> {
                        val folders = LocalData.bookmarkFolders.filter { !onlyPublicFolders || it.public }
                        if (folders.isEmpty()) Text(
                            if (onlyPublicFolders) "No public folders yet" else "No folders yet",
                            color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp
                        )
                        folders.forEach { f ->
                            // A public one is added by its public address, so
                            // anyone shown this feed sees its posts too.
                            val src = if (f.public && selfDid.isNotBlank()) LocalFeedSource("publicFolder", "$selfDid/${f.id}", f.name)
                                else LocalFeedSource("folder", f.id, f.name)
                            val added = sources.any { (it.kind == "folder" && it.value == f.id) || (it.kind == "publicFolder" && it.value.endsWith("/" + f.id)) }
                            PickRow(f.name, added, tint, icon = if (f.public) Icons.Default.Public else Icons.Default.Lock) {
                                sources = if (added) sources.filterNot { (it.kind == "folder" && it.value == f.id) || (it.kind == "publicFolder" && it.value.endsWith("/" + f.id)) }
                                else sources + src
                            }
                        }
                    }
                    else -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // (An account: the composer's own @mention guess —
                            // the single most likely account, dimmed after
                            // what's typed; Tab, a swipe right, Done or Add takes it.)
                            var accountGuess by remember { mutableStateOf<String?>(null) }
                            if (adding == "account") AccountGuessField(
                                input, { input = it; error = null }, tint, Modifier.weight(1f),
                                onGuess = { accountGuess = it }, onDone = { submitInput() }
                            ) else LocalTextField(
                                input, { input = it; error = null },
                                when (adding) {
                                    "link" -> "https://bsky.app/profile/…/lists/…"
                                    "tag" -> "A tag, like red_fox"
                                    else -> "hashtag"
                                },
                                tint, Modifier.weight(1f), capitalization = KeyboardCapitalization.None
                            )
                            Spacer(Modifier.width(8.dp))
                            LocalPillButton(if (busy) "…" else "Add", liquidGlass, tint, {
                                if (adding == "account") accountGuess?.let { input = it }
                                submitInput()
                            }, enabled = input.isNotBlank() && !busy)
                        }
                        if (adding == "tag" && tagIdeas.isNotEmpty()) Chips {
                            tagIdeas.forEach { idea ->
                                LocalChip(idea.replace('_', ' '), false, tint, { add(LocalFeedSource("tag", idea, idea.replace('_', ' '))); input = "" })
                            }
                        }
                        error?.let { Text(it, color = Color(0xFFFF6B8A), fontSize = 12.sp) }
                    }
                }
            }

            // ── What it shows ──
            Card("Show") {
                Chips {
                    LocalChip("Text Posts", textPosts, tint, { textPosts = !textPosts })
                    LocalChip("Images", images, tint, { images = !images })
                    LocalChip("Horizontal Videos", hVideos, tint, { hVideos = !hVideos })
                    LocalChip("Vertical Videos", vVideos, tint, { vVideos = !vVideos })
                    LocalChip("Polls", polls, tint, { polls = !polls })
                    LocalChip("Reposts", reposts, tint, { reposts = !reposts })
                    LocalChip("Blogs", blogs, tint, { blogs = !blogs })
                    LocalChip("Reviews", reviews, tint, { reviews = !reviews })
                    LocalChip("Livestreams", livestreams, tint, { livestreams = !livestreams })
                }
            }

            // ── Order ──
            Card("Order") {
                Chips {
                    listOf("recent" to "Most Recent", "oldest" to "Oldest First", "likes" to "Most Liked", "reposts" to "Most Reposted", "replies" to "Most Replies")
                        .forEach { (id, label) -> LocalChip(label, sort == id, tint, { sort = id }) }
                }
            }

            // ── Layout ──
            Card("Layout") {
                Chips {
                    listOf(
                        "natural" to "As Posted", "square" to "Squares", "tiktok" to "Portrait",
                        "youtube" to "Horizontal", "avatars" to "Profiles Only"
                    ).forEach { (id, label) -> LocalChip(label, style == id, tint, { style = id }) }
                }
                if (style != "avatars") Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Rows", color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp, modifier = Modifier.padding(end = 4.dp))
                    (1..3).forEach { n -> LocalChip("$n", rowCount == n, tint, { rowCount = n }) }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LocalPillButton(
                if (editing) "Save" else "Create", liquidGlass, tint,
                {
                    val feed = initial.copy(
                        name = name.ifBlank { "My Feed" }, sources = sources,
                        textPosts = textPosts, images = images, horizontalVideos = hVideos, verticalVideos = vVideos,
                        polls = polls, sort = sort, style = style, rows = rowCount,
                        blogs = blogs, reviews = reviews, reposts = reposts, livestreams = livestreams
                    )
                    val saved = if (onSave != null) onSave(feed) else LocalData.saveLocalFeed(feed)
                    onSaved(saved)
                    onClose()
                },
                Modifier.weight(1f),
                enabled = sources.isNotEmpty() && (textPosts || images || hVideos || vVideos || polls || reposts || blogs || reviews || livestreams)
            )
        }
    }
}

/** One pickable line in the Feed Builder (a list, a folder): tap to add or take out. */
@Composable
private fun PickRow(label: String, added: Boolean, tint: Color, icon: androidx.compose.ui.graphics.vector.ImageVector? = null, onClick: () -> Unit) {
    val tap = rememberHapticTap()
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(if (added) lerp(tint, Color.White, 0.1f).copy(alpha = 0.35f) else Color.White.copy(alpha = 0.05f))
            .clickable { tap(); onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(label, color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Icon(
            if (added) Icons.Default.Check else Icons.Default.Add, contentDescription = null,
            tint = Color.White.copy(alpha = if (added) 1f else 0.6f), modifier = Modifier.size(16.dp)
        )
    }
}

@Composable
private fun BuilderLabel(text: String) {
    Text(
        text, color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 4.dp)
    )
}


/** A folder's Private (lock, encrypted) / Public (world, on your PDS for
 *  anyone) switch, beside its name. */
@Composable
fun FolderPrivacyToggle(public: Boolean, tint: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val tap = rememberHapticTap()
    Box(
        modifier.size(40.dp).clip(CircleShape)
            .background(if (public) lerp(tint, Color.White, 0.2f).copy(alpha = 0.55f) else Color.White.copy(alpha = 0.10f))
            .clickable { tap(); onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            if (public) Icons.Default.Public else Icons.Default.Lock,
            contentDescription = if (public) "Public" else "Private",
            tint = Color.White, modifier = Modifier.size(18.dp)
        )
    }
}

/** How a folder is laid out when opened: the usual grid, tall (vertical)
 *  videos, or wide (horizontal) videos with their words. */
@Composable
fun FolderLayoutChoice(layout: String, onPick: (String) -> Unit) {
    val options = listOf("grid", "vertical", "horizontal")
    Text("Folder Layout", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 2.dp, bottom = 6.dp))
    ConnectedChoice(
        options = listOf(ChoiceOption("Grid"), ChoiceOption("Vertical"), ChoiceOption("Horizontal")),
        selected = options.indexOf(layout).coerceAtLeast(0),
        onSelect = { onPick(options[it]) },
        modifier = Modifier.fillMaxWidth(), height = 38.dp, fill = true
    )
}

/**
 * Feed Creator → Accounts: a handle field with the composer's @mention
 * autocomplete — the single most likely account, dimmed after what's typed
 * (see MentionSuggest); Tab, a swipe right across it, or Done takes it.
 * [onGuess] hears the whole handle it would complete to (null = none).
 */
@Composable
private fun AccountGuessField(
    value: String, onValueChange: (String) -> Unit, tint: Color, modifier: Modifier,
    onGuess: (String?) -> Unit, onDone: () -> Unit
) {
    var field by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(value)) }
    if (field.text != value) field = androidx.compose.ui.text.input.TextFieldValue(value, androidx.compose.ui.text.TextRange(value.length))
    // The single most likely account: by a handle's start (the rest of it
    // shown dimmed after the caret, as in the composer) or a display name's
    // (its handle shown dimmed after what's typed) — with or without "@".
    var guess by remember { mutableStateOf<com.mediaviewer.util.MentionSuggest.Candidate?>(null) }
    val typed = value.trim().removePrefix("@")
    LaunchedEffect(typed) {
        if (typed.isEmpty()) { guess = null; onGuess(null); return@LaunchedEffect }
        kotlinx.coroutines.delay(150)
        guess = com.mediaviewer.util.MentionSuggest.bestAccount(typed)
        onGuess(guess?.handle?.lowercase())
    }
    val handleMatch = guess?.handle?.lowercase()?.takeIf { typed.isNotEmpty() && it.startsWith(typed.lowercase()) }
    val ghost = when {
        typed.isEmpty() || guess == null -> null
        handleMatch != null -> handleMatch.takeIf { it.length > typed.length }?.drop(typed.length)
        else -> "  @" + guess!!.handle.lowercase()
    }
    fun accept(): Boolean {
        val g = guess?.handle?.lowercase()?.takeIf { typed.isNotEmpty() } ?: return false
        if (g == typed.lowercase()) return false
        guess = null; onGuess(null)
        onValueChange(g)
        return true
    }
    val transformation = if (ghost == null || field.selection.end != field.text.length) androidx.compose.ui.text.input.VisualTransformation.None
        else remember(ghost, field.selection.end) { ghostTransformation(androidx.compose.ui.text.input.VisualTransformation.None, field.selection.end, ghost) }
    // A left-to-right swipe across the field takes the guess — the
    // composer's own watcher, on the field's box, reading presses before
    // the text field's drag handling can make a selection of them. (A
    // drag detector on the field itself lost that race — a mouse drag
    // most of all — so the swipe never landed here.)
    val ghostShown = androidx.compose.runtime.rememberUpdatedState(ghost != null)
    val acceptLatest = androidx.compose.runtime.rememberUpdatedState { accept() }
    val swipeTap = rememberHapticTap()
    Box(
        modifier.fillMaxWidth().heightIn(min = 44.dp).popupFieldWell(tint, RoundedCornerShape(22.dp)).padding(horizontal = 14.dp, vertical = 10.dp)
            .pointerInput(Unit) {
                val reach = 36.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                    if (!ghostShown.value) return@awaitEachGesture
                    var dx = 0f
                    var dy = 0f
                    while (true) {
                        val ev = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) break
                        val d = ch.positionChange()
                        dx += d.x; dy += d.y
                        if (kotlin.math.abs(dy) > reach || dx < -reach) break
                        if (dx > reach && dx > kotlin.math.abs(dy) * 2f) {
                            if (acceptLatest.value()) {
                                swipeTap()
                                // The rest of this swipe is ours, not a text selection.
                                do {
                                    val more = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                                    more.changes.forEach { it.consume() }
                                } while (more.changes.any { it.pressed })
                            }
                            break
                        }
                    }
                }
            },
        contentAlignment = Alignment.CenterStart
    ) {
        val themeFamily = androidx.compose.material3.LocalTextStyle.current.fontFamily
        val fieldStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 14.sp, lineHeight = 20.sp).let { if (it.fontFamily == null) it.copy(fontFamily = themeFamily) else it }
        androidx.compose.foundation.text.BasicTextField(
            value = field, onValueChange = { field = it; onValueChange(it.text) },
            singleLine = true, visualTransformation = transformation,
            textStyle = fieldStyle,
            cursorBrush = androidx.compose.ui.graphics.SolidColor(Color.White),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(capitalization = KeyboardCapitalization.None, imeAction = androidx.compose.ui.text.input.ImeAction.Done),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { if (!accept()) onDone() }),
            modifier = Modifier.fillMaxWidth()
                .then(androidx.compose.ui.Modifier.onPreviewKeyEvent { e ->
                    e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown && e.key == androidx.compose.ui.input.key.Key.Tab && ghost != null && accept()
                })
        )
        if (value.isEmpty()) Text("Name or handle", color = Color.White.copy(alpha = 0.45f), fontSize = 14.sp, maxLines = 1)
    }
}
