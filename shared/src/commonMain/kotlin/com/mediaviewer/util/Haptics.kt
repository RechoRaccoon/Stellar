package com.mediaviewer.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

/**
 * Fix 9 (per feedback): every button/clickable across the app should give
 * haptic feedback, and it should all feel identical — one consistent DEEP
 * tap everywhere. This is the single shared helper for that: call
 * `val tap = rememberHapticTap()` once per composable, then invoke `tap()`
 * at the top of each onClick handler (before the handler's real work).
 *
 * Uses [HapticFeedbackType.LongPress] — the deeper buzz — rather than the
 * lighter [HapticFeedbackType.TextHandleMove] tick the first version used,
 * so the whole app has one consistent, pronounced tap feel.
 */
@Composable
fun rememberHapticTap(): () -> Unit {
    val haptic = LocalHapticFeedback.current
    return remember(haptic) {
        { haptic.performHapticFeedback(HapticFeedbackType.LongPress) }
    }
}

/** Compose's haptics, silenced while Settings → Haptics is off. Provided
 *  as LocalHapticFeedback at the root of the app (SharedAppHost / Android's
 *  MainActivity), so every Compose haptic goes through it. */
class SwitchableHapticFeedback(private val base: androidx.compose.ui.hapticfeedback.HapticFeedback) :
    androidx.compose.ui.hapticfeedback.HapticFeedback {
    override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
        if (UiToggles.hapticsEnabled) base.performHapticFeedback(hapticFeedbackType)
    }
}

/** Wraps [content] so its Compose haptics follow Settings → Haptics. */
@Composable
fun SwitchableHaptics(content: @Composable () -> Unit) {
    val base = LocalHapticFeedback.current
    val wrapped = remember(base) { SwitchableHapticFeedback(base) }
    androidx.compose.runtime.CompositionLocalProvider(LocalHapticFeedback provides wrapped, content = content)
}
