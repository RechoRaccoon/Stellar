package com.mediaviewer.platform

actual fun writeBytesToPickedFile(context: PlatformContext, uri: PlatformUri, bytes: ByteArray) {
    if (!writeLocalFile(uri.toString().removePrefix("file://"), bytes)) throw IllegalStateException("Couldn't write the file")
}
