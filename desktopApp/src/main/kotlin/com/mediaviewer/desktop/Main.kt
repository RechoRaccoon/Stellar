package com.mediaviewer.desktop

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.mediaviewer.platform.DesktopAppPlatform
import com.mediaviewer.platform.DesktopContext
import com.mediaviewer.platform.DesktopNotifications
import com.mediaviewer.platform.DesktopPaths
import com.mediaviewer.platform.sharedPreferences
import com.mediaviewer.ui.SharedAppHost
import com.mediaviewer.ui.compat.DesktopBack
import com.mediaviewer.ui.compat.DesktopPickers
import com.mediaviewer.util.DesktopImageLoading
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import java.awt.AWTEvent
import java.awt.Dimension
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import java.awt.event.MouseEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.File

/**
 * Stellar for Windows and macOS: the same shared app as Android and iOS, in
 * a window. Stellar was made for phones, so the window opens in portrait
 * (phone-shaped, as tall as the screen comfortably allows); it can be
 * resized to any shape, and is reopened at whatever size and place it was
 * left in.
 */
@OptIn(FlowPreview::class)
fun main() {
    System.setProperty("apple.awt.application.name", "Stellar")
    System.setProperty("apple.awt.application.appearance", "NSAppearanceNameDarkAqua")
    CrashLog.install()
    DesktopImageLoading.install()
    val crash = CrashLog.read()
    val icon = runCatching {
        val bytes = Thread.currentThread().contextClassLoader.getResourceAsStream("stellar_icon.png")!!.use { it.readBytes() }
        org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap()
    }.getOrNull()
    DesktopNotifications.trayImage = runCatching {
        Thread.currentThread().contextClassLoader.getResource("stellar_icon.png")?.let { Toolkit.getDefaultToolkit().getImage(it) }
    }.getOrNull()
    // Back: the mouse's back button, anywhere in the window.
    Toolkit.getDefaultToolkit().addAWTEventListener({ e ->
        if (e is MouseEvent && e.id == MouseEvent.MOUSE_RELEASED && e.button == 4) {
            javax.swing.SwingUtilities.invokeLater { DesktopBack.dispatch() }
        }
    }, AWTEvent.MOUSE_EVENT_MASK)

    val saved = WindowMemory.load()
    application {
        val state = rememberWindowState(
            placement = if (saved?.maximized == true) WindowPlacement.Maximized else WindowPlacement.Floating,
            position = saved?.let { WindowPosition(it.x.dp, it.y.dp) } ?: WindowPosition(androidx.compose.ui.Alignment.Center),
            size = saved?.let { DpSize(it.width.dp, it.height.dp) } ?: WindowMemory.portraitSize(),
        )
        Window(
            onCloseRequest = {
                DesktopNotifications.shutdown()
                exitApplication()
            },
            title = "Stellar",
            icon = icon?.let { BitmapPainter(it) },
            state = state,
            onPreviewKeyEvent = { e ->
                // Esc is "back" (closes the open page / sheet), like a phone's back.
                if (e.key == Key.Escape && e.type == KeyEventType.KeyUp) DesktopBack.dispatch() else false
            },
        ) {
            DisposableEffect(window) {
                window.minimumSize = Dimension(320, 480)
                DesktopPickers.owner = window
                DesktopNotifications.showWindow = {
                    window.isVisible = true
                    if (window.extendedState and java.awt.Frame.ICONIFIED != 0) window.extendedState = window.extendedState and java.awt.Frame.ICONIFIED.inv()
                    window.toFront()
                    window.requestFocus()
                }
                val focus = object : WindowAdapter() {
                    override fun windowGainedFocus(e: WindowEvent?) { DesktopNotifications.windowFocused = true }
                    override fun windowLostFocus(e: WindowEvent?) { DesktopNotifications.windowFocused = false }
                }
                window.addWindowFocusListener(focus)
                onDispose { window.removeWindowFocusListener(focus) }
            }
            // Where the window is, remembered for next time.
            LaunchedEffect(state) {
                snapshotFlow { Triple(state.size, state.position, state.placement) }
                    .debounce(600)
                    .collect { (size, pos, placement) ->
                        WindowMemory.save(
                            WindowMemory.Bounds(
                                x = if (pos.isSpecified) pos.x.value else Float.NaN,
                                y = if (pos.isSpecified) pos.y.value else Float.NaN,
                                width = size.width.value, height = size.height.value,
                                maximized = placement == WindowPlacement.Maximized
                            )
                        )
                    }
            }
            remember { com.mediaviewer.platform.DesktopNotifications.sync(); true }
            SharedAppHost(
                context = DesktopContext,
                createPlatform = { bsky, e621 -> DesktopAppPlatform(bsky, e621) },
                crashLog = crash,
                onCrashLogDismissed = { CrashLog.clear() }
            )
        }
    }
}

/** The window's size and place between launches. */
private object WindowMemory {
    class Bounds(val x: Float, val y: Float, val width: Float, val height: Float, val maximized: Boolean)

    private val prefs get() = DesktopContext.sharedPreferences("desktop_window")

    fun load(): Bounds? {
        val p = prefs
        if (!p.contains("width")) return null
        val b = Bounds(p.getFloat("x", Float.NaN), p.getFloat("y", Float.NaN), p.getFloat("width", 0f), p.getFloat("height", 0f), p.getBoolean("maximized", false))
        if (b.width < 320f || b.height < 480f || b.x.isNaN() || b.y.isNaN()) return null
        // Still on a screen? (A monitor may have been unplugged since.)
        val onScreen = GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.any { d ->
            d.defaultConfiguration.bounds.intersects(java.awt.Rectangle(b.x.toInt(), b.y.toInt(), b.width.toInt().coerceAtMost(200), 60))
        }
        return if (onScreen) b else null
    }

    fun save(b: Bounds) {
        if (b.x.isNaN() || b.y.isNaN()) return
        prefs.edit()
            .putFloat("x", b.x).putFloat("y", b.y)
            .putFloat("width", b.width).putFloat("height", b.height)
            .putBoolean("maximized", b.maximized)
            .apply()
    }

    /** A phone-shaped window: as tall as the screen comfortably allows
     *  (at most 940), about half as wide as it is tall. */
    fun portraitSize(): DpSize {
        val usable = runCatching { GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds }.getOrNull()
        val h = ((usable?.height ?: 900) - 40).coerceIn(560, 940)
        val w = (h * 0.48f).toInt()
        return DpSize(w.dp, h.dp)
    }
}

/**
 * Same idea as the phone apps' crash screen: an uncaught error is written
 * to a file before Stellar closes, and shown as copyable text the next
 * time it opens.
 */
private object CrashLog {
    private val file get() = File(DesktopPaths.filesDir, "last_crash.txt")

    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { file.writeText(e.stackTraceToString()) }
            previous?.uncaughtException(t, e)
        }
    }

    fun read(): String? = runCatching { file.takeIf { it.isFile }?.readText() }.getOrNull()?.takeIf { it.isNotBlank() }

    fun clear() { runCatching { file.delete() } }
}
