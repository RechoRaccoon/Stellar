package com.mediaviewer.platform

actual fun writeBytesToPickedFile(context: PlatformContext, uri: PlatformUri, bytes: ByteArray) {
    val out = context.contentResolver.openOutputStream(uri, "wt") ?: throw java.io.IOException("Couldn't write the file")
    out.use { it.write(bytes) }
}
