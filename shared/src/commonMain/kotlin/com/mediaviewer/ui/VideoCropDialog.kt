package com.mediaviewer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mediaviewer.i18n.Text
import com.mediaviewer.platform.MediaBridge
import com.mediaviewer.platform.PlatformUri
import com.mediaviewer.ui.compat.LocalContext
import com.mediaviewer.util.rememberHapticTap
import kotlinx.coroutines.launch

/** The video icon: square, at most this many pixels a side. */
const val ICON_VIDEO_SIDE = 640

/** The video banner: 3:1, at most this wide. */
const val BANNER_VIDEO_WIDTH = 1500
const val BANNER_VIDEO_ASPECT = 3f

/**
 * Picking the part of a video to use as a profile icon or banner: the
 * video plays with a frame of the right shape over it ([aspect] = width /
 * height; [round] draws the icon's circle). Drag the frame to move it,
 * pinch or drag its corner to size it. "Use This" really crops the video
 * (see MediaBridge.cropVideo) and hands back the cropped file — that, not
 * the original, is what gets uploaded.
 */
@Composable
fun VideoCropDialog(
    uri: PlatformUri,
    aspect: Float,
    round: Boolean,
    maxWidth: Int,
    maxHeight: Int,
    tint: Color,
    liquidGlass: Boolean,
    onCropped: (PlatformUri) -> Unit,
    onDismiss: () -> Unit
) {
    val tap = rememberHapticTap()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // The video's upright shape (16:9 until it's known).
    val videoAspect by produceState(16f / 9f, uri) {
        val (w, h) = kotlinx.coroutines.withContext(com.mediaviewer.platform.ioDispatcher) {
            runCatching { MediaBridge.videoDimensions(context, uri) }.getOrDefault(0 to 0)
        }
        if (w > 0 && h > 0) value = w.toFloat() / h
    }
    // The frame, in fractions of the picture: its left/top and its width
    // (its height follows from the shape).
    val maxFrameW = minOf(1f, aspect / videoAspect)
    var frameW by remember(videoAspect) { mutableFloatStateOf(maxFrameW) }
    val frameH = frameW * videoAspect / aspect
    var frameX by remember(videoAspect) { mutableFloatStateOf((1f - maxFrameW) / 2f) }
    var frameY by remember(videoAspect) { mutableFloatStateOf((1f - maxFrameW * videoAspect / aspect) / 2f) }
    fun clamp() {
        frameW = frameW.coerceIn(maxFrameW * 0.2f, maxFrameW)
        val h = frameW * videoAspect / aspect
        frameX = frameX.coerceIn(0f, 1f - frameW)
        frameY = frameY.coerceIn(0f, 1f - h)
    }
    com.mediaviewer.ui.compat.BackHandler(onBack = { if (!busy) onDismiss() })
    val accent = lerp(tint, Color.White, 0.35f)
    val panelShape = RoundedCornerShape(24.dp)
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { if (!busy) onDismiss() },
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier.padding(16.dp).widthIn(max = 560.dp).fillMaxWidth()
                .clip(panelShape).background(lerp(Color(0xFF111114), tint, 0.22f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(if (round) "Crop Video Icon" else "Crop Video Banner", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            BoxWithConstraints(Modifier.fillMaxWidth().heightIn(max = 420.dp), contentAlignment = Alignment.Center) {
                // (this.: the box's own room — the function's maxWidth/maxHeight
                // are the output size.)
                val boxW = this.maxWidth
                val boxH = if (this.maxHeight == androidx.compose.ui.unit.Dp.Infinity) 420.dp else this.maxHeight
                val dispW = minOf(boxW, boxH * videoAspect)
                val dispH = dispW / videoAspect
                val density = LocalDensity.current
                val dispWPx = with(density) { dispW.toPx() }
                val dispHPx = with(density) { dispH.toPx() }
                Box(Modifier.size(dispW, dispH).clip(RoundedCornerShape(10.dp))) {
                    // Playing on its own, muted, round and round.
                    LoopingVideoPreview(uri, Modifier.fillMaxSize())
                    // Darkened outside the frame; the frame (and the icon's
                    // circle) on top. Drag to move, pinch to size.
                    Canvas(
                        Modifier.fillMaxSize().pointerInput(videoAspect) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                if (busy) return@detectTransformGestures
                                val oldW = frameW
                                val oldH = oldW * videoAspect / aspect
                                frameW *= zoom
                                clamp()
                                val newH = frameW * videoAspect / aspect
                                // (Sized around its own middle.)
                                frameX += (oldW - frameW) / 2f + pan.x / dispWPx
                                frameY += (oldH - newH) / 2f + pan.y / dispHPx
                                clamp()
                            }
                        }
                    ) {
                        val l = frameX * size.width
                        val t = frameY * size.height
                        val w = frameW * size.width
                        val h = frameH * size.height
                        val dim = Color.Black.copy(alpha = 0.55f)
                        drawRect(dim, Offset.Zero, Size(size.width, t))
                        drawRect(dim, Offset(0f, t + h), Size(size.width, size.height - t - h))
                        drawRect(dim, Offset(0f, t), Size(l, h))
                        drawRect(dim, Offset(l + w, t), Size(size.width - l - w, h))
                        drawRect(Color.White, Offset(l, t), Size(w, h), style = Stroke(2.dp.toPx()))
                        if (round) drawCircle(Color.White.copy(alpha = 0.7f), radius = w / 2f, center = Offset(l + w / 2f, t + h / 2f), style = Stroke(1.dp.toPx()))
                    }
                    // The corner handle: drag it to size the frame (mouse too).
                    val handle = 22.dp
                    val handlePx = with(density) { handle.toPx() }
                    Box(
                        Modifier
                            .offset {
                                IntOffset(
                                    ((frameX + frameW) * dispWPx - handlePx / 2f).toInt(),
                                    ((frameY + frameH) * dispHPx - handlePx / 2f).toInt()
                                )
                            }
                            .size(handle).clip(CircleShape).background(accent).border(2.dp, Color.White, CircleShape)
                            .pointerInput(videoAspect) {
                                detectDragGestures { change, drag ->
                                    change.consume()
                                    if (busy) return@detectDragGestures
                                    // Whichever way it's dragged further.
                                    val dw = maxOf(drag.x / dispWPx, drag.y / dispHPx * aspect / videoAspect)
                                    frameW += dw
                                    clamp()
                                }
                            }
                    )
                }
            }
            OneLineText("Drag to move it · pinch or drag the corner to resize", Color.White.copy(alpha = 0.6f), 12f)
            error?.let { Text(it, color = Color(0xFFFF8A80), fontSize = 12.sp) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(22.dp))
                        .border(1.dp, tint.copy(0.6f), RoundedCornerShape(22.dp))
                        .clickable(enabled = !busy) { tap(); onDismiss() },
                    contentAlignment = Alignment.Center
                ) { Text("Cancel", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
                Box(
                    Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(22.dp))
                        .background(lerp(tint, Color.Black, 0.12f))
                        .clickable(enabled = !busy) {
                            tap(); busy = true; error = null
                            scope.launch {
                                val result = runCatching {
                                    MediaBridge.cropVideo(context, uri, frameX, frameY, frameW, frameH, maxWidth, maxHeight)
                                }
                                busy = false
                                result.onSuccess(onCropped).onFailure { error = it.message ?: "Cropping the video failed" }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (busy) Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.size(8.dp))
                        Text("Cropping…", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                    else Text("Use This", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/** [uri] playing muted and looping, from the start, with no controls
 *  ([fill]: cropped to fill instead of letterboxed). */
@Composable
fun LoopingVideoPreview(uri: PlatformUri, modifier: Modifier, fill: Boolean = false, cornerPercent: Int = 0) {
    val context = LocalContext.current
    val url = uri.toString()
    val player = remember(url) { FeedVideos.acquire(context, url) }
    androidx.compose.runtime.DisposableEffect(player) {
        player.setVolumeMuted(true)
        player.seekTo(0L)
        player.play()
        onDispose {
            player.setVolumeMuted(false)
            FeedVideos.recycle(url, player)
        }
    }
    FeedVideoView(player, modifier, fill = fill, cornerPercent = cornerPercent)
}

/** One line of text that gets smaller (never below 8sp) until it fits. */
@Composable
private fun OneLineText(text: String, color: Color, startSp: Float) {
    var size by remember(text) { mutableFloatStateOf(startSp) }
    var ready by remember(text) { mutableStateOf(false) }
    Text(
        text, color = color, fontSize = size.sp, maxLines = 1, softWrap = false,
        overflow = androidx.compose.ui.text.style.TextOverflow.Clip,
        onTextLayout = { r -> if (r.didOverflowWidth && size > 8f) size = (size - 0.5f).coerceAtLeast(8f) else ready = true },
        modifier = Modifier.fillMaxWidth().drawWithContent { if (ready) drawContent() }
    )
}
