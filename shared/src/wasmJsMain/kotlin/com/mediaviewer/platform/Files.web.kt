package com.mediaviewer.platform

// The same small file helpers the iOS and desktop code use, on WebFiles.

/** Reads a whole stored file, or null if there isn't one. */
fun readLocalFile(path: String): ByteArray? = WebFiles.read(path)

/** Writes [bytes] to a stored file. Returns false on failure. */
fun writeLocalFile(path: String, bytes: ByteArray): Boolean = runCatching { WebFiles.write(path, bytes) }.isSuccess

/** True if [path] exists. */
fun localFileExists(path: String): Boolean = WebFiles.exists(path)

/** Deletes [path] if it exists. */
fun deleteLocalFile(path: String) { WebFiles.delete(path) }

/** Where things are kept in [WebFiles]. */
object WebPaths {
    /** Stellar's data (what a phone keeps in the app's files folder). */
    const val FILES = "/files"
    /** Picked files and temporary work: never saved between visits. */
    const val TEMP = "/tmp"
}
