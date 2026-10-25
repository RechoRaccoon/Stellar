package com.mediaviewer.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

actual suspend fun appResolvedIps(host: String): List<String>? = withContext(Dispatchers.IO) {
    runCatching { java.net.InetAddress.getAllByName(host).mapNotNull { it.hostAddress } }.getOrNull()
}

/** Written by the cardyb-recording interceptor in AndroidHttpClients. */
@Volatile
var lastCardybRequestLineValue: String? = null

actual fun lastCardybRequestLine(): String? = lastCardybRequestLineValue
