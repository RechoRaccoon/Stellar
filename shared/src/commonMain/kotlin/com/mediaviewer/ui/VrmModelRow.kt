package com.mediaviewer.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.mediaviewer.ui.compat.navBarSpace
import com.mediaviewer.util.VrmModel
import com.mediaviewer.util.rememberHapticTap

/**
 * VRM Settings › Avatar, top row: one small rounded button per avatar —
 * its thumbnail picture, or its name when it has none — and a round "+"
 * at the end to add another. Tap: load that one. Hold: delete it (the
 * caller asks first). Double-tap: pick a new picture for its button.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VrmModelRow(
    models: List<VrmModel>,
    selectedId: String?,
    tint: Color,
    /** A model is being added/loaded: the "+" shows a spinner. */
    busy: Boolean,
    onSelect: (VrmModel) -> Unit,
    onAdd: () -> Unit,
    onRequestDelete: (VrmModel) -> Unit,
    onChangeThumb: (VrmModel) -> Unit,
    modifier: Modifier = Modifier
) {
    val tap = rememberHapticTap()
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        models.forEach { model ->
            val selected = model.id == selectedId
            Box(
                Modifier.size(56.dp).clip(shape)
                    .background(lerp(Color(0xFF1C1C22), tint, 0.25f))
                    .border(if (selected) 2.5.dp else 1.dp, if (selected) lerp(tint, Color.White, 0.45f) else Color.White.copy(alpha = 0.18f), shape)
                    .combinedClickable(
                        onClick = { if (!selected) { tap(); onSelect(model) } },
                        onLongClick = { tap(); onRequestDelete(model) },
                        onDoubleClick = { tap(); onChangeThumb(model) }
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (model.thumb.isNotBlank()) {
                    AsyncImage(
                        model = model.thumb, contentDescription = model.name, contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().clip(shape)
                    )
                } else {
                    Text(
                        model.name, color = Color.White, fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(4.dp)
                    )
                }
            }
        }
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(lerp(tint, Color.Black, 0.25f))
                .border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape)
                .combinedClickable(enabled = !busy, onClick = { tap(); onAdd() }),
            contentAlignment = Alignment.Center
        ) {
            if (busy) CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
            else Icon(Icons.Default.Add, contentDescription = "Add avatar", tint = Color.White, modifier = Modifier.size(22.dp))
        }
    }
}

/**
 * How tall a VRM popup's scrolling body may grow: from just under the
 * camera notch to just over the gesture bar, less [chrome] (the popup's
 * own header, tabs and padding).
 */
@Composable
fun rememberVrmPopupMaxBody(chrome: Dp = 132.dp): Dp {
    val screen = com.mediaviewer.ui.compat.rememberScreenSizeDp()
    val top = rememberTopCutoutClearance()
    val bottom = WindowInsets.navBarSpace.asPaddingValues().calculateBottomPadding()
    return (screen.height.dp - top - bottom - chrome - 16.dp).coerceAtLeast(260.dp)
}
