package com.mediaviewer.tagging

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mediaviewer.json.StellarJson
import com.mediaviewer.network.PlainHttp
import com.mediaviewer.network.bodyString
import com.mediaviewer.network.isSuccessful
import com.mediaviewer.platform.PlatformContext
import com.mediaviewer.platform.SharedPreferences
import com.mediaviewer.platform.currentTimeMillis
import com.mediaviewer.platform.ioDispatcher
import com.mediaviewer.platform.sharedPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Stellar's shared tag dataset: the tagged posts Recho publishes (built by
 * the "Shared Tag Dataset" workflow from the exports on the repo's
 * `tag-datasets` release, see tools/tag-dataset/merge.py), downloaded
 * whole and kept on the device as one more imported dataset, so search
 * uses it together with the person's own tags.
 *
 * Opening Tagged search checks for a new version, at most once a day. If
 * there is one (or it's never been downloaded), [prompt] asks first;
 * the download shows its progress there too.
 */
object SharedTagDataset {
    private const val BASE = "https://raw.githubusercontent.com/${com.mediaviewer.util.AppUpdates.REPO}/tag-dataset/"
    const val NAME = "Stellar Shared Tags"
    private const val CHECK_EVERY_MS = 24 * 60 * 60 * 1000L

    sealed class Prompt {
        /** Never downloaded ([isUpdate] false), or a newer version is out. */
        data class Offer(val isUpdate: Boolean, val bytes: Long, val posts: Int) : Prompt()
        /** [progress] 0–1; [label] e.g. "12.4 MB of 48.0 MB". */
        data class Downloading(val progress: Float, val label: String) : Prompt()
        data class Failed(val message: String, val isUpdate: Boolean) : Prompt()
    }

    /** What the Tagged search page shows over itself (Compose state). */
    var prompt by mutableStateOf<Prompt?>(null)
        private set

    /** Set by the ViewModel: where the dataset goes, and what to refresh after. */
    var service: TaggingService? = null
    var onInstalled: (() -> Unit)? = null

    private class Part(val file: String, val bytes: Long)
    private class Manifest(val version: String, val posts: Int, val bytes: Long, val parts: List<Part>)

    private var prefs: SharedPreferences? = null
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private var checking = false
    private var manifest: Manifest? = null
    private var job: Job? = null

    fun init(context: PlatformContext) {
        if (prefs == null) prefs = context.sharedPreferences("shared_tag_dataset")
    }

    /** The installed copy's dataset id (left out of exports), or null. */
    fun installedDatasetId(): String? = prefs?.getString("dataset_id", null)?.takeIf { it.isNotBlank() }
    private fun installedVersion(): String? = prefs?.getString("version", null)?.takeIf { it.isNotBlank() }

    /** Tagged search was opened: checks for a new version (once a day). */
    fun onTaggedSearchOpened() {
        val p = prefs ?: return
        if (checking || prompt != null || job?.isActive == true) return
        val now = currentTimeMillis()
        if (now - p.getLong("last_check", 0L) < CHECK_EVERY_MS) return
        checking = true
        scope.launch {
            try {
                // Deleted in Settings meanwhile: it counts as not downloaded.
                val id = installedDatasetId()
                val sets = service?.listImportedDatasets()
                if (id != null && sets?.none { it.id == id } == true) {
                    p.edit().remove("dataset_id").remove("version").apply()
                }
                val m = fetchManifest()
                // Installed, but the app was closed before it noted so (a
                // long install the system stopped): it's taken as is
                // instead of being offered again and again.
                if (installedDatasetId() == null) adopt(sets.orEmpty(), m)
                p.edit().putLong("last_check", now).apply()
                manifest = m
                if (m.posts > 0 && m.version != installedVersion()) {
                    withContext(Dispatchers.Main) { prompt = Prompt.Offer(installedVersion() != null, m.bytes, m.posts) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Offline or not published yet: asked again next time.
            } finally {
                checking = false
            }
        }
    }

    /** Copies of the shared dataset already in the list that this device
     *  never noted (or leftover duplicates): the newest is kept and noted
     *  as installed — as this version when it has all its posts — and the
     *  rest removed. */
    private suspend fun adopt(sets: List<TagDatasetInfo>, m: Manifest) {
        val copies = sets.filter { it.name == NAME }.sortedByDescending { it.importedAt }
        val keep = copies.firstOrNull() ?: return
        copies.drop(1).forEach { runCatching { service?.deleteDataset(it.id) } }
        val complete = m.posts > 0 && keep.postCount >= m.posts * 0.98
        prefs?.edit()?.putString("dataset_id", keep.id)?.apply {
            if (complete) putString("version", m.version) else putString("version", "partial")
        }?.apply()
    }

    /** "Download" / "Update" / "Try Again". */
    fun download() {
        if (job?.isActive == true) return
        val isUpdate = installedVersion() != null
        prompt = Prompt.Downloading(0f, "Starting…")
        job = scope.launch {
            try {
                val m = manifest ?: fetchManifest().also { manifest = it }
                val total = m.bytes.coerceAtLeast(1L)
                var done = 0L
                val posts = ArrayList<TagExportedPost>(m.posts)
                for (part in m.parts) {
                    val resp = PlainHttp.get(BASE + part.file)
                    if (!resp.isSuccessful) error("The download stopped (${resp.code})")
                    parsePart(resp.bodyString(), posts)
                    done += part.bytes
                    val shown = done
                    withContext(Dispatchers.Main) {
                        prompt = Prompt.Downloading((shown.toFloat() / total) * 0.95f, "${mb(shown)} of ${mb(total)}")
                    }
                }
                withContext(Dispatchers.Main) { prompt = Prompt.Downloading(0.95f, "Adding it to your tags…") }
                val svc = service ?: error("Tagging isn't available")
                // The new copy goes in before the old one comes out, so
                // search never goes empty in between.
                val old = installedDatasetId()
                var lastShown = -1
                val info = svc.importDataset(NAME, posts) { f ->
                    val pct = (f * 100).toInt()
                    if (pct != lastShown) {
                        lastShown = pct
                        scope.launch(Dispatchers.Main) { if (job?.isActive == true) prompt = Prompt.Downloading(0.95f + 0.05f * f, "Adding it to your tags… $pct%") }
                    }
                }
                if (old != null && old != info.id) runCatching { svc.deleteDataset(old) }
                prefs?.edit()?.putString("dataset_id", info.id)?.putString("version", m.version)?.apply()
                withContext(Dispatchers.Main) {
                    prompt = null
                    onInstalled?.invoke()
                }
            } catch (e: CancellationException) {
                withContext(Dispatchers.Main + kotlinx.coroutines.NonCancellable) { prompt = null }
                throw e
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { prompt = Prompt.Failed(e.message ?: "The download didn't finish", isUpdate) }
            }
        }
    }

    /** "Not Now" / "Cancel" / "Close": asked again on the next day's check. */
    fun dismiss() {
        job?.cancel()
        job = null
        prompt = null
    }

    private suspend fun fetchManifest(): Manifest {
        val resp = PlainHttp.get(BASE + "manifest.json")
        if (!resp.isSuccessful) error("No shared dataset yet (${resp.code})")
        val o = StellarJson.default.parseToJsonElement(resp.bodyString()) as? JsonObject ?: error("Unreadable manifest")
        val parts = (o["parts"] as? JsonArray).orEmpty().mapNotNull { e ->
            val po = e as? JsonObject ?: return@mapNotNull null
            val file = (po["file"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            Part(file, (po["bytes"] as? JsonPrimitive)?.longOrNull ?: 0L)
        }
        return Manifest(
            version = (o["version"] as? JsonPrimitive)?.content ?: error("Unreadable manifest"),
            posts = (o["posts"] as? JsonPrimitive)?.intOrNull ?: 0,
            bytes = (o["bytes"] as? JsonPrimitive)?.longOrNull ?: parts.sumOf { it.bytes },
            parts = parts
        )
    }

    /** A part: [["uri", "cid", "media url", [["tag", 0.93], …]], …]. */
    private fun parsePart(text: String, into: MutableList<TagExportedPost>) {
        val rows = StellarJson.default.parseToJsonElement(text) as? JsonArray ?: error("Unreadable dataset part")
        for (row in rows) {
            val r = row as? JsonArray ?: continue
            fun str(i: Int) = (r.getOrNull(i) as? JsonPrimitive)?.content.orEmpty()
            val uri = str(0).takeIf { it.isNotBlank() } ?: continue
            val tags = (r.getOrNull(3) as? JsonArray).orEmpty().mapNotNull { t ->
                val ta = t as? JsonArray ?: return@mapNotNull null
                val name = (ta.getOrNull(0) as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                name to ((ta.getOrNull(1) as? JsonPrimitive)?.floatOrNull ?: 0f)
            }
            into += TagExportedPost(uri, str(1), str(2), tags)
        }
    }

    fun mb(bytes: Long): String {
        val tenths = (bytes * 10 + 500_000) / 1_000_000
        return "${tenths / 10}.${tenths % 10} MB"
    }
}
