// check:jvm
package com.mediaviewer.platform

import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File

actual object PrivateFiles {
    private fun root(context: PlatformContext): File = File(context.applicationContext.filesDir, "private")

    actual fun rootUri(context: PlatformContext): String = Uri.fromFile(root(context)).toString()

    private fun target(context: PlatformContext, folder: String, name: String): File {
        val dir = File(root(context), folder).apply { mkdirs() }
        return File(dir, name.replace('/', '_'))
    }

    /** Only files under our own folder are ever touched. */
    private fun ownFile(context: PlatformContext, uri: String): File? {
        val path = (if (uri.startsWith("file://")) Uri.parse(uri).path else uri) ?: return null
        val file = File(path)
        return if (file.absolutePath.startsWith(root(context).absolutePath)) file else null
    }

    actual fun write(context: PlatformContext, folder: String, name: String, bytes: ByteArray): String? = try {
        val out = target(context, folder, name)
        out.writeBytes(bytes)
        Uri.fromFile(out).toString()
    } catch (_: Exception) {
        null
    }

    actual suspend fun download(context: PlatformContext, url: String, folder: String, name: String): String? = withContext(Dispatchers.IO) {
        val out = target(context, folder, name)
        try {
            val call = com.mediaviewer.network.AndroidHttpClients.downloadClient.newCall(Request.Builder().url(url).build())
            call.execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val body = resp.body ?: return@withContext null
                // Streamed to disk: a long video never sits in memory.
                body.byteStream().use { input -> out.outputStream().use { input.copyTo(it) } }
            }
            if (out.length() > 0L) Uri.fromFile(out).toString() else { out.delete(); null }
        } catch (_: Exception) {
            out.delete()
            null
        }
    }

    actual fun copyIn(context: PlatformContext, uri: PlatformUri, folder: String, name: String): String? = try {
        val out = target(context, folder, name)
        val input = context.applicationContext.contentResolver.openInputStream(uri)
        if (input == null) null else {
            input.use { ins -> out.outputStream().use { ins.copyTo(it) } }
            if (out.length() > 0L) Uri.fromFile(out).toString() else { out.delete(); null }
        }
    } catch (_: Exception) {
        null
    }

    actual fun read(context: PlatformContext, uri: String): ByteArray? = try {
        ownFile(context, uri)?.takeIf { it.isFile }?.readBytes()
    } catch (_: Exception) {
        null
    }

    actual fun size(context: PlatformContext, uri: String): Long = try {
        ownFile(context, uri)?.length() ?: 0L
    } catch (_: Exception) {
        0L
    }

    actual fun delete(context: PlatformContext, uri: String) {
        try { ownFile(context, uri)?.delete() } catch (_: Exception) {}
    }

    actual fun deleteFolder(context: PlatformContext, folder: String) {
        try { File(root(context), folder).deleteRecursively() } catch (_: Exception) {}
    }

    actual fun displayName(context: PlatformContext, uri: PlatformUri): String? = try {
        context.applicationContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
        } ?: uri.lastPathSegment
    } catch (_: Exception) {
        uri.lastPathSegment
    }
}
