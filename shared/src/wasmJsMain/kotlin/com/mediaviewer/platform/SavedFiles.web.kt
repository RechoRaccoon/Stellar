package com.mediaviewer.platform

// (A CreateDocument file on the web is in Stellar's Downloads folder:
// writing it hands it to the browser's own download.)
actual fun writeBytesToPickedFile(context: PlatformContext, uri: PlatformUri, bytes: ByteArray) {
    val path = uri.webPath ?: throw IllegalStateException("Couldn't write the file")
    WebFiles.write(path, bytes)
}
