package com.mediaviewer.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.mediaviewer.util.AppLinks
import com.mediaviewer.util.InAppNotice
import com.mediaviewer.util.InAppNotices
import com.mediaviewer.util.rememberHapticTap
import kotlinx.coroutines.delay

/**
 * The in-app notification banner: slides down from the top over whatever
 * page is showing, stays a few seconds, and opens what it's about when
 * tapped (swipe it up to dismiss).
 */
@Composable
fun InAppNoticeBanner(tint: Color, modifier: Modifier = Modifier) {
    val tap = rememberHapticTap()
    val incoming = InAppNotices.current
    // The last one stays drawn while it slides away.
    var shown by remember { mutableStateOf<InAppNotice?>(null) }
    if (incoming != null) shown = incoming
    LaunchedEffect(incoming?.id) {
        val id = incoming?.id ?: return@LaunchedEffect
        tap()
        delay(4500)
        InAppNotices.dismiss(id)
    }
    Box(modifier.fillMaxSize().padding(top = rememberTopCutoutClearance() + 6.dp), contentAlignment = Alignment.TopCenter) {
        AnimatedVisibility(
            visible = incoming != null,
            enter = slideInVertically { -it * 2 } + fadeIn(),
            exit = slideOutVertically { -it * 2 } + fadeOut()
        ) {
            val notice = shown ?: return@AnimatedVisibility
            val shape = RoundedCornerShape(22.dp)
            Row(
                Modifier.padding(horizontal = 12.dp).widthIn(max = 460.dp).fillMaxWidth()
                    .clip(shape)
                    .background(lerp(Color(0xFF131318), tint, 0.28f))
                    .border(1.dp, lerp(tint, Color.White, 0.3f).copy(alpha = 0.75f), shape)
                    .pointerInput(notice.id) {
                        var dragged = 0f
                        detectVerticalDragGestures(
                            onDragStart = { dragged = 0f },
                            onVerticalDrag = { change, amount ->
                                change.consume()
                                dragged += amount
                                if (dragged < -24.dp.toPx()) InAppNotices.dismiss(notice.id)
                            }
                        )
                    }
                    .clickable {
                        tap()
                        InAppNotices.dismiss(notice.id)
                        AppLinks.open(notice.link)
                    }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(38.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                    if (notice.avatarUrl != null) AsyncImage(
                        model = notice.avatarUrl, contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    ) else Icon(Icons.Default.Notifications, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(notice.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        notice.text, color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp, lineHeight = 17.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
