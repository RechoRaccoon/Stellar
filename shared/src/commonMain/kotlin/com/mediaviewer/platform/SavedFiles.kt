package com.mediaviewer.platform

/** Writes [bytes] to a file the person just chose to save (a
 *  CreateDocument result). Throws if it can't be written. */
expect fun writeBytesToPickedFile(context: PlatformContext, uri: PlatformUri, bytes: ByteArray)
