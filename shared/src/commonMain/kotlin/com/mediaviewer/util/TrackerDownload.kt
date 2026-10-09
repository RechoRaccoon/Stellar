package com.mediaviewer.util

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * VRM mode's tracker download (Android's TrackingModels), as Compose state
 * any screen can show: Timeline mode's status bubbles show it while it
 * runs, the way they show like-tagging. The download carries on if VRM
 * mode is left.
 */
object TrackerDownload {
    /** 0…1 while downloading; null when nothing is. */
    var progress by mutableStateOf<Float?>(null)

    val label: String? get() = progress?.let { "Downloading VRM trackers… ${(it * 100).toInt()}%" }
}
