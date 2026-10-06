package com.mediaviewer.util

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import com.mediaviewer.platform.PlatformContext
import com.mediaviewer.platform.PlatformUri
import com.mediaviewer.resources.Res
import com.mediaviewer.resources.audiowide
import org.jetbrains.compose.resources.Font

@Composable
actual fun audiowideFontFamily(): FontFamily = FontFamily(Font(Res.font.audiowide))

/**
 * Imported fonts on iOS. They're kept in the app's own folder; a font is
 * remembered by its path, but iOS moves an app's folder when the app is
 * updated, so every path is looked up again by its file name.
 */
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
actual object FontFiles {
    private fun dir(): String = (com.mediaviewer.platform.IosPaths.filesDir() + "/fonts").also {
        platform.Foundation.NSFileManager.defaultManager.createDirectoryAtPath(it, withIntermediateDirectories = true, attributes = null, error = null)
    }

    /** Where the font saved as [path] is now. */
    private fun here(path: String): String = dir() + "/" + path.substringAfterLast('/')

    actual fun exists(path: String): Boolean = com.mediaviewer.platform.localFileExists(here(path))
    actual fun delete(path: String) { com.mediaviewer.platform.deleteLocalFile(here(path)) }
    actual fun family(path: String): FontFamily {
        val bytes = com.mediaviewer.platform.readLocalFile(here(path)) ?: error("unreadable font")
        return FontFamily(androidx.compose.ui.text.platform.Font(identity = path, data = bytes))
    }

    actual fun copyIn(context: PlatformContext, uri: PlatformUri): FontCopied {
        val source = uri.toString().removePrefix("file://")
        val displayName = source.substringAfterLast('/').substringAfter('_', source.substringAfterLast('/')).ifBlank { "Imported Font" }
        val ext = source.substringAfterLast('.', "").lowercase()
        if (ext !in setOf("ttf", "otf", "ttc")) return FontCopied.Error("Please choose a .ttf or .otf font file")
        val bytes = com.mediaviewer.platform.readLocalFile(source) ?: return FontCopied.Error("Couldn't read that font file")
        // Checked before it becomes the app font: a broken file would
        // otherwise break the first page drawn with it.
        val valid = runCatching {
            org.jetbrains.skia.FontMgr.default.makeFromData(org.jetbrains.skia.Data.makeFromBytes(bytes)) != null
        }.getOrDefault(false)
        if (!valid) return FontCopied.Error("That file isn't a font iOS can read")
        val dest = dir() + "/imported_" + com.mediaviewer.platform.currentTimeMillis() + "." + ext
        if (!com.mediaviewer.platform.writeLocalFile(dest, bytes)) return FontCopied.Error("Couldn't save that font file")
        return FontCopied.Ok(dest, displayName)
    }
}
