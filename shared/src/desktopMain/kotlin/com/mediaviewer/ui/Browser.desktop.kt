package com.mediaviewer.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import javafx.application.Platform
import javafx.concurrent.Worker
import javafx.embed.swing.JFXPanel
import javafx.scene.Scene
import javafx.scene.web.WebView
import kotlinx.coroutines.delay

/**
 * Web pages on the desktop app: JavaFX's WebView (WebKit), bundled with the
 * app, inside the Compose window. One [DesktopWebPage] per page; it's kept
 * in [BrowserState.native] like Android's WebView and iOS's WKWebView, so a
 * page hidden and shown again doesn't reload.
 */
internal class DesktopWebPage(initialUrl: String) {
    val panel: JFXPanel = DesktopWeb.newPanel()
    @Volatile private var view: WebView? = null

    // What the page reports, read by the Compose side.
    @Volatile var location: String = initialUrl
    @Volatile var title: String = ""
    @Volatile var canGoBack = false
    @Volatile var canGoForward = false
    @Volatile var loading = true
    /** Called on the UI thread the moment the page reaches an address. */
    @Volatile var onLocation: ((String) -> Unit)? = null

    init {
        DesktopWeb.fx {
            val web = WebView()
            val engine = web.engine
            engine.userAgent = DesktopWeb.USER_AGENT
            engine.locationProperty().addListener { _, _, now ->
                if (now != null) {
                    location = now
                    onLocation?.let { cb -> javax.swing.SwingUtilities.invokeLater { cb(now) } }
                }
            }
            engine.titleProperty().addListener { _, _, now -> title = now.orEmpty() }
            engine.loadWorker.stateProperty().addListener { _, _, s ->
                loading = s == Worker.State.RUNNING || s == Worker.State.SCHEDULED
                val h = engine.history
                canGoBack = h.currentIndex > 0
                canGoForward = h.currentIndex < h.entries.size - 1
            }
            // Pages that open a new window open it here instead.
            engine.setCreatePopupHandler { engine }
            panel.scene = Scene(web, javafx.scene.paint.Color.BLACK)
            view = web
            engine.load(initialUrl)
        }
    }

    fun command(cmd: String) = DesktopWeb.fx {
        val engine = view?.engine ?: return@fx
        when {
            cmd.startsWith("load:") -> engine.load(cmd.removePrefix("load:"))
            cmd == "back" -> runCatching { engine.history.go(-1) }
            cmd == "forward" -> runCatching { engine.history.go(1) }
            cmd == "reload" -> engine.reload()
        }
    }

    fun setZoom(zoom: Float) = DesktopWeb.fx { view?.zoom = zoom.toDouble() }

    fun destroy() = DesktopWeb.fx {
        view?.engine?.load(null)
        view = null
        panel.scene = null
    }
}

internal object DesktopWeb {
    /** A current desktop Safari's: some sites turn away a browser they don't recognise. */
    const val USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Safari/605.1.15"

    @Volatile private var started = false

    /** A JavaFX panel (making the first one starts JavaFX). */
    fun newPanel(): JFXPanel {
        val panel = JFXPanel()
        if (!started) {
            started = true
            // Hiding every page mustn't shut JavaFX down for good.
            Platform.setImplicitExit(false)
        }
        return panel
    }

    /** Runs [block] on JavaFX's thread. */
    fun fx(block: () -> Unit) {
        if (Platform.isFxApplicationThread()) block() else Platform.runLater { runCatching(block) }
    }
}

@Composable
actual fun PlatformBrowserView(state: BrowserState, modifier: Modifier) {
    val made = remember(state) {
        (state.native as? DesktopWebPage) ?: runCatching { DesktopWebPage(state.pendingUrl) }.getOrNull()?.also { page ->
            state.native = page
            state.command = { page.command(it) }
            state.destroy = { page.destroy() }
        }
    }
    // (Only if the web view can't start at all.)
    val page = made ?: return WebPageCard(state.url, modifier)
    DisposableEffect(page, state) {
        page.onLocation = { address -> if (address != state.url) state.url = address }
        onDispose { page.onLocation = null }
    }
    SwingPanel(background = Color.Black, factory = { page.panel }, modifier = modifier, update = { page.setZoom(state.zoom) })
    // The page's title and history buttons, read a few times a second
    // while it's on screen (its address is passed on as it changes).
    LaunchedEffect(state, page) {
        while (true) {
            if (page.title != state.title) state.title = page.title
            state.canGoBack = page.canGoBack
            state.canGoForward = page.canGoForward
            state.loading = page.loading
            delay(300)
        }
    }
}
