package com.mediaviewer.util

import com.mediaviewer.platform.DesktopOs
import com.mediaviewer.platform.DesktopPaths
import com.mediaviewer.platform.PlatformContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File

/** Windows and macOS: the release's installer for this computer,
 *  downloaded and opened. On Windows Stellar then closes, so the installer
 *  can replace it; on a Mac the disk image opens for the app to be dragged
 *  into Applications. */
actual object PlatformUpdater {
    actual val checks: Boolean = DesktopOs.isWindows || DesktopOs.isMac
    actual val canInstall: Boolean = DesktopOs.isWindows || DesktopOs.isMac

    actual fun pickAsset(names: List<String>): String? = when {
        DesktopOs.isWindows -> names.firstOrNull { it.equals("Stellar-Windows.exe", ignoreCase = true) }
            ?: names.firstOrNull { it.endsWith(".exe", ignoreCase = true) }
            ?: names.firstOrNull { it.endsWith(".msi", ignoreCase = true) }
        DesktopOs.isMac -> {
            val arm = System.getProperty("os.arch").orEmpty().let { it.contains("aarch64") || it.contains("arm") }
            names.firstOrNull { it.endsWith(".dmg", ignoreCase = true) && it.contains(if (arm) "AppleSilicon" else "Intel", ignoreCase = true) }
                ?: names.firstOrNull { it.endsWith(".dmg", ignoreCase = true) }
        }
        else -> null
    }

    actual suspend fun downloadAndInstall(context: PlatformContext, url: String, name: String, version: String, onProgress: (Float) -> Unit) {
        val dir = File(DesktopPaths.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, name)
        withContext(Dispatchers.IO) {
            val part = File(dir, "$name.part")
            val call = com.mediaviewer.network.DesktopHttpClients.downloadClient.newCall(Request.Builder().url(url).build())
            call.execute().use { resp ->
                if (!resp.isSuccessful) error("Download failed (${resp.code})")
                val body = resp.body ?: error("Download failed")
                val total = body.contentLength()
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
            }
            if (!part.renameTo(out)) { part.copyTo(out, overwrite = true); part.delete() }
        }
        onProgress(1f)
        withContext(Dispatchers.IO) {
            if (DesktopOs.isMac) ProcessBuilder("open", out.absolutePath).start()
            else java.awt.Desktop.getDesktop().open(out)
        }
        if (DesktopOs.isWindows) {
            // Out of the installer's way.
            delay(1500)
            kotlin.system.exitProcess(0)
        }
    }
}
