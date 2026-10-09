package com.mediaviewer.util

import com.mediaviewer.json.StellarJson
import com.mediaviewer.platform.SharedPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * VRM mode's avatars: every model the person has added, shown as a row of
 * small thumbnail buttons at the top of VRM Settings › Avatar. Tapping one
 * loads it (only one model is ever loaded at a time); holding one deletes
 * it; double-tapping picks a different picture for its button.
 *
 * Kept in the same "vrm_settings" file as the rest of VRM mode (so App
 * Data Export carries it). [VrmModel.source] is platform-specific: a
 * content:// link on Android (the file stays where the person keeps it),
 * a file name in the app's own storage on iOS (iOS can't keep a link to a
 * file elsewhere).
 */
@Serializable
data class VrmModel(
    val id: String,
    /** Where the .vrm is (see the class comment). */
    val source: String,
    /** The file's name without ".vrm" — shown when there's no picture. */
    val name: String,
    /** The button's picture (a private file:// uri), or "" for none. */
    val thumb: String = "",
    /** The picture was picked by hand (not the one inside the file). */
    val customThumb: Boolean = false
)

object VrmLibrary {
    const val PREFS = "vrm_settings"
    const val THUMB_FOLDER = "vrm_thumbs"
    private const val KEY_MODELS = "vrm_library"
    private const val KEY_SELECTED = "vrm_library_selected"

    fun load(prefs: SharedPreferences): List<VrmModel> =
        prefs.getString(KEY_MODELS, null)?.let {
            runCatching { StellarJson.default.decodeFromString(ListSerializer(VrmModel.serializer()), it) }.getOrNull()
        } ?: emptyList()

    fun selected(prefs: SharedPreferences): String? = prefs.getString(KEY_SELECTED, null)?.takeIf { it.isNotBlank() }

    fun save(prefs: SharedPreferences, models: List<VrmModel>, selectedId: String?) {
        prefs.edit()
            .putString(KEY_MODELS, StellarJson.default.encodeToString(ListSerializer(VrmModel.serializer()), models))
            .putString(KEY_SELECTED, selectedId ?: "")
            .apply()
    }

    /** "Alicia Solid.vrm" → "Alicia Solid". */
    fun nameFrom(fileName: String?): String =
        fileName?.substringAfterLast('/')?.substringBeforeLast('.')?.trim()?.takeIf { it.isNotBlank() } ?: "Avatar"

    /**
     * The thumbnail picture a .vrm carries inside it (VRM 0.x's
     * meta.texture, VRM 1.0's meta.thumbnailImage), as PNG/JPEG bytes and
     * the matching file extension — or null when it has none.
     */
    fun embeddedThumbnail(bytes: ByteArray): Pair<ByteArray, String>? = runCatching {
        if (bytes.size < 20 || u32(bytes, 0) != 0x46546C67) return null // "glTF"
        var at = 12
        var json: JsonObject? = null
        var bin: Pair<Int, Int>? = null
        while (at + 8 <= bytes.size) {
            val len = u32(bytes, at)
            val type = u32(bytes, at + 4)
            val start = at + 8
            if (len < 0 || start + len > bytes.size) break
            when (type) {
                0x4E4F534A -> json = StellarJson.default.parseToJsonElement(bytes.decodeToString(start, start + len)) as? JsonObject
                0x004E4942 -> bin = start to len
            }
            at = start + len
        }
        val doc = json ?: return null
        val binChunk = bin ?: return null
        fun obj(o: JsonObject?, key: String) = o?.get(key) as? JsonObject
        fun arr(o: JsonObject?, key: String) = o?.get(key) as? JsonArray
        fun int(o: JsonObject?, key: String) = (o?.get(key) as? JsonPrimitive)?.intOrNull
        val ext = obj(doc, "extensions")
        val imageIndex = int(obj(obj(ext, "VRMC_vrm"), "meta"), "thumbnailImage")
            ?: int(obj(obj(ext, "VRM"), "meta"), "texture")?.takeIf { it >= 0 }?.let { t ->
                int(arr(doc, "textures")?.getOrNull(t) as? JsonObject, "source")
            }
            ?: return null
        val image = arr(doc, "images")?.getOrNull(imageIndex) as? JsonObject ?: return null
        val view = arr(doc, "bufferViews")?.getOrNull(int(image, "bufferView") ?: return null) as? JsonObject ?: return null
        val offset = int(view, "byteOffset") ?: 0
        val length = int(view, "byteLength") ?: return null
        if (offset < 0 || length <= 0 || offset + length > binChunk.second) return null
        val data = bytes.copyOfRange(binChunk.first + offset, binChunk.first + offset + length)
        val mime = (image["mimeType"] as? JsonPrimitive)?.content.orEmpty()
        data to (if (mime.contains("jpeg") || mime.contains("jpg")) "jpg" else "png")
    }.getOrNull()

    private fun u32(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or
            ((b[at + 2].toInt() and 0xFF) shl 16) or ((b[at + 3].toInt() and 0xFF) shl 24)
}
