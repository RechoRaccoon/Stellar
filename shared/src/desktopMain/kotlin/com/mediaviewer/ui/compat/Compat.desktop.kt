package com.mediaviewer.ui.compat

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocal
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.mediaviewer.platform.AppEvents
import com.mediaviewer.platform.DesktopContext
import com.mediaviewer.platform.PlatformContext

private val DesktopLocalContext = staticCompositionLocalOf<PlatformContext> { DesktopContext }

actual val LocalContext: CompositionLocal<PlatformContext> get() = DesktopLocalContext

actual fun showPlatformToast(message: String, long: Boolean) = AppEvents.postMessage(message)

/** A computer has no vibration motor and no camera cutout. */
private object DesktopPlatformView : PlatformView {
    override fun performHapticFeedback(feedbackConstant: Int): Boolean = false

    /** (Taking a picture of the screen asks for a permission on a Mac;
     *  the transitions that use it run without one.) */
    override suspend fun captureScreen(): androidx.compose.ui.graphics.ImageBitmap? = null

    override fun displayCutoutCenterYPx(maxTopPx: Float): Float? = null

    override fun crunchHaptic() {}
}

@Composable
actual fun rememberPlatformView(): PlatformView = DesktopPlatformView

/** "Newest Android": every capability check passes on a computer. */
actual fun platformSdkInt(): Int = 10_000

/**
 * "Back" on a computer: the Esc key or the mouse's back button (see
 * DesktopBack, wired up by the window). Like Android, the most recently
 * shown enabled handler gets it.
 */
@Composable
actual fun BackHandler(enabled: Boolean, onBack: () -> Unit) {
    val current = rememberUpdatedState(onBack)
    val entry = remember { DesktopBack.Entry { current.value() } }
    SideEffect { entry.enabled = enabled }
    DisposableEffect(entry) {
        DesktopBack.add(entry)
        onDispose { DesktopBack.remove(entry) }
    }
}

/** The back handlers on screen, oldest first. */
object DesktopBack {
    class Entry(val onBack: () -> Unit) {
        @Volatile var enabled: Boolean = true
    }

    private val entries = ArrayList<Entry>()

    fun add(e: Entry) = synchronized(entries) { entries += e }
    fun remove(e: Entry) = synchronized(entries) { entries -= e }

    /** Hands "back" to the newest enabled handler; false when none wanted it. */
    fun dispatch(): Boolean {
        val target = synchronized(entries) { entries.lastOrNull { it.enabled } } ?: return false
        target.onBack()
        return true
    }
}

@Composable
actual fun rememberScreenSizeDp(): IntSize {
    val size = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current.density
    return remember(size, density) { IntSize((size.width / density).toInt(), (size.height / density).toInt()) }
}

@Composable
actual fun DialogBlurBehind(radius: Int, dimAmount: Float) {
    // The dialog's scrim dims the app behind it on a computer.
}

actual fun edgeToEdgeDialogProperties(): DialogProperties = DialogProperties(usePlatformDefaultWidth = false)

actual fun String.jformat(vararg args: Any?): String = java.lang.String.format(this, *args)

actual fun openUrl(context: PlatformContext, url: String) = DesktopLinks.open(url)

actual fun openAppLinkSettings(context: PlatformContext, packageName: String): Boolean = false

actual fun uptimeMillis(): Long = System.nanoTime() / 1_000_000L

actual object PlatformColor {
    actual fun colorToHSV(color: Int, hsv: FloatArray) {
        val r = ((color shr 16) and 0xFF) / 255f
        val g = ((color shr 8) and 0xFF) / 255f
        val b = (color and 0xFF) / 255f
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val delta = max - min
        val v = max
        val s = if (max == 0f) 0f else delta / max
        var h = when {
            delta == 0f -> 0f
            max == r -> (g - b) / delta
            max == g -> 2f + (b - r) / delta
            else -> 4f + (r - g) / delta
        } * 60f
        if (h < 0f) h += 360f
        hsv[0] = h; hsv[1] = s; hsv[2] = v
    }

    actual fun HSVToColor(hsv: FloatArray): Int = HSVToColor(0xFF, hsv)

    actual fun HSVToColor(alpha: Int, hsv: FloatArray): Int {
        val h = ((hsv[0] % 360f) + 360f) % 360f
        val s = hsv[1].coerceIn(0f, 1f)
        val v = hsv[2].coerceIn(0f, 1f)
        val c = v * s
        val hh = h / 60f
        val x = c * (1f - kotlin.math.abs(hh % 2f - 1f))
        val (r1, g1, b1) = when (hh.toInt()) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        val m = v - c
        fun ch(f: Float) = ((f + m) * 255f + 0.5f).toInt().coerceIn(0, 255)
        return ((alpha and 0xFF) shl 24) or (ch(r1) shl 16) or (ch(g1) shl 8) or ch(b1)
    }
}

actual fun vibrateOneShot(context: PlatformContext, ms: Long) {}

/** Rebuilds the app state in place from what's saved (like iOS). */
actual fun restartApp(context: PlatformContext) = AppEvents.requestRestart()

actual fun appPackageName(context: PlatformContext): String = "rechoraccoon.stellar"

actual fun applyReducedAnimations(context: PlatformContext, reduced: Boolean) {}

/** No navigation bar on a computer. */
actual val androidx.compose.foundation.layout.WindowInsets.Companion.navBarSpace: androidx.compose.foundation.layout.WindowInsets
    @Composable get() = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp)

/** Opens links in the computer's default browser. */
object DesktopLinks {
    fun open(url: String) {
        Thread {
            val ok = runCatching {
                val d = java.awt.Desktop.getDesktop()
                if (!java.awt.Desktop.isDesktopSupported() || !d.isSupported(java.awt.Desktop.Action.BROWSE)) error("no browse")
                d.browse(java.net.URI(url))
            }.isSuccess
            if (!ok) runCatching {
                val os = System.getProperty("os.name").orEmpty().lowercase()
                val cmd = when {
                    os.startsWith("windows") -> arrayOf("rundll32", "url.dll,FileProtocolHandler", url)
                    os.startsWith("mac") -> arrayOf("open", url)
                    else -> arrayOf("xdg-open", url)
                }
                Runtime.getRuntime().exec(cmd)
            }.onFailure { AppEvents.postMessage("Couldn't open the link") }
        }.apply { isDaemon = true }.start()
    }
}
