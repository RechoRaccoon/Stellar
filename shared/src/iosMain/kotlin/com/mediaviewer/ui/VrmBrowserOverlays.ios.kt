package com.mediaviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.UIKitView
import com.mediaviewer.platform.SharedPreferences
import com.mediaviewer.platform.randomUuidString
import com.mediaviewer.util.rememberHapticTap
import com.mediaviewer.vrm.CaptureLayer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readValue
import kotlinx.cinterop.useContents
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import platform.CoreGraphics.CGAffineTransformIdentity
import platform.CoreGraphics.CGAffineTransformMakeScale
import platform.CoreGraphics.CGPointMake
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.UIKit.UIColor
import platform.UIKit.UIImage
import platform.UIKit.UIView
import platform.WebKit.WKAudiovisualMediaTypeNone
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import kotlin.math.roundToInt

/**
 * One floating browser window in VRM mode (chat, alerts, any stream
 * widget). Position/size are fractions of the screen so they survive
 * rotation. [inCapture] = also drawn into photos, recordings and streams;
 * off by default, so normally only you see them. The same record Android
 * keeps, under the same name, so a backup carries the windows across.
 */
internal data class BrowserOverlaySpec(
    val id: String,
    val url: String,
    val x: Float = 0.08f,
    val y: Float = 0.18f,
    val w: Float = 0.6f,
    val h: Float = 0.3f,
    val inCapture: Boolean = false
)

internal object BrowserOverlayStore {
    private const val KEY = "browser_overlays"
    private const val KEY_ENABLED = "browser_overlays_enabled"

    fun enabled(prefs: SharedPreferences) = prefs.getBoolean(KEY_ENABLED, false)
    fun setEnabled(prefs: SharedPreferences, value: Boolean) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    fun load(prefs: SharedPreferences): List<BrowserOverlaySpec> = runCatching {
        Json.parseToJsonElement(prefs.getString(KEY, null) ?: "[]").jsonArray.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            fun number(name: String, fallback: Float) = o[name]?.jsonPrimitive?.floatOrNull ?: fallback
            BrowserOverlaySpec(
                id = o["id"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: randomUuidString(),
                url = o["url"]?.jsonPrimitive?.content.orEmpty(),
                x = number("x", 0.08f), y = number("y", 0.18f), w = number("w", 0.6f), h = number("h", 0.3f),
                inCapture = o["inCapture"]?.jsonPrimitive?.booleanOrNull ?: false
            )
        }
    }.getOrDefault(emptyList())

    fun save(prefs: SharedPreferences, list: List<BrowserOverlaySpec>) {
        val array = buildJsonArray {
            for (s in list) add(buildJsonObject {
                put("id", s.id); put("url", s.url)
                put("x", s.x); put("y", s.y); put("w", s.w); put("h", s.h)
                put("inCapture", s.inCapture)
            })
        }
        // Written straight to disk: a later crash or the app being swiped
        // away can't lose where the windows were.
        prefs.edit().putString(KEY, array.toString()).commit()
    }

    /** "twitch.tv/popout/x/chat" → "https://twitch.tv/popout/x/chat". */
    fun normalizeUrl(input: String): String {
        val t = input.trim()
        if (t.isBlank()) return t
        return if (t.startsWith("http://", true) || t.startsWith("https://", true) || t.startsWith("about:")) t else "https://$t"
    }
}

/**
 * The live web pages and where each sits on screen, for getting them into
 * captures: a fresh picture of every page that's set to show is taken a
 * few times a second (iOS hands those over a moment after they're asked
 * for), and [layers] gives the latest ones. Main thread only.
 */
internal class BrowserOverlayRegistry {
    val webViews = HashMap<String, WKWebView>()
    /** Each page's place as fractions of the screen: left, top, right, bottom. */
    val bounds = HashMap<String, FloatArray>()
    private val pictures = HashMap<String, UIImage>()
    private val waiting = HashSet<String>()

    /** Asks each page in [ids] for a new picture. */
    fun refresh(ids: Collection<String>) {
        pictures.keys.retainAll(ids.toSet())
        for (id in ids) {
            val web = webViews[id] ?: continue
            if (!waiting.add(id)) continue
            runCatching {
                web.takeSnapshotWithConfiguration(null) { image, _ ->
                    waiting.remove(id)
                    if (image != null && webViews[id] === web) pictures[id] = image
                }
            }.onFailure { waiting.remove(id) }
        }
    }

    fun layers(ids: Collection<String>): List<CaptureLayer> = ids.mapNotNull { id ->
        val picture = pictures[id] ?: return@mapNotNull null
        val r = bounds[id] ?: return@mapNotNull null
        CaptureLayer(picture, r[0], r[1], r[2], r[3])
    }

    fun release() {
        pictures.clear(); waiting.clear()
    }
}

/** The width, in points, every overlay page is laid out at (then scaled
 *  to its window) — so the WHOLE page is always visible: a smaller window
 *  shrinks the page instead of cutting it off. */
private const val PAGE_WIDTH = 360.0
private val BAR_HEIGHT = 30.dp
private val GRIP_SIZE = 28.dp

/** Holds one page and keeps it scaled to fit: the page itself is always
 *  [PAGE_WIDTH] wide, and as tall as the window's shape makes it. */
@OptIn(ExperimentalForeignApi::class)
private class OverlayPageContainer(val web: WKWebView) : UIView(frame = CGRectMake(0.0, 0.0, 10.0, 10.0)) {
    init {
        setClipsToBounds(true)
        setBackgroundColor(UIColor.clearColor)
        addSubview(web)
    }

    override fun layoutSubviews() {
        super.layoutSubviews()
        val (w, h) = bounds.useContents { size.width to size.height }
        if (w < 1.0 || h < 1.0) return
        val s = w / PAGE_WIDTH
        web.setTransform(CGAffineTransformIdentity.readValue())
        web.setBounds(CGRectMake(0.0, 0.0, PAGE_WIDTH, h / s))
        web.setCenter(CGPointMake(w / 2.0, h / 2.0))
        web.setTransform(CGAffineTransformMakeScale(s, s))
    }
}

/**
 * All of VRM mode's (and the Camera page's) browser windows, floating over
 * the page. Each one: a slim tinted title bar (drag it to move; tap the
 * address to change it; reload; close) above a live web page, with a
 * resize grip in the bottom-right corner. Pages with a see-through
 * background (chat and alert widgets made for streaming) show the avatar
 * through them.
 */
@Composable
internal fun VrmBrowserOverlays(
    overlays: List<BrowserOverlaySpec>,
    tint: Color,
    registry: BrowserOverlayRegistry,
    onChange: (BrowserOverlaySpec) -> Unit,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val rootW = with(density) { maxWidth.toPx() }
        val rootH = with(density) { maxHeight.toPx() }
        for (spec in overlays) {
            key(spec.id) { BrowserOverlayWindow(spec, tint, rootW, rootH, registry, onChange, onRemove) }
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
@Composable
private fun BrowserOverlayWindow(
    spec: BrowserOverlaySpec,
    tint: Color,
    rootW: Float,
    rootH: Float,
    registry: BrowserOverlayRegistry,
    onChange: (BrowserOverlaySpec) -> Unit,
    onRemove: (String) -> Unit
) {
    val tap = rememberHapticTap()
    val density = LocalDensity.current
    // While a finger is moving or resizing it, the window follows `live`;
    // the change is saved when the finger lifts.
    var live by remember(spec) { mutableStateOf(spec) }
    val latestOnChange by rememberUpdatedState(onChange)
    val latestW by rememberUpdatedState(rootW)
    val latestH by rememberUpdatedState(rootH)
    var editing by remember { mutableStateOf(false) }
    var urlField by remember { mutableStateOf(spec.url) }
    val barColor = lerp(Color(0xFF14141A), tint, 0.35f).copy(alpha = 0.92f)
    val shape = RoundedCornerShape(12.dp)
    val minW = with(density) { 140.dp.toPx() } / rootW.coerceAtLeast(1f)
    val minH = with(density) { 90.dp.toPx() } / rootH.coerceAtLeast(1f)

    val web = remember(spec.id) {
        WKWebView(
            frame = CGRectMake(0.0, 0.0, PAGE_WIDTH, 300.0),
            configuration = WKWebViewConfiguration().apply {
                allowsInlineMediaPlayback = true
                // (Alert sounds and videos play without a tap first.)
                mediaTypesRequiringUserActionForPlayback = WKAudiovisualMediaTypeNone
            }
        ).apply {
            // Transparent pages show the avatar through them.
            setOpaque(false)
            setBackgroundColor(UIColor.clearColor)
            scrollView.setBackgroundColor(UIColor.clearColor)
        }
    }
    DisposableEffect(spec.id) {
        registry.webViews[spec.id] = web
        onDispose {
            registry.webViews.remove(spec.id)
            registry.bounds.remove(spec.id)
            runCatching { web.stopLoading(); web.removeFromSuperview() }
        }
    }
    // The address: loaded at first, and again whenever it's changed.
    LaunchedEffect(spec.id, spec.url) {
        if (spec.url.isNotBlank()) NSURL.URLWithString(spec.url)?.let { web.loadRequest(NSURLRequest.requestWithURL(it)) }
    }

    Box(
        Modifier
            .offset { IntOffset((live.x * rootW).roundToInt(), (live.y * rootH).roundToInt()) }
            .size(with(density) { (live.w * rootW).toDp() }, with(density) { (live.h * rootH).toDp() })
            .clip(shape)
            .border(1.dp, tint.copy(alpha = 0.6f), shape)
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().height(BAR_HEIGHT).background(barColor)
                    .pointerInput(spec.id) {
                        detectDragGestures(
                            onDragEnd = { latestOnChange(live) },
                            onDragCancel = { latestOnChange(live) }
                        ) { change, amount ->
                            change.consume()
                            live = live.copy(
                                x = (live.x + amount.x / latestW.coerceAtLeast(1f)).coerceIn(-live.w + 0.1f, 0.9f),
                                y = (live.y + amount.y / latestH.coerceAtLeast(1f)).coerceIn(0f, 0.95f)
                            )
                        }
                    }
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.DragIndicator, contentDescription = "Move", tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.padding(horizontal = 4.dp).size(16.dp))
                if (editing) {
                    BasicTextField(
                        value = urlField, onValueChange = { urlField = it }, singleLine = true,
                        textStyle = TextStyle(color = Color.White, fontSize = 11.sp),
                        cursorBrush = SolidColor(Color.White),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
                        keyboardActions = KeyboardActions(onGo = {
                            editing = false
                            val u = BrowserOverlayStore.normalizeUrl(urlField)
                            if (u.isNotBlank()) latestOnChange(live.copy(url = u))
                        }),
                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(6.dp)).background(Color.White.copy(alpha = 0.1f)).padding(horizontal = 6.dp, vertical = 3.dp)
                    )
                } else {
                    Text(
                        spec.url.removePrefix("https://").removePrefix("http://").ifBlank { "Tap to enter a URL" },
                        color = Color.White.copy(alpha = 0.85f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(horizontal = 4.dp)
                            .pointerInput(spec.id) { detectTapGestures { urlField = spec.url; editing = true } }
                    )
                }
                Box(
                    Modifier.size(26.dp).clip(CircleShape).clickable { tap(); runCatching { web.reload() } },
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Default.Refresh, contentDescription = "Reload", tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(14.dp)) }
                Box(
                    Modifier.size(26.dp).clip(CircleShape).clickable { tap(); onRemove(spec.id) },
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Default.Close, contentDescription = "Remove overlay", tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(14.dp)) }
            }
            Box(
                Modifier.fillMaxWidth().weight(1f).onGloballyPositioned { c ->
                    val r = c.boundsInRoot()
                    val w = latestW.coerceAtLeast(1f); val h = latestH.coerceAtLeast(1f)
                    registry.bounds[spec.id] = floatArrayOf(r.left / w, r.top / h, r.right / w, r.bottom / h)
                }
            ) {
                UIKitView(
                    factory = { OverlayPageContainer(web) },
                    modifier = Modifier.fillMaxSize(),
                    update = { it.setNeedsLayout() }
                )
            }
        }
        // Resize grip.
        Box(
            Modifier.align(Alignment.BottomEnd).size(GRIP_SIZE).clip(RoundedCornerShape(topStart = 10.dp)).background(barColor)
                .pointerInput(spec.id) {
                    detectDragGestures(
                        onDragEnd = { latestOnChange(live) },
                        onDragCancel = { latestOnChange(live) }
                    ) { change, amount ->
                        change.consume()
                        live = live.copy(
                            w = (live.w + amount.x / latestW.coerceAtLeast(1f)).coerceIn(minW, 1.2f),
                            h = (live.h + amount.y / latestH.coerceAtLeast(1f)).coerceIn(minH, 1.2f)
                        )
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Fullscreen, contentDescription = "Resize", tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(15.dp))
        }
    }
}

/** Compact editor for the overlay list, shown inside VRM Settings. */
@Composable
internal fun VrmBrowserOverlaySettings(
    enabled: Boolean,
    onToggleEnabled: (Boolean) -> Unit,
    overlays: List<BrowserOverlaySpec>,
    tint: Color,
    onAdd: (String) -> Unit,
    onUpdate: (BrowserOverlaySpec) -> Unit,
    onRemove: (String) -> Unit
) {
    val tap = rememberHapticTap()
    VrmToggleRow(
        "Browser overlays", enabled, tint,
        sub = "Floating web pages (chat, alerts…) over your avatar. Only you see them unless one is set to show in captures.",
        onToggle = onToggleEnabled
    )
    if (!enabled) return
    for (o in overlays) {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                o.url.removePrefix("https://").removePrefix("http://").ifBlank { "(no URL)" },
                color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(6.dp))
            val on = o.inCapture
            Box(
                Modifier.clip(RoundedCornerShape(8.dp)).background(if (on) lerp(tint, Color.Black, 0.12f) else Color.White.copy(alpha = 0.1f))
                    .clickable { tap(); onUpdate(o.copy(inCapture = !on)) }.padding(horizontal = 8.dp, vertical = 4.dp)
            ) { Text(if (on) "In captures" else "Hidden in captures", color = Color.White, fontSize = 11.sp, maxLines = 1) }
            Spacer(Modifier.width(4.dp))
            Box(
                Modifier.size(26.dp).clip(CircleShape).clickable { tap(); onRemove(o.id) },
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Default.Close, contentDescription = "Remove", tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(15.dp)) }
        }
    }
    var newUrl by remember { mutableStateOf("") }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicTextField(
            value = newUrl, onValueChange = { newUrl = it }, singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = 13.sp),
            cursorBrush = SolidColor(Color.White),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onDone = { if (newUrl.isNotBlank()) { onAdd(newUrl); newUrl = "" } }),
            modifier = Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = 0.08f))
                .border(1.dp, tint.copy(alpha = 0.35f), RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 8.dp),
            decorationBox = { inner ->
                Box {
                    if (newUrl.isEmpty()) Text("Add a page: URL", color = Color.White.copy(alpha = 0.35f), fontSize = 13.sp, maxLines = 1)
                    inner()
                }
            }
        )
        Spacer(Modifier.width(6.dp))
        Box(
            Modifier.clip(RoundedCornerShape(10.dp)).background(if (newUrl.isNotBlank()) lerp(tint, Color.Black, 0.12f) else Color.White.copy(alpha = 0.1f))
                .clickable(enabled = newUrl.isNotBlank()) { tap(); onAdd(newUrl); newUrl = "" }
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) { Text("Add", color = Color.White, fontSize = 13.sp) }
    }
}
