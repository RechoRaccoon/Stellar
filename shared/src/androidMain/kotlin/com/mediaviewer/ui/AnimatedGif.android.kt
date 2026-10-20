package com.mediaviewer.ui

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.request.ImageRequest

@Composable
actual fun AnimatedGifImage(url: String, contentDescription: String?, modifier: Modifier, contentScale: ContentScale) {
    val context = LocalContext.current
    val request = remember(url) {
        ImageRequest.Builder(context)
            .data(url)
            .decoderFactory(if (Build.VERSION.SDK_INT >= 28) AnimatedImageDecoder.Factory() else GifDecoder.Factory())
            .build()
    }
    AsyncImage(model = request, contentDescription = contentDescription, modifier = modifier, contentScale = contentScale)
}

@Composable
actual fun AnimatedGifBytes(key: String, load: suspend () -> ByteArray?, contentDescription: String?, modifier: Modifier, contentScale: ContentScale) {
    val context = LocalContext.current
    val bytes by androidx.compose.runtime.produceState<ByteArray?>(null, key) { value = runCatching { load() }.getOrNull() }
    val data = bytes ?: return
    val request = remember(key, data) {
        ImageRequest.Builder(context)
            .data(data)
            .memoryCacheKey(key)
            .decoderFactory(if (Build.VERSION.SDK_INT >= 28) AnimatedImageDecoder.Factory() else GifDecoder.Factory())
            .build()
    }
    AsyncImage(model = request, contentDescription = contentDescription, modifier = modifier, contentScale = contentScale)
}
