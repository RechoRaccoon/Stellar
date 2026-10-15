package com.mediaviewer.ui

import com.mediaviewer.ui.compat.navBarSpace

import com.mediaviewer.ui.compat.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.mediaviewer.ui.theme.*

/** Bluesky's own app allows alt text this long on a picture. */
const val IMAGE_ALT_TEXT_LIMIT = 2000

/**
 * "Alt Text" for a picture attached in the composer (an image dropped on the
 * Alt Text bubble): the same popup as Quote Repost, the picture on the left,
 * and a check instead of send. Saving empty text removes the alt text.
 */
@Composable
fun AltTextDialog(
    image: Any,
    initial: String,
    liquidGlass: Boolean,
    dominantColor: Color = NeutralGlassTint,
    backdrop: GlassBackdrop? = null,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember(image) { mutableStateOf(initial) }
    val tint = dominantColor
    val overLimit = text.length > IMAGE_ALT_TEXT_LIMIT
    val focus = remember { FocusRequester() }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    LaunchedEffect(image) {
        kotlinx.coroutines.delay(120)
        runCatching { focus.requestFocus() }
        keyboard?.show()
    }
    // Like Quote Repost: closing the keyboard closes the popup (unsaved).
    val density = androidx.compose.ui.platform.LocalDensity.current
    val imeBottom = WindowInsets.ime.getBottom(density)
    var keyboardWasShown by remember(image) { mutableStateOf(false) }
    LaunchedEffect(imeBottom > 0) {
        if (imeBottom > 0) keyboardWasShown = true
        else if (keyboardWasShown) onDismiss()
    }
    BackHandler(onBack = onDismiss)

    Box(
        modifier = Modifier.fillMaxSize()
            .background(Color.Black.copy(alpha = if (liquidGlass) 0.22f else 0.6f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
            .padding(top = rememberTopCutoutClearance())
            .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navBarSpace))
            .padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        PopupSheetSurface(
            liquidGlass = liquidGlass, tint = tint, backdrop = backdrop,
            modifier = Modifier.landscapeReadable(560.dp).fillMaxWidth()
        ) {
            Column(Modifier.fillMaxWidth()) {
                PopupSheetHeader(
                    title = "Alt Text",
                    liquidGlass = liquidGlass, tint = tint, backdrop = backdrop,
                    onClose = onDismiss,
                    // (In the header's free right end, so it takes no room
                    // of its own.)
                    counter = "${text.length}/$IMAGE_ALT_TEXT_LIMIT",
                    counterOver = overLimit
                )
                PopupMessageBox(
                    thumbUrl = "", thumbModel = image,
                    value = text, onValueChange = { text = it },
                    placeholder = "Describe this image…",
                    tint = tint,
                    canSend = !overLimit,
                    sending = false,
                    onSend = { if (!overLimit) onSave(text.trim()) },
                    maxLines = 8,
                    focusRequester = focus,
                    sendIcon = Icons.Default.Check,
                    sendDescription = "Save alt text",
                    modifier = Modifier.padding(start = 10.dp, end = 10.dp, bottom = 10.dp, top = 4.dp)
                )
            }
        }
    }
}
