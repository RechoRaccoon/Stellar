package com.mediaviewer.util

import kotlin.math.max
import kotlin.math.pow

/**
 * A spectrum turned into the visualizer's bars (the web and desktop
 * sources; Android has its own): [count] log-spaced bands from 50 Hz to
 * 12 kHz, each against a slowly falling peak so quiet and loud songs both
 * fill the bars, rising fast and falling gently.
 */
class VisualizerBands(val count: Int = 28) {
    private val smoothed = FloatArray(count)
    private val peak = FloatArray(count) { 1f }

    /** [mags]: linear-frequency bins from 0 Hz up to [sampleRate] / 2,
     *  each 0..1 (already on a loudness scale). The new bar heights. */
    fun update(mags: FloatArray, sampleRate: Float): FloatArray {
        val bins = mags.size
        if (bins < 4) return decay()
        val hzPerBin = (sampleRate / 2f) / bins
        val lo = 50.0; val hi = 12000.0
        for (b in 0 until count) {
            val f0 = lo * (hi / lo).pow(b.toDouble() / count)
            val f1 = lo * (hi / lo).pow((b + 1).toDouble() / count)
            val start = (f0 / hzPerBin).toInt().coerceIn(1, bins - 1)
            val end = (f1 / hzPerBin).toInt().coerceIn(start + 1, bins)
            var m = 0f
            for (k in start until end) m = max(m, mags[k])
            peak[b] = max(peak[b] * 0.995f, max(m, 0.25f))
            val target = (m / peak[b]).coerceIn(0f, 1f).let { if (m < 0.02f) 0f else it }
            smoothed[b] = if (target > smoothed[b]) smoothed[b] + (target - smoothed[b]) * 0.6f
                else smoothed[b] + (target - smoothed[b]) * 0.25f
        }
        return smoothed.copyOf()
    }

    /** No sound: the bars sink back down. */
    fun decay(): FloatArray {
        for (i in 0 until count) { smoothed[i] *= 0.6f; if (smoothed[i] < 0.01f) smoothed[i] = 0f }
        return smoothed.copyOf()
    }
}
