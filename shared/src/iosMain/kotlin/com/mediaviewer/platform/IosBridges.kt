package com.mediaviewer.platform

import com.mediaviewer.ui.ProfileColorStore
import com.mediaviewer.ui.SelfProfileColors
import com.mediaviewer.util.AppLinks
import com.mediaviewer.util.LocalData
import com.mediaviewer.util.Supporter
import com.mediaviewer.util.TranslationEngine
import com.mediaviewer.util.TranslationManager
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import platform.Foundation.NSUserDefaults
import platform.NaturalLanguage.NLLanguageRecognizer
import kotlin.coroutines.resume

// The few things the iOS app has to do in Swift (Apple only offers them to
// Swift): on-device translation, and the home-screen widgets. The Swift app
// (iosApp/iosApp/iOSApp.swift) calls the register… functions below once at
// launch; everything else stays in Kotlin.

/** Apple's on-device Translation framework, as the Swift app wraps it. */
interface IosTranslator {
    /** False below iOS 18 (the framework doesn't exist there). */
    fun isAvailable(): Boolean
    /** [onResult] gets the translation, or null when Apple doesn't offer
     *  that language pair (or the person declined the language download). */
    fun translate(text: String, source: String, target: String, onResult: (String?) -> Unit)
}

/** Translation on iOS: the language is detected with Apple's
 *  NaturalLanguage framework and the text translated on the device by
 *  Apple's Translation framework — nothing is sent anywhere, and it's free. */
private class AppleTranslationEngine(private val translator: IosTranslator) : TranslationEngine {
    override suspend fun identifyLanguage(text: String): String {
        val tag = NLLanguageRecognizer.dominantLanguageForString(text) ?: return "und"
        // "zh-Hans" / "zh-Hant" → "zh", to match the picker's tags.
        return tag.substringBefore('-').ifBlank { "und" }
    }

    override suspend fun translate(text: String, sourceTag: String, targetTag: String): String? =
        suspendCancellableCoroutine { cont ->
            translator.translate(text, sourceTag, targetTag) { result -> if (cont.isActive) cont.resume(result) }
        }
}

/** Called by the Swift app at launch. */
fun registerIosTranslator(translator: IosTranslator) {
    if (translator.isAvailable()) TranslationManager.engine = AppleTranslationEngine(translator)
}

/** Called by the Swift app at launch: the on-device tagger (AI Tagging). */
fun registerIosTagger(tagger: com.mediaviewer.tagging.IosTagger) {
    if (!tagger.isAvailable()) return
    com.mediaviewer.tagging.IosTaggerBridge.tagger = tagger
    IosCapabilities.aiTagging = true
}

/** Making GIFs, as the Swift app does it with Apple's ImageIO and
 *  AVFoundation (iosApp/iosApp/StellarMediaTools.swift). */
interface IosMediaTools {
    /** Every frame of the video file at [videoPath] (up to 25 a second),
     *  written as an animated GIF at [outPath]. [onDone] gets an error
     *  message, or null when the GIF is there. */
    fun gifFromVideo(videoPath: String, outPath: String, onDone: (String?) -> Unit)
    /** The picture at [imagePath] as a GIF at [outPath]. */
    fun gifFromImage(imagePath: String, outPath: String, onDone: (String?) -> Unit)
}

object IosMediaBridge {
    @kotlin.concurrent.Volatile var tools: IosMediaTools? = null
}

/** Called by the Swift app at launch. */
fun registerIosMediaTools(tools: IosMediaTools) {
    IosMediaBridge.tools = tools
    IosCapabilities.gifExport = true
}

/** Called by the Swift app at launch: [reload] asks WidgetKit to redraw. */
fun registerIosWidgetReloader(reload: () -> Unit) {
    IosWidgetBridge.reload = reload
}

/** Called by the Swift app when a stellar:// link (a widget tap) opens it. */
fun handleIosOpenUrl(url: String) {
    AppLinks.open(url)
}

/**
 * What the home-screen widgets show, handed to the widget extension through
 * the app group's shared defaults (the only storage both can read). The
 * extension is plain SwiftUI and only reads these values.
 */
@OptIn(ExperimentalForeignApi::class)
object IosWidgetBridge {
    const val GROUP = "group.rechoraccoon.stellar"
    var reload: (() -> Unit)? = null

    fun publish(dms: List<WidgetChat>?) {
        runCatching {
            val defaults = NSUserDefaults(suiteName = GROUP)
            if (dms != null) {
                val chats = JsonArray(dms.take(30).map { c ->
                    buildJsonObject {
                        put("id", c.convoId); put("name", c.name); put("text", c.text); put("unread", c.unread)
                        put("avatar", c.avatarUrl ?: "")
                        put("streak", c.streak)
                        put("group", c.isGroup)
                    }
                })
                defaults.setObject(chats.toString(), forKey = "chats")
            }
            val events = JsonArray(LocalData.upcomingAgenda(30).map { e ->
                buildJsonObject { put("day", e.day); put("minute", e.minute); put("title", e.title) }
            })
            defaults.setObject(events.toString(), forKey = "events")
            val notes = LocalData.notes
            val note = notes.firstOrNull { it.id == LocalData.widgetNoteId } ?: notes.maxByOrNull { it.updatedAt }
            defaults.setObject(
                if (note == null) "" else buildJsonObject { put("id", note.id); put("title", note.title); put("body", note.body) }.toString(),
                forKey = "note"
            )
            defaults.setBool(Supporter.active, forKey = "supporter")
            val did = Supporter.selfDid.ifBlank { SelfProfileColors.savedDid.orEmpty() }
            val colors = ProfileColorStore.get(did)
            if (colors != null) {
                defaults.setObject(JsonArray(listOf(colors.banner, colors.avatar).map { c ->
                    JsonArray(listOf(JsonPrimitive(c.red), JsonPrimitive(c.green), JsonPrimitive(c.blue)))
                }).toString(), forKey = "colors")
            }
            reload?.invoke()
        }
    }
}
