package com.mediaviewer.platform

/** Which platform this build of Stellar is running on. Shared code checks
 *  this to hide features a platform can't support (e.g. the audio
 *  visualizer on iOS) — never to remove them from Android. */
enum class PlatformKind { ANDROID, IOS,
    /** Windows and macOS (the desktop app, a JVM build of the shared code). */
    DESKTOP,
    /** A web browser (Kotlin/Wasm, the page in :webApp). */
    WEB }

expect val currentPlatform: PlatformKind
