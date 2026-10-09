package com.mediaviewer.platform

import platform.UIKit.*

object IosHaptics {
    fun tick() {
        if (!com.mediaviewer.util.UiToggles.hapticsEnabled) return
        runCatching {
            val generator = UIImpactFeedbackGenerator(style = UIImpactFeedbackStyle.UIImpactFeedbackStyleLight)
            generator.impactOccurred()
        }
    }
}
