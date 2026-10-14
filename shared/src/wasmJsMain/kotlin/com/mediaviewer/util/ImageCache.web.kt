package com.mediaviewer.util

import coil3.SingletonImageLoader

/** The browser keeps downloaded pictures in its own cache; Stellar's is
 *  the in-memory one. */
actual fun removeFromImageCache(context: com.mediaviewer.platform.PlatformContext, urls: List<String>): Int {
    val loader = SingletonImageLoader.get(coil3.PlatformContext.INSTANCE)
    var removed = 0
    val memory = loader.memoryCache
    if (memory != null) {
        for (key in memory.keys.toList()) {
            if (urls.any { it == key.key }) { memory.remove(key); removed++ }
        }
    }
    return removed
}
