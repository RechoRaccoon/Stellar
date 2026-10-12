package com.mediaviewer.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale

/** An animated GIF from [url], looping (Android: Coil's GIF decoder; iOS:
 *  Skia's own decoder, frame by frame). */
@Composable
expect fun AnimatedGifImage(
    url: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop
)
