package com.mediaviewer.platform

import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFAudio.AVAudioPlayer
import platform.posix.mkdir
import kotlinx.cinterop.convert

@OptIn(ExperimentalForeignApi::class)
actual object LocalPlatform {
    private var player: AVAudioPlayer? = null

    private fun mediaRoot(): String {
        val dir = IosPaths.filesDir() + "/local_media"
        mkdir(dir, 0x1ED.convert())
        return dir
    }

    actual fun importMedia(context: PlatformContext, uri: PlatformUri, folder: String): PlatformUri? {
        return try {
            val root = mediaRoot()
            val path = MediaBridge.pathOf(uri)
            if (path.startsWith(root)) return uri
            val bytes = readLocalFile(path) ?: return null
            val dir = "$root/$folder"
            mkdir(dir, 0x1ED.convert())
            val ext = path.substringAfterLast('.', "").takeIf { it.length in 2..5 } ?: "bin"
            val out = dir + "/" + randomUuidString() + "." + ext
            if (writeLocalFile(out, bytes)) IosUri("file://$out") else null
        } catch (_: Throwable) {
            null
        }
    }

    actual fun parseUri(text: String): PlatformUri = IosUri(text)

    actual fun deleteMedia(context: PlatformContext, uri: String) {
        val path = if (uri.startsWith("file://")) uri.removePrefix("file://") else uri
        if (path.startsWith(mediaRoot())) deleteLocalFile(path)
    }

    /** iOS manages the display's refresh rate itself. */
    actual fun setBatterySaver(context: PlatformContext, on: Boolean) {}

    actual fun playLoopingWav(context: PlatformContext, wav: ByteArray) {
        stopSound()
        try {
            val p = AVAudioPlayer(data = wav.toNSData(), error = null)
            p.numberOfLoops = -1
            p.prepareToPlay()
            p.play()
            player = p
        } catch (_: Throwable) {
            player = null
        }
    }

    actual fun stopSound() {
        val p = player ?: return
        player = null
        p.stop()
    }

    /** Device notifications while Stellar is closed are Android-only for
     *  now (the in-app banner works here too). */
    actual fun syncNotifications(context: PlatformContext, requestPermission: Boolean) {}

    /** Hands the widgets (the iOS app's WidgetKit extension) what they show. */
    actual fun updateWidgets(context: PlatformContext, dms: List<WidgetChat>?) {
        IosWidgetBridge.publish(dms)
    }
}
