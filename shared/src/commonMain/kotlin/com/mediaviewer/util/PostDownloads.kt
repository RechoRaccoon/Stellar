package com.mediaviewer.util

import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A post being downloaded (normally or as a GIF), for the bubble at the
 * top of that post: "Downloading…" / "Saving GIF…" while it runs, then
 * "Downloaded" / "GIF Saved" (or "Download Failed") for a few seconds.
 *
 * The view model registers the post with the ids of the platform jobs it
 * started (one per picture of a multi-picture post); each job reports back
 * with [jobDone] when it has finished for good.
 */
object PostDownloads {
    enum class Stage { RUNNING, DONE, FAILED }
    data class Entry(val asGif: Boolean, val stage: Stage)

    private class Pending(val postId: String, val asGif: Boolean, val jobs: MutableSet<String>, var failed: Boolean = false)

    private val entries = mutableStateMapOf<String, Entry>()
    private val pending = HashMap<String, Pending>()
    private val jobToPost = HashMap<String, String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun start(postId: String, asGif: Boolean, jobIds: List<String>) {
        if (postId.isBlank() || jobIds.isEmpty()) return
        scope.launch {
            pending[postId] = Pending(postId, asGif, jobIds.toMutableSet())
            jobIds.forEach { jobToPost[it] = postId }
            entries[postId] = Entry(asGif, Stage.RUNNING)
        }
    }

    /** A platform download job has finished (successfully or not). */
    fun jobDone(jobId: String, ok: Boolean) {
        scope.launch {
            val postId = jobToPost.remove(jobId) ?: return@launch
            val p = pending[postId] ?: return@launch
            p.jobs.remove(jobId)
            if (!ok) p.failed = true
            if (p.jobs.isNotEmpty()) return@launch
            pending.remove(postId)
            val done = Entry(p.asGif, if (p.failed) Stage.FAILED else Stage.DONE)
            entries[postId] = done
            delay(2500)
            if (entries[postId] == done && pending[postId] == null) entries.remove(postId)
        }
    }

    /** The bubble's text for [postId], or null when nothing's going on. */
    fun labelFor(postId: String): String? {
        val e = entries[postId] ?: return null
        return when (e.stage) {
            Stage.RUNNING -> if (e.asGif) "Saving GIF…" else "Downloading…"
            Stage.DONE -> if (e.asGif) "GIF Saved" else "Downloaded"
            Stage.FAILED -> "Download Failed"
        }
    }

    fun isRunning(postId: String): Boolean = entries[postId]?.stage == Stage.RUNNING
}
