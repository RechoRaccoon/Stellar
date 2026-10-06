package com.mediaviewer.platform

/**
 * Features that only exist on Android. On iOS they stay visible in the UI
 * but dimmed, not clickable, and marked with an "Android only" tag (see
 * ui/AndroidOnly.kt) — never hidden, never removed from Android.
 */
enum class PlatformFeature(val label: String) {
    AI_TAGGING("AI Tagging"),
    AUDIO_VISUALIZER("Audio visualizer"),
    LIVE_STREAMING("Live streaming"),
    VRM_MODE("VRM mode"),
    GIF_EXPORT("Save as GIF"),
    CUSTOM_FONT("Custom font"),
    OPEN_BY_DEFAULT_LINKS("Open Bluesky links in Stellar"),
    TRANSLATION("Translate post text"),
    APP_RESTART("Restart app");

    /** True where this feature works on the current platform. */
    val isAvailable: Boolean get() = currentPlatform == PlatformKind.ANDROID ||
        // iOS: on-device translation through Apple's own framework (iOS 18+).
        (this == TRANSLATION && com.mediaviewer.util.TranslationManager.isAvailable) ||
        // iOS: the same tagger model, run by the Swift app (ONNX Runtime).
        (this == AI_TAGGING && IosCapabilities.aiTagging) ||
        // iOS: GIFs made with Apple's own image and video frameworks.
        (this == GIF_EXPORT && IosCapabilities.gifExport) ||
        // iOS: fonts imported from the Files app.
        this == CUSTOM_FONT
}

/** What the iOS app turned out to be able to do, set once at launch when
 *  the Swift side hands over its helpers (always false on Android, where
 *  every feature is simply there). */
object IosCapabilities {
    @kotlin.concurrent.Volatile var aiTagging = false
    @kotlin.concurrent.Volatile var gifExport = false
}
