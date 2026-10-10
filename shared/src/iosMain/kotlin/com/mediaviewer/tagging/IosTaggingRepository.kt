package com.mediaviewer.tagging

import com.mediaviewer.json.StellarJson
import com.mediaviewer.model.MediaItem
import com.mediaviewer.network.PlainHttp
import com.mediaviewer.network.isSuccessful
import com.mediaviewer.platform.IosPaths
import com.mediaviewer.platform.currentTimeMillis
import com.mediaviewer.platform.deleteLocalFile
import com.mediaviewer.platform.randomUuidString
import com.mediaviewer.platform.readLocalFile
import com.mediaviewer.platform.synchronizedCompat
import com.mediaviewer.platform.writeLocalFile
import com.mediaviewer.repository.BlueskyRepository
import com.mediaviewer.repository.E621Repository
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSNumber
import kotlin.concurrent.AtomicInt
import kotlin.concurrent.Volatile
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The on-device tagger as the Swift app provides it (iosApp/iosApp/
 * StellarTagger.swift). It runs the very same model file Android uses —
 * Z3D-E621-Convnext, through ONNX Runtime — so tags, search and exported
 * datasets match between the two platforms. Everything that doesn't need
 * Apple's or ONNX Runtime's APIs (the dataset, the liked-posts paging, the
 * thresholds) stays in Kotlin below.
 */
interface IosTagger {
    /** False when the app was built without ONNX Runtime. */
    fun isAvailable(): Boolean

    /** Downloads [url] straight to the file at [toPath] (never into
     *  memory). [onDone] gets an error message, or null when it's there. */
    fun download(url: String, toPath: String, onProgress: (Long, Long) -> Unit, onDone: (String?) -> Unit)
    fun cancelDownloads()

    /** Loads the model at [modelPath]; [onDone] gets an error or null. */
    fun load(modelPath: String, onDone: (String?) -> Unit)
    fun isLoaded(): Boolean
    fun unload()

    /**
     * Tags the picture in the file at [imagePath]: letterboxed onto a white
     * 448×448 square and fed to the model as raw 0–255 BGR values, exactly
     * as Android does. [onResult] gets the positions (in the model's tag
     * list) and scores of everything scoring at least [minScore] — or two
     * nulls if the picture couldn't be read or the model failed.
     */
    fun tag(imagePath: String, minScore: Float, onResult: (List<Int>?, List<Float>?) -> Unit)
}

/** Set by the Swift app at launch (see registerIosTagger in IosBridges.kt). */
object IosTaggerBridge {
    @Volatile var tagger: IosTagger? = null
}

@OptIn(ExperimentalForeignApi::class)
private fun fileSize(path: String): Long =
    (NSFileManager.defaultManager.attributesOfItemAtPath(path, null)?.get(NSFileSize) as? NSNumber)?.longLongValue ?: 0L

@OptIn(ExperimentalForeignApi::class)
private fun ensureDir(path: String): String {
    NSFileManager.defaultManager.createDirectoryAtPath(path, withIntermediateDirectories = true, attributes = null, error = null)
    return path
}

// ── The dataset ─────────────────────────────────────────────────────────

@Serializable
private data class StoredTag(val n: String = "", val c: Float = 0f)

@Serializable
private data class StoredPost(
    val uri: String = "",
    val cid: String = "",
    val url: String = "",
    val at: Long = 0L,
    /** "" = tagged on this device; else the imported dataset it came with. */
    val set: String = "",
    val tags: List<StoredTag> = emptyList()
)

@Serializable
private data class StoredDataset(val id: String = "", val name: String = "", val at: Long = 0L)

@Serializable
private data class StoredFile(val posts: List<StoredPost> = emptyList(), val datasets: List<StoredDataset> = emptyList())

/**
 * The tagged-posts dataset on iOS: the same information Android keeps in
 * its SQLite database (post, when it was tagged, which dataset it belongs
 * to, its tags and scores), held in memory and saved as one JSON file in
 * the app's own folder. Only links and tags are stored, never the media.
 */
private class IosTagStore(private val path: String) {
    private val lock = Any()
    private val posts = LinkedHashMap<String, StoredPost>()
    private val datasets = ArrayList<StoredDataset>()
    private var loaded = false
    private var unsaved = 0
    private var bytesOnDisk = 0L

    private fun load() {
        if (loaded) return
        loaded = true
        val text = readLocalFile(path)?.decodeToString() ?: return
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
        val bytes = text.encodeToByteArray()
        if (writeLocalFile(path, bytes)) synchronizedCompat(lock) { bytesOnDisk = bytes.size.toLong() }
    }

    private fun changed(urgent: Boolean = false) {
        unsaved++
        if (urgent || unsaved >= 25) flush()
    }

    fun isIndexed(uri: String): Boolean = locked { posts.containsKey(uri) }
    fun isInImportedDataset(uri: String): Boolean = locked { posts[uri]?.set?.isNotEmpty() == true }

    fun storeTags(uri: String, cid: String, url: String, tags: List<Pair<String, Float>>) {
        locked {
            posts.remove(uri)
            posts[uri] = StoredPost(uri, cid, url, currentTimeMillis(), "", tags.map { StoredTag(it.first, it.second) })
        }
        changed()
    }

    /** The Tags page's hand edits (see TaggingService.editPostTag). */
    fun editTag(uri: String, cid: String, url: String, oldTag: String?, newTag: String?) {
        locked {
            val post = posts[uri] ?: StoredPost(uri, cid, url, currentTimeMillis(), "", emptyList())
            var tags = post.tags
            when {
                oldTag != null && newTag == null -> tags = tags.filterNot { it.n == oldTag }
                oldTag == null && newTag != null -> tags = tags.filterNot { it.n == newTag } + StoredTag(newTag, 1f)
                oldTag != null && newTag != null && oldTag != newTag -> {
                    val kept = tags.firstOrNull { it.n == oldTag }?.c ?: 1f
                    tags = tags.filterNot { it.n == oldTag || it.n == newTag } + StoredTag(newTag, kept)
                }
            }
            posts[uri] = post.copy(tags = tags)
        }
        changed(urgent = true)
    }

    fun scannedCount(): Int = locked { posts.size }
    fun taggedCount(): Int = locked { posts.values.count { it.tags.isNotEmpty() } }
    fun sizeBytes(): Long = synchronizedCompat(lock) { bytesOnDisk }

    /** AND across [groups], OR within one; a term matches a tag that is
     *  it or contains it (so "34" finds "rule_34"). Newest first. */
    fun search(groups: List<List<String>>, limit: Int): List<String> = locked {
        if (groups.isEmpty() || groups.any { it.isEmpty() }) return@locked emptyList()
        posts.values.asSequence()
            .filter { post -> groups.all { group -> post.tags.any { tag -> group.any { term -> tag.n.contains(term, ignoreCase = true) } } } }
            .sortedByDescending { it.at }
            .take(limit).map { it.uri }.toList()
    }

    fun allTagged(limit: Int): List<String> = locked {
        posts.values.asSequence().filter { it.tags.isNotEmpty() }.sortedByDescending { it.at }.take(limit).map { it.uri }.toList()
    }

    fun tagsFor(uri: String): List<String> = locked { posts[uri]?.tags?.sortedByDescending { it.c }?.map { it.n }.orEmpty() }

    fun export(): List<TagExportedPost> = locked {
        posts.values.map { p -> TagExportedPost(p.uri, p.cid, p.url, p.tags.sortedByDescending { it.c }.map { it.n to it.c }) }
    }

    /** Added as its own dataset; a post this device already has keeps what it has. */
    fun import(name: String, incoming: List<TagExportedPost>): TagDatasetInfo {
        val id = randomUuidString()
        val now = currentTimeMillis()
        val added = locked {
            datasets.add(StoredDataset(id, name, now))
            var count = 0
            for (post in incoming) {
                if (post.postUri.isBlank() || posts.containsKey(post.postUri)) continue
                posts[post.postUri] = StoredPost(post.postUri, post.cid, post.mediaUrl, now, id, post.tags.map { StoredTag(it.first, it.second) })
                count++
            }
            count
        }
        changed(urgent = true)
        return TagDatasetInfo(id, name, now, added)
    }

    fun listDatasets(): List<TagDatasetInfo> = locked {
        datasets.sortedByDescending { it.at }.map { d -> TagDatasetInfo(d.id, d.name, d.at, posts.values.count { it.set == d.id }) }
    }

    fun deleteDataset(id: String) {
        locked {
            posts.values.filter { it.set == id }.map { it.uri }.forEach { posts.remove(it) }
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
 * AI Tagging on iOS: the same service Android's TaggingRepository provides
 * (same model, same thresholds, same paging through liked posts, same
 * import/export shape), with the model run by [IosTagger].
 *
 * One difference: a video post is tagged from its cover picture. Android
 * pulls the frame at the middle of the video; iOS can't read a single
 * frame out of Bluesky's streamed (HLS) video without downloading it all.
 */
class IosTaggingRepository(
    private val tagger: IosTagger,
    private val bskyRepo: BlueskyRepository,
    private val e621Repo: E621Repository
) : TaggingService {
    private class TagEntry(val name: String, val category: Int)

    private val dir = ensureDir(IosPaths.filesDir() + "/tagger")
    private val modelPath = "$dir/z3d_e621_convnext.onnx"
    private val tagsPath = "$dir/z3d_e621_tags.csv"
    private val store = IosTagStore("$dir/dataset.json")
    private val loadLock = Mutex()
    private val inputCounter = AtomicInt(0)

    @Volatile private var cancelRequested = false
    @Volatile private var entries: List<TagEntry>? = null

    override fun currentCounts(): Pair<Int, Int> = store.scannedCount() to store.taggedCount()
    override fun datasetSizeBytes(): Long = store.sizeBytes()
    override fun isModelReady(): Boolean = fileSize(modelPath) > 0L && fileSize(tagsPath) > 0L
    override fun isTaggerLoaded(): Boolean = tagger.isLoaded()
    override fun cancel() { cancelRequested = true }

    /** The model's tag list (name and e621 category per line), from the
     *  CSV that comes with it. */
    private fun tagEntries(): List<TagEntry> {
        entries?.let { return it }
        val lines = readLocalFile(tagsPath)?.decodeToString()?.lines()?.filter { it.isNotBlank() } ?: return emptyList()
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

    private suspend fun downloadFile(url: String, path: String, onProgress: (Long, Long) -> Unit) {
        suspendCancellableCoroutine<Unit> { cont ->
            tagger.download(url, path, onProgress) { error ->
                if (cont.isActive) {
                    if (error == null) cont.resume(Unit) else cont.resumeWithException(IllegalStateException(error))
                }
            }
            cont.invokeOnCancellation { tagger.cancelDownloads() }
        }
    }

    private suspend fun ensureModel(onState: (TaggerState) -> Unit) {
        if (isModelReady()) { onState(TaggerState.Ready); return }
        try {
            onState(TaggerState.Downloading(0, 0))
            downloadFile(TAGS_URL, tagsPath) { done, total -> onState(TaggerState.Downloading(done, total)) }
            downloadFile(MODEL_URL, modelPath) { done, total -> onState(TaggerState.Downloading(done, total)) }
            entries = null
            onState(if (isModelReady()) TaggerState.Ready else TaggerState.Failed("Download finished but files look incomplete"))
        } catch (e: CancellationException) {
            deleteLocalFile(modelPath); deleteLocalFile(tagsPath)
            throw e
        } catch (e: Exception) {
            deleteLocalFile(modelPath); deleteLocalFile(tagsPath)
            onState(TaggerState.Failed(e.message ?: "Download failed"))
        }
    }

    override suspend fun downloadModel(onState: (TaggerState) -> Unit) = ensureModel(onState)

    /** The model, loaded (downloaded first if it has to be). One at a time. */
    private suspend fun ensureLoaded(onState: (TaggerState) -> Unit) {
        if (tagger.isLoaded()) return
        loadLock.withLock {
            if (tagger.isLoaded()) return@withLock
            ensureModel(onState)
            if (!isModelReady()) error("Model not ready")
            val failure = suspendCancellableCoroutine<String?> { cont ->
                tagger.load(modelPath) { error -> if (cont.isActive) cont.resume(error) }
            }
            if (failure != null) error(failure)
        }
    }

    override suspend fun deleteDatabase() = withContext(Dispatchers.IO) { store.clear() }
    override suspend fun exportAllPosts(): List<TagExportedPost> = withContext(Dispatchers.IO) { store.export() }
    override suspend fun importDataset(name: String, posts: List<TagExportedPost>): TagDatasetInfo =
        withContext(Dispatchers.IO) { store.import(name, posts) }
    override suspend fun listImportedDatasets(): List<TagDatasetInfo> = withContext(Dispatchers.IO) { store.listDatasets() }
    override suspend fun deleteDataset(id: String) = withContext(Dispatchers.IO) { store.deleteDataset(id) }

    /** A post's picture, fetched and waiting for the model. */
    private class Prepared(val item: MediaItem, val bytes: ByteArray?, val recordUrl: String)

    private suspend fun fetch(url: String): ByteArray? {
        if (url.isBlank()) return null
        return runCatching {
            val resp = PlainHttp.get(url)
            if (resp.isSuccessful && resp.body.isNotEmpty()) resp.body else null
        }.getOrNull()
    }

    private suspend fun prepare(item: MediaItem): Prepared {
        return if (item.isVideo) {
            // The cover picture (see the class comment).
            val cover = item.thumbUrl.ifBlank { item.mediaUrl }
            Prepared(item, fetch(cover), item.mediaUrl.ifBlank { item.thumbUrl })
        } else {
            // The mid-size picture is plenty for a 448-pixel input.
            val url = item.taggingUrl.ifBlank { item.mediaUrl.ifBlank { item.thumbUrl } }
            Prepared(item, fetch(url), item.mediaUrl.ifBlank { item.thumbUrl })
        }
    }

    /** Runs the model on [bytes]; every tag over its threshold, best first. */
    private suspend fun infer(bytes: ByteArray): List<Pair<String, Float>> {
        val path = IosPaths.cacheDir() + "/tagger_input_" + inputCounter.incrementAndGet() + ".img"
        if (!writeLocalFile(path, bytes)) return emptyList()
        try {
            val (indices, scores) = suspendCancellableCoroutine<Pair<List<Int>?, List<Float>?>> { cont ->
                tagger.tag(path, CHARACTER_THRESHOLD) { i, s -> if (cont.isActive) cont.resume(i to s) }
            }
            if (indices == null || scores == null) return emptyList()
            val list = tagEntries()
            val out = ArrayList<Pair<String, Float>>()
            for (k in indices.indices) {
                val entry = list.getOrNull(indices[k]) ?: continue
                val score = scores.getOrNull(k) ?: continue
                // Character and species tags are one pick out of thousands of
                // look-alikes, so a right answer scores lower: they get the
                // more lenient cut-off (the same two as on Android).
                val threshold = if (entry.category == 4 || entry.category == 5) CHARACTER_THRESHOLD else GENERAL_THRESHOLD
                if (score >= threshold && entry.name.isNotBlank()) out.add(entry.name to score)
            }
            return out.sortedByDescending { it.second }
        } finally {
            deleteLocalFile(path)
        }
    }

    /** True when the post ended up with at least one tag. */
    private suspend fun inferAndStore(prepared: Prepared): Boolean {
        val item = prepared.item
        val bytes = prepared.bytes
        if (bytes == null) {
            store.storeTags(item.postUri, item.postCid, prepared.recordUrl, emptyList())
            return false
        }
        val tags = infer(bytes)
        store.storeTags(item.postUri, item.postCid, prepared.recordUrl, tags)
        return tags.isNotEmpty()
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
        withContext(Dispatchers.IO) {
            fun snapshot(running: Boolean, complete: Boolean, state: TaggerState = TaggerState.Ready, current: MediaItem? = null) =
                TaggingProgress(store.scannedCount(), store.taggedCount(), store.sizeBytes(), running, complete, state, current)

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

            // Pictures are fetched a few posts ahead (that's what the
            // "posts at once" setting is) while the model works through
            // them one at a time — it already uses every core for one.
            suspend fun tagBatch(items: List<MediaItem>) {
                val toTag = items.filter { it.postUri.isNotBlank() && !store.isIndexed(it.postUri) }
                if (toTag.isEmpty() || cancelRequested) return
                coroutineScope {
                    val queue = Channel<MediaItem>(Channel.UNLIMITED)
                    toTag.forEach { queue.trySend(it) }
                    queue.close()
                    val prepared = Channel<Prepared>(capacity = parallelism)
                    val producers = (1..parallelism).map {
                        launch(Dispatchers.IO) {
                            for (item in queue) {
                                if (cancelRequested) break
                                if (item.isTextOnly) {
                                    store.storeTags(item.postUri, item.postCid, "", emptyList())
                                    report()
                                    continue
                                }
                                prepared.send(prepare(item))
                            }
                        }
                    }
                    launch {
                        producers.forEach { it.join() }
                        prepared.close()
                    }
                    for (next in prepared) {
                        if (cancelRequested) continue
                        current = next.item
                        report()
                        inferAndStore(next)
                        report()
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
        if (tagger.isLoaded()) return true
        if (!isModelReady()) return false
        return withContext(Dispatchers.IO) {
            try { ensureLoaded { }; true } catch (e: CancellationException) { throw e } catch (_: Exception) { false }
        }
    }

    override suspend fun tagOnLike(item: MediaItem) {
        if (!isModelReady() || item.postUri.isBlank()) return
        withContext(Dispatchers.IO) {
            // A post from an imported dataset keeps the tags it came with.
            if (store.isInImportedDataset(item.postUri)) return@withContext
            try { ensureLoaded { } } catch (e: CancellationException) { throw e } catch (_: Exception) { return@withContext }
            if (item.isTextOnly) store.storeTags(item.postUri, item.postCid, "", emptyList())
            else inferAndStore(prepare(item))
            store.flush()
        }
    }

    override fun search(query: String): List<String> {
        val groups = TagAliases.toTagGroups(query)
        // Every match in the dataset (no cap): Search › Tagged sorts them
        // all, so the most liked etc. really is first.
        if (groups.isEmpty()) return browseAllTagged(Int.MAX_VALUE)
        return store.search(groups, Int.MAX_VALUE)
    }

    override fun browseAllTagged(limit: Int): List<String> = store.allTagged(limit)
    override fun tagsForPost(postUri: String): List<String> = store.tagsFor(postUri)
    override suspend fun editPostTag(postUri: String, cid: String, mediaUrl: String, oldTag: String?, newTag: String?): List<String> =
        withContext(Dispatchers.IO) {
            store.editTag(postUri, cid, mediaUrl, oldTag, newTag)
            store.tagsFor(postUri)
        }

    companion object {
        // The same files Android downloads (see TaggerModelManager).
        private const val MODEL_URL = "https://huggingface.co/toynya/Z3D-E621-Convnext/resolve/main/model.onnx"
        private const val TAGS_URL = "https://huggingface.co/toynya/Z3D-E621-Convnext/resolve/main/tags-selected.csv"
        private const val GENERAL_THRESHOLD = 0.25f
        private const val CHARACTER_THRESHOLD = 0.15f
    }
}
