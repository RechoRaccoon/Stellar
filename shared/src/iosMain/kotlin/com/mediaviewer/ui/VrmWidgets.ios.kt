package com.mediaviewer.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mediaviewer.stream.RtmpPublisher
import com.mediaviewer.stream.StreamQuality
import com.mediaviewer.ui.compat.navBarSpace
import com.mediaviewer.util.rememberHapticTap

// The small pieces VRM mode and the Camera page are both built from on
// iOS: the round buttons, the popups' panel and their rows. They follow
// Android's VRM mode in look and wording; the one difference is that the
// buttons are tinted rather than live-blurred, because what's behind them
// (SceneKit's view, the camera) isn't something Compose can blur.

/** A round button over the avatar or the camera. */
@Composable
internal fun VrmBubble(
    size: Dp, liquidGlass: Boolean, tint: Color, modifier: Modifier = Modifier,
    enabled: Boolean = true, border: Color? = null, onClick: () -> Unit, content: @Composable () -> Unit
) {
    Box(
        modifier.size(size).clip(CircleShape)
            .background(if (liquidGlass) lerp(tint, Color.Black, 0.45f).copy(alpha = 0.55f) else Color.Black.copy(alpha = 0.55f))
            .border(if (border != null) 2.dp else 1.dp, border ?: Color.White.copy(alpha = 0.25f), CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) { content() }
}

/** The rounded panel every VRM popup sits in, in your colour. */
@Composable
internal fun VrmPanel(tint: Color, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    Box(
        modifier.clip(shape).background(lerp(Color(0xFF121212), tint, 0.22f).copy(alpha = 0.97f))
            .border(1.dp, tint.copy(alpha = 0.55f), shape)
            // (Taps on the panel itself don't close the popup behind it.)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
    ) { content() }
}

/** The dimmed backdrop a popup sits on; tapping it closes the popup. */
@Composable
internal fun VrmScrim(onDismiss: () -> Unit, alpha: Float = 0.55f, content: @Composable () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = alpha))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onDismiss() }
            .imePadding(),
        contentAlignment = Alignment.Center
    ) { content() }
}

/** A popup's title row with its close button. */
@Composable
internal fun VrmPopupHeader(title: String, icon: ImageVector? = null, tint: Color, onClose: () -> Unit) {
    val tap = rememberHapticTap()
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Box(
            Modifier.size(30.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.1f)).clickable { tap(); onClose() },
            contentAlignment = Alignment.Center
        ) { Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White, modifier = Modifier.size(16.dp)) }
    }
}

@Composable
internal fun VrmNote(text: String, modifier: Modifier = Modifier) {
    Text(
        text, color = Color.White, fontSize = 12.sp, lineHeight = 15.sp, textAlign = TextAlign.Center,
        modifier = modifier.widthIn(max = 260.dp).clip(RoundedCornerShape(14.dp)).background(Color.Black.copy(alpha = 0.45f))
            .padding(horizontal = 12.dp, vertical = 5.dp)
    )
}

@Composable
internal fun VrmPill(label: String, tint: Color, filled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Box(
        Modifier.clip(shape)
            .background(if (filled) lerp(tint, Color.Black, 0.12f) else Color.White.copy(alpha = 0.12f))
            .border(1.dp, tint.copy(alpha = 0.6f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = if (filled) 22.dp else 12.dp, vertical = if (filled) 12.dp else 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = Color.White, fontSize = if (filled) 14.sp else 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
internal fun VrmDivider() {
    HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
}

@Composable
internal fun VrmSwitch(checked: Boolean, tint: Color, enabled: Boolean = true, onChange: ((Boolean) -> Unit)?) {
    val tap = rememberHapticTap()
    Box(Modifier.size(width = 36.dp, height = 22.dp), contentAlignment = Alignment.Center) {
        Switch(
            checked = checked, onCheckedChange = if (onChange != null) { v -> tap(); onChange(v) } else null,
            enabled = enabled,
            modifier = Modifier.scale(0.7f),
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White, checkedTrackColor = lerp(tint, Color.White, 0.25f),
                uncheckedThumbColor = Color.White.copy(alpha = 0.6f), uncheckedTrackColor = Color.White.copy(alpha = 0.1f),
                disabledCheckedThumbColor = Color.White.copy(alpha = 0.4f), disabledCheckedTrackColor = tint.copy(alpha = 0.3f),
                disabledUncheckedThumbColor = Color.White.copy(alpha = 0.25f), disabledUncheckedTrackColor = Color.White.copy(alpha = 0.05f)
            )
        )
    }
}

/** One on/off setting as a small tile. */
internal class VrmTile(val label: String, val checked: Boolean, val enabled: Boolean = true, val onToggle: (Boolean) -> Unit)

/** On/off settings as small tiles, two per row: the label and a mini
 *  switch. Tapping anywhere on a tile flips it. */
@Composable
internal fun VrmTileGrid(tint: Color, tiles: List<VrmTile>) {
    val tap = rememberHapticTap()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        tiles.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { tile ->
                    val shape = RoundedCornerShape(12.dp)
                    val bg by animateColorAsState(
                        if (tile.checked && tile.enabled) tint.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.06f), label = "vrmTile"
                    )
                    Row(
                        Modifier.weight(1f).height(44.dp).clip(shape).background(bg)
                            .border(1.dp, tint.copy(alpha = if (tile.checked && tile.enabled) 0.6f else 0.18f), shape)
                            .clickable(enabled = tile.enabled) { tap(); tile.onToggle(!tile.checked) }
                            .padding(start = 10.dp, end = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            tile.label, color = Color.White.copy(alpha = if (tile.enabled) 1f else 0.4f), fontSize = 12.sp,
                            fontWeight = FontWeight.Medium, maxLines = 2, lineHeight = 14.sp, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        VrmSwitch(tile.checked, tint, enabled = tile.enabled, onChange = null)
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** A label, what it's set to, and something to tap on the right. */
@Composable
internal fun VrmActionRow(label: String, value: String, tint: Color, supporterOnly: Boolean = false, onClick: () -> Unit) {
    val tap = rememberHapticTap()
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
        val pill = RoundedCornerShape(10.dp)
        val pink = Color(0xFFFF4FA1)
        Box(
            Modifier.clip(pill).background(if (supporterOnly) pink.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.1f))
                .then(if (supporterOnly) Modifier.border(1.dp, pink.copy(alpha = 0.7f), pill) else Modifier)
                .clickable { tap(); onClick() }.padding(horizontal = 10.dp, vertical = 7.dp)
        ) {
            Text(
                if (supporterOnly) "Supporters" else value, color = if (supporterOnly) pink else Color.White,
                fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1
            )
        }
    }
}

@Composable
internal fun VrmToggleRow(label: String, checked: Boolean, tint: Color, sub: String? = null, onToggle: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, color = Color.White, fontSize = 14.sp)
            if (sub != null) Text(sub, color = Color.White.copy(alpha = 0.55f), fontSize = 11.sp, lineHeight = 14.sp)
        }
        Spacer(Modifier.width(10.dp))
        VrmSwitch(checked, tint, onChange = onToggle)
    }
}

@Composable
internal fun <T> VrmChoiceRow(label: String, options: List<T>, selected: T, tint: Color, optionLabel: (T) -> String, onPick: (T) -> Unit) {
    val tap = rememberHapticTap()
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Row(Modifier.clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = 0.08f)).padding(2.dp)) {
            options.forEach { o ->
                val picked = o == selected
                Box(
                    Modifier.clip(RoundedCornerShape(8.dp)).background(if (picked) lerp(tint, Color.Black, 0.12f) else Color.Transparent)
                        .clickable { if (!picked) { tap(); onPick(o) } }.padding(horizontal = 12.dp, vertical = 5.dp)
                ) {
                    Text(optionLabel(o), color = Color.White.copy(alpha = if (picked) 1f else 0.6f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
internal fun VrmSlider(
    label: String, valueText: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int, tint: Color,
    startLabel: String? = null, endLabel: String? = null, onChange: (Float) -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Text(valueText, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        Slider(
            value = value, onValueChange = onChange, valueRange = range, steps = steps,
            colors = SliderDefaults.colors(
                thumbColor = Color.White, activeTrackColor = lerp(tint, Color.White, 0.3f), inactiveTrackColor = Color.White.copy(alpha = 0.15f),
                activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent
            ),
            modifier = Modifier.fillMaxWidth().height(32.dp)
        )
        if (startLabel != null || endLabel != null) {
            Row {
                Text(startLabel.orEmpty(), color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp, modifier = Modifier.weight(1f))
                Text(endLabel.orEmpty(), color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp)
            }
        }
    }
}

@Composable
internal fun VrmTextField(
    value: String, onValue: (String) -> Unit, placeholder: String, tint: Color,
    keyboardType: KeyboardType = KeyboardType.Text, imeAction: ImeAction = ImeAction.Done,
    secret: Boolean = false, trailing: (@Composable () -> Unit)? = null
) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(shape).background(Color.White.copy(alpha = 0.07f))
            .border(1.dp, tint.copy(alpha = 0.4f), shape).padding(start = 12.dp, end = if (trailing != null) 4.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(placeholder, color = Color.White.copy(alpha = 0.3f), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            BasicTextField(
                value = value, onValueChange = onValue, singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
                cursorBrush = SolidColor(Color.White),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false,
                    keyboardType = keyboardType, imeAction = imeAction
                ),
                visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                modifier = Modifier.fillMaxWidth()
            )
        }
        trailing?.invoke()
    }
}

/** "1:05" / "1:02:05". */
internal fun formatLiveClock(totalSeconds: Long): String {
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    val ss = s.toString().padStart(2, '0')
    return if (h > 0) "$h:" + m.toString().padStart(2, '0') + ":$ss" else "$m:$ss"
}

/** One decimal, without leaning on a formatter: 2.5 → "2.5". */
internal fun oneDecimal(value: Float): String {
    val tenths = kotlin.math.round(value * 10f).toInt()
    return "${tenths / 10}.${kotlin.math.abs(tenths % 10)}"
}

/**
 * The Go Live popup: where to stream to (server address and stream key,
 * both from your platform's stream settings), an optional link for
 * Bluesky's Live badge, and the picture quality.
 */
@Composable
internal fun VrmLiveDialog(
    tint: Color,
    what: String,
    url: String, onUrl: (String) -> Unit,
    key: String, onKey: (String) -> Unit,
    quality: StreamQuality, onQuality: (StreamQuality) -> Unit,
    link: String, onLink: (String) -> Unit,
    onGoLive: () -> Unit,
    onDismiss: () -> Unit
) {
    val tap = rememberHapticTap()
    var showKey by remember { mutableStateOf(false) }
    val urlError = remember(url, key) {
        if (url.isBlank()) null else runCatching { RtmpPublisher.parseEndpoint(url, key); null }.getOrElse { it.message }
    }
    val canGo = url.isNotBlank() && urlError == null
    val label = lerp(tint, Color.White, 0.5f)
    VrmScrim(onDismiss) {
        VrmPanel(tint, Modifier.padding(horizontal = 28.dp).widthIn(max = 380.dp).fillMaxWidth()) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(18.dp)) {
                VrmPopupHeader("Go Live", tint = tint, onClose = onDismiss)
                Spacer(Modifier.height(2.dp))
                Text(
                    "Stream $what to YouTube, Twitch, Kick or any RTMP server. Copy both from your platform's stream settings.",
                    color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, lineHeight = 16.sp
                )
                Spacer(Modifier.height(14.dp))
                Text("SERVER URL", color = label, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Spacer(Modifier.height(4.dp))
                VrmTextField(url, { onUrl(it.trim()) }, "rtmp://a.rtmp.youtube.com/live2", tint, KeyboardType.Uri, ImeAction.Next)
                Spacer(Modifier.height(10.dp))
                Text("STREAM KEY", color = label, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Spacer(Modifier.height(4.dp))
                VrmTextField(
                    key, { onKey(it.trim()) }, "xxxx-xxxx-xxxx-xxxx", tint, KeyboardType.Password, ImeAction.Next,
                    secret = !showKey,
                    trailing = {
                        Box(Modifier.size(36.dp).clip(CircleShape).clickable { tap(); showKey = !showKey }, contentAlignment = Alignment.Center) {
                            Icon(
                                if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (showKey) "Hide key" else "Show key",
                                tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                )
                if (urlError != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(urlError, color = Color(0xFFFF8A80), fontSize = 11.sp)
                }
                Spacer(Modifier.height(10.dp))
                Text("STREAM LINK", color = label, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Spacer(Modifier.height(4.dp))
                VrmTextField(link, onLink, "twitch.tv/you  ·  or just your channel name", tint, KeyboardType.Uri)
                Spacer(Modifier.height(3.dp))
                Text(
                    "Optional. Shows Bluesky's Live badge on your profile, linking here, while you're live — ending the stream ends it.",
                    color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp, lineHeight = 14.sp
                )
                Spacer(Modifier.height(12.dp))
                Text("QUALITY", color = label, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StreamQuality.entries.forEach { q ->
                        val picked = q == quality
                        Box(
                            Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                                .background(if (picked) lerp(tint, Color.Black, 0.12f) else Color.White.copy(alpha = 0.08f))
                                .border(1.dp, tint.copy(alpha = if (picked) 0.9f else 0.35f), RoundedCornerShape(10.dp))
                                .clickable { tap(); onQuality(q) }.padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) { Text(q.label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1) }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "${quality.fps} fps, ${oneDecimal(quality.bitrate / 1_000_000f)} Mbps. Lowers itself automatically if your connection struggles.",
                    color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp, lineHeight = 14.sp
                )
                Spacer(Modifier.height(16.dp))
                Box(
                    Modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(14.dp))
                        .background(if (canGo) lerp(tint, Color.Black, 0.12f) else Color.White.copy(alpha = 0.12f))
                        .clickable(enabled = canGo) { tap(); onGoLive() },
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(if (canGo) Color(0xFFFF3B30) else Color.White.copy(alpha = 0.3f)))
                        Spacer(Modifier.width(8.dp))
                        Text("Go Live", color = Color.White.copy(alpha = if (canGo) 1f else 0.4f), fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

/** A yes/no popup ("End stream?"). */
@Composable
internal fun VrmConfirmDialog(tint: Color, title: String, message: String, confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val tap = rememberHapticTap()
    VrmScrim(onDismiss) {
        VrmPanel(tint, Modifier.padding(horizontal = 40.dp).widthIn(max = 340.dp).fillMaxWidth()) {
            Column(Modifier.padding(18.dp)) {
                Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(message, color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(
                        Modifier.weight(1f).height(42.dp).clip(RoundedCornerShape(12.dp))
                            .border(1.dp, tint.copy(alpha = 0.6f), RoundedCornerShape(12.dp)).clickable { tap(); onDismiss() },
                        contentAlignment = Alignment.Center
                    ) { Text("Cancel", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
                    Box(
                        Modifier.weight(1f).height(42.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFFFF3B30)).clickable { tap(); onConfirm() },
                        contentAlignment = Alignment.Center
                    ) { Text(confirmLabel, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}

/**
 * The capture page's bottom controls, shared by VRM mode and the Camera
 * page, laid out like Android's: [mic] [photo/video mode] [capture]
 * [Activity or Live] [right button — Settings in VRM mode, Flip camera on
 * the Camera page], with a status pill (timer, "Saving…", errors) above.
 * The capture button shows what it will do; the second bubble shows the
 * mode it would switch to.
 */
@Composable
internal fun CaptureControlsBar(
    liquidGlass: Boolean,
    tint: Color,
    status: String?,
    statusDot: Boolean,
    micMuted: Boolean,
    micEnabled: Boolean,
    onToggleMic: () -> Unit,
    videoMode: Boolean,
    swapEnabled: Boolean,
    onToggleVideoMode: () -> Unit,
    connecting: Boolean,
    isLive: Boolean,
    captureBusy: Boolean,
    recording: Boolean,
    captureEnabled: Boolean,
    onCapture: () -> Unit,
    liveEnabled: Boolean,
    onLive: () -> Unit,
    rightIcon: ImageVector,
    rightDescription: String,
    onRight: () -> Unit,
    rightEnabled: Boolean = true,
    modifier: Modifier = Modifier,
    /** VRM mode: "Live" is the mode button's third stop (photo → video →
     *  live) and the capture button then sets up the stream. */
    liveMode: Boolean = false,
    /** VRM mode: the fourth bubble is Activity instead of Live. */
    onActivity: (() -> Unit)? = null,
    /** Activity shown as a supporter feature (pink) to everyone else. */
    activityLocked: Boolean = false
) {
    Column(
        modifier.windowInsetsPadding(WindowInsets.navBarSpace).padding(bottom = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(Modifier.heightIn(min = 30.dp), contentAlignment = Alignment.Center) {
            if (status != null) {
                Row(
                    Modifier.clip(RoundedCornerShape(14.dp)).background(Color.Black.copy(alpha = 0.45f)).padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (statusDot) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(Color(0xFFFF3B30)))
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        status, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center, maxLines = 4, modifier = Modifier.widthIn(max = 320.dp)
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        val gap = 14.dp
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Far left: mic mute. Locked mid-recording; live, it mutes the
            // stream at once.
            VrmBubble(48.dp, liquidGlass, tint, enabled = micEnabled, onClick = onToggleMic) {
                Icon(
                    if (micMuted) Icons.Default.MicOff else Icons.Default.Mic,
                    contentDescription = if (micMuted) "Unmute microphone" else "Mute microphone",
                    tint = (if (micMuted) Color(0xFFFF6B61) else Color.White).copy(alpha = if (micEnabled) 1f else 0.35f),
                    modifier = Modifier.size(21.dp)
                )
            }
            Spacer(Modifier.width(gap))
            // Switch photo → video (→ live, in VRM mode).
            VrmBubble(48.dp, liquidGlass, tint, enabled = swapEnabled, onClick = onToggleVideoMode) {
                if (onActivity != null && videoMode) {
                    Text("Live", color = Color.White.copy(alpha = if (swapEnabled) 1f else 0.35f), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                } else Icon(
                    if (videoMode || liveMode) Icons.Default.PhotoCamera else Icons.Default.Videocam,
                    contentDescription = if (videoMode || liveMode) "Switch to photo" else "Switch to video",
                    tint = Color.White.copy(alpha = if (swapEnabled) 1f else 0.35f), modifier = Modifier.size(22.dp)
                )
            }
            Spacer(Modifier.width(gap))
            // Centre: capture — or, while streaming, the Live button.
            VrmBubble(
                72.dp, liquidGlass, tint, enabled = !captureBusy && captureEnabled,
                border = if (recording || isLive) Color(0xFFFF3B30) else null, onClick = onCapture
            ) {
                val on = if (captureEnabled) 1f else 0.35f
                when {
                    connecting -> CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(26.dp))
                    isLive -> Text("Live", color = Color(0xFFFF3B30), fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    captureBusy -> CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(26.dp))
                    recording -> Icon(Icons.Default.Stop, contentDescription = "Stop recording", tint = Color(0xFFFF3B30), modifier = Modifier.size(34.dp))
                    liveMode -> Text("Live", color = Color.White.copy(alpha = if (liveEnabled) 1f else 0.35f), fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    videoMode -> Icon(Icons.Default.Videocam, contentDescription = "Record video", tint = Color.White.copy(alpha = on), modifier = Modifier.size(32.dp))
                    else -> Icon(Icons.Default.PhotoCamera, contentDescription = "Take photo", tint = Color.White.copy(alpha = on), modifier = Modifier.size(30.dp))
                }
            }
            Spacer(Modifier.width(gap))
            if (onActivity != null) VrmBubble(48.dp, liquidGlass, tint, onClick = onActivity) {
                // VRM mode: Activity (scenes, soundboard, effects).
                Icon(
                    Icons.Default.AutoAwesome, contentDescription = "Activity", tint = Color.White,
                    modifier = Modifier.size(21.dp).supporterShine(enabled = activityLocked)
                )
            } else VrmBubble(48.dp, liquidGlass, tint, enabled = liveEnabled, onClick = onLive) {
                // Camera page: Live (opens the stream setup popup).
                Text("Live", color = Color.White.copy(alpha = if (liveEnabled) 1f else 0.35f), fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(gap))
            VrmBubble(48.dp, liquidGlass, tint, enabled = rightEnabled, onClick = onRight) {
                Icon(rightIcon, contentDescription = rightDescription, tint = Color.White.copy(alpha = if (rightEnabled) 1f else 0.35f), modifier = Modifier.size(20.dp))
            }
        }
    }
}
