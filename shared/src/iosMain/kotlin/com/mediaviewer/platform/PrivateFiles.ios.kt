package com.mediaviewer.platform

import com.mediaviewer.network.PlainHttp
import com.mediaviewer.network.isSuccessful
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.convert
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileProtectionCompleteUnlessOpen
import platform.Foundation.NSFileProtectionKey
import platform.Foundation.NSHomeDirectory
import platform.posix.mkdir

@OptIn(ExperimentalForeignApi::class)
actual object PrivateFiles {
    /** Library/StellarPrivate — inside the app's sandbox, no spaces in the
     *  path (so "file://" + path is a valid URL as is). */
    private fun root(): String {
        val lib = NSHomeDirectory() + "/Library"
        mkdir(lib, 0x1ED.convert())
        val dir = "$lib/StellarPrivate"
        mkdir(dir, 0x1ED.convert())
        return dir
    }

    private fun target(folder: String, name: String): String {
        var dir = root()
        // (Nested folders are made one level at a time.)
        folder.split('/').filter { it.isNotBlank() }.forEach { part ->
            dir = "$dir/$part"
            mkdir(dir, 0x1ED.convert())
        }
        return dir + "/" + name.replace('/', '_').replace(' ', '_')
    }

    private fun protect(path: String) {
        runCatching {
            NSFileManager.defaultManager.setAttributes(
                mapOf<Any?, Any?>(NSFileProtectionKey to NSFileProtectionCompleteUnlessOpen), ofItemAtPath = path, error = null
            )
        }
    }

    private fun ownPath(uri: String): String? {
        val path = if (uri.startsWith("file://")) uri.removePrefix("file://") else uri
        return if (path.startsWith(root())) path else null
    }

    actual fun write(context: PlatformContext, folder: String, name: String, bytes: ByteArray): String? {
        val out = target(folder, name)
        return if (writeLocalFile(out, bytes)) { protect(out); "file://$out" } else null
    }

    actual suspend fun download(context: PlatformContext, url: String, folder: String, name: String): String? = withContext(Dispatchers.IO) {
        try {
            val resp = PlainHttp.get(url)
            if (!resp.isSuccessful || resp.body.isEmpty()) null else write(context, folder, name, resp.body)
        } catch (_: Throwable) {
            null
        }
    }

    actual fun copyIn(context: PlatformContext, uri: PlatformUri, folder: String, name: String): String? = try {
        val bytes = readLocalFile(MediaBridge.pathOf(uri))
        if (bytes == null || bytes.isEmpty()) null else write(context, folder, name, bytes)
    } catch (_: Throwable) {
        null
    }

    actual fun read(context: PlatformContext, uri: String): ByteArray? = ownPath(uri)?.let { readLocalFile(it) }

    actual fun size(context: PlatformContext, uri: String): Long {
        val path = ownPath(uri) ?: return 0L
        val attrs = runCatching { NSFileManager.defaultManager.attributesOfItemAtPath(path, error = null) }.getOrNull() ?: return 0L
        return (attrs[platform.Foundation.NSFileSize] as? Number)?.toLong() ?: 0L
    }

    actual fun delete(context: PlatformContext, uri: String) {
        ownPath(uri)?.let { deleteLocalFile(it) }
    }

    actual fun deleteFolder(context: PlatformContext, folder: String) {
        val dir = root() + "/" + folder.trim('/')
        runCatching { NSFileManager.defaultManager.removeItemAtPath(dir, error = null) }
    }

    actual fun displayName(context: PlatformContext, uri: PlatformUri): String? =
        MediaBridge.pathOf(uri).substringAfterLast('/').takeIf { it.isNotBlank() }
}
