package com.mediaviewer.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.mediaviewer.model.MediaItem
import com.mediaviewer.model.PopfeedBacklogItem
import com.mediaviewer.model.PopfeedReview
import com.mediaviewer.model.TitleSearchResult

// UI-level types the ViewModel also uses (moved from ComposePostScreen.kt /
// ProfileOverlay.kt so shared code can see them). Same package, same names.

enum class ComposeMode { SINGLE, THREAD, TEXTSHOT, VIDEO, REVIEW, BLOG }


/** One post's worth of content inside a [ComposeMode.THREAD] thread. */
data class ThreadPostDraft(
    val text: String,
    val images: List<com.mediaviewer.platform.PlatformUri> = emptyList(),
    val video: com.mediaviewer.platform.PlatformUri? = null,
    /** Alt text per picture, by index ("" = none). */
    val imageAlts: List<String> = emptyList()
)


/** Everything the composer collected, handed to the caller on "Post". */
data class ComposePostDraft(
    val mode: ComposeMode,
    /** SINGLE: exactly one entry. THREAD: two or more, in posting order.
     *  REVIEW: exactly one entry, carrying just the typed review text (no
     *  images — see reviewTarget's own doc comment below for why). */
    val posts: List<ThreadPostDraft> = emptyList(),
    val videoUri: com.mediaviewer.platform.PlatformUri? = null,
    val videoThumbnailUri: com.mediaviewer.platform.PlatformUri? = null,
    val videoTitle: String = "",
    val videoDescription: String = "",
    /** The video's alt text. */
    val videoAlt: String = "",
    val textshotText: String = "",
    /** Textshot mode: the post's own (regular Bluesky) text, separate from
     *  the text rendered into the image — for hashtags, a caption… */
    val textshotPostText: String = "",
    // Item 10: the title being reviewed, and the picked star rating on
    // Popfeed's own native 0–10 half-star scale (so 0 = unrated, 10 = full
    // 5 stars) — both only populated for ComposeMode.REVIEW. Image
    // attach/Textshot/Blog/thread are greyed out for the whole lifetime of
    // a review draft (see ComposePostScreen's reviewTarget param), so the
    // review itself is always exactly one plain-text post.
    val reviewTarget: TitleSearchResult? = null,
    val reviewRating: Int = 0,
    // Item 12: mirrors social.popfeed.feed.review's own "containsSpoilers"
    // boolean (see review.json) — set from the composer's "Mark as Spoiler"
    // toggle, only meaningful for ComposeMode.REVIEW.
    val reviewContainsSpoilers: Boolean = false,
    // Bluesky self-label values picked via the composer's "Labels" popup
    // (e.g. "sexual", "nudity", "porn", "graphic-media"). Empty = no labels.
    // Applied to every post the draft produces (all posts of a thread).
    val selfLabels: List<String> = emptyList(),
    // Item 12: ComposeMode.BLOG — the whole blog (title, description, rows).
    val blog: com.mediaviewer.model.BlogDraft? = null,
    /** Editing an existing post (More → Edit): the post record is rewritten
     *  in place instead of a new one being created. */
    val editingPost: EditPostTarget? = null,
    /** A poll (ComposeMode.SINGLE): the answers, in A, B, C… order; the
     *  question is posts[0].text. Empty = not a poll. */
    val pollOptions: List<String> = emptyList(),
    /** The saved draft this was loaded from (deleted once it's posted). */
    val fromDraftId: String? = null
)

/** A post of your own opened in the composer to be edited. */
data class EditPostTarget(
    val postUri: String,
    val text: String,
    /** The post's current pictures (full-size URLs), in order. */
    val imageUrls: List<String> = emptyList(),
    val labels: List<String> = emptyList(),
    /** False for posts whose attachment isn't a set of pictures (a video,
     *  a quote, a link card): only the text and labels can change. */
    val imagesEditable: Boolean = true
)

/** What the composer should open with (set just before it opens). */
object ComposerSeed {
    var editPost by androidx.compose.runtime.mutableStateOf<EditPostTarget?>(null)
}

/** Poll posts are plain text: "Poll:" on the first line, then "Q. …" and
 *  one lettered line per answer ("A. …", "B. …"). That shape is how Stellar
 *  recognises a poll. */
object PollFormat {
    const val HEADER = "Poll:"
    const val MAX_OPTIONS = 6

    fun letter(index: Int): String = ('A' + index).toString()

    fun postText(question: String, options: List<String>): String =
        (listOf(HEADER, "Q. " + question.trim()) + options.mapIndexed { i, o -> letter(i) + ". " + o.trim() }).joinToString("\n")

    /** The question and answers back out of a poll post's text. */
    fun parse(text: String): Pair<String, List<String>>? {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.size < 4 || !lines[0].equals(HEADER, ignoreCase = true)) return null
        val q = lines[1].takeIf { it.startsWith("Q.") } ?: return null
        val options = ArrayList<String>()
        for (line in lines.drop(2)) {
            val expected = letter(options.size) + "."
            if (!line.startsWith(expected)) break
            options += line.removePrefix(expected).trim()
        }
        if (options.size < 2) return null
        return q.removePrefix("Q.").trim() to options
    }
}


/**
 * What kinds of post a list shows. Profiles offer All, Media and Text Posts
 * ([PROFILE_POST_KINDS]); Media and Text Posts each narrow further with the
 * pill at the row's right ([facetsOf]): Both / Images / Videos, and
 * Both / Posts / Polls. Horizontal and Vertical Videos (and their own
 * layouts) are kept for search and for bringing back later.
 * (New kinds go at the end: a post's kinds are kept as bits by position.)
 */
enum class PostKindFilter { ALL, IMAGES, TEXT_POSTS, HORIZONTAL_VIDEOS, VERTICAL_VIDEOS, MEDIA, VIDEOS, TEXT_ONLY, POLLS }

fun PostKindFilter.matches(item: MediaItem) = when (this) {
    PostKindFilter.ALL              -> true
    PostKindFilter.IMAGES            -> !item.isVideo && !item.isTextOnly
    PostKindFilter.TEXT_POSTS        -> item.isTextOnly
    PostKindFilter.HORIZONTAL_VIDEOS -> item.isHorizontalVideo
    PostKindFilter.VERTICAL_VIDEOS   -> item.isVerticalVideo
    PostKindFilter.MEDIA             -> !item.isTextOnly
    PostKindFilter.VIDEOS            -> item.isVideo
    PostKindFilter.TEXT_ONLY         -> item.isTextOnly && !item.isPoll
    PostKindFilter.POLLS             -> item.isTextOnly && item.isPoll
}

/** A profile's post kinds, in their row. */
val PROFILE_POST_KINDS = listOf(PostKindFilter.ALL, PostKindFilter.MEDIA, PostKindFilter.TEXT_POSTS)

/** Search's and the grid's post kinds (as before). */
val CLASSIC_POST_KINDS = listOf(
    PostKindFilter.ALL, PostKindFilter.IMAGES, PostKindFilter.TEXT_POSTS, PostKindFilter.HORIZONTAL_VIDEOS, PostKindFilter.VERTICAL_VIDEOS
)

/** Which of [PROFILE_POST_KINDS] a kind is part of. */
fun PostKindFilter.profileParent(): PostKindFilter = when (this) {
    PostKindFilter.ALL -> PostKindFilter.ALL
    PostKindFilter.TEXT_POSTS, PostKindFilter.TEXT_ONLY, PostKindFilter.POLLS -> PostKindFilter.TEXT_POSTS
    else -> PostKindFilter.MEDIA
}

/** The pill's choices for a profile kind (none for All); the first is "Both". */
fun facetsOf(parent: PostKindFilter): List<PostKindFilter> = when (parent) {
    PostKindFilter.MEDIA -> listOf(PostKindFilter.MEDIA, PostKindFilter.IMAGES, PostKindFilter.VIDEOS)
    PostKindFilter.TEXT_POSTS -> listOf(PostKindFilter.TEXT_POSTS, PostKindFilter.TEXT_ONLY, PostKindFilter.POLLS)
    else -> emptyList()
}

/** Whether a post with these kind bits (one per kind's position) shows
 *  under this kind — working out the newer kinds from the older bits for
 *  posts noted before they existed. */
fun PostKindFilter.matchesBits(kinds: Int): Boolean {
    fun has(k: PostKindFilter) = kinds and (1 shl k.ordinal) != 0
    if (has(this)) return true
    val noted = kinds and ((1 shl PostKindFilter.MEDIA.ordinal) - 1).inv() != 0
    if (noted) return false
    return when (this) {
        PostKindFilter.MEDIA -> has(PostKindFilter.IMAGES) || has(PostKindFilter.HORIZONTAL_VIDEOS) || has(PostKindFilter.VERTICAL_VIDEOS)
        PostKindFilter.VIDEOS -> has(PostKindFilter.HORIZONTAL_VIDEOS) || has(PostKindFilter.VERTICAL_VIDEOS)
        PostKindFilter.TEXT_ONLY, PostKindFilter.POLLS -> has(PostKindFilter.TEXT_POSTS)
        else -> false
    }
}


enum class ReviewKindFilter { ALL, MOVIES, TV, GAMES, MUSIC, BOOKS }
fun ReviewKindFilter.label() = when (this) {
    ReviewKindFilter.ALL -> "All"; ReviewKindFilter.MOVIES -> "Movies"; ReviewKindFilter.TV -> "TV"
    ReviewKindFilter.GAMES -> "Games"; ReviewKindFilter.MUSIC -> "Music"; ReviewKindFilter.BOOKS -> "Books"
}

/** Buckets a raw creativeWorkType string (e.g. "movie", "tv_show",
 *  "video_game", "album") into one of the four sub-filter categories.
 *  Keyword-contains matching, same defensive style as the rest of this
 *  record's parsing (see BlueskyRepository.getPopfeedBacklog) — Popfeed's
 *  exact set of type strings isn't fully documented, so this is deliberately
 *  loose rather than an exact-match enum. Null/unrecognized categories only
 *  show up under "All", never hidden entirely. */
fun categoryBucket(raw: String?): ReviewKindFilter? {
    val v = raw?.lowercase() ?: return null
    return when {
        v.contains("movie") || v.contains("film") -> ReviewKindFilter.MOVIES
        v.contains("tv") || v.contains("show") || v.contains("series") || v.contains("episode") -> ReviewKindFilter.TV
        v.contains("game") -> ReviewKindFilter.GAMES
        v.contains("album") || v.contains("music") || v.contains("song") || v.contains("track") -> ReviewKindFilter.MUSIC
        // Titles feature: books, added alongside the Titles tab per spec
        // ("which btw need the 'Books' options at the end") — keyed off the
        // same loose keyword-contains matching as every other bucket here.
        v.contains("book") || v.contains("novel") || v.contains("comic") || v.contains("literature") -> ReviewKindFilter.BOOKS
        else -> null
    }
}
fun ReviewKindFilter.matchesReview(review: PopfeedReview) = this == ReviewKindFilter.ALL || categoryBucket(review.mediaCategory) == this
fun ReviewKindFilter.matchesBacklog(item: PopfeedBacklogItem) = this == ReviewKindFilter.ALL || categoryBucket(item.mediaCategory) == this
// Titles feature: same bucketing, applied to a search result instead of a
// Popfeed backlog/review record.
fun ReviewKindFilter.matchesTitle(result: com.mediaviewer.model.TitleSearchResult) =
    this == ReviewKindFilter.ALL || categoryBucket(result.mediaCategory) == this

/** Shown blurred: an NSFW post set to Blur / Warn, or locked on iOS (see
 *  AdultContentPolicy). [hateFun] is no longer used. */
@Suppress("UNUSED_PARAMETER")
fun MediaItem.nsfwBlurred(hateFun: Boolean = false): Boolean =
    com.mediaviewer.util.AdultContentPolicy.warns(this) || com.mediaviewer.util.AdultContentPolicy.locked(this)
