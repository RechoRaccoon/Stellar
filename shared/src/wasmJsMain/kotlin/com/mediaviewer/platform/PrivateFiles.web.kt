package com.mediaviewer.platform

import com.mediaviewer.network.PlainHttp
import com.mediaviewer.network.isSuccessful

actual object PrivateFiles {
    private const val ROOT = "${WebPaths.FILES}/private"

    actual fun rootUri(context: PlatformContext): String = WebFiles.uriOf(ROOT)

    private fun target(folder: String, name: String): String =
        "$ROOT/$folder/" + name.replace('/', '_').replace('\\', '_').replace(' ', '_')

    /** Only files under our own folder are ever touched. */
    private fun own(uri: String): String? = webPathOf(uri)?.takeIf { it.startsWith("$ROOT/") }

    actual fun write(context: PlatformContext, folder: String, name: String, bytes: ByteArray): String? {
        val path = target(folder, name)
        return if (writeLocalFile(path, bytes)) WebFiles.uriOf(path) else null
    }

    actual suspend fun download(context: PlatformContext, url: String, folder: String, name: String): String? = try {
        val resp = PlainHttp.get(url)
        if (!resp.isSuccessful || resp.body.isEmpty()) null else write(context, folder, name, resp.body)
    } catch (_: Exception) {
        null
    }

    actual fun copyIn(context: PlatformContext, uri: PlatformUri, folder: String, name: String): String? {
        val bytes = uri.webPath?.let { WebFiles.read(it) }?.takeIf { it.isNotEmpty() } ?: return null
        return write(context, folder, name, bytes)
    }

    actual fun read(context: PlatformContext, uri: String): ByteArray? = own(uri)?.let { WebFiles.read(it) }

    actual fun size(context: PlatformContext, uri: String): Long = own(uri)?.let { WebFiles.size(it) } ?: 0L

    actual fun delete(context: PlatformContext, uri: String) { own(uri)?.let { WebFiles.delete(it) } }

    actual fun deleteFolder(context: PlatformContext, folder: String) = WebFiles.deleteFolder("$ROOT/$folder")

    actual fun displayName(context: PlatformContext, uri: PlatformUri): String? =
        uri.webPath?.let { WebBlobs.nameOf(it) } ?: uri.toString().substringAfterLast('/').takeIf { it.isNotBlank() }

    private const val MEDIA = "${WebPaths.FILES}/local_media"

    actual fun mediaRootUri(context: PlatformContext): String = WebFiles.uriOf(MEDIA)

    /** A file in either of our folders (never outside them, even via ".."). */
    private fun syncPath(uri: String): String? {
        val path = webPathOf(uri)?.let { WebFiles.normalize(it) } ?: return null
        if (path.split('/').any { it == ".." }) return null
        return path.takeIf { it.startsWith("$ROOT/") || it.startsWith("$MEDIA/") }
    }

    actual fun readRange(context: PlatformContext, uri: String, offset: Long, length: Int): ByteArray? {
        val bytes = syncPath(uri)?.let { WebFiles.read(it) } ?: return null
        val from = offset.coerceIn(0L, bytes.size.toLong()).toInt()
        return bytes.copyOfRange(from, minOf(bytes.size, from + length))
    }

    actual fun appendBytes(context: PlatformContext, uri: String, bytes: ByteArray, restart: Boolean): Boolean {
        val path = syncPath(uri) ?: return false
        val before = if (restart) null else WebFiles.read(path)
        WebFiles.write(path, if (before == null) bytes else before + bytes)
        return true
    }

    actual fun ownSize(context: PlatformContext, uri: String): Long =
        syncPath(uri)?.takeIf { WebFiles.exists(it) }?.let { WebFiles.size(it) } ?: -1L
}
