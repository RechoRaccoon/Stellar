package com.mediaviewer.ui

import com.mediaviewer.ui.compat.jformat

import com.mediaviewer.tagging.TaggerState

import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import com.mediaviewer.i18n.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.mediaviewer.ui.theme.*
import com.mediaviewer.util.rememberHapticTap
import com.mediaviewer.viewmodel.MainViewModel
import kotlin.math.roundToInt

/** AI Tagging feature: the full-screen overlay shown while "Locally Tag All
 *  Liked Posts" runs — per the request, this doesn't go away until tagging
 *  is complete (its own BackHandler swallows the system back gesture rather
 *  than letting it dismiss mid-run; tapping the close bubble still lets the
 *  person cancel explicitly, same as e.g. the download-all-liked flow
 *  elsewhere in the app already allows).
 *
 *  Uses the same profile-color-reflected background gradient as every other
 *  full-screen page (Search, Hub, DM inbox) — see rememberDominantColor/
 *  postBackgroundBrush in GlassTheme.kt.
 *
 *  Redesign (item 3, this session): while a post is actively being tagged,
 *  instead of a generic centered loading card this now shows that post
 *  full-screen with a live "Tagging…"/"Tagged N Posts" header — see
 *  ActiveTaggingView below. The other states (downloading the model,
 *  finished, failed — none of which have a "current post" to show) keep the
 *  original centered glass card. */
@Composable
fun TaggingOverlay(
    state: MainViewModel.TaggingUiState,
    liquidGlass: Boolean,
    selfAvatarUrl: String? = null,
    onDismiss: () -> Unit,
    onSearchLiked: () -> Unit
) {
    // Only intercept back to *cancel* an in-flight run, asking for the tap
    // that already exists (the close bubble) rather than letting a stray
    // back-gesture silently kill a long-running pass; once the run is
    // finished there's nothing left to protect, so back behaves normally.
    if (state.isRunning) {
        com.mediaviewer.ui.compat.BackHandler(onBack = {})
    } else {
        com.mediaviewer.ui.compat.BackHandler(onBack = onDismiss)
    }

    val profileTint = rememberSelfTint(selfAvatarUrl, NeutralGlassTint)

    val backdropLayer = rememberGraphicsLayer()
    var backdropOrigin by remember { mutableStateOf(Offset.Zero) }
    val backdrop = remember(liquidGlass, backdropLayer) {
        if (liquidGlass) GlassBackdrop(backdropLayer) { backdropOrigin } else null
    }

    Box(Modifier.fillMaxSize().blockClicksBehind()) {
        Box(
            Modifier.fillMaxSize()
                .onGloballyPositioned { backdropOrigin = it.positionInRoot() }
                .then(
                    if (liquidGlass) Modifier.drawWithContent {
                        if (LiteGlass.LIVE) { backdropLayer.record { this@drawWithContent.drawContent() }; drawLayer(backdropLayer) } else drawContent()
                    } else Modifier.background(OledBlack)
                )
        ) {
            if (liquidGlass) SpaceSky(profileTint, Modifier.matchParentSize())
        }

        val modelState = state.modelState
        val currentItem = state.currentItem
        when {
            state.errorMessage != null -> {
                CenteredCard(liquidGlass, profileTint, backdrop) {
                    CardTitle("Model Download Failed", 19.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(state.errorMessage, color = DimGray, fontSize = 13.sp, textAlign = TextAlign.Center)
                }
            }
            state.isComplete -> {
                CenteredCard(liquidGlass, profileTint, backdrop) {
                    Box(Modifier.size(56.dp).clip(CircleShape).background(VoteGreen.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Check, contentDescription = null, tint = VoteGreen, modifier = Modifier.size(30.dp))
                    }
                    Spacer(Modifier.height(14.dp))
                    CardTitle("Tagging Complete", 19.sp)
                    Spacer(Modifier.height(6.dp))
                    Text("${state.tagged} of ${state.scanned} liked posts tagged · ${formatBytes(state.datasetBytes)}", color = DimGray, fontSize = 12.sp, textAlign = TextAlign.Center)
                    if (state.tagged > 0) {
                        Spacer(Modifier.height(20.dp))
                        DismissButton(liquidGlass, profileTint, "Search Liked Posts", onSearchLiked)
                    }
                }
            }
            modelState is TaggerState.Downloading -> {
                CenteredCard(liquidGlass, profileTint, backdrop) {
                    CircularProgressIndicator(Modifier.size(28.dp), color = Color.White, strokeWidth = 2.dp)
                    Spacer(Modifier.height(14.dp))
                    CardTitle("Downloading Tagging Model", 17.sp)
                    Spacer(Modifier.height(6.dp))
                    val total = modelState.totalBytes
                    val subtitle = if (total > 0) "${formatBytes(modelState.bytesDownloaded)} of ${formatBytes(total)}" else "Starting…"
                    Text(subtitle, color = DimGray, fontSize = 12.sp)
                    Spacer(Modifier.height(2.dp))
                    Text("One-time download — the model stays on your device from now on", color = DimGray, fontSize = 11.sp, textAlign = TextAlign.Center)
                }
            }
            state.isRunning && (state.lastTaggedItem != null || currentItem != null) -> {
                val shown = state.lastTaggedItem
                ActiveTaggingView(
                    item = shown ?: currentItem!!,
                    tags = if (shown != null) state.lastTags else null,
                    state = state, liquidGlass = liquidGlass, tint = profileTint, backdrop = backdrop,
                    onBack = onDismiss
                )
            }
            else -> {
                // Running, but the very first post hasn't come back from the
                // network/decoder yet — or not running yet at all (opening
                // transition). Nothing to show full-screen, so this is the
                // one moment that still falls back to a simple spinner.
                CenteredCard(liquidGlass, profileTint, backdrop) {
                    CircularProgressIndicator(Modifier.size(28.dp), color = Color.White, strokeWidth = 2.dp)
                    Spacer(Modifier.height(12.dp))
                    CardTitle("Tagging Liked Posts…", 17.sp)
                }
            }
        }

        // Close bubble — top-left, glass, matches ProfileOverlay/
        // SearchOverlay's own close bubble exactly (item 3: "instead of the
        // close button, there should be a glass X bubble at the top left of
        // the page similar to profile and search pages"). Drawn last/on top
        // so it's reachable in every state above, including the full-screen
        // post view.
        // (The tagging view has its own, in its header row.)
        val activeView = state.isRunning && modelState !is TaggerState.Downloading && state.errorMessage == null &&
            !state.isComplete && (state.lastTaggedItem != null || currentItem != null)
        if (!activeView) Box(
            Modifier.fillMaxSize().padding(top = rememberTopCutoutClearance()).padding(16.dp),
            contentAlignment = Alignment.TopStart
        ) {
            TaggingCloseBubble(liquidGlass = liquidGlass, tint = profileTint, backdrop = backdrop, onClick = onDismiss)
        }
    }
}

/** The original centered glass card layout — still used for every state
 *  that isn't "actively tagging a specific post" (nothing to show
 *  full-screen for a model download, a finished run, or a failure). */
@Composable
private fun CenteredCard(
    liquidGlass: Boolean,
    tint: Color,
    backdrop: GlassBackdrop?,
    content: @Composable ColumnScope.() -> Unit
) {
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
        val cardShape = RoundedCornerShape(24.dp)
        // A phone's width at most, so a wide window doesn't stretch it.
        val cardWidth = minOf(maxWidth * 0.82f, 420.dp)
        @Composable
        fun CardContent() {
            Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, content = content)
        }
        if (liquidGlass) {
            LiquidGlassSurface(Modifier.width(cardWidth), shape = cardShape, tint = tint, backdrop = backdrop) { CardContent() }
        } else {
            Box(Modifier.width(cardWidth).clip(cardShape).background(Color.White.copy(0.06f))) { CardContent() }
        }
    }
}

/** The "actively tagging" view: one glass outline, centred, holding a
 *  single header row (back button, "Tagging" + spinner, the running count
 *  and dataset size) and under it the post at its own aspect ratio — never
 *  cropped. It shows the post that most recently *finished* tagging, with
 *  every tag it got laid over the picture (shrunk to fit inside the
 *  picture's own bounds), rather than the one still being worked on; before
 *  the first post finishes it shows the one in progress, without tags. */
@Composable
private fun ActiveTaggingView(
    item: com.mediaviewer.model.MediaItem,
    tags: List<String>?,
    state: MainViewModel.TaggingUiState,
    liquidGlass: Boolean,
    tint: Color,
    backdrop: GlassBackdrop?,
    onBack: () -> Unit
) {
    // Pictures without reported dimensions take theirs from the loaded image.
    var loadedAspect by remember(item.id) { mutableStateOf<Float?>(null) }
    val aspect = (item.aspectRatio?.takeIf { it > 0f } ?: loadedAspect ?: 1f).coerceIn(0.2f, 5f)
    androidx.compose.foundation.layout.BoxWithConstraints(
        Modifier.fillMaxSize().padding(top = rememberTopCutoutClearance())
            .padding(horizontal = 18.dp, vertical = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        val headerHeight = 58.dp
        val maxFrameWidth = minOf(maxWidth, 520.dp)
        val maxImageHeight = (maxHeight - headerHeight).coerceAtLeast(80.dp)
        // Fit the picture's aspect ratio into the space left under the header.
        var imageWidth = maxFrameWidth
        var imageHeight = imageWidth / aspect
        if (imageHeight > maxImageHeight) { imageHeight = maxImageHeight; imageWidth = imageHeight * aspect }
        // The header row needs some width even for a very tall, thin post.
        val frameWidth = maxOf(imageWidth, minOf(maxFrameWidth, 300.dp))
        val outlineShape = RoundedCornerShape(28.dp)
        @Composable
        fun FrameContent() {
            Column(Modifier.width(frameWidth)) {
                Row(
                    Modifier.fillMaxWidth().height(headerHeight).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TaggingCloseBubble(liquidGlass = liquidGlass, tint = tint, backdrop = backdrop, onClick = onBack)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Tagging", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                        maxLines = 1, softWrap = false
                    )
                    Spacer(Modifier.width(7.dp))
                    CircularProgressIndicator(Modifier.size(11.dp), color = Color.White.copy(alpha = 0.85f), strokeWidth = 1.5.dp)
                    Spacer(Modifier.weight(1f).widthIn(min = 8.dp))
                    Box(
                        Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.08f))
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Text(
                            "${groupDigits(state.tagged)} Posts · ${formatBytes(state.datasetBytes)}",
                            color = Color.White.copy(alpha = 0.8f), fontSize = 11.sp, fontWeight = FontWeight.Medium,
                            maxLines = 1, softWrap = false, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
                }
                Box(Modifier.fillMaxWidth().height(imageHeight), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(imageWidth, imageHeight)) {
                        AsyncImage(
                            model = item.thumbUrl.ifBlank { item.mediaUrl },
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            onSuccess = { st ->
                                val d = st.result.image
                                if (d.width > 0 && d.height > 0) loadedAspect = d.width.toFloat() / d.height
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                        if (tags != null) {
                            val text = if (tags.isEmpty()) "No tags found" else tags.joinToString("  ·  ") { it.replace('_', ' ') }
                            Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.5f)))
                            FitText(
                                if (tags.isEmpty()) com.mediaviewer.i18n.tr(text) else text,
                                Modifier.matchParentSize().padding(14.dp)
                            )
                        }
                    }
                }
            }
        }
        if (liquidGlass) {
            LiquidGlassSurface(Modifier.width(frameWidth), shape = outlineShape, tint = tint, backdrop = backdrop) { FrameContent() }
        } else {
            Box(Modifier.width(frameWidth).clip(outlineShape).background(Color.White.copy(0.06f))) { FrameContent() }
        }
    }
}

/** A card's heading: always one line, made smaller when it wouldn't fit. */
@Composable
private fun CardTitle(text: String, size: androidx.compose.ui.unit.TextUnit) {
    FitText(
        com.mediaviewer.i18n.tr(text), Modifier.fillMaxWidth(),
        maxSize = size, minSize = 9.sp, fontWeight = FontWeight.Bold, singleLine = true, shadow = false
    )
}

/** 9252 → "9,252". */
private fun groupDigits(n: Int): String {
    val digits = kotlin.math.abs(n).toString()
    val grouped = digits.reversed().chunked(3).joinToString(",").reversed()
    return if (n < 0) "-$grouped" else grouped
}

/** Matches ProfileOverlay's CloseGlassBubble / SearchOverlay's
 *  SearchCloseBubble exactly (30dp glass circle, white X). */
@Composable
private fun TaggingCloseBubble(liquidGlass: Boolean, tint: Color, backdrop: GlassBackdrop?, onClick: () -> Unit) {
    val shape = CircleShape
    if (liquidGlass) {
        LiquidGlassSurface(
            modifier = Modifier.size(30.dp).clickable(onClick = onClick),
            shape = shape, tint = tint, backdrop = backdrop
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White, modifier = Modifier.size(16.dp))
            }
        }
    } else {
        Box(
            Modifier.size(30.dp).clip(shape).background(Color.Black.copy(alpha = 0.5f)).clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun DismissButton(liquidGlass: Boolean, tint: Color, label: String, onClick: () -> Unit) {
    val tap = rememberHapticTap()
    val shape = RoundedCornerShape(16.dp)
    Box(
        Modifier
            .then(if (liquidGlass) Modifier.glassPanel(true, shape = shape, tint = tint) else Modifier.clip(shape).background(tint.copy(alpha = 0.35f)))
            .clickable(onClick = { tap(); onClick() })
            .padding(horizontal = 22.dp, vertical = 10.dp)
    ) {
        Text(label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000L -> "%.2f GB".jformat(bytes / 1_000_000_000.0)
    bytes >= 1_000_000L -> "%.1f MB".jformat(bytes / 1_000_000.0)
    bytes >= 1_000L -> "${(bytes / 1000.0).roundToInt()} KB"
    else -> "$bytes B"
}
