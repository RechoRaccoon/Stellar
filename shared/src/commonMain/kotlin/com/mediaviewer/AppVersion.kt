package com.mediaviewer

/**
 * Stellar's version — the one place it's kept. The Android and desktop
 * builds read it from this file (see their build.gradle.kts), and the
 * update check compares it with the newest GitHub release's tag (v2.8 →
 * "2.8").
 *
 * Bump it BEFORE building the files for a new release, to the same number
 * as the release's tag — otherwise that build thinks it's out of date and
 * keeps offering the update it already is.
 */
object AppVersion {
    const val NAME = "2.8"
}
