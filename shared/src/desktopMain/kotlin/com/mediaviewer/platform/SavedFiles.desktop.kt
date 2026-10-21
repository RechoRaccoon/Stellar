package com.mediaviewer.platform

actual fun writeBytesToPickedFile(context: PlatformContext, uri: PlatformUri, bytes: ByteArray) {
    val file = uri.file ?: throw java.io.IOException("Couldn't write the file")
    file.writeBytes(bytes)
}
