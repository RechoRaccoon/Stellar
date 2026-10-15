package com.mediaviewer.tagging

import com.mediaviewer.json.StellarJson
import com.mediaviewer.model.MediaItem
import com.mediaviewer.platform.currentTimeMillis
import com.mediaviewer.platform.ioDispatcher
import com.mediaviewer.platform.randomUuidString
import com.mediaviewer.platform.synchronizedCompat
import com.mediaviewer.repository.BlueskyRepository
import com.mediaviewer.repository.E621Repository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlin.concurrent.Volatile

/**
 * What a platform supplies to run the tagger: the very same model file
 * Android uses (Z3D-E621-Convnext through ONNX Runtime) and a place to keep
 * the dataset. iOS (StellarTagger.swift), desktop (ONNX Runtime for Java)
 * and the web (onnxruntime-web) each have one; Android keeps its own
 * TaggingRepository.
 */
interface TaggerEngine {
    /** The model file and its tag list are both downloaded. */
    fun hasModel(): Boolean

    /** Downloads the tag list and the model (see [TaggerFiles]); throws on
     *  failure, leaving nothing half-downloaded behind. */
    suspend fun download(onProgress: (Long, Long) -> Unit)

    /** The tag list CSV that comes with the model, or null. */
    fun tagsCsv(): String?

    /** Loads the model; throws if it can't. */
    suspend fun load()
    fun isLoaded(): Boolean

    /**
     * Tags the picture at [imageUrl] (fetched by the engine): letterboxed
     * onto a white 448×448 square and fed to the model as raw 0–255 BGR
     * values, exactly as Android does. Returns (position in the tag list,
     * score) for everything scoring at least [minScore], or null if the
     * picture couldn't be read or the model failed. Called for several
     * posts at once: fetching overlaps, the model runs one at a time.
     */
    suspend fun tag(imageUrl: String, minScore: Float): List<Pair<Int, Float>>?

    /** The saved dataset (JSON), or null when there isn't one yet. */
    fun readDataset(): String?
    fun writeDataset(text: String): Boolean
}

/** Where the model comes from (the same files Android downloads). */
object TaggerFiles {
    const val MODEL_URL = "https://huggingface.co/toynya/Z3D-E621-Convnext/resolve/main/model.onnx"
    const val TAGS_URL = "https://huggingface.co/toynya/Z3D-E621-Convnext/resolve/main/tags-selected.csv"
    const val MODEL_NAME = "z3d_e621_convnext.onnx"
    const val TAGS_NAME = "z3d_e621_tags.csv"
    const val INPUT_SIZE = 448
}

// ── The dataset ─────────────────────────────────────────────────────────

@Serializable
private data class StoredTag(
    val n: String = "", val c: Float = 0f,
    /** Added by hand on the Tags page (kept when the AI tags the post). */
    val m: Boolean = false,
    /** The datasets that gave this tag ("" = this device). Null in files
     *  from before tags were merged: then the post's own datasets. */
    val s: List<String>? = null
)

@Serializable
private data class StoredPost(
    val uri: String = "",
    val cid: String = "",
    val url: String = "",
    val at: Long = 0L,
    /** "" = tagged on this device; else the imported dataset it came with. */
    val set: String = "",
    val tags: List<StoredTag> = emptyList(),
    /** The AI has tagged it. Null in files from before hand tags were told
     *  apart: then a post whose every tag scores exactly 1 was only ever
     *  tagged by hand. */
    val ai: Boolean? = null,
    /** Every dataset this post came with ("" = this device). Null in files
     *  from before posts could be in several: then just [set]. */
    val sets: List<String>? = null
) {
    val sources: List<String> get() = sets ?: listOf(set)
    fun sourcesOf(tag: StoredTag): List<String> = tag.s ?: if (tag.m) emptyList() else sources
    val aiDone: Boolean get() = sources.any { it.isNotEmpty() } || (ai ?: !(tags.isNotEmpty() && tags.all { it.c == 1f }))
    val handTags: List<StoredTag> get() = tags.filter { it.m || (ai != true && it.c == 1f && "" in sourcesOf(it)) }

    /** The same post with every tag's sources written out, the hand-added
     *  ones marked. */
    fun normalized(): StoredPost {
        val hand = handTags.toSet()
        return copy(sets = sources, tags = tags.map { t -> if (t in hand) t.copy(m = true, s = emptyList()) else t.copy(s = sourcesOf(t)) })
    }

    /** [incoming] added as coming from [source]: each tag once (the higher
     *  score kept); a tag added by hand stays as it is. */
    fun merged(source: String, incoming: List<Pair<String, Float>>): StoredPost {
        val byName = LinkedHashMap<String, StoredTag>()
        tags.forEach { byName[it.n] = it }
        for ((name, score) in incoming) {
            if (name.isBlank()) continue
            val e = byName[name]
            byName[name] = when {
                e == null -> StoredTag(name, score, s = listOf(source))
                e.m -> e
                else -> e.copy(c = maxOf(e.c, score), s = (e.s.orEmpty() + source).distinct())
            }
        }
        return copy(tags = byName.values.toList(), sets = (sources + source).distinct())
    }

    /** Everything [source] gave it taken away: tags only it had go (hand
     *  tags stay). */
    fun without(source: String): StoredPost = copy(
        sets = sources - source,
        tags = tags.mapNotNull { t -> if (t.m) t else { val left = t.s.orEmpty() - source; if (left.isEmpty()) null else t.copy(s = left) } }
    )
}

@Serializable
private data class StoredDataset(val id: String = "", val name: String = "", val at: Long = 0L)

@Serializable
private data class StoredFile(val posts: List<StoredPost> = emptyList(), val datasets: List<StoredDataset> = emptyList())

/**
 * The tagged-posts dataset: the same information Android keeps in its
 * SQLite database (post, when it was tagged, which dataset it belongs to,
 * its tags and scores), held in memory and saved as one JSON file through
 * the engine. Only links and tags are stored, never the media.
 */
private class TagStore(private val engine: TaggerEngine) {
    private val lock = Any()
    private val posts = LinkedHashMap<String, StoredPost>()
    private val datasets = ArrayList<StoredDataset>()
    private var loaded = false
    private var unsaved = 0
    private var bytesOnDisk = 0L

    private fun load() {
        if (loaded) return
        loaded = true
        val text = engine.readDataset() ?: return
        bytesOnDisk = text.length.toLong()
        val file = runCatching { StellarJson.default.decodeFromString(StoredFile.serializer(), text) }.getOrNull() ?: return
        file.posts.forEach { if (it.uri.isNotBlank()) posts[it.uri] = it }
        datasets.addAll(file.datasets)
    }

    private fun <T> locked(block: () -> T): T = synchronizedCompat(lock) { load(); block() }

    /** Writes the file now (done every so often while tagging, and at the end). */
    fun flush() {
        val text = locked {
            if (unsaved == 0) return@locked null
            unsaved = 0
            StellarJson.default.encodeToString(StoredFile.serializer(), StoredFile(posts.values.toList(), datasets.toList()))
        } ?: return
        if (engine.writeDataset(text)) synchronizedCompat(lock) { bytesOnDisk = text.length.toLong() }
    }

    private fun changed(urgent: Boolean = false) {
        unsaved++
        if (urgent || unsaved >= 25) flush()
    }

    /** The AI has already tagged it (hand tags alone don't count). */
    fun isAiTagged(uri: String): Boolean = locked { posts[uri]?.aiDone == true }
    fun isInImportedDataset(uri: String): Boolean = locked { posts[uri]?.sources?.any { it.isNotEmpty() } == true }

    fun storeTags(uri: String, cid: String, url: String, tags: List<Pair<String, Float>>) {
        locked {
            // This device's earlier AI tags are replaced; hand-added ones,
            // and the ones imported datasets gave it, stay.
            val base = posts.remove(uri)?.normalized()?.without("")
                ?: StoredPost(uri, cid, url, 0L, "", emptyList(), sets = emptyList())
            posts[uri] = base.merged("", tags).copy(cid = cid, url = url, at = currentTimeMillis(), ai = true)
        }
        changed()
    }

    /** The Tags page's hand edits (see TaggingService.editPostTag). */
    fun editTag(uri: String, cid: String, url: String, oldTag: String?, newTag: String?) {
        locked {
            // A post first tagged by hand is still tagged by the AI later.
            val existing = posts[uri]
            val post = existing?.let { it.normalized().copy(ai = it.aiDone) }
                ?: StoredPost(uri, cid, url, currentTimeMillis(), "", emptyList(), ai = false, sets = listOf(""))
            var tags = post.tags
            when {
                oldTag != null && newTag == null -> tags = tags.filterNot { it.n == oldTag }
                oldTag == null && newTag != null -> tags = tags.filterNot { it.n == newTag } + StoredTag(newTag, 1f, m = true)
                oldTag != null && newTag != null && oldTag != newTag -> {
                    val kept = tags.firstOrNull { it.n == oldTag }?.c ?: 1f
                    tags = tags.filterNot { it.n == oldTag || it.n == newTag } + StoredTag(newTag, kept, m = true)
                }
            }
            posts[uri] = post.copy(tags = tags)
        }
        changed(urgent = true)
    }

    fun scannedCount(): Int = locked { posts.size }
    fun taggedCount(): Int = locked { posts.values.count { it.tags.isNotEmpty() } }
    fun sizeBytes(): Long = synchronizedCompat(lock) { bytesOnDisk }

    /** Whole tags only: every include group must match one of the post's
     *  tags exactly (any alias in the group), and none of the excluded tags
     *  may be on it. Only include → every tagged post. Newest first. */
    fun search(query: TagAliases.TagQuery, limit: Int): List<String> = locked {
        if (query.isEmpty) return@locked emptyList()
        posts.values.asSequence()
            .filter { it.tags.isNotEmpty() }
            .filter { post ->
                val names = post.tags.mapTo(HashSet()) { it.n.lowercase() }
                query.include.all { group -> group.any { it in names } } && query.exclude.none { it in names }
            }
            .sortedByDescending { it.at }
            .take(limit).map { it.uri }.toList()
    }

    fun allTagged(limit: Int): List<String> = locked {
        posts.values.asSequence().filter { it.tags.isNotEmpty() }.sortedByDescending { it.at }.take(limit).map { it.uri }.toList()
    }

    fun tagsFor(uri: String): List<String> = locked { posts[uri]?.tags?.sortedByDescending { it.c }?.map { it.n }.orEmpty() }

    fun export(exclude: Set<String> = emptySet()): List<TagExportedPost> = locked {
        posts.values.mapNotNull { raw ->
            val p = raw.normalized()
            val tags = p.tags.filter { t -> t.m || p.sourcesOf(t).any { it !in exclude } }
            if (p.sources.none { it !in exclude } && tags.isEmpty()) return@mapNotNull null
            TagExportedPost(p.uri, p.cid, p.url, tags.sortedByDescending { it.c }.map { it.n to it.c })
        }
    }

    /** Added as its own dataset. A post this device already has (from its
     *  own tagging or another dataset) gets this dataset's tags added to it,
     *  each tag once; every post and tag remembers which datasets it came
     *  from, so [deleteDataset] removes only what this one alone brought. */
    fun import(name: String, incoming: List<TagExportedPost>): TagDatasetInfo {
        val id = randomUuidString()
        val now = currentTimeMillis()
        val added = locked {
            datasets.add(StoredDataset(id, name, now))
            var count = 0
            for (post in incoming) {
                if (post.postUri.isBlank()) continue
                val base = posts[post.postUri]?.normalized()
                    ?: StoredPost(post.postUri, post.cid, post.mediaUrl, now, id, emptyList(), sets = emptyList())
                posts[post.postUri] = base.merged(id, post.tags)
                count++
            }
            count
        }
        changed(urgent = true)
        return TagDatasetInfo(id, name, now, added)
    }

    fun listDatasets(): List<TagDatasetInfo> = locked {
        datasets.sortedByDescending { it.at }.map { d -> TagDatasetInfo(d.id, d.name, d.at, posts.values.count { d.id in it.sources }) }
    }

    fun deleteDataset(id: String) {
        locked {
            for (uri in posts.values.filter { id in it.sources }.map { it.uri }) {
                val left = posts.getValue(uri).normalized().without(id)
                if (left.sources.isEmpty() && left.tags.isEmpty()) { posts.remove(uri); continue }
                // Kept by another dataset or by hand tags; with only hand
                // tags left it's this device's again, and still needs the AI.
                posts[uri] = when {
                    left.sources.isEmpty() -> left.copy(set = "", sets = listOf(""), ai = false)
                    left.set == id -> left.copy(set = left.sources.first())
                    else -> left
                }
            }
            datasets.removeAll { it.id == id }
        }
        changed(urgent = true)
    }

    fun clear() {
        locked { posts.clear(); datasets.clear() }
        changed(urgent = true)
    }
}

// ── The service ─────────────────────────────────────────────────────────

/**
 * AI Tagging wherever a [TaggerEngine] runs the model (iOS, desktop, web):
 * the same service Android's TaggingRepository provides — same model, same
 * thresholds, same paging through liked posts, same import/export shape.
 *
 * One difference: a video post is tagged from its cover picture. Android
 * pulls the frame at the middle of the video; the others can't read a
 * single frame out of Bluesky's streamed (HLS) video without downloading
 * it all.
 */
class EngineTaggingRepository(
    private val engine: TaggerEngine,
    private val bskyRepo: BlueskyRepository,
    private val e621Repo: E621Repository
) : TaggingService {
    private class TagEntry(val name: String, val category: Int)

    private val store = TagStore(engine)
    private val loadLock = Mutex()

    @Volatile private var cancelRequested = false
    @Volatile private var entries: List<TagEntry>? = null

    override fun currentCounts(): Pair<Int, Int> = store.scannedCount() to store.taggedCount()
    override fun datasetSizeBytes(): Long = store.sizeBytes()
    override fun isModelReady(): Boolean = engine.hasModel()
    override fun isTaggerLoaded(): Boolean = engine.isLoaded()
    override fun cancel() { cancelRequested = true }

    /** The model's tag list (name and e621 category per line), from the
     *  CSV that comes with it. */
    private fun tagEntries(): List<TagEntry> {
        entries?.let { return it }
        val lines = engine.tagsCsv()?.lines()?.filter { it.isNotBlank() } ?: return emptyList()
        if (lines.isEmpty()) return emptyList()
        val header = lines.first().split(",")
        val nameIdx = header.indexOfFirst { it.trim().equals("name", ignoreCase = true) }
            .let { if (it >= 0) it else 1.coerceAtMost(header.lastIndex) }
        val categoryIdx = header.indexOfFirst { it.trim().equals("category", ignoreCase = true) }
            .let { if (it >= 0) it else 2.coerceAtMost(header.lastIndex) }
        val parsed = lines.drop(1).map { line ->
            val cols = line.split(",")
            TagEntry(cols.getOrNull(nameIdx)?.trim()?.trim('"').orEmpty(), cols.getOrNull(categoryIdx)?.trim()?.toIntOrNull() ?: -1)
        }
        entries = parsed
        return parsed
    }

    override fun tagVocabulary(): List<String> = tagEntries().map { it.name }.filter { it.isNotBlank() }

    private suspend fun ensureModel(onState: (TaggerState) -> Unit) {
        if (engine.hasModel()) { onState(TaggerState.Ready); return }
        try {
            onState(TaggerState.Downloading(0, 0))
            engine.download { done, total -> onState(TaggerState.Downloading(done, total)) }
            entries = null
            onState(if (engine.hasModel()) TaggerState.Ready else TaggerState.Failed("Download finished but files look incomplete"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onState(TaggerState.Failed(e.message ?: "Download failed"))
        }
    }

    override suspend fun downloadModel(onState: (TaggerState) -> Unit) = ensureModel(onState)

    /** The model, loaded (downloaded first if it has to be). One at a time. */
    private suspend fun ensureLoaded(onState: (TaggerState) -> Unit) {
        if (engine.isLoaded()) return
        loadLock.withLock {
            if (engine.isLoaded()) return@withLock
            ensureModel(onState)
            if (!engine.hasModel()) error("Model not ready")
            engine.load()
        }
    }

    override suspend fun deleteDatabase() = withContext(ioDispatcher) { store.clear() }
    override suspend fun exportAllPosts(exclude: Set<String>): List<TagExportedPost> = withContext(ioDispatcher) { store.export(exclude) }
    override suspend fun importDataset(name: String, posts: List<TagExportedPost>): TagDatasetInfo =
        withContext(ioDispatcher) { store.import(name, posts) }
    override suspend fun listImportedDatasets(): List<TagDatasetInfo> = withContext(ioDispatcher) { store.listDatasets() }
    override suspend fun deleteDataset(id: String) = withContext(ioDispatcher) { store.deleteDataset(id) }

    /** The picture the model looks at, and the link kept with the tags. */
    private fun pictureOf(item: MediaItem): Pair<String, String> =
        if (item.isVideo) item.thumbUrl.ifBlank { item.mediaUrl } to item.mediaUrl.ifBlank { item.thumbUrl }
        // The mid-size picture is plenty for a 448-pixel input.
        else item.taggingUrl.ifBlank { item.mediaUrl.ifBlank { item.thumbUrl } } to item.mediaUrl.ifBlank { item.thumbUrl }

    /** Runs the model on the post's picture; every tag over its threshold,
     *  best first. Null when the picture couldn't be read. */
    private suspend fun infer(url: String): List<Pair<String, Float>>? {
        if (url.isBlank()) return null
        val hits = engine.tag(url, CHARACTER_THRESHOLD) ?: return null
        val list = tagEntries()
        val out = ArrayList<Pair<String, Float>>()
        for ((index, score) in hits) {
            val entry = list.getOrNull(index) ?: continue
            // Character and species tags are one pick out of thousands of
            // look-alikes, so a right answer scores lower: they get the
            // more lenient cut-off (the same two as on Android).
            val threshold = if (entry.category == 4 || entry.category == 5) CHARACTER_THRESHOLD else GENERAL_THRESHOLD
            if (score >= threshold && entry.name.isNotBlank()) out.add(entry.name to score)
        }
        return out.sortedByDescending { it.second }
    }

    /** The tags stored for [item] (best first). */
    private suspend fun tagAndStore(item: MediaItem): List<Pair<String, Float>> {
        if (item.isTextOnly) { store.storeTags(item.postUri, item.postCid, "", emptyList()); return emptyList() }
        val (picture, recordUrl) = pictureOf(item)
        val tags = infer(picture).orEmpty()
        store.storeTags(item.postUri, item.postCid, recordUrl, tags)
        return tags
    }

    override suspend fun tagAllLiked(
        isBlueskyMode: Boolean,
        bskyToken: String,
        bskyDid: String,
        e621Username: String,
        e621ApiKey: String,
        concurrency: Int,
        onProgress: (TaggingProgress) -> Unit
    ) {
        cancelRequested = false
        val parallelism = concurrency.coerceIn(1, 10)
        withContext(ioDispatcher) {
            var last: MediaItem? = null
            var lastTags: List<String> = emptyList()
            fun snapshot(running: Boolean, complete: Boolean, state: TaggerState = TaggerState.Ready, current: MediaItem? = null) =
                TaggingProgress(store.scannedCount(), store.taggedCount(), store.sizeBytes(), running, complete, state, current, last, lastTags)

            onProgress(snapshot(running = true, complete = false, state = TaggerState.Downloading(0, 0)))
            try {
                ensureLoaded { state -> onProgress(snapshot(running = true, complete = false, state = state)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onProgress(snapshot(running = false, complete = false, state = TaggerState.Failed(e.message ?: "Model load failed")))
                return@withContext
            }

            var current: MediaItem? = null
            fun report() = onProgress(snapshot(running = true, complete = false, current = current))

            // Several posts are tagged at once (the "posts at once"
            // setting): fetching one's picture overlaps the model working
            // on another's.
            suspend fun tagBatch(items: List<MediaItem>) {
                val toTag = items.filter { it.postUri.isNotBlank() && !store.isAiTagged(it.postUri) }
                if (toTag.isEmpty() || cancelRequested) return
                coroutineScope {
                    val queue = Channel<MediaItem>(Channel.UNLIMITED)
                    toTag.forEach { queue.trySend(it) }
                    queue.close()
                    repeat(parallelism) {
                        launch {
                            for (item in queue) {
                                if (cancelRequested) break
                                if (!item.isTextOnly) { current = item; report() }
                                val tags = tagAndStore(item)
                                if (!item.isTextOnly) { last = item; lastTags = tags.map { it.first } }
                                report()
                            }
                        }
                    }
                }
            }

            try {
                if (isBlueskyMode) {
                    var cursor: String? = null
                    do {
                        if (cancelRequested) break
                        val (items, nextCursor) = bskyRepo.getActorLikes(bskyToken, bskyDid, cursor).getOrNull() ?: break
                        tagBatch(items)
                        cursor = nextCursor
                    } while (cursor != null && !cancelRequested)
                } else {
                    var page = 1
                    while (!cancelRequested) {
                        val items = e621Repo.getFavorites(e621Username, e621ApiKey, page).getOrNull() ?: break
                        if (items.isEmpty()) break
                        tagBatch(items)
                        page++
                    }
                }
            } finally {
                store.flush()
            }
            onProgress(snapshot(running = false, complete = !cancelRequested))
        }
    }

    override suspend fun warmUp(): Boolean {
        if (engine.isLoaded()) return true
        if (!engine.hasModel()) return false
        return withContext(ioDispatcher) {
            try { ensureLoaded { }; true } catch (e: CancellationException) { throw e } catch (_: Exception) { false }
        }
    }

    override suspend fun tagOnLike(item: MediaItem) {
        if (!engine.hasModel() || item.postUri.isBlank()) return
        withContext(ioDispatcher) {
            // A post from an imported dataset keeps the tags it came with.
            if (store.isInImportedDataset(item.postUri)) return@withContext
            try { ensureLoaded { } } catch (e: CancellationException) { throw e } catch (_: Exception) { return@withContext }
            tagAndStore(item)
            store.flush()
        }
    }

    override fun search(query: String): List<String> {
        val parsed = TagAliases.parse(query)
        // Every match in the dataset (no cap): Search › Tagged sorts them
        // all, so the most liked etc. really is first.
        if (parsed.isEmpty) return browseAllTagged(Int.MAX_VALUE)
        return store.search(parsed, Int.MAX_VALUE)
    }

    override fun browseAllTagged(limit: Int): List<String> = store.allTagged(limit)
    override fun tagsForPost(postUri: String): List<String> = store.tagsFor(postUri)
    override suspend fun editPostTag(postUri: String, cid: String, mediaUrl: String, oldTag: String?, newTag: String?): List<String> =
        withContext(ioDispatcher) {
            store.editTag(postUri, cid, mediaUrl, oldTag, newTag)
            store.tagsFor(postUri)
        }

    companion object {
        private const val GENERAL_THRESHOLD = 0.25f
        private const val CHARACTER_THRESHOLD = 0.15f
    }
}
