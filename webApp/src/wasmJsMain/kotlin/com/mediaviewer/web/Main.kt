package com.mediaviewer.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.window.ComposeViewport
import com.mediaviewer.platform.WebAppPlatform
import com.mediaviewer.platform.WebContext
import com.mediaviewer.platform.WebFiles
import com.mediaviewer.i18n.Translations
import com.mediaviewer.platform.defaultLanguageCode
import com.mediaviewer.ui.SharedAppHost
import com.mediaviewer.ui.compat.WebBack
import com.mediaviewer.util.WebFonts
import com.mediaviewer.util.WebImageLoading
import kotlinx.browser.document
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Stellar in a web browser: the same shared app as Android, iOS and the
 * desktop app, filling the browser window (drag the window to any shape;
 * a narrow one looks like the phone app). What's saved (sign-in,
 * settings, drafts) is read from the browser's storage before the first
 * frame.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    WebCrashLog.install()
    WebImageLoading.install()
    WebBackButton.install()
    installMiddleClickScroll()
    MainScope().launch {
        WebFiles.load()
        val crash = WebCrashLog.read()
        ComposeViewport(document.body!!) {
            WebFontsLoader()
            // Text laid out before a font arrived (a Japanese feed name, say)
            // keeps its blank glyphs until something lays it out again, so
            // each time a font finishes loading everything is laid out anew:
            // the font scale is nudged by a millionth (invisible), which
            // every piece of text depends on.
            val d = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(d.density, d.fontScale * (1f + (fontsLoaded % 2) * 1e-6f))
            ) {
                SharedAppHost(
                    context = WebContext,
                    createPlatform = { bsky, e621 -> WebAppPlatform(bsky, e621) },
                    crashLog = crash,
                    onCrashLogDismissed = { WebCrashLog.clear() }
                )
            }
        }
        hideSplash()
    }
}

/**
 * Brings in the fonts a browser doesn't have (see WebFonts): Compose then
 * uses them for any character its own font can't draw — emoji, Japanese,
 * Korean, Chinese.
 */
/** How many fonts have finished loading (see the font-scale nudge in main). */
private var fontsLoaded by mutableStateOf(0)

@Composable
private fun WebFontsLoader() {
    val resolver = LocalFontFamilyResolver.current
    LaunchedEffect(Unit) {
        suspend fun use(font: WebFonts.WebFont) {
            val bytes = WebFonts.fetch(font) ?: return
            runCatching { resolver.preload(FontFamily(Font(identity = font.name, data = bytes))) }
            fontsLoaded++
        }
        use(WebFonts.LATIN)
        launch {
            // The app in Japanese, Korean or Chinese: that font straight away.
            snapshotFlow { Translations.language.code }.collect { code -> if (WebFonts.isCjk(code)) use(WebFonts.cjkFor(code)) }
        }
        use(WebFonts.EMOJI)
        // (Small: the symbols and decorations in people's names, like 𖤐.)
        WebFonts.SYMBOLS.forEach { use(it) }
        // (Everything else a little later, so the first screen isn't kept waiting.)
        delay(4_000)
        use(WebFonts.LATIN_BOLD)
        use(WebFonts.cjkFor(defaultLanguageCode()))
    }
}

/**
 * "Back" on the web, wherever it comes from: Esc, the mouse's back button,
 * or the browser's own back button / a phone's back gesture. While
 * something on screen can go back (an open page, a sheet), the page keeps
 * one extra step in the browser's history, so the browser's back closes
 * that instead of leaving Stellar.
 */
private object WebBackButton {
    private var pushed = false
    private var ignoreNextPop = false

    fun install() {
        WebBack.onWantsBackChanged = { want -> sync(want) }
        listenBack(
            onEscape = { WebBack.dispatch() },
            onPop = {
                if (ignoreNextPop) {
                    ignoreNextPop = false
                } else {
                    pushed = false
                    WebBack.dispatch()
                    // (What's left on screen may still want "back" next time.)
                    com.mediaviewer.platform.setTimeoutMs(300) { sync(WebBack.wantsBack()) }
                }
            }
        )
    }

    private fun sync(want: Boolean) {
        if (want && !pushed) {
            pushed = true
            pushHistory()
        } else if (!want && pushed) {
            pushed = false
            ignoreNextPop = true
            historyBack()
        }
    }
}

private fun listenBack(onEscape: () -> Boolean, onPop: () -> Unit): Unit = js("""{
    window.addEventListener('keydown', (e) => {
        if (e.key === 'Escape' && !e.repeat) { if (onEscape()) { e.preventDefault(); } }
    }, true);
    window.addEventListener('mouseup', (e) => {
        if (e.button === 3) { e.preventDefault(); onEscape(); }
    }, true);
    window.addEventListener('popstate', () => onPop());
}""")

private fun pushHistory(): Unit = js("history.pushState({ stellar: true }, '')")
private fun historyBack(): Unit = js("history.back()")
private fun hideSplash(): Unit = js("{ const s = document.getElementById('splash'); if (s) { s.style.opacity = '0'; setTimeout(() => s.remove(), 400); } }")

/**
 * Like the other versions' crash screen: an error that stops Stellar is
 * kept (in the browser's storage) and shown as copyable text the next
 * time the page is opened.
 */
private object WebCrashLog {
    fun install() = installCrashHandler()
    fun read(): String? = runCatching { readCrash() }.getOrNull()?.takeIf { it.isNotBlank() }
    fun clear() { runCatching { clearCrash() } }
}

private fun installCrashHandler(): Unit = js("""{
    const save = (text) => { try { localStorage.setItem('stellar_last_crash', String(text).slice(0, 20000)); } catch (e) {} };
    // (The error's own name and message first: Firefox's stack has neither,
    // and "RuntimeError: unreachable" vs "out of memory" is the whole clue.)
    const describe = (x) => {
        if (!x) return '';
        const head = ((x.name || 'Error') + ': ' + (x.message || String(x)));
        return head + '\n' + (x.stack || '');
    };
    window.addEventListener('error', (e) => { if (e && e.error) save(describe(e.error)); else if (e && e.message) save(e.message + ' @ ' + (e.filename || '') + ':' + (e.lineno || '')); });
    window.addEventListener('unhandledrejection', (e) => {
        const r = e && e.reason;
        if (r && r instanceof WebAssembly.RuntimeError) save(describe(r));
    });
}""")
private fun readCrash(): String? = js("localStorage.getItem('stellar_last_crash')")
private fun clearCrash(): Unit = js("localStorage.removeItem('stellar_last_crash')")

/**
 * Autoscroll with the mouse's middle button, like Firefox: press it and
 * move away from where it was pressed — whatever is under that spot
 * scrolls, faster the farther the pointer is. Drag with it held and let go
 * to stop; or click it, move, and click anything to stop. A small marker
 * sits where it started. The scrolling is sent to the page as ordinary
 * wheel events, so every list, grid and page scrolls with it.
 */
private fun installMiddleClickScroll(): Unit = js("""{
    let active = false, sticky = false, moved = false;
    let ox = 0, oy = 0, px = 0, py = 0, last = 0, target = null, marker = null;
    const speed = (d) => {
        const a = Math.abs(d);
        if (a <= 12) return 0;
        const over = a - 12;
        return Math.sign(d) * over * 7 * (1 + over / 300);
    };
    const frame = (t) => {
        if (!active) return;
        const dt = last ? Math.min(0.1, (t - last) / 1000) : 1 / 60;
        last = t;
        const dx = speed(px - ox) * dt, dy = speed(py - oy) * dt;
        if ((dx !== 0 || dy !== 0) && target) {
            target.dispatchEvent(new WheelEvent('wheel', {
                deltaX: dx, deltaY: dy, deltaMode: 0, clientX: ox, clientY: oy, screenX: ox, screenY: oy,
                bubbles: true, cancelable: true, composed: true
            }));
        }
        requestAnimationFrame(frame);
    };
    const stop = () => {
        if (!active) return;
        active = false; sticky = false; target = null;
        if (marker) { marker.remove(); marker = null; }
        document.documentElement.style.cursor = '';
    };
    const start = (e) => {
        active = true; sticky = false; moved = false;
        ox = px = e.clientX; oy = py = e.clientY; last = 0;
        target = document.elementFromPoint(ox, oy) || document.body;
        marker = document.createElement('div');
        marker.style.cssText = 'position:fixed;width:28px;height:28px;margin:-14px 0 0 -14px;border-radius:50%;' +
            'background:rgba(0,0,0,0.6);border:2px solid #fff;box-sizing:border-box;pointer-events:none;z-index:2147483647;' +
            'color:#fff;font:14px/24px sans-serif;text-align:center;';
        marker.textContent = '↕';
        marker.style.left = ox + 'px'; marker.style.top = oy + 'px';
        document.body.appendChild(marker);
        document.documentElement.style.cursor = 'all-scroll';
        requestAnimationFrame(frame);
    };
    window.addEventListener('mousedown', (e) => {
        if (active && sticky) { e.preventDefault(); e.stopPropagation(); stop(); return; }
        // (Ctrl + a wheel click is the app's own — resetting the side
        // list's size — so it never starts this scrolling or its marker.)
        if (e.button === 1 && (e.ctrlKey || e.metaKey)) { e.preventDefault(); return; }
        if (e.button === 1) { e.preventDefault(); e.stopPropagation(); start(e); }
    }, true);
    window.addEventListener('mousemove', (e) => {
        if (!active) return;
        px = e.clientX; py = e.clientY;
        if (Math.hypot(px - ox, py - oy) > 12) moved = true;
    }, true);
    window.addEventListener('mouseup', (e) => {
        if (!active || e.button !== 1) return;
        e.preventDefault(); e.stopPropagation();
        if (moved) stop(); else sticky = true;
    }, true);
    // (A middle click never pastes or opens anything meanwhile.)
    window.addEventListener('auxclick', (e) => { if (e.button === 1) e.preventDefault(); }, true);
    window.addEventListener('blur', () => stop());
}""")
