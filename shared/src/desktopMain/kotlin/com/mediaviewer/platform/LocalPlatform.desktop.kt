package com.mediaviewer.platform

import java.io.ByteArrayInputStream
import java.io.File
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip

actual object LocalPlatform {
    private var clip: Clip? = null

    private fun mediaRoot(): File = File(DesktopPaths.filesDir, "local_media").apply { mkdirs() }

    actual fun importMedia(context: PlatformContext, uri: PlatformUri, folder: String): PlatformUri? = try {
        val source = uri.file
        when {
            source == null || !source.isFile -> null
            source.absolutePath.startsWith(mediaRoot().absolutePath) -> uri
            else -> {
                val dir = File(mediaRoot(), folder).apply { mkdirs() }
                val ext = source.extension.lowercase().takeIf { it.length in 2..5 } ?: "bin"
                val out = File(dir, randomUuidString() + "." + ext)
                source.copyTo(out, overwrite = true)
                DesktopUri.of(out)
            }
        }
    } catch (_: Throwable) {
        null
    }

    actual fun parseUri(text: String): PlatformUri = DesktopUri(text)

    actual fun deleteMedia(context: PlatformContext, uri: String) {
        val file = fileOfUri(uri) ?: return
        if (file.absolutePath.startsWith(mediaRoot().absolutePath)) file.delete()
    }

    /** The computer manages the display's refresh rate itself. */
    actual fun setBatterySaver(context: PlatformContext, on: Boolean) {}

    actual fun playLoopingWav(context: PlatformContext, wav: ByteArray) {
        stopSound()
        try {
            val stream = AudioSystem.getAudioInputStream(ByteArrayInputStream(wav))
            val c = AudioSystem.getClip()
            c.open(stream)
            c.loop(Clip.LOOP_CONTINUOUSLY)
            clip = c
        } catch (_: Throwable) {
            clip = null
        }
    }

    actual fun stopSound() {
        val c = clip ?: return
        clip = null
        runCatching { c.stop(); c.close() }
    }

    /** Notifications while Stellar is open are shown by DesktopNotifications;
     *  there's no background check once the window is closed. */
    actual fun syncNotifications(context: PlatformContext, requestPermission: Boolean) {
        DesktopNotifications.sync()
    }

    /** No home-screen widgets on a computer. */
    actual fun updateWidgets(context: PlatformContext, dms: List<WidgetChat>?) {}

    // "Scrobble Music to Rocksky" watches what other apps are playing
    // through Android's notification access: not supported here.
    actual fun scrobblerStatus(context: PlatformContext): com.mediaviewer.util.ScrobblerStatus = com.mediaviewer.util.ScrobblerStatus()
    actual fun setScrobblerEnabled(context: PlatformContext, on: Boolean, did: String) {}
    actual fun setScrobblerApp(context: PlatformContext, packageName: String, on: Boolean) {}
    actual fun openScrobblerAccessSettings(context: PlatformContext) {}
    actual fun setScrobblerThreshold(context: PlatformContext, percent: Int, seconds: Int) {}
    actual fun importScrobbleHistory(context: PlatformContext, uri: PlatformUri, did: String) {}
    actual fun cancelScrobbleImport(context: PlatformContext, id: Long) {}
    actual fun setScrobbleImportBackground(context: PlatformContext, on: Boolean) {}
    actual fun requestScrobbleBatteryExemption(context: PlatformContext) {}
}
