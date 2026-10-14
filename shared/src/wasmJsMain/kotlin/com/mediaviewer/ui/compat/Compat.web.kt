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
import com.mediaviewer.platform.PlatformContext
import com.mediaviewer.platform.WebContext
import com.mediaviewer.platform.openInNewTab

private val WebLocalContext = staticCompositionLocalOf<PlatformContext> { WebContext }

actual val LocalContext: CompositionLocal<PlatformContext> get() = WebLocalContext

actual fun showPlatformToast(message: String, long: Boolean) = AppEvents.postMessage(message)

/** A browser has no camera cutout; haptics only where the device has a motor. */
private object WebPlatformView : PlatformView {
    override fun performHapticFeedback(feedbackConstant: Int): Boolean = false
    override suspend fun captureScreen(): androidx.compose.ui.graphics.ImageBitmap? = null
    override fun displayCutoutCenterYPx(maxTopPx: Float): Float? = null
    override fun crunchHaptic() {}
}

@Composable
actual fun rememberPlatformView(): PlatformView = WebPlatformView

/** "Newest Android": every capability check passes in a browser. */
actual fun platformSdkInt(): Int = 10_000

/**
 * "Back" on the web: the browser's back button, Esc, or the mouse's back
 * button (see WebBack, wired up by the page). Like Android, the most
 * recently shown enabled handler gets it.
 */
@Composable
actual fun BackHandler(enabled: Boolean, onBack: () -> Unit) {
    val current = rememberUpdatedState(onBack)
    val entry = remember { WebBack.Entry { current.value() } }
    SideEffect {
        if (entry.enabled != enabled) {
            entry.enabled = enabled
            WebBack.changed()
        }
    }
    DisposableEffect(entry) {
        WebBack.add(entry)
        onDispose { WebBack.remove(entry) }
    }
}

/**
 * The back handlers on screen, oldest first. While any of them wants
 * "back", the page keeps one extra entry in the browser's history, so the
 * browser's back button (and a phone's back gesture) closes the open page
 * or sheet instead of leaving Stellar.
 */
object WebBack {
    class Entry(val onBack: () -> Unit) {
        var enabled: Boolean = true
    }

    private val entries = ArrayList<Entry>()

    /** Set by the page: keeps the browser's history in step. */
    var onWantsBackChanged: ((Boolean) -> Unit)? = null

    fun add(e: Entry) { entries += e; changed() }
    fun remove(e: Entry) { entries -= e; changed() }

    fun wantsBack(): Boolean = entries.any { it.enabled }

    fun changed() { onWantsBackChanged?.invoke(wantsBack()) }

    /** Hands "back" to the newest enabled handler; false when none wanted it. */
    fun dispatch(): Boolean {
        val target = entries.lastOrNull { it.enabled } ?: return false
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
    // The dialog's scrim dims the app behind it in a browser.
}

actual fun edgeToEdgeDialogProperties(): DialogProperties = DialogProperties(usePlatformDefaultWidth = false)

actual fun String.jformat(vararg args: Any?): String = JFormat.format(this, args)

/** Links open in a new browser tab. */
actual fun openUrl(context: PlatformContext, url: String) = openInNewTab(url)

actual fun openAppLinkSettings(context: PlatformContext, packageName: String): Boolean = false

actual fun uptimeMillis(): Long = com.mediaviewer.platform.nanoTime() / 1_000_000L

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

/** A short buzz, on phones whose browser allows it. */
actual fun vibrateOneShot(context: PlatformContext, ms: Long) { runCatching { jsVibrate(ms.toInt()) } }

private fun jsVibrate(ms: Int): Unit = js("{ if (navigator.vibrate) navigator.vibrate(ms); }")

/** Rebuilds the app state in place from what's saved (like iOS). */
actual fun restartApp(context: PlatformContext) = AppEvents.requestRestart()

actual fun appPackageName(context: PlatformContext): String = "rechoraccoon.stellar"

actual fun applyReducedAnimations(context: PlatformContext, reduced: Boolean) {}

/** No navigation bar in a browser. */
actual val androidx.compose.foundation.layout.WindowInsets.Companion.navBarSpace: androidx.compose.foundation.layout.WindowInsets
    @Composable get() = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp)
