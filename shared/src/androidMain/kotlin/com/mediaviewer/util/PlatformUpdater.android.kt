package com.mediaviewer.util

import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import com.mediaviewer.platform.PlatformContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File

/** Android: the release's APK, downloaded into the cache and handed to the
 *  system installer (which still asks before installing). The first time,
 *  Android asks to allow Stellar to install apps; after that, Update again. */
actual object PlatformUpdater {
    actual val checks: Boolean = true
    actual val canInstall: Boolean = true

    actual fun pickAsset(names: List<String>): String? =
        names.firstOrNull { it.equals("Stellar.apk", ignoreCase = true) } ?: names.firstOrNull { it.endsWith(".apk", ignoreCase = true) }

    actual suspend fun downloadAndInstall(context: PlatformContext, url: String, name: String, version: String, onProgress: (Float) -> Unit) {
        val app = context.applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !app.packageManager.canRequestPackageInstalls()) {
            // Settings → "Install unknown apps" → Stellar.
            val intent = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, android.net.Uri.parse("package:" + app.packageName))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            withContext(Dispatchers.Main) { app.startActivity(intent) }
            error("Allow Stellar to install apps, then tap Update again")
        }
        val dir = File(app.cacheDir, "updates").apply { mkdirs() }
        val out = File(dir, "Stellar-$version.apk")
        // (Anything left from an older update goes.)
        dir.listFiles()?.filter { it != out }?.forEach { it.delete() }
        withContext(Dispatchers.IO) {
            val part = File(dir, out.name + ".part")
            val call = com.mediaviewer.network.AndroidHttpClients.downloadClient.newCall(Request.Builder().url(url).build())
            call.execute().use { resp ->
                if (!resp.isSuccessful) error("Download failed (${resp.code})")
                val body = resp.body ?: error("Download failed")
                val total = body.contentLength()
                if (out.isFile && total > 0 && out.length() == total) return@use
                body.byteStream().use { input ->
                    part.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var lastShown = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            done += n
                            if (total > 0 && done - lastShown > total / 100) { lastShown = done; onProgress(done.toFloat() / total) }
                        }
                    }
                }
                if (!part.renameTo(out)) { part.copyTo(out, overwrite = true); part.delete() }
            }
        }
        onProgress(1f)
        val uri = FileProvider.getUriForFile(app, app.packageName + ".fileprovider", out)
        val install = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        withContext(Dispatchers.Main) { app.startActivity(install) }
    }
}
