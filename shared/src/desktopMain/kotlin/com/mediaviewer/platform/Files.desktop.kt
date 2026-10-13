package com.mediaviewer.platform

import java.io.File

// The same small file helpers the iOS code uses (there on posix), so code
// shared in spirit between the two reads the same.

/** Reads a whole local file, or null if it can't be read. */
fun readLocalFile(path: String): ByteArray? = runCatching { File(path).takeIf { it.isFile }?.readBytes() }.getOrNull()

/** Writes [bytes] to a local file. Returns false on failure. */
fun writeLocalFile(path: String, bytes: ByteArray): Boolean = runCatching {
    File(path).also { it.parentFile?.mkdirs() }.writeBytes(bytes)
}.isSuccess

/** True if [path] exists. */
fun localFileExists(path: String): Boolean = File(path).exists()

/** Deletes [path] if it exists. */
fun deleteLocalFile(path: String) { runCatching { File(path).delete() } }
