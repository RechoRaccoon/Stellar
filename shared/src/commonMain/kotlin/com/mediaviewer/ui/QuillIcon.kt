package com.mediaviewer.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** A quill (a feather pen): Quote Repost's icon. Tinted like any icon. */
val QuillIcon: ImageVector by lazy {
    ImageVector.Builder("Quill", 24.dp, 24.dp, 24f, 24f).apply {
        // The feather.
        path(fill = SolidColor(Color.Black)) {
            moveTo(20.6f, 2.6f)
            curveTo(14.6f, 3.0f, 9.6f, 6.8f, 7.6f, 13.4f)
            lineTo(7.2f, 14.9f)
            lineTo(9.0f, 16.6f)
            lineTo(10.6f, 16.1f)
            curveTo(16.8f, 14.0f, 20.4f, 9.2f, 20.6f, 2.6f)
            close()
        }
        // The shaft running down into the nib.
        path(
            stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(17.2f, 6.0f)
            lineTo(3.4f, 20.6f)
        }
        // Ink.
        path(
            stroke = SolidColor(Color.Black), strokeLineWidth = 1.6f,
            strokeLineCap = StrokeCap.Round
        ) {
            moveTo(3.0f, 21.4f)
            lineTo(8.0f, 21.4f)
        }
    }.build()
}
