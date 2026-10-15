package com.mediaviewer.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.mediaviewer.util.UiToggles

/** What a [PublicDataNotice] is about; each is shown once, on its own. */
enum class PublicDataKind { STATUS, EMOJI }

/**
 * Shown once each, the first time the status box ([PublicDataKind.STATUS])
 * or a custom emoji menu ([PublicDataKind.EMOJI]) is opened: statuses (and
 * their history) and custom emoji are saved on the person's own PDS, where
 * anyone can see them. Place it as the last child of a full-size Box; it
 * does nothing once it's been closed.
 */
@Composable
fun PublicDataNotice(kind: PublicDataKind, liquidGlass: Boolean, tint: Color, backdrop: GlassBackdrop? = null, modifier: Modifier = Modifier) {
    val seen = when (kind) {
        PublicDataKind.STATUS -> UiToggles.statusNoticeSeen
        PublicDataKind.EMOJI -> UiToggles.emojiNoticeSeen
    }
    if (seen) return
    val close = {
        when (kind) {
            PublicDataKind.STATUS -> UiToggles.markStatusNoticeSeen()
            PublicDataKind.EMOJI -> UiToggles.markEmojiNoticeSeen()
        }
    }
    ConfirmPopup(
        title = when (kind) {
            PublicDataKind.STATUS -> "Your Status Is Public"
            PublicDataKind.EMOJI -> "Your Emoji Are Public"
        },
        message = when (kind) {
            PublicDataKind.STATUS -> "Your status and your status history are saved to your Bluesky account, where anyone can see them. Don't post anything you wouldn't want others to see."
            PublicDataKind.EMOJI -> "The custom emoji you add are saved to your Bluesky account, where anyone can see them. Don't upload anything you wouldn't want others to see."
        },
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
