package com.mediaviewer.util

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import com.mediaviewer.platform.PlatformContext
import com.mediaviewer.platform.PlatformUri
import com.mediaviewer.platform.WebBlobs
import com.mediaviewer.platform.WebFiles
import com.mediaviewer.platform.WebPaths
import com.mediaviewer.platform.webPath
import com.mediaviewer.resources.Res
import com.mediaviewer.resources.audiowide
import org.jetbrains.compose.resources.Font

@Composable
actual fun audiowideFontFamily(): FontFamily = FontFamily(Font(Res.font.audiowide))

/** Imported fonts on the web, kept in the browser's storage. A font is
 *  remembered by its path and looked up by file name (so a backup from
 *  another device still finds it). */
actual object FontFiles {
    private const val DIR = "${WebPaths.FILES}/fonts"

    private fun here(path: String): String = DIR + "/" + path.substringAfterLast('/').substringAfterLast('\\')

    actual fun exists(path: String): Boolean = WebFiles.exists(here(path))
    actual fun delete(path: String) = WebFiles.delete(here(path))
    actual fun family(path: String): FontFamily {
        val bytes = WebFiles.read(here(path)) ?: error("Font not found")
        return FontFamily(androidx.compose.ui.text.platform.Font(identity = path, data = bytes))
    }

    actual fun copyIn(context: PlatformContext, uri: PlatformUri): FontCopied {
        val source = uri.webPath ?: return FontCopied.Error("Couldn't read that font file")
        val name = WebBlobs.nameOf(source) ?: source.substringAfterLast('/')
        val displayName = name.ifBlank { "Imported Font" }
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext !in setOf("ttf", "otf", "ttc")) return FontCopied.Error("Please choose a .ttf or .otf font file")
        val bytes = WebFiles.read(source) ?: return FontCopied.Error("Couldn't read that font file")
        // Checked before it becomes the app font: a broken file would
        // otherwise break the first page drawn with it.
        val valid = runCatching {
            org.jetbrains.skia.FontMgr.default.makeFromData(org.jetbrains.skia.Data.makeFromBytes(bytes)) != null
        }.getOrDefault(false)
        if (!valid) return FontCopied.Error("That file isn't a font Stellar can read")
        val dest = DIR + "/imported_" + com.mediaviewer.platform.currentTimeMillis() + "." + ext
        WebFiles.write(dest, bytes)
        return FontCopied.Ok(dest, displayName)
    }
}
