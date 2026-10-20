package com.mediaviewer.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File

actual object PrivateFiles {
    private fun root(): File = File(DesktopPaths.filesDir, "private").apply { mkdirs() }

    actual fun rootUri(context: PlatformContext): String = root().toFileUri().trimEnd('/')

    private fun target(folder: String, name: String): File {
        val dir = File(root(), folder).apply { mkdirs() }
        return File(dir, name.replace('/', '_').replace('\\', '_').replace(' ', '_'))
    }

    /** Only files under our own folder are ever touched. */
    private fun ownFile(uri: String): File? {
        val file = fileOfUri(uri) ?: return null
        return if (file.absoluteFile.normalize().path.startsWith(root().absoluteFile.normalize().path)) file else null
    }

    actual fun write(context: PlatformContext, folder: String, name: String, bytes: ByteArray): String? = try {
        val out = target(folder, name)
        out.writeBytes(bytes)
        out.toFileUri()
    } catch (_: Exception) {
        null
    }

    actual suspend fun download(context: PlatformContext, url: String, folder: String, name: String): String? = withContext(Dispatchers.IO) {
        val out = target(folder, name)
        try {
            val call = com.mediaviewer.network.DesktopHttpClients.downloadClient.newCall(Request.Builder().url(url).build())
            call.execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val body = resp.body ?: return@withContext null
                // Streamed to disk: a long video never sits in memory.
                body.byteStream().use { input -> out.outputStream().use { input.copyTo(it) } }
            }
            if (out.length() > 0L) out.toFileUri() else { out.delete(); null }
        } catch (_: Exception) {
            out.delete()
            null
        }
    }

    actual fun copyIn(context: PlatformContext, uri: PlatformUri, folder: String, name: String): String? = try {
        val source = uri.file?.takeIf { it.isFile }
        if (source == null) null else {
            val out = target(folder, name)
            source.copyTo(out, overwrite = true)
            if (out.length() > 0L) out.toFileUri() else { out.delete(); null }
        }
    } catch (_: Exception) {
        null
    }

    actual fun read(context: PlatformContext, uri: String): ByteArray? = try {
        ownFile(uri)?.takeIf { it.isFile }?.readBytes()
    } catch (_: Exception) {
        null
    }

    actual fun size(context: PlatformContext, uri: String): Long = try {
        ownFile(uri)?.length() ?: 0L
    } catch (_: Exception) {
        0L
    }

    actual fun delete(context: PlatformContext, uri: String) {
        try { ownFile(uri)?.delete() } catch (_: Exception) {}
    }

    actual fun deleteFolder(context: PlatformContext, folder: String) {
        try { File(root(), folder).deleteRecursively() } catch (_: Exception) {}
    }

    actual fun displayName(context: PlatformContext, uri: PlatformUri): String? =
        uri.file?.name?.takeIf { it.isNotBlank() } ?: uri.toString().substringAfterLast('/').takeIf { it.isNotBlank() }

    private fun mediaRoot(): File = File(DesktopPaths.filesDir, "local_media")

    actual fun mediaRootUri(context: PlatformContext): String = mediaRoot().apply { mkdirs() }.toFileUri().trimEnd('/')

    /** A file in either of our folders (never outside them, even via ".."). */
    private fun syncFile(uri: String): File? {
        val file = (fileOfUri(uri) ?: return null).canonicalFile
        val roots = listOf(root(), mediaRoot()).map { it.canonicalPath + File.separator }
        return if (roots.any { file.path.startsWith(it) }) file else null
    }

    actual fun readRange(context: PlatformContext, uri: String, offset: Long, length: Int): ByteArray? = try {
        val f = syncFile(uri)?.takeIf { it.isFile }
        if (f == null) null else java.io.RandomAccessFile(f, "r").use { raf ->
            val n = minOf(length.toLong(), (raf.length() - offset).coerceAtLeast(0L)).toInt()
            val out = ByteArray(n)
            raf.seek(offset)
            raf.readFully(out)
            out
        }
    } catch (_: Exception) {
        null
    }

    actual fun appendBytes(context: PlatformContext, uri: String, bytes: ByteArray, restart: Boolean): Boolean = try {
        val f = syncFile(uri)
        if (f == null) false else {
            f.parentFile?.mkdirs()
            java.io.FileOutputStream(f, !restart).use { it.write(bytes) }
            true
        }
    } catch (_: Exception) {
        false
    }

    actual fun ownSize(context: PlatformContext, uri: String): Long = syncFile(uri)?.takeIf { it.isFile }?.length() ?: -1L
}
