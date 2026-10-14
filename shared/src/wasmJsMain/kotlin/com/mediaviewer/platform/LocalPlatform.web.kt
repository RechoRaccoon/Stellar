package com.mediaviewer.platform

actual object LocalPlatform {
    private const val MEDIA = "${WebPaths.FILES}/local_media"
    private var sound: JsAny? = null

    actual fun importMedia(context: PlatformContext, uri: PlatformUri, folder: String): PlatformUri? {
        val source = uri.webPath ?: return null
        if (source.startsWith("$MEDIA/")) return uri
        val name = WebBlobs.nameOf(source) ?: source.substringAfterLast('/')
        val ext = name.substringAfterLast('.', "").lowercase().takeIf { it.length in 2..5 } ?: "bin"
        val out = "$MEDIA/$folder/" + randomUuidString() + "." + ext
        return if (WebFiles.copy(source, out)) WebUri(WebFiles.uriOf(out)) else null
    }

    actual fun parseUri(text: String): PlatformUri = WebUri(text)

    actual fun deleteMedia(context: PlatformContext, uri: String) {
        val path = webPathOf(uri) ?: return
        if (path.startsWith("$MEDIA/")) WebFiles.delete(path)
    }

    /** The browser manages the display's refresh rate itself. */
    actual fun setBatterySaver(context: PlatformContext, on: Boolean) {}

    actual fun playLoopingWav(context: PlatformContext, wav: ByteArray) {
        stopSound()
        sound = runCatching { playLoop(createObjectUrl(bytesToBlob(wav.toJsBytes(), "audio/wav"))) }.getOrNull()
    }

    actual fun stopSound() {
        val s = sound ?: return
        sound = null
        runCatching { stopAudio(s) }
    }

    /** Notifications while the tab is open are shown by WebNotifications;
     *  nothing runs once it's closed. */
    actual fun syncNotifications(context: PlatformContext, requestPermission: Boolean) {
        WebNotifications.sync(requestPermission)
    }

    /** No home-screen widgets on the web. */
    actual fun updateWidgets(context: PlatformContext, dms: List<WidgetChat>?) {}

    // "Scrobble Music to Rocksky" watches what other apps are playing
    // through Android's notification access: not on the web.
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

private fun playLoop(url: String): JsAny = js("(() => { const a = new Audio(url); a.loop = true; a.play().catch(() => {}); return a; })()")
private fun stopAudio(a: JsAny): Unit = js("{ a.pause(); a.src = ''; }")
