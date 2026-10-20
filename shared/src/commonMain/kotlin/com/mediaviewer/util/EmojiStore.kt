package com.mediaviewer.util

import androidx.compose.ui.graphics.ImageBitmap
import com.mediaviewer.platform.PlatformContext

/** One imported custom emoji. [id] is permanent for the emoji's lifetime and
 *  is what the in-text token character is derived from (see [EmojiStore.charFor]).
 *  [file] is the copy stored under the app's private files dir: a PNG, or a
 *  GIF for an animated emoji (see [EmojiMedia]). */
data class EmojiEntry(val id: Int, val name: String, val file: String) {
    val animated: Boolean get() = EmojiMedia.isAnimatedFile(file)
}

/** The name a new emoji folder starts with. */
const val EMOJI_DEFAULT_FOLDER_NAME = "New Folder"

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

    /** Switches to [did]'s own library (each signed-in account has its
     *  own; the one from before libraries were per account goes to the
     *  first account signed in). */
    suspend fun useAccount(did: String)

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

    // ── The emoji menu's edits ──
    /** A new, empty folder after the others; its id. */
    fun createFolder(name: String): Int
    fun renameFolder(id: Int, name: String)
    /** The folder and every emoji in it. */
    fun deleteFolder(id: Int)
    fun emojiCountIn(folderId: Int): Int
    /** One emoji, everywhere. */
    fun deleteEmoji(id: Int)
    /** Every emoji not in a folder; how many went. */
    fun deleteOrphanEmoji(): Int
    fun orphanEmojiCount(): Int
    /** A tab's emoji: [folderId] null = "All". */
    fun emojiFor(folderId: Int?): List<EmojiEntry>
    /** Puts a rearranged library in place (new order, an emoji moved to
     *  another folder) — the same emoji and folders, only arranged anew. */
    fun setIndex(next: EmojiIndex)
    /** Imports picked pictures, GIFs and videos (animated ones become
     *  animated emoji) into [folderId] (null = only "All"); how many. */
    suspend fun importImages(uris: List<com.mediaviewer.platform.PlatformUri>, folderId: Int?): Int
    /** Every picture and video in a picked folder (Android; 0 elsewhere). */
    suspend fun importTree(treeUri: com.mediaviewer.platform.PlatformUri, folderId: Int?): Int

    companion object {
        fun get(context: PlatformContext): EmojiStore
    }
}

/** The folder an account's emoji library lives in, under the emoji folder. */
internal fun emojiOwnerFolder(did: String): String = did.replace(':', '_').filter { it.isLetterOrDigit() || it == '_' || it == '.' || it == '-' }

/** The picture files an emoji index (index.json's text) lists. */
internal fun emojiIndexFiles(indexText: String): List<String> =
    Regex("\"file\"\\s*:\\s*\"([^\"]+)\"").findAll(indexText).map { it.groupValues[1] }
        .filter { !it.contains('/') && !it.contains('\\') }.toList()
