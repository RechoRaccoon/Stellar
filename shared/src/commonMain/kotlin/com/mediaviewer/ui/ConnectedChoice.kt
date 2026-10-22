package com.mediaviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mediaviewer.i18n.Text
import com.mediaviewer.ui.theme.DimGray
import com.mediaviewer.ui.theme.RoundedCornerShape
import com.mediaviewer.util.rememberHapticTap

/** One option of a [ConnectedChoice]: a label, an icon, or both. */
class ChoiceOption(val label: String? = null, val icon: ImageVector? = null, val description: String? = label)

/**
 * A few buttons joined into one pill, the picked one lit — the Settings
 * page's look (its Settings / Support / About switch). [fill] stretches
 * them across the width given; otherwise each is its own size.
 */
@Composable
fun ConnectedChoice(
    options: List<ChoiceOption>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 32.dp,
    fill: Boolean = false
) {
    val tap = rememberHapticTap()
    val outer = RoundedCornerShape(height / 2)
    val inner = RoundedCornerShape((height - 6.dp) / 2)
    Row(
        modifier.height(height).clip(outer).background(Color.White.copy(alpha = 0.08f)).padding(3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        options.forEachIndexed { i, o ->
            val on = i == selected
            Row(
                Modifier.then(if (fill) Modifier.weight(1f) else Modifier).fillMaxHeight().clip(inner)
                    .background(if (on) Color.White.copy(alpha = 0.18f) else Color.Transparent)
                    .clickable { if (!on) { tap(); onSelect(i) } }
                    .padding(horizontal = if (o.label != null) 10.dp else 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                val color = if (on) Color.White else DimGray
                o.icon?.let { Icon(it, contentDescription = o.description, tint = color, modifier = Modifier.size(16.dp)) }
                if (o.icon != null && o.label != null) Spacer(Modifier.width(5.dp))
                o.label?.let { Text(it, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false) }
            }
        }
    }
}
