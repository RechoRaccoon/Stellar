package com.mediaviewer.util

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import com.mediaviewer.platform.DesktopPaths
import com.mediaviewer.platform.PlatformContext
import com.mediaviewer.platform.PlatformUri
import com.mediaviewer.platform.file
import com.mediaviewer.resources.Res
import com.mediaviewer.resources.audiowide
import org.jetbrains.compose.resources.Font
import java.io.File

@Composable
actual fun audiowideFontFamily(): FontFamily = FontFamily(Font(Res.font.audiowide))

/** Imported fonts on a computer, kept in Stellar's data folder. A font is
 *  remembered by its path; it's looked up by file name in that folder, so
 *  a moved data folder (or a backup from another computer) still finds it. */
actual object FontFiles {
    private fun dir(): File = File(DesktopPaths.filesDir, "fonts").apply { mkdirs() }

    /** Where the font saved as [path] is now. */
    private fun here(path: String): File = File(dir(), path.substringAfterLast('/').substringAfterLast('\\'))

    actual fun exists(path: String): Boolean = here(path).isFile
    actual fun delete(path: String) { here(path).delete() }
    actual fun family(path: String): FontFamily {
        val bytes = here(path).readBytes()
        return FontFamily(androidx.compose.ui.text.platform.Font(identity = path, data = bytes))
    }

    actual fun copyIn(context: PlatformContext, uri: PlatformUri): FontCopied {
        val source = uri.file ?: return FontCopied.Error("Couldn't read that font file")
        val displayName = source.name.ifBlank { "Imported Font" }
        val ext = source.extension.lowercase()
        if (ext !in setOf("ttf", "otf", "ttc")) return FontCopied.Error("Please choose a .ttf or .otf font file")
        val bytes = runCatching { source.readBytes() }.getOrNull() ?: return FontCopied.Error("Couldn't read that font file")
        // Checked before it becomes the app font: a broken file would
        // otherwise break the first page drawn with it.
        val valid = runCatching {
            org.jetbrains.skia.FontMgr.default.makeFromData(org.jetbrains.skia.Data.makeFromBytes(bytes)) != null
        }.getOrDefault(false)
        if (!valid) return FontCopied.Error("That file isn't a font Stellar can read")
        val dest = File(dir(), "imported_" + com.mediaviewer.platform.currentTimeMillis() + "." + ext)
        if (runCatching { dest.writeBytes(bytes) }.isFailure) return FontCopied.Error("Couldn't save that font file")
        return FontCopied.Ok(dest.absolutePath, displayName)
    }
}
