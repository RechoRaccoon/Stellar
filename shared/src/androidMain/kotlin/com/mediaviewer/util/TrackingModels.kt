package com.mediaviewer.util

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import kotlin.coroutines.coroutineContext

/**
 * VRM mode's face, hand and body trackers (MediaPipe's model files, ~21 MB
 * together). They used to ship inside the APK, so everyone paid for them
 * whether or not they ever opened VRM mode; now they're fetched from
 * Google's own MediaPipe model storage the first time VRM mode opens, and
 * kept in the app's private storage from then on. Same files, same
 * versions as were bundled (float16, v1).
 *
 * A build that still has them in its assets uses those instead.
 */
object TrackingModels {
    enum class Model(val fileName: String, val url: String) {
        FACE("face_landmarker.task", "https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task"),
        HAND("hand_landmarker.task", "https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task"),
        POSE("pose_landmarker_full.task", "https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_full/float16/1/pose_landmarker_full.task")
    }

    private fun dir(context: Context) = File(context.filesDir, "mediapipe").apply { mkdirs() }
    private fun file(context: Context, model: Model) = File(dir(context), model.fileName)

    private fun inAssets(context: Context, model: Model): Boolean =
        runCatching { context.assets.open(model.fileName).close(); true }.getOrDefault(false)

    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    @Volatile private var running: kotlinx.coroutines.Deferred<Unit>? = null

    /**
     * Starts the download (or joins the one already running) in the app's
     * own scope, so it finishes even if VRM mode is closed meanwhile. Its
     * progress shows as [TrackerDownload.progress] (the Timeline's status
     * bubble and VRM mode both show it). Await it for the result.
     */
    fun startDownload(context: Context): kotlinx.coroutines.Deferred<Unit> = synchronized(this) {
        running?.takeIf { it.isActive } ?: scope.async {
            TrackerDownload.progress = 0f
            try { ensure(context.applicationContext) { TrackerDownload.progress = it } }
            finally { TrackerDownload.progress = null }
        }.also { running = it }
    }

    fun isDownloading(): Boolean = running?.isActive == true

    fun isReady(context: Context): Boolean =
        Model.entries.all { inAssets(context, it) || file(context, it).length() > 0L }

    /**
     * Fetches whatever isn't on the phone yet. [onProgress] gets 0…1 (on
     * this coroutine's thread). Throws with a readable message when a file
     * can't be fetched (offline, …).
     */
    suspend fun ensure(context: Context, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        val missing = Model.entries.filter { !inAssets(context, it) && file(context, it).length() <= 0L }
        if (missing.isEmpty()) return@withContext
        val client = OkHttpClient.Builder().build()
        missing.forEachIndexed { index, model ->
            val target = file(context, model)
            val part = File(target.path + ".part")
            try {
                // (Version 1 — the files that used to be bundled — or, should
                // that ever move, the current one.)
                val first = client.newCall(Request.Builder().url(model.url).build()).execute()
                val response = if (first.isSuccessful) first else {
                    first.close()
                    client.newCall(Request.Builder().url(model.url.replace("/float16/1/", "/float16/latest/")).build()).execute()
                }
                response.use { resp ->
                    if (!resp.isSuccessful) error("Couldn't download the trackers (${resp.code})")
                    val body = resp.body ?: error("Couldn't download the trackers")
                    val total = body.contentLength().takeIf { it > 0 } ?: -1L
                    var done = 0L
                    body.byteStream().use { input ->
                        part.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                coroutineContext.ensureActive()
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                if (total > 0) onProgress((index + done.toFloat() / total) / missing.size)
                            }
                        }
                    }
                }
                if (part.length() <= 0L || !part.renameTo(target)) error("Couldn't save the trackers")
            } catch (t: Throwable) {
                part.delete()
                if (t is kotlinx.coroutines.CancellationException) throw t
                throw IllegalStateException(
                    "VRM mode needs to download its trackers once (about 21 MB). Check your connection and open it again.", t
                )
            }
        }
        onProgress(1f)
    }

    /** The model for MediaPipe: memory-mapped from the downloaded file
     *  (no copy in the app's heap), or null to use the bundled asset. */
    fun buffer(context: Context, model: Model): ByteBuffer? {
        if (inAssets(context, model)) return null
        val f = file(context, model)
        if (f.length() <= 0L) return null
        return RandomAccessFile(f, "r").use { it.channel.map(FileChannel.MapMode.READ_ONLY, 0, f.length()) }
    }
}
