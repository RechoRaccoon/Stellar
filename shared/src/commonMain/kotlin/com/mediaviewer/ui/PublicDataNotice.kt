package com.mediaviewer.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.mediaviewer.util.UiToggles

/** What a [PublicDataNotice] is about; each is shown once, on its own.
 *  (Statuses have none: their history is encrypted by Private Sync.) */
enum class PublicDataKind { EMOJI }

/**
 * Shown once, the first time a custom emoji menu ([PublicDataKind.EMOJI])
 * is opened: custom emoji are uploaded unencrypted to the person's own
 * PDS, where anyone can see them. Place it as the last child of a
 * full-size Box; it does nothing once it's been closed.
 */
@Composable
fun PublicDataNotice(kind: PublicDataKind, liquidGlass: Boolean, tint: Color, backdrop: GlassBackdrop? = null, modifier: Modifier = Modifier) {
    if (UiToggles.emojiNoticeSeen) return
    val close = { UiToggles.markEmojiNoticeSeen() }
    ConfirmPopup(
        title = "Your Emojis Are Public",
        message = "The custom emoji you import are uploaded unencrypted to your PDS, and can be seen by anyone. Don't import anything you wouldn't want others to see.",
        confirmLabel = "Got It",
        liquidGlass = liquidGlass, tint = tint, backdrop = backdrop,
        onConfirm = close,
        onDismiss = close,
        modifier = modifier,
        showCancel = false,
        destructive = false,
        dismissOnOutsideTap = false
    )
}
