package com.mediaviewer.util

import androidx.compose.ui.graphics.ImageBitmap
import com.mediaviewer.platform.PlatformContext

/** One imported custom emoji. [id] is permanent for the emoji's lifetime and
 *  is what the in-text token character is derived from (see [EmojiStore.charFor]).
 *  [file] is the PNG copy stored under the app's private files dir. */
data class EmojiEntry(val id: Int, val name: String, val file: String)

/** A user-made emoji folder/tab. "All" is not stored — it is implicitly every
 *  emoji in the library. */
data class EmojiFolder(val id: Int, val name: String, val emojiIds: List<Int> = emptyList())

/** The persisted shape (written as JSON). */
data class EmojiIndex(
    val emojis: List<EmojiEntry> = emptyList(),
    val folders: List<EmojiFolder> = emptyList()
)

/** [folders] with emoji [id] filed into the one named [folderName] (made
 *  if there isn't one); unchanged for a null name. */
fun filedInto(folders: List<EmojiFolder>, folderName: String?, id: Int): List<EmojiFolder> {
    if (folderName.isNullOrBlank()) return folders
    val existing = folders.firstOrNull { it.name == folderName }
        ?: return folders + EmojiFolder((folders.maxOfOrNull { it.id } ?: 0) + 1, folderName, listOf(id))
    return folders.map { if (it.id == existing.id) it.copy(emojiIds = it.emojiIds + id) else it }
}

/** [EmojiIndex] plus a lookup table, rebuilt whenever the index changes. */
class EmojiState(val index: EmojiIndex) {
    val byId: Map<Int, EmojiEntry> = index.emojis.associateBy { it.id }
}

/**
 * The user's custom emoji library. Each emoji is typed into a draft as one
 * private-use character ("token") and drawn as its picture. The Android
 * store keeps pictures + an index in app storage; iOS has an empty library
 * for now.
 */
expect class EmojiStore {
    /** Observable by Compose — recomposes readers when emoji change. */
    val state: EmojiState

    /** Reads the saved library from disk (once). */
    suspend fun load()

    fun entryFor(c: Char): EmojiEntry?
    fun charFor(entry: EmojiEntry): Char
    fun containsEmoji(text: String): Boolean
    fun toShortcodes(text: String): String
    fun stripEmoji(text: String): String
    fun imageBitmapForChar(c: Char): ImageBitmap?

    /** The emoji's picture, as stored (a PNG). */
    fun pngBytes(entry: EmojiEntry): ByteArray?

    /** Adds a picture that's already a small PNG (an emoji from the
     *  person's PDS, see EmojiCloud), filed into the folder named
     *  [folderName] (made if there isn't one). Null if it couldn't be read. */
    suspend fun addPng(name: String, png: ByteArray, folderName: String?): EmojiEntry?

    companion object {
        fun get(context: PlatformContext): EmojiStore
    }
}
