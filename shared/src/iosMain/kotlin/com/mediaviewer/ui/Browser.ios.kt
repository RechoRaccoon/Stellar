package com.mediaviewer.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitInteropInteractionMode
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.delay
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration

/** The system WKWebView (part of iOS — nothing bundled). */
@OptIn(ExperimentalForeignApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
actual fun PlatformBrowserView(state: BrowserState, modifier: Modifier) {
    UIKitView(
        factory = {
            val existing = state.native as? WKWebView
            val web = existing ?: WKWebView(
                frame = CGRectMake(0.0, 0.0, 0.0, 0.0),
                configuration = WKWebViewConfiguration().apply { allowsInlineMediaPlayback = true }
            ).apply {
                // (A page that holds its touches doesn't swipe back either.)
                allowsBackForwardNavigationGestures = !state.holdsTouches
                NSURL.URLWithString(state.pendingUrl)?.let { loadRequest(NSURLRequest.requestWithURL(it)) }
            }
            web.removeFromSuperview()
            state.native = web
            state.command = { cmd ->
                when {
                    cmd.startsWith("load:") -> NSURL.URLWithString(cmd.removePrefix("load:"))?.let { web.loadRequest(NSURLRequest.requestWithURL(it)) }
                    cmd == "back" -> web.goBack()
                    cmd == "forward" -> web.goForward()
                    cmd == "reload" -> web.reload()
                }
            }
            state.destroy = {
                web.stopLoading()
                web.removeFromSuperview()
            }
            web
        },
        modifier = modifier,
        update = { web -> web.pageZoom = state.zoom.toDouble() },
        // Normally a touch goes to the Compose page around the web view
        // first, which can claim it as a scroll once it starts moving — so
        // dragging something on the web page moved it a little and then let
        // go. A page that holds its touches gets them directly instead.
        properties = UIKitInteropProperties(
            interactionMode = if (state.holdsTouches) UIKitInteropInteractionMode.NonCooperative else UIKitInteropInteractionMode.Cooperative()
        )
    )
    // The page's address, title and history buttons, read a few times a
    // second while it's on screen.
    LaunchedEffect(state) {
        while (true) {
            val web = state.native as? WKWebView
            if (web != null) {
                state.canGoBack = web.canGoBack
                state.canGoForward = web.canGoForward
                web.URL?.absoluteString?.let { if (it != state.url) state.url = it }
                web.title?.let { if (it != state.title) state.title = it }
                state.loading = web.estimatedProgress < 1.0
            }
            // (Someone waiting on an address is told sooner.)
            delay(if (state.onUrl != null) 50 else 350)
        }
    }
}
