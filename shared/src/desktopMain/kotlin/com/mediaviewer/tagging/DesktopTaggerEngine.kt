package com.mediaviewer.tagging

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.mediaviewer.network.DesktopHttpClients
import com.mediaviewer.platform.DesktopPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Color
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import java.io.File
import java.nio.FloatBuffer

/**
 * AI Tagging on a computer: the model runs in ONNX Runtime for Java (the
 * same runtime Android uses), and Skia prepares the picture the same way
 * Android's ImageTagger does — letterboxed onto white at 448×448, raw
 * 0–255 BGR values. Model and dataset live in Stellar's data folder.
 */
class DesktopTaggerEngine : TaggerEngine {
    private val dir = File(DesktopPaths.filesDir, "tagger").apply { mkdirs() }
    private val modelFile = File(dir, TaggerFiles.MODEL_NAME)
    private val tagsFile = File(dir, TaggerFiles.TAGS_NAME)
    private val datasetFile = File(dir, "dataset.json")
    private val runLock = Mutex()

    @Volatile private var session: OrtSession? = null
    @Volatile private var inputName: String = ""

    override fun hasModel(): Boolean = modelFile.length() > 0L && tagsFile.length() > 0L

    private suspend fun downloadFile(url: String, to: File, onProgress: (Long, Long) -> Unit) = withContext(Dispatchers.IO) {
        val part = File(to.path + ".part")
        val call = DesktopHttpClients.downloadClient.newCall(Request.Builder().url(url).build())
        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) error("Download failed (HTTP ${resp.code})")
                val body = resp.body ?: error("Download failed")
                val total = body.contentLength()
                var done = 0L
                var lastReport = 0L
                body.byteStream().use { input ->
                    part.outputStream().use { out ->
                        val buf = ByteArray(1 shl 16)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (done - lastReport >= 512 * 1024) { lastReport = done; onProgress(done, total) }
                        }
                    }
                }
                onProgress(done, total)
            }
            if (!part.renameTo(to)) { to.delete(); if (!part.renameTo(to)) error("Couldn't save the model") }
        } finally {
            call.cancel()
            part.delete()
        }
    }

    override suspend fun download(onProgress: (Long, Long) -> Unit) {
        try {
            downloadFile(TaggerFiles.TAGS_URL, tagsFile, onProgress)
            downloadFile(TaggerFiles.MODEL_URL, modelFile, onProgress)
        } catch (e: Throwable) {
            modelFile.delete(); tagsFile.delete()
            throw e
        }
    }

    override fun tagsCsv(): String? = runCatching { tagsFile.takeIf { it.isFile }?.readText() }.getOrNull()

    override suspend fun load() = withContext(Dispatchers.IO) {
        if (session != null) return@withContext
        val env = OrtEnvironment.getEnvironment()
        val options = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            // Leave a core for the window so scrolling stays smooth.
            setIntraOpNumThreads((Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 16))
            // (See Android's ImageTagger: tiny values, much faster.)
            addConfigEntry("session.set_denormal_as_zero", "1")
        }
        val s = env.createSession(modelFile.absolutePath, options)
        inputName = s.inputNames.first()
        session = s
    }

    override fun isLoaded(): Boolean = session != null

    /** The picture, letterboxed onto white, as NHWC BGR 0–255 floats. */
    private fun preprocess(bytes: ByteArray): FloatArray? {
        val size = TaggerFiles.INPUT_SIZE
        val image = runCatching { Image.makeFromEncoded(bytes) }.getOrNull() ?: return null
        try {
            if (image.width <= 0 || image.height <= 0) return null
            val scale = minOf(size.toFloat() / image.width, size.toFloat() / image.height)
            val w = (image.width * scale).toInt().coerceAtLeast(1)
            val h = (image.height * scale).toInt().coerceAtLeast(1)
            val left = (size - w) / 2f
            val top = (size - h) / 2f
            val surface = Surface.makeRasterN32Premul(size, size)
            try {
                surface.canvas.clear(Color.WHITE)
                surface.canvas.drawImageRect(
                    image, Rect.makeWH(image.width.toFloat(), image.height.toFloat()),
                    Rect.makeXYWH(left, top, w.toFloat(), h.toFloat()),
                    FilterMipmap(FilterMode.LINEAR, MipmapMode.LINEAR), null, true
                )
                val bitmap = Bitmap()
                try {
                    bitmap.allocPixels(ImageInfo(size, size, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL))
                    if (!surface.readPixels(bitmap, 0, 0)) return null
                    val px = bitmap.readPixels() ?: return null
                    val out = FloatArray(size * size * 3)
                    var o = 0
                    var i = 0
                    while (i < px.size) {
                        out[o] = (px[i + 2].toInt() and 0xFF).toFloat()     // B
                        out[o + 1] = (px[i + 1].toInt() and 0xFF).toFloat() // G
                        out[o + 2] = (px[i].toInt() and 0xFF).toFloat()     // R
                        o += 3
                        i += 4
                    }
                    return out
                } finally {
                    bitmap.close()
                }
            } finally {
                surface.close()
            }
        } finally {
            image.close()
        }
    }

    override suspend fun tag(imageUrl: String, minScore: Float): List<Pair<Int, Float>>? = withContext(Dispatchers.IO) {
        val bytes = runCatching {
            DesktopHttpClients.downloadClient.newCall(Request.Builder().url(imageUrl).build()).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.bytes() else null
            }
        }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return@withContext null
        val input = preprocess(bytes) ?: return@withContext null
        val s = session ?: return@withContext null
        // The model already uses every core for one picture.
        runLock.withLock {
            runCatching {
                val size = TaggerFiles.INPUT_SIZE.toLong()
                OnnxTensor.createTensor(OrtEnvironment.getEnvironment(), FloatBuffer.wrap(input), longArrayOf(1, size, size, 3)).use { tensor ->
                    s.run(mapOf(inputName to tensor)).use { results ->
                        val scores = when (val v = results[0].value) {
                            is Array<*> -> v[0] as? FloatArray
                            is FloatArray -> v
                            else -> null
                        } ?: return@use null
                        scores.indices.filter { scores[it] >= minScore }.map { it to scores[it] }
                    }
                }
            }.getOrNull()
        }
    }

    override fun readDataset(): String? = runCatching { datasetFile.takeIf { it.isFile }?.readText() }.getOrNull()

    override fun writeDataset(text: String): Boolean = runCatching {
        val tmp = File(datasetFile.path + ".tmp")
        tmp.writeText(text)
        tmp.renameTo(datasetFile) || (datasetFile.delete() && tmp.renameTo(datasetFile))
    }.getOrDefault(false)
}
