package com.mediaviewer.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import com.mediaviewer.platform.openInNewTab

/**
 * Search → Web Browser on the web: a page can't be shown inside Stellar's
 * canvas (and most sites refuse to be framed), so the page opens in a new
 * browser tab; the card shows where it goes, with a button to open it.
 */
@Composable
actual fun PlatformBrowserView(state: BrowserState, modifier: Modifier) {
    SideEffect {
        if (state.command == null) {
            state.command = { cmd ->
                if (cmd.startsWith("load:")) {
                    val address = cmd.removePrefix("load:")
                    state.url = address
                    openInNewTab(address)
                }
            }
            state.destroy = {}
        }
        state.loading = false
        state.canGoBack = false
        state.canGoForward = false
    }
    WebPageCard(state.url, modifier, title = state.title)
}
