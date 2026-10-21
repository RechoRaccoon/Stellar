package com.mediaviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import com.mediaviewer.ui.theme.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mediaviewer.i18n.Text
import com.mediaviewer.ui.compat.LocalContext
import com.mediaviewer.util.AppUpdates
import com.mediaviewer.util.PlatformUpdater
import com.mediaviewer.util.rememberHapticTap

/**
 * "A new version of Stellar is out": the release's notes, and Update (the
 * download and the system installer — or the release page on iOS), Later,
 * or Skip This Version. See AppUpdates.
 */
@Composable
fun AppUpdatePopup(tint: Color, modifier: Modifier = Modifier) {
    val release = AppUpdates.available ?: return
    val context = LocalContext.current
    val tap = rememberHapticTap()
    val progress = AppUpdates.progress
    val busy = progress != null
    val panel = RoundedCornerShape(24.dp)
    Box(
        modifier.fillMaxSize()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { if (!busy) AppUpdates.later() },
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier.padding(20.dp).widthIn(max = 440.dp).fillMaxWidth()
                .clip(panel).background(lerp(Color(0xFF111114), tint, 0.22f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Update Available", color = lerp(tint, Color.White, 0.5f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text(release.title, color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Text("You have v${com.mediaviewer.AppVersion.NAME}", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
            if (release.notes.isNotBlank()) {
                // GitHub's Markdown, shown plainly.
                val notes = remember(release.notes) {
                    release.notes.lines().joinToString("\n") { line ->
                        line.trim().removePrefix("#").removePrefix("#").removePrefix("#").trim()
                            .let { if (it.startsWith("- ") || it.startsWith("* ")) "•  " + it.drop(2) else it }
                    }.trim()
                }
                Box(
                    Modifier.fillMaxWidth().heightIn(max = 220.dp).clip(RoundedCornerShape(14.dp))
                        .background(Color.White.copy(alpha = 0.06f)).verticalScroll(rememberScrollState()).padding(12.dp)
                ) { Text(notes, color = Color.White.copy(alpha = 0.9f), fontSize = 13.sp, lineHeight = 18.sp) }
            }
            if (progress != null) {
                Text("Downloading…", color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp)
                if (progress < 0f) LinearProgressIndicator(Modifier.fillMaxWidth(), color = lerp(tint, Color.White, 0.3f), trackColor = Color.White.copy(alpha = 0.12f))
                else LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth(), color = lerp(tint, Color.White, 0.3f), trackColor = Color.White.copy(alpha = 0.12f))
            }
            AppUpdates.error?.let { Text(it, color = Color(0xFFFF8A80), fontSize = 12.sp) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                @Composable
                fun Button(label: String, filled: Boolean, modifier: Modifier, onClick: () -> Unit) {
                    val shape = RoundedCornerShape(22.dp)
                    Box(
                        modifier.height(44.dp).clip(shape)
                            .then(if (filled) Modifier.background(lerp(tint, Color.Black, 0.12f)) else Modifier.border(1.dp, tint.copy(0.6f), shape))
                            .clickable(enabled = !busy) { tap(); onClick() },
                        contentAlignment = Alignment.Center
                    ) { Text(label, color = Color.White, fontSize = 13.sp, fontWeight = if (filled) FontWeight.Bold else FontWeight.SemiBold) }
                }
                Button("Skip", false, Modifier.weight(1f)) { AppUpdates.skip(release) }
                Button("Later", false, Modifier.weight(1f)) { AppUpdates.later() }
                Button(if (PlatformUpdater.canInstall) "Update" else "Open Release", true, Modifier.weight(1.3f)) { AppUpdates.install(context, release) }
            }
        }
    }
}
