package com.mediaviewer.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.mediaviewer.util.UiToggles

/**
 * Shown once, the first time the status box or the custom emoji button is
 * opened: statuses, status history and custom emoji are saved on the
 * person's own PDS, where anyone can see them. Place it as the last child
 * of a full-size Box; it does nothing once it's been closed.
 */
@Composable
fun PublicDataNotice(liquidGlass: Boolean, tint: Color, backdrop: GlassBackdrop? = null, modifier: Modifier = Modifier) {
    if (UiToggles.publicDataNoticeSeen) return
    ConfirmPopup(
        title = "These Are Public",
        message = "Your status, your status history and your custom emoji are saved to your Bluesky account, where anyone can see them. Don't post or upload anything you wouldn't want others to see.",
        confirmLabel = "Got It",
        liquidGlass = liquidGlass, tint = tint, backdrop = backdrop,
        onConfirm = { UiToggles.markPublicDataNoticeSeen() },
        onDismiss = { UiToggles.markPublicDataNoticeSeen() },
        modifier = modifier,
        showCancel = false,
        destructive = false,
        dismissOnOutsideTap = false
    )
}
