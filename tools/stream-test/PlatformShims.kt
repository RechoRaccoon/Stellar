package com.mediaviewer.platform

open class IOException(message: String? = null) : Exception(message)

fun currentTimeMillis(): Long = System.currentTimeMillis()
