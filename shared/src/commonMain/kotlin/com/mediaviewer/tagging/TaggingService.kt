package com.mediaviewer.tagging

import com.mediaviewer.model.MediaItem

/*
 * AI Tagging, as the ViewModel and UI see it. Android's implementation is
 * TaggingRepository (on-device ONNX model + SQLite); iOS, desktop and the
 * web use [EngineTaggingRepository] with their own [TaggerEngine].
 * [UnavailableTaggingService] is for an iOS build without the tagger.
 */

/** Where the tagger model download stands (was TaggerModelManager.State). */
sealed class TaggerState {
    data object NotDownloaded : TaggerState()
    data class Downloading(val bytesDownloaded: Long, val totalBytes: Long) : TaggerState()
    data object Ready : TaggerState()
    data class Failed(val message: String) : TaggerState()
}

/** One row of the "imported datasets" list Settings shows under the
 *  Import/Export buttons — [postCount] is computed live off liked_media
 *  rather than stored, so it can never drift out of sync with reality
 *  (e.g. if a future feature ever lets someone delete individual posts).
 *  (Was TagDatabase.DatasetInfo.) */
data class TagDatasetInfo(val id: String, val name: String, val importedAt: Long, val postCount: Int)

/** One post as read back out for an export (was TagDatabase.ExportedPost). */
data class TagExportedPost(val postUri: String, val cid: String, val mediaUrl: String, val tags: List<Pair<String, Float>>)

/** Progress of a tagging run (was TaggingRepository.Progress). */
data class TaggingProgress(
    val scanned: Int,
    val tagged: Int,
    val datasetBytes: Long,
    val isRunning: Boolean,
    val isComplete: Boolean,
    val modelState: TaggerState = TaggerState.Ready,
    // Tagging page redesign (item 3): whichever post is being fetched/
    // inferred right now, so the overlay can show it full-screen in real
    // time instead of a generic loading box. Null before the first item
    // starts (still downloading the model, or between pages of the
    // liked-posts pagination) and while nothing is running.
    val currentItem: MediaItem? = null,
    // The post that most recently finished tagging and the tags it got
    // (best first), so the overlay can show a finished post with its tags
    // laid over it instead of one that's still being worked on.
    val lastTaggedItem: MediaItem? = null,
    val lastTags: List<String> = emptyList()
)

interface TaggingService {
    /** False where the platform has no on-device tagger. */
    val isSupported: Boolean get() = true
    fun currentCounts(): Pair<Int, Int>
    /** Posts in this device's OWN dataset only (its AI's tags and hand
     *  tags) — the set [exportDataset] with a null id exports, and what
     *  Settings' "Your Dataset" row counts. Imported datasets' posts are
     *  not in it (they're counted per dataset in [listImportedDatasets]);
     *  [currentCounts]' scanned figure counts every dataset together. */
    fun ownDatasetCount(): Int
    fun datasetSizeBytes(): Long
    fun isModelReady(): Boolean
    fun tagVocabulary(): List<String>
    fun isTaggerLoaded(): Boolean
    fun cancel()
    suspend fun downloadModel(onState: (TaggerState) -> Unit)
    suspend fun deleteDatabase()
    /** Every tagged post, leaving out what only the datasets in [exclude] gave. */
    suspend fun exportAllPosts(exclude: Set<String> = emptySet()): List<TagExportedPost>
    /** [onProgress] hears 0–1 while a big one goes in. */
    suspend fun importDataset(name: String, posts: List<TagExportedPost>, onProgress: (Float) -> Unit = {}): TagDatasetInfo
    suspend fun listImportedDatasets(): List<TagDatasetInfo>
    suspend fun deleteDataset(id: String)
    /** One dataset's posts and the tags it gave them: an imported one by
     *  [id], or (null) this device's own — its AI's tags and hand tags. */
    suspend fun exportDataset(id: String?): List<TagExportedPost>
    /** This device's own dataset (its AI's tags and hand tags) goes;
     *  imported datasets stay. */
    suspend fun deleteOwnDataset()
    /** Tags [items] (History's posts, say) the way [tagAllLiked] tags
     *  likes: posts already tagged are skipped. */
    suspend fun tagPosts(items: List<MediaItem>, concurrency: Int = 1, onProgress: (TaggingProgress) -> Unit)
    suspend fun tagAllLiked(
        isBlueskyMode: Boolean,
        bskyToken: String,
        bskyDid: String,
        e621Username: String,
        e621ApiKey: String,
        concurrency: Int = 1,
        onProgress: (TaggingProgress) -> Unit
    )
    suspend fun tagOnLike(item: MediaItem)
    /** Loads the model into memory if it isn't yet (the "Activating
     *  tagger" step). True when it's ready to tag. */
    suspend fun warmUp(): Boolean = isTaggerLoaded()
    fun search(query: String): List<String>
    fun browseAllTagged(limit: Int = 200): List<String>
    fun tagsForPost(postUri: String): List<String>

    /** The current AI tagger ([AiTaggers.CURRENT]) has already tagged this
     *  post — its own tag is on it (or, from before the tag was added, this
     *  device's AI tagged it, or it came in a dataset with no AI tagger's
     *  tag on it at all). Tags added by hand don't count. */
    fun isTaggedByCurrentModel(postUri: String): Boolean = false

    /** Every tag in the dataset (this device's, hand-added and imported
     *  ones), for the Tagged search's autocomplete. */
    fun datasetTagNames(): List<String> = emptyList()

    /**
     * Edits one post's tags by hand (the Tags page): [oldTag] null adds
     * [newTag]; [newTag] null removes [oldTag]; both renames. A post that
     * isn't in the dataset yet is added to the device's own dataset.
     * Returns the post's tags afterwards.
     */
    suspend fun editPostTag(postUri: String, cid: String, mediaUrl: String, oldTag: String?, newTag: String?): List<String> =
        tagsForPost(postUri)
}

/** The AI taggers. Every post an AI tagger tags also gets that tagger's
 *  own tag (its name and version), so it's clear which one tagged it, and
 *  a post it has tagged once isn't tagged by it again. */
object AiTaggers {
    /** What every AI tagger's own tag starts with. */
    const val PREFIX = "ai:"
    /** Z3D-E621-Convnext (toynya), the on-device tagger. */
    const val CURRENT = "ai:z3d_e621_convnext_v1"
    /** Its confidence: low, so it's listed after the post's real tags. */
    const val CONFIDENCE = 0.01f
}

/** A tag as typed on the Tags page: trimmed, spaces become "_". */
fun normalizeTypedTag(text: String): String =
    text.trim().replace(Regex("\\s+"), "_")

/** For platforms without the on-device tagger: everything is empty/no-op. */
object UnavailableTaggingService : TaggingService {
    override val isSupported: Boolean get() = false
    override fun currentCounts(): Pair<Int, Int> = 0 to 0
    override fun ownDatasetCount(): Int = 0
    override fun datasetSizeBytes(): Long = 0L
    override fun isModelReady(): Boolean = false
    override fun tagVocabulary(): List<String> = emptyList()
    override fun isTaggerLoaded(): Boolean = false
    override fun cancel() {}
    override suspend fun downloadModel(onState: (TaggerState) -> Unit) {
        onState(TaggerState.Failed("AI Tagging isn't available on this device yet"))
    }
    override suspend fun deleteDatabase() {}
    override suspend fun exportAllPosts(exclude: Set<String>): List<TagExportedPost> = emptyList()
    override suspend fun importDataset(name: String, posts: List<TagExportedPost>, onProgress: (Float) -> Unit): TagDatasetInfo =
        throw UnsupportedOperationException("AI Tagging isn't available on this device yet")
    override suspend fun listImportedDatasets(): List<TagDatasetInfo> = emptyList()
    override suspend fun deleteDataset(id: String) {}
    override suspend fun exportDataset(id: String?): List<TagExportedPost> = emptyList()
    override suspend fun deleteOwnDataset() {}
    override suspend fun tagPosts(items: List<MediaItem>, concurrency: Int, onProgress: (TaggingProgress) -> Unit) {
        onProgress(TaggingProgress(0, 0, 0, isRunning = false, isComplete = true, modelState = TaggerState.Failed("AI Tagging isn't available on this device yet")))
    }
    override suspend fun tagAllLiked(
        isBlueskyMode: Boolean, bskyToken: String, bskyDid: String, e621Username: String, e621ApiKey: String,
        concurrency: Int, onProgress: (TaggingProgress) -> Unit
    ) {
        onProgress(TaggingProgress(0, 0, 0, isRunning = false, isComplete = true, modelState = TaggerState.Failed("AI Tagging isn't available on this device yet")))
    }
    override suspend fun tagOnLike(item: MediaItem) {}
    override fun search(query: String): List<String> = emptyList()
    override fun browseAllTagged(limit: Int): List<String> = emptyList()
    override fun tagsForPost(postUri: String): List<String> = emptyList()
}
