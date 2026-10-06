package com.mediaviewer.util

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Where the composer's attached video is on its way to Bluesky. The upload
 * starts as soon as a video is attached (see
 * BlueskyRepository.prepareVideoUpload); the composer shows this under the
 * video. Readable anywhere as Compose state.
 */
object VideoUpload {
    enum class Stage { IDLE, UPLOADING, PROCESSING, READY, FAILED }

    var stage by mutableStateOf(Stage.IDLE)
        private set
    /** Processing progress, 0–100, or -1 while unknown. */
    var progress by mutableStateOf(-1)
        private set
    var error by mutableStateOf("")
        private set

    fun set(stage: Stage, error: String = "", progress: Int = -1) {
        this.stage = stage
        this.error = error
        this.progress = progress
    }

    val label: String get() = when (stage) {
        Stage.IDLE -> ""
        Stage.UPLOADING -> "Uploading…"
        Stage.PROCESSING -> if (progress in 1..99) "Processing… $progress%" else "Processing…"
        Stage.READY -> "Ready to post"
        Stage.FAILED -> "Will retry when you post"
    }
}
