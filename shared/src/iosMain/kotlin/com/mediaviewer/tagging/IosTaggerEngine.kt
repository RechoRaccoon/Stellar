package com.mediaviewer.tagging

import com.mediaviewer.network.PlainHttp
import com.mediaviewer.network.isSuccessful
import com.mediaviewer.platform.IosPaths
import com.mediaviewer.platform.deleteLocalFile
import com.mediaviewer.platform.readLocalFile
import com.mediaviewer.platform.writeLocalFile
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

/**
 * [IosTagger] as the shared [EngineTaggingRepository] uses it: the model and
 * the dataset live in the app's own folder (the same files as before, so
 * nothing already tagged is lost).
 */
class IosTaggerEngine(private val tagger: IosTagger) : TaggerEngine {
    private val dir = ensureDir(IosPaths.filesDir() + "/tagger")
    private val modelPath = "$dir/${TaggerFiles.MODEL_NAME}"
    private val tagsPath = "$dir/${TaggerFiles.TAGS_NAME}"
    private val datasetPath = "$dir/dataset.json"
    private val inputCounter = AtomicInt(0)
    private val runLock = Mutex()

    override fun hasModel(): Boolean = fileSize(modelPath) > 0L && fileSize(tagsPath) > 0L

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

    override suspend fun download(onProgress: (Long, Long) -> Unit) {
        try {
            downloadFile(TaggerFiles.TAGS_URL, tagsPath, onProgress)
            downloadFile(TaggerFiles.MODEL_URL, modelPath, onProgress)
        } catch (e: Throwable) {
            deleteLocalFile(modelPath); deleteLocalFile(tagsPath)
            throw e
        }
    }

    override fun tagsCsv(): String? = readLocalFile(tagsPath)?.decodeToString()

    override suspend fun load() {
        val failure = suspendCancellableCoroutine<String?> { cont ->
            tagger.load(modelPath) { error -> if (cont.isActive) cont.resume(error) }
        }
        if (failure != null) error(failure)
    }

    override fun isLoaded(): Boolean = tagger.isLoaded()

    override suspend fun tag(imageUrl: String, minScore: Float): List<Pair<Int, Float>>? {
        val bytes = runCatching {
            val resp = PlainHttp.get(imageUrl)
            if (resp.isSuccessful && resp.body.isNotEmpty()) resp.body else null
        }.getOrNull() ?: return null
        val path = IosPaths.cacheDir() + "/tagger_input_" + inputCounter.incrementAndGet() + ".img"
        if (!writeLocalFile(path, bytes)) return null
        try {
            // The model already uses every core for one picture.
            val (indices, scores) = runLock.withLock {
                suspendCancellableCoroutine<Pair<List<Int>?, List<Float>?>> { cont ->
                    tagger.tag(path, minScore) { i, s -> if (cont.isActive) cont.resume(i to s) }
                }
            }
            if (indices == null || scores == null) return null
            return indices.indices.mapNotNull { k -> scores.getOrNull(k)?.let { indices[k] to it } }
        } finally {
            deleteLocalFile(path)
        }
    }

    override fun readDataset(): String? = readLocalFile(datasetPath)?.decodeToString()
    override fun writeDataset(text: String): Boolean = writeLocalFile(datasetPath, text.encodeToByteArray())
}
