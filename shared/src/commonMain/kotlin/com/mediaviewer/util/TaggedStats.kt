package com.mediaviewer.util

import com.mediaviewer.platform.PlatformContext
import com.mediaviewer.platform.PrivateFiles
import com.mediaviewer.platform.currentTimeMillis
import com.mediaviewer.platform.synchronizedCompat

/** One tagged post's numbers, as Bluesky last reported them. */
class PostStat(
    val likes: Int, val reposts: Int, val saves: Int, val replies: Int,
    /** When it was posted (ms since 1970), 0 if unknown. */
    val createdMs: Long,
    /** When these numbers were fetched. */
    val fetchedMs: Long,
    /** The post no longer exists (deleted, or its account is gone). */
    val gone: Boolean = false
)

/**
 * The like / repost / save / comment counts and post dates of every post in
 * the tagged dataset, kept on the phone — the Tagged search's index.
 *
 * This is how sites like e621 sort fast: the numbers every sort needs are
 * stored next to the posts, so "most liked" is a sort of a few thousand
 * numbers on the phone, not a download of thousands of posts. Only the one
 * screenful being shown is then fetched in full, and more as you scroll.
 * Counts are filled in the first time a post is sorted on, then updated
 * from every page that's loaded — no separate refreshing.
 *
 * Saved as one small tab-separated file in the app's private storage.
 */
object TaggedStats {
    private const val FOLDER = "tag_stats"
    private const val FILE = "stats.tsv"

    private val map = HashMap<String, PostStat>()
    private var loaded = false
    private var context: PlatformContext? = null
    private var dirty = false

    fun init(context: PlatformContext) {
        synchronizedCompat(map) {
            if (loaded) return
            this.context = context
            loaded = true
            val bytes = runCatching { PrivateFiles.read(context, PrivateFiles.rootUri(context) + "/" + FOLDER + "/" + FILE) }.getOrNull() ?: return
            bytes.decodeToString().lineSequence().forEach { line ->
                val f = line.split('\t')
                if (f.size < 8 || f[0].isBlank()) return@forEach
                map[f[0]] = PostStat(
                    likes = f[1].toIntOrNull() ?: 0, reposts = f[2].toIntOrNull() ?: 0,
                    saves = f[3].toIntOrNull() ?: 0, replies = f[4].toIntOrNull() ?: 0,
                    createdMs = f[5].toLongOrNull() ?: 0L, fetchedMs = f[6].toLongOrNull() ?: 0L,
                    gone = f[7] == "1"
                )
            }
        }
    }

    fun get(uri: String): PostStat? = synchronizedCompat(map) { map[uri] }

    /** Of [uris], the ones with no numbers yet. */
    fun missing(uris: List<String>): List<String> = synchronizedCompat(map) { uris.filter { it !in map } }

    fun putAll(stats: Map<String, PostStat>) {
        if (stats.isEmpty()) return
        synchronizedCompat(map) { map.putAll(stats); dirty = true }
    }

    /** Writes the file if anything changed. Call off the main thread. */
    fun save() {
        val c = context ?: return
        val text = synchronizedCompat(map) {
            if (!dirty) return
            dirty = false
            buildString {
                for ((uri, s) in map) {
                    append(uri).append('\t').append(s.likes).append('\t').append(s.reposts).append('\t')
                        .append(s.saves).append('\t').append(s.replies).append('\t').append(s.createdMs).append('\t')
                        .append(s.fetchedMs).append('\t').append(if (s.gone) "1" else "0").append('\n')
                }
            }
        }
        runCatching { PrivateFiles.write(c, FOLDER, FILE, text.encodeToByteArray()) }
    }

    /**
     * [uris] in [sort]'s order (0 most liked, 1 most reposted, 2 most saved,
     * 3 most comments, 4 most recently uploaded, 5 most recently tagged —
     * [uris] is already in that last order). Deleted posts are left out.
     * Ties keep their most-recently-tagged order.
     */
    fun order(uris: List<String>, sort: Int): List<String> {
        val stats = synchronizedCompat(map) { uris.associateWith { map[it] } }
        val alive = uris.filter { stats[it]?.gone != true }
        fun key(uri: String): Long {
            val s = stats[uri] ?: return Long.MIN_VALUE
            return when (sort) {
                0 -> s.likes.toLong()
                1 -> s.reposts.toLong()
                2 -> s.saves.toLong()
                3 -> s.replies.toLong()
                4 -> s.createdMs
                else -> 0L
            }
        }
        return if (sort !in 0..4) alive else alive.sortedByDescending { key(it) }
    }
}
