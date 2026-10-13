package com.mediaviewer.ui.compat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.mediaviewer.platform.DesktopOs
import com.mediaviewer.platform.DesktopUri
import com.mediaviewer.platform.PlatformUri
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.swing.JFileChooser
import javax.swing.SwingUtilities

/** The computer's own Open / Save dialogs behind the pickers. */
@Suppress("UNCHECKED_CAST")
@Composable
actual fun <I, O> rememberLauncherForActivityResult(
    contract: ResultContract<I, O>,
    onResult: (O) -> Unit,
): ActivityResultLauncher<I> {
    val latest = rememberUpdatedState(onResult)
    return remember(contract) {
        object : ActivityResultLauncher<I> {
            override fun launch(input: I) {
                val deliver: (Any?) -> Unit = { result -> SwingUtilities.invokeLater { latest.value(result as O) } }
                // (After the click that asked for it has finished.)
                SwingUtilities.invokeLater { DesktopPickers.launch(contract, input, deliver) }
            }
        }
    }
}

/**
 * Picking files on a computer:
 *  - photos / videos / any file → the system's Open dialog (several at once
 *    where several are allowed); the file is used where it is;
 *  - "save a file" (dataset export, backups) → the system's Save dialog;
 *  - a folder → the folder chooser;
 *  - permissions (only the Android audio visualizer asks) → denied.
 */
object DesktopPickers {
    /** The window dialogs belong to (set by the app). */
    @Volatile var owner: Frame? = null

    private var lastFolder: String? = null

    fun launch(contract: ResultContract<*, *>, input: Any?, deliver: (Any?) -> Unit) {
        when (contract) {
            is ActivityResultContracts.PickVisualMedia -> {
                val type = (input as? PickVisualMediaRequest)?.mediaType ?: ActivityResultContracts.PickVisualMedia.ImageAndVideo
                deliver(open("Choose a picture or video", extensionsFor(type), multiple = false).firstOrNull())
            }
            is ActivityResultContracts.PickMultipleVisualMedia -> {
                val type = (input as? PickVisualMediaRequest)?.mediaType ?: ActivityResultContracts.PickVisualMedia.ImageAndVideo
                deliver(open("Choose pictures or videos", extensionsFor(type), multiple = true).take(contract.maxItems))
            }
            is ActivityResultContracts.GetContent -> deliver(open("Choose a file", extensionsForMime(arrayOf(input as? String ?: "*/*")), false).firstOrNull())
            is ActivityResultContracts.OpenDocument -> {
                val mimes = input as? Array<String> ?: arrayOf("*/*")
                deliver(open("Choose a file", extensionsForMime(mimes), false).firstOrNull())
            }
            is ActivityResultContracts.CreateDocument -> deliver(save(input as? String ?: "Stellar-file"))
            is ActivityResultContracts.RequestPermission -> deliver(false)
            is ActivityResultContracts.OpenDocumentTree -> deliver(folder())
        }
    }

    private val IMAGES = listOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif")
    private val VIDEOS = listOf("mp4", "m4v", "mov", "webm", "mkv", "avi")
    private val AUDIO = listOf("mp3", "wav", "ogg", "m4a", "aac", "flac", "opus")

    private fun extensionsFor(type: ActivityResultContracts.PickVisualMedia.VisualMediaType): List<String> = when (type) {
        ActivityResultContracts.PickVisualMedia.ImageOnly -> IMAGES
        ActivityResultContracts.PickVisualMedia.VideoOnly -> VIDEOS
        else -> IMAGES + VIDEOS
    }

    /** File extensions for MIME types; empty = anything. */
    private fun extensionsForMime(mimes: Array<String>): List<String> {
        val out = LinkedHashSet<String>()
        for (m in mimes) when {
            m == "*/*" || m.isBlank() -> return emptyList()
            m == "image/*" -> out += IMAGES
            m == "video/*" -> out += VIDEOS
            m == "audio/*" -> out += AUDIO
            m == "application/json" -> out += "json"
            m == "text/plain" -> out += "txt"
            m == "text/csv" -> out += "csv"
            m == "application/zip" -> out += "zip"
            m.startsWith("font/") || m == "application/x-font-ttf" || m == "application/vnd.ms-opentype" -> out += listOf("ttf", "otf", "ttc")
            m.startsWith("image/") -> out += m.substringAfter('/').let { if (it == "jpeg") listOf("jpg", "jpeg") else listOf(it) }
            m.startsWith("video/") -> out += VIDEOS
            m.startsWith("audio/") -> out += AUDIO
            else -> return emptyList()
        }
        return out.toList()
    }

    private fun open(title: String, extensions: List<String>, multiple: Boolean): List<PlatformUri> {
        val dialog = FileDialog(owner, title, FileDialog.LOAD)
        dialog.isMultipleMode = multiple
        lastFolder?.let { dialog.directory = it }
        if (extensions.isNotEmpty()) {
            val set = extensions.map { it.lowercase() }.toSet()
            // (macOS honours the filter; Windows goes by the name pattern.)
            dialog.setFilenameFilter { _, name -> name.substringAfterLast('.', "").lowercase() in set }
            if (DesktopOs.isWindows) dialog.file = extensions.joinToString(";") { "*.$it" }
        }
        dialog.isVisible = true
        val files = dialog.files?.toList().orEmpty().filter { it.isFile }
        files.firstOrNull()?.parentFile?.let { lastFolder = it.absolutePath }
        return files.map { DesktopUri.of(it) }
    }

    private fun save(suggestedName: String): PlatformUri? {
        val dialog = FileDialog(owner, "Save", FileDialog.SAVE)
        dialog.directory = lastFolder ?: File(System.getProperty("user.home"), "Documents").takeIf { it.isDirectory }?.absolutePath
        dialog.file = suggestedName.replace('/', '-').replace('\\', '-').ifBlank { "Stellar-file" }
        dialog.isVisible = true
        val name = dialog.file ?: return null
        val dir = dialog.directory ?: return null
        lastFolder = dir
        return DesktopUri.of(File(dir, name))
    }

    private fun folder(): PlatformUri? {
        val chooser = JFileChooser(lastFolder ?: System.getProperty("user.home")).apply {
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            dialogTitle = "Choose a folder"
        }
        if (chooser.showOpenDialog(owner) != JFileChooser.APPROVE_OPTION) return null
        val dir = chooser.selectedFile ?: return null
        lastFolder = dir.absolutePath
        return DesktopUri.of(dir)
    }
}
