package com.mediaviewer.util

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mediaviewer.json.StellarJson
import com.mediaviewer.model.MediaItem
import com.mediaviewer.platform.PlatformContext
import com.mediaviewer.platform.SharedPreferences
import com.mediaviewer.platform.sharedPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/** One file that belonged to an archived post (a picture or its video),
 *  exactly as it was stored on the PDS. */
@Serializable
data class ArchivedBlob(
    /** The blob's CID on the PDS. */
    val cid: String = "",
    val mimeType: String = "",
    val size: Long = 0L,
    /** Where the copy lives on this device (file://…). */
    val file: String = ""
)

/**
 * A post taken off your profile and kept only on this device: its complete
 * record exactly as it was on the PDS (text, facets, labels, reply/quote
 * references, original createdAt, edit history…), every file it had, and
 * how it looked — so it can be shown offline and put back as it was.
 */
@Serializable
data class ArchivedPost(
    /** The post's record key: the last part of its at:// link. */
    val rkey: String = "",
    val did: String = "",
    val uri: String = "",
    val archivedAt: Long = 0L,
    /** The app.bsky.feed.post record, as JSON text. */
    val record: String = "",
    val blobs: List<ArchivedBlob> = emptyList(),
    /** The post as the feed shows it, pointing at the local files. */
    val item: MediaItem = MediaItem()
)

/**
 * Archived posts (supporters). Nothing here is ever sent anywhere: the list
 * is one private preferences file and the media sits in the app's private
 * storage (see PrivateFiles) until the post is added back or deleted.
 */
object PostArchive {
    /** Feed items made from the archive carry this in front of their id. */
    const val ID_PREFIX = "archive:"
    const val FOLDER = "archive"

    private var prefs: SharedPreferences? = null

    var posts by mutableStateOf<List<ArchivedPost>>(emptyList())
        private set

    fun init(context: PlatformContext) {
        if (prefs != null) return
        val p = context.sharedPreferences("post_archive")
        prefs = p
        posts = p.getString("posts", null)?.let {
            runCatching { StellarJson.default.decodeFromString(ListSerializer(ArchivedPost.serializer()), it) }.getOrNull()
        } ?: emptyList()
    }

    private fun save(list: List<ArchivedPost>) {
        posts = list
        runCatching {
            prefs?.edit()?.putString("posts", StellarJson.default.encodeToString(ListSerializer(ArchivedPost.serializer()), list))?.commit()
        }
    }

    fun isArchived(item: MediaItem?): Boolean = item != null && item.id.startsWith(ID_PREFIX)

    /** The archived post a feed item was made from. */
    fun find(item: MediaItem): ArchivedPost? = posts.firstOrNull { ID_PREFIX + it.did + "/" + it.rkey == item.id }

    /** Newest archived first; one account's only. */
    fun itemsFor(did: String): List<MediaItem> =
        posts.filter { it.did == did }.sortedByDescending { it.archivedAt }.map { it.item }

    fun add(post: ArchivedPost) { save(listOf(post) + posts.filterNot { it.did == post.did && it.rkey == post.rkey }) }

    fun remove(post: ArchivedPost) { save(posts.filterNot { it.did == post.did && it.rkey == post.rkey }) }
}
