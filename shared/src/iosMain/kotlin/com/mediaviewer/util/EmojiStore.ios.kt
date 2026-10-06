package com.mediaviewer.util

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.mediaviewer.json.JSONArray
import com.mediaviewer.json.JSONObject
import com.mediaviewer.platform.IosPaths
import com.mediaviewer.platform.PlatformContext
import com.mediaviewer.platform.PlatformUri
import com.mediaviewer.platform.deleteLocalFile
import com.mediaviewer.platform.localFileExists
import com.mediaviewer.platform.readLocalFile
import com.mediaviewer.platform.synchronizedCompat
import com.mediaviewer.platform.toByteArray
import com.mediaviewer.platform.writeLocalFile
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import platform.Foundation.NSFileManager
import platform.UIKit.UIImage
import platform.UIKit.UIImagePNGRepresentation
import kotlin.concurrent.Volatile
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Custom emoji for Textshot mode on iOS — the same library Android keeps
 * (see the Android EmojiStore for how an emoji lives in text as one
 * private-use character): pictures and an index in the app's own folder,
 * with folders/tabs, import, rename and delete.
 */
@OptIn(ExperimentalForeignApi::class)
actual class EmojiStore private constructor() {
    private val dir: String by lazy {
        (IosPaths.filesDir() + "/emoji").also {
            NSFileManager.defaultManager.createDirectoryAtPath(it, withIntermediateDirectories = true, attributes = null, error = null)
        }
    }
    private val indexPath: String get() = "$dir/index.json"

    private val lock = Any()
    private val images = HashMap<Int, Image>()
    private val composeImages = HashMap<Int, ImageBitmap>()
    private val reservedIds = HashSet<Int>()

    private var current by mutableStateOf(EmojiState(EmojiIndex()))
    actual val state: EmojiState get() = current

    @Volatile private var loaded = false

    // ── Loading / saving ────────────────────────────────────────────────

    actual suspend fun load(): Unit = withContext(Dispatchers.IO) {
        if (loaded) return@withContext
        val parsed = runCatching {
            val root = JSONObject(readLocalFile(indexPath)?.decodeToString() ?: "{}")
            val emojiArray = root.optJSONArray("emojis") ?: JSONArray()
            val emojis = (0 until emojiArray.length()).mapNotNull { i ->
                val o = emojiArray.getJSONObject(i)
                val file = o.optString("file")
                if (file.isBlank() || !localFileExists("$dir/$file")) null
                else EmojiEntry(o.optInt("id"), o.optString("name", "emoji"), file)
            }
            val keep = emojis.map { it.id }.toSet()
            val folderArray = root.optJSONArray("folders") ?: JSONArray()
            val folders = (0 until folderArray.length()).map { i ->
                val o = folderArray.getJSONObject(i)
                val ids = o.optJSONArray("emojiIds") ?: JSONArray()
                EmojiFolder(o.optInt("id"), o.optString("name", DEFAULT_FOLDER_NAME), (0 until ids.length()).map { ids.optInt(it) }.filter { it in keep })
            }
            EmojiIndex(emojis, folders)
        }.getOrDefault(EmojiIndex())
        withContext(Dispatchers.Main) {
            if (!loaded) {
                current = EmojiState(parsed)
                loaded = true
            }
        }
    }

    private fun persist(index: EmojiIndex) {
        val root = JSONObject()
        val emojis = JSONArray()
        index.emojis.forEach { emojis.put(JSONObject().put("id", it.id).put("name", it.name).put("file", it.file)) }
        val folders = JSONArray()
        index.folders.forEach { f ->
            val ids = JSONArray()
            f.emojiIds.forEach { ids.put(it) }
            folders.put(JSONObject().put("id", f.id).put("name", f.name).put("emojiIds", ids))
        }
        root.put("emojis", emojis).put("folders", folders)
        runCatching { writeLocalFile(indexPath, root.toString().encodeToByteArray()) }
    }

    private fun commit(next: EmojiIndex) {
        current = EmojiState(next)
        persist(next)
    }

    // ── Token <-> emoji ─────────────────────────────────────────────────

    actual fun entryFor(c: Char): EmojiEntry? {
        val code = c.code
        if (code < TOKEN_BASE || code >= TOKEN_BASE + TOKEN_LIMIT) return null
        return state.byId[code - TOKEN_BASE]
    }

    actual fun charFor(entry: EmojiEntry): Char = (TOKEN_BASE + entry.id).toChar()

    actual fun containsEmoji(text: String): Boolean = text.any { entryFor(it) != null }

    actual fun toShortcodes(text: String): String {
        if (text.none { entryFor(it) != null }) return text
        val sb = StringBuilder(text.length + 16)
        for (c in text) {
            val e = entryFor(c)
            if (e != null) sb.append(':').append(e.name).append(':') else sb.append(c)
        }
        return sb.toString()
    }

    actual fun stripEmoji(text: String): String {
        if (text.none { entryFor(it) != null }) return text
        val sb = StringBuilder(text.length)
        for (c in text) if (entryFor(c) == null) sb.append(c)
        return sb.toString()
    }

    // ── Pictures ────────────────────────────────────────────────────────

    /** The stored picture (cached) — used by the Textshot renderer. */
    fun imageFor(entry: EmojiEntry): Image? = synchronizedCompat(lock) {
        images[entry.id] ?: runCatching { readLocalFile("$dir/${entry.file}")?.let { Image.makeFromEncoded(it) } }.getOrNull()
            ?.also { images[entry.id] = it }
    }

    fun imageForChar(c: Char): Image? = entryFor(c)?.let { imageFor(it) }

    fun imageBitmapFor(entry: EmojiEntry): ImageBitmap? {
        synchronizedCompat(lock) { composeImages[entry.id] }?.let { return it }
        val made = imageFor(entry)?.toComposeImageBitmap() ?: return null
        synchronizedCompat(lock) { composeImages[entry.id] = made }
        return made
    }

    actual fun imageBitmapForChar(c: Char): ImageBitmap? = entryFor(c)?.let { imageBitmapFor(it) }

    private fun forget(entry: EmojiEntry) {
        synchronizedCompat(lock) { images.remove(entry.id); composeImages.remove(entry.id) }
        runCatching { deleteLocalFile("$dir/${entry.file}") }
    }

    // ── Folders ─────────────────────────────────────────────────────────

    fun createFolder(name: String = DEFAULT_FOLDER_NAME): Int {
        val cur = state.index
        val id = (cur.folders.maxOfOrNull { it.id } ?: 0) + 1
        commit(cur.copy(folders = cur.folders + EmojiFolder(id, name)))
        return id
    }

    fun renameFolder(id: Int, name: String) {
        val clean = name.trim().ifBlank { DEFAULT_FOLDER_NAME }
        val cur = state.index
        commit(cur.copy(folders = cur.folders.map { if (it.id == id) it.copy(name = clean) else it }))
    }

    /** Removes the folder and every emoji that was filed into it. */
    fun deleteFolder(id: Int) {
        val cur = state.index
        val folder = cur.folders.firstOrNull { it.id == id } ?: return
        val gone = folder.emojiIds.toSet()
        val deleted = cur.emojis.filter { it.id in gone }
        commit(
            cur.copy(
                emojis = cur.emojis.filterNot { it.id in gone },
                folders = cur.folders.filterNot { it.id == id }.map { f -> f.copy(emojiIds = f.emojiIds.filterNot { it in gone }) }
            )
        )
        deleted.forEach { forget(it) }
    }

    fun emojiCountIn(folderId: Int): Int = state.index.folders.firstOrNull { it.id == folderId }?.emojiIds?.size ?: 0

    fun deleteEmoji(id: Int) {
        val cur = state.index
        val entry = cur.emojis.firstOrNull { it.id == id } ?: return
        commit(cur.copy(emojis = cur.emojis - entry, folders = cur.folders.map { f -> f.copy(emojiIds = f.emojiIds - id) }))
        forget(entry)
    }

    /** Deletes every emoji that isn't in any folder; how many went. */
    fun deleteOrphanEmoji(): Int {
        val cur = state.index
        val filed = cur.folders.flatMapTo(HashSet()) { it.emojiIds }
        val orphans = cur.emojis.filter { it.id !in filed }
        if (orphans.isEmpty()) return 0
        commit(cur.copy(emojis = cur.emojis.filter { it.id in filed }))
        orphans.forEach { forget(it) }
        return orphans.size
    }

    fun orphanEmojiCount(): Int {
        val filed = state.index.folders.flatMapTo(HashSet()) { it.emojiIds }
        return state.index.emojis.count { it.id !in filed }
    }

    /** Emoji shown for a tab: [folderId] null = "All". */
    fun emojiFor(folderId: Int?): List<EmojiEntry> {
        val st = state
        if (folderId == null) return st.index.emojis
        val folder = st.index.folders.firstOrNull { it.id == folderId } ?: return emptyList()
        return folder.emojiIds.mapNotNull { st.byId[it] }
    }

    // ── Import ──────────────────────────────────────────────────────────

    /** Imports the pictures the person picked. Returns how many were added. */
    suspend fun importImages(uris: List<PlatformUri>, folderId: Int?): Int {
        load()
        // Decoding and scaling happen off the main thread…
        val staged = withContext(Dispatchers.IO) {
            val out = ArrayList<Pair<Int, String>>()
            for (uri in uris) {
                val path = uri.toString().removePrefix("file://")
                val id = reserveId() ?: break
                val ok = runCatching { decodeAndStore(path, id) }.getOrDefault(false)
                if (ok) out += id to path.substringAfterLast('/')
                else synchronizedCompat(lock) { reservedIds.remove(id) }
            }
            out
        }
        if (staged.isEmpty()) return 0
        // …and the library itself changes on it.
        withContext(Dispatchers.Main) {
            val cur = state.index
            val taken = cur.emojis.map { it.name.lowercase() }.toMutableSet()
            val added = staged.map { (id, rawName) ->
                val name = uniqueName(sanitizeName(rawName), taken)
                taken += name.lowercase()
                EmojiEntry(id, name, "e_$id.png")
            }
            val addedIds = added.map { it.id }
            val folders = cur.folders.map { f -> if (f.id == folderId) f.copy(emojiIds = f.emojiIds + addedIds) else f }
            commit(EmojiIndex(cur.emojis + added, folders))
            synchronizedCompat(lock) { reservedIds.removeAll(addedIds.toSet()) }
        }
        return staged.size
    }

    private fun reserveId(): Int? = synchronizedCompat(lock) {
        val used = state.index.emojis.map { it.id }.toHashSet()
        var id = 0
        var found: Int? = null
        while (id < TOKEN_LIMIT && found == null) {
            if (id !in used && id !in reservedIds) { reservedIds += id; found = id }
            id++
        }
        found
    }

    /** Reads the picture at [path], shrinks it so its longest side is at
     *  most [MAX_EMOJI_SIDE], and stores it as a PNG (transparency kept). */
    private fun decodeAndStore(path: String, id: Int): Boolean {
        val bytes = readLocalFile(path) ?: return false
        // Formats the drawing engine doesn't read itself (HEIC photos) go
        // through the system's decoder first.
        val source = runCatching { Image.makeFromEncoded(bytes) }.getOrNull()
            ?: UIImage(contentsOfFile = path)?.let { UIImagePNGRepresentation(it) }?.toByteArray()
                ?.let { runCatching { Image.makeFromEncoded(it) }.getOrNull() }
            ?: return false
        val longest = max(source.width, source.height)
        if (longest <= 0) return false
        val scale = if (longest > MAX_EMOJI_SIDE) MAX_EMOJI_SIDE.toFloat() / longest else 1f
        val w = (source.width * scale).roundToInt().coerceAtLeast(1)
        val h = (source.height * scale).roundToInt().coerceAtLeast(1)
        val surface = Surface.makeRasterN32Premul(w, h)
        surface.canvas.clear(0)
        surface.canvas.drawImageRect(source, Rect.makeWH(w.toFloat(), h.toFloat()))
        val small = surface.makeImageSnapshot()
        val png = small.encodeToData(EncodedImageFormat.PNG)?.bytes ?: return false
        if (!writeLocalFile("$dir/e_$id.png", png)) return false
        synchronizedCompat(lock) { images[id] = small }
        return true
    }

    private fun sanitizeName(raw: String): String {
        val base = raw.substringBeforeLast('.', raw)
        val cleaned = base.replace(Regex("[^A-Za-z0-9_\\-]+"), "_").trim('_').take(40)
        return cleaned.ifBlank { "emoji" }
    }

    private fun uniqueName(base: String, taken: Set<String>): String {
        if (base.lowercase() !in taken) return base
        var n = 2
        while ("${base}_$n".lowercase() in taken) n++
        return "${base}_$n"
    }

    actual companion object {
        const val TOKEN_BASE = 0xE000
        const val TOKEN_LIMIT = 0x1900
        const val DEFAULT_FOLDER_NAME = "New Folder"
        private const val MAX_EMOJI_SIDE = 256

        private val instance by lazy { EmojiStore() }
        actual fun get(context: PlatformContext): EmojiStore = instance

        /** True for any char in the private-use range (a possible token). */
        fun isTokenChar(c: Char): Boolean = c.code in TOKEN_BASE until (TOKEN_BASE + TOKEN_LIMIT)
    }
}
