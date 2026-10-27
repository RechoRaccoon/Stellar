package com.mediaviewer.util

import com.mediaviewer.platform.PlatformContext
import com.mediaviewer.platform.sharedPreferences

/**
 * The emoji folder and the notes folder you were last on, kept on this device
 * only (their own small preferences file: never backed up or synced, since a
 * folder you left open here says nothing about another device) so each opens
 * where you left it next time.
 */
object LastFolders {
    private const val PREFS = "last_folders"
    private const val KEY_EMOJI = "emoji_folder"
    private const val KEY_NOTES = "notes_folder"

    /** The emoji folder's id last selected; null = the "All" tab. */
    fun emoji(context: PlatformContext): Int? = runCatching {
        context.sharedPreferences(PREFS).getInt(KEY_EMOJI, -1).takeIf { it >= 0 }
    }.getOrNull()

    fun saveEmoji(context: PlatformContext, id: Int?) {
        runCatching { context.sharedPreferences(PREFS).edit().putInt(KEY_EMOJI, id ?: -1).apply() }
    }

    /** The notes folder's id last selected; null = all notes. */
    fun notes(context: PlatformContext): String? = runCatching {
        context.sharedPreferences(PREFS).getString(KEY_NOTES, null)?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    fun saveNotes(context: PlatformContext, id: String?) {
        runCatching { context.sharedPreferences(PREFS).edit().putString(KEY_NOTES, id ?: "").apply() }
    }
}
