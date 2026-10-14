package com.mediaviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import com.mediaviewer.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.mediaviewer.platform.PlatformUri
import com.mediaviewer.ui.compat.BackHandler
import com.mediaviewer.ui.compat.LocalContext
import com.mediaviewer.ui.compat.openUrl
import com.mediaviewer.util.EmojiStore
import com.mediaviewer.util.rememberHapticTap

/** A muted, looping preview of a picked video (the composer); a click
 *  pauses / resumes it. */
@Composable
actual fun InlineVideoPlayer(uri: PlatformUri, modifier: Modifier) {
    val player = remember(uri) { WebVideoPlayer(uri.toString(), muted = true) }
    DisposableEffect(player) {
        player.play()
        onDispose { player.release() }
    }
    var paused by remember(player) { mutableStateOf(false) }
    Box(
        modifier.background(Color.Black).clickable {
            paused = !paused
            if (paused) player.pause() else player.play()
        },
        contentAlignment = Alignment.Center
    ) {
        WebVideoSurface(player, Modifier.fillMaxSize())
        if (paused) {
            Box(Modifier.size(52.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.PlayArrow, contentDescription = "Play", tint = Color.White, modifier = Modifier.size(30.dp))
            }
        }
    }
}

/** A web page inside the UI (live-stream embeds): on the web, a card that
 *  opens it in a new tab (a page can't be drawn inside Stellar's canvas). */
@Composable
actual fun EmbeddedWebView(url: String, modifier: Modifier) = WebPageCard(url, modifier)

/** Where the page goes, and a button that opens it in a new tab. */
@Composable
internal fun WebPageCard(url: String, modifier: Modifier, title: String? = null) {
    val context = LocalContext.current
    val tap = rememberHapticTap()
    val host = remember(url) { runCatching { com.mediaviewer.platform.uriHost(url) }.getOrNull().orEmpty().removePrefix("www.") }
    Box(modifier.background(Color(0xFF101014)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 360.dp).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(Icons.Default.OpenInBrowser, contentDescription = null, tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(40.dp))
            Text(
                title?.takeIf { it.isNotBlank() } ?: host.ifBlank { url },
                color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis
            )
            Text(
                url, color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp,
                textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            Box(
                Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.14f))
                    .border(1.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(50))
                    .clickable { tap(); openUrl(context, url) }
                    .padding(horizontal = 18.dp, vertical = 9.dp)
            ) {
                Text("Open in New Tab", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

/** The Textshot preview: the very picture that gets posted, a little smaller. */
actual fun renderTextshotPreview(text: String, store: EmojiStore): ImageBitmap? = runCatching {
    com.mediaviewer.util.SkiaTextshotRenderer.render(text, 720, emoji = store::imageForChar).toComposeImageBitmap()
}.getOrNull()

/** A browser has no camera cutout to put the Camera / VRM ring around, and
 *  neither mode runs on the web: nothing is drawn. */
@Composable
actual fun CameraNotchButton(
    liquidGlass: Boolean,
    tint: Color,
    modifier: Modifier,
    interactive: Boolean,
    showButtons: Boolean,
    onOpenCamera: () -> Unit,
    onOpenVrm: () -> Unit
) {}

@Composable
actual fun VrmModeScreen(
    liquidGlass: Boolean,
    tint: Color,
    onClose: () -> Unit,
    onCapture: (imageUri: PlatformUri?, videoUri: PlatformUri?) -> Unit
) {
    NotOnWebScreen("VRM Mode", "VRM mode needs the phone's camera tracking. It's on the Android app.", onClose)
}

@Composable
actual fun CameraModeScreen(
    liquidGlass: Boolean,
    tint: Color,
    onClose: () -> Unit,
    onCapture: (imageUri: PlatformUri?, videoUri: PlatformUri?) -> Unit
) {
    NotOnWebScreen("Camera", "The Camera page is on the Android and iOS apps.", onClose)
}

@Composable
private fun NotOnWebScreen(title: String, message: String, onClose: () -> Unit) {
    BackHandler { onClose() }
    val tap = rememberHapticTap()
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(32.dp)) {
            Text(title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            AndroidOnlyTag()
            Text(message, color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp, textAlign = TextAlign.Center)
            Box(
                Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.14f))
                    .clickable { tap(); onClose() }.padding(horizontal = 20.dp, vertical = 9.dp)
            ) { Text("Back", color = Color.White, fontSize = 14.sp) }
        }
    }
}

/** Review page for a capture: the picture or video, and "Create Post". */
@Composable
actual fun CapturePreviewScreen(
    uri: PlatformUri,
    isVideo: Boolean,
    liquidGlass: Boolean,
    tint: Color,
    onClose: () -> Unit,
    onCreatePost: (PlatformUri) -> Unit
) {
    BackHandler { onClose() }
    val tap = rememberHapticTap()
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (isVideo) InlineVideoPlayer(uri, Modifier.fillMaxSize())
        else AsyncImage(model = uri.toString(), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        Row(
            Modifier.fillMaxWidth().align(Alignment.TopStart).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.5f)).clickable { tap(); onClose() },
                contentAlignment = Alignment.Center
            ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White) }
        }
        Box(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp).clip(RoundedCornerShape(50))
                .background(tint.copy(alpha = 0.85f)).clickable { tap(); onCreatePost(uri) }
                .padding(horizontal = 24.dp, vertical = 12.dp)
        ) { Text("Create Post", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
    }
}

/** Frames per second, counted from Compose's own frame clock. */
@Composable
actual fun DebugOverlay(tint: Color, modifier: Modifier) {
    var fps by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        var windowStart = withFrameNanos { it }
        var frames = 0
        while (true) {
            val now = withFrameNanos { it }
            frames++
            val elapsed = now - windowStart
            if (elapsed >= 500_000_000L) {
                fps = (frames * 1_000_000_000.0 / elapsed).toInt()
                frames = 0
                windowStart = now
            }
        }
    }
    val hsv = FloatArray(3)
    com.mediaviewer.ui.compat.PlatformColor.colorToHSV(tint.toArgb(), hsv)
    hsv[2] = hsv[2].coerceAtLeast(0.85f)
    val color = Color(com.mediaviewer.ui.compat.PlatformColor.HSVToColor(hsv))
    val style = TextStyle(
        color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold,
        shadow = Shadow(Color.Black.copy(alpha = 0.8f), blurRadius = 4f)
    )
    Box(modifier.fillMaxWidth().height(rememberTopCutoutClearance()).padding(horizontal = 16.dp)) {
        Row(Modifier.align(Alignment.CenterEnd)) {
            Text("$fps FPS", style = style)
        }
    }
}
