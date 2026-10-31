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
        val out: File
        try {
            out = download(url, name, onProgress)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            // The download itself failed: the browser can still fetch it.
            com.mediaviewer.ui.compat.DesktopLinks.open(url)
            error("Couldn't download the update (${e.message ?: e::class.simpleName}). It was opened in your browser instead.")
        }
        onProgress(1f)
        val launched = withContext(Dispatchers.IO) { launchInstaller(out) }
        if (launched != null) {
            // Everything failed to start it: hand over the folder so it can be run by hand.
            runCatching { com.mediaviewer.ui.compat.DesktopLinks.open(out.parentFile.toURI().toString()) }
            error("The update downloaded, but Windows wouldn't start the installer ($launched). Its folder was opened: run ${out.name} from there.")
        }
        if (DesktopOs.isWindows) {
            // Out of the installer's way.
            delay(1500)
            kotlin.system.exitProcess(0)
        }
    }

    /** Downloads [url] to the updates folder and checks it's whole. */
    private suspend fun download(url: String, name: String, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(DesktopPaths.cacheDir, "updates").apply { mkdirs() }
        // Old installers go (one still running or open can't be deleted: it's left alone).
        dir.listFiles()?.forEach { runCatching { it.delete() } }
        var out = File(dir, name)
        // A leftover that couldn't be removed: a fresh name instead of failing on it.
        if (out.exists()) out = File(dir, "${System.currentTimeMillis()}-$name")
        val part = File(dir, "${out.name}.part")
        val call = com.mediaviewer.network.DesktopHttpClients.downloadClient.newCall(Request.Builder().url(url).build())
        var expected = -1L
        call.execute().use { resp ->
            if (!resp.isSuccessful) error("Download failed (${resp.code})")
            val body = resp.body ?: error("Download failed")
            val total = body.contentLength()
            expected = total
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
        // A cut-off download would only fail later as "not a valid Win32 application".
        if (part.length() == 0L || (expected > 0 && part.length() != expected)) {
            part.delete()
            error("the download was cut off (${part.length()} of $expected bytes)")
        }
        if (!part.renameTo(out)) { part.copyTo(out, overwrite = true); part.delete() }
        out
    }

    /** Starts the installer, trying each way Windows / macOS offers in turn
     *  (Desktop.open alone is unreliable from a packaged app, the same as
     *  opening links — see DesktopLinks). Null when one worked, else why not. */
    private fun launchInstaller(file: File): String? {
        val path = file.absolutePath
        val attempts: List<Pair<String, () -> Unit>> = if (DesktopOs.isMac) listOf(
            "open" to { ProcessBuilder("open", path).start(); Unit }
        ) else listOf(
            // Through the shell, like double-clicking it (handles a UAC prompt too).
            "cmd start" to { ProcessBuilder("cmd", "/c", "start", "", path).start(); Unit },
            "Desktop.open" to {
                if (!java.awt.Desktop.isDesktopSupported()) error("Desktop not supported")
                java.awt.Desktop.getDesktop().open(file)
            },
            "explorer" to { ProcessBuilder("explorer.exe", path).start(); Unit },
            "rundll32" to { ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", path).start(); Unit },
            "direct" to { ProcessBuilder(path).start(); Unit }
        )
        var last: String? = null
        for ((label, run) in attempts) {
            val ok = runCatching { run() }.onFailure { last = "$label: ${it.message ?: it::class.simpleName}" }.isSuccess
            if (ok) return null
        }
        return last ?: "unknown error"
    }
}
