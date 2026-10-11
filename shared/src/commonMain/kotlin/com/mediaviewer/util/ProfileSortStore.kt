package com.mediaviewer.util

import com.mediaviewer.platform.PlatformContext
import com.mediaviewer.platform.PrivateFiles
import com.mediaviewer.repository.BlueskyRepository.ProfilePostEntry

/**
 * A profile's post index (each own post's likes / reposts / saves / comments
 * and which sub-tabs it's in), saved in the app's private storage per
 * account, so sorting that profile is instant on later opens — even after a
 * restart.
 *
 * It's built by reading the account's posts newest to oldest, 100 at a
 * time. If that's interrupted (the profile was closed), what's read so far
 * is kept along with where it stopped, and the next open carries on from
 * there until the whole account has been read once.
 *
 * File: profile_sort/<did>.tsv — first line "v2 <complete 0/1> <cursor>"
 * (tab-separated; the cursor is where to carry on, empty when complete),
 * then one post per line: uri, likes, reposts, saves, comments, sub-tab
 * bits, posted at, numbers read at.
 */
object ProfileSortStore {
    private const val FOLDER = "profile_sort"

    class Stored(val entries: List<ProfilePostEntry>, val complete: Boolean, val cursor: String?)

    private fun name(did: String) = did.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".tsv"

    fun load(context: PlatformContext, did: String): Stored? = runCatching {
        val bytes = PrivateFiles.read(context, PrivateFiles.rootUri(context) + "/" + FOLDER + "/" + name(did)) ?: return null
        val lines = bytes.decodeToString().lines()
        val head = lines.firstOrNull()?.split('\t') ?: return null
        // Files from before resuming existed were always full reads.
        val complete: Boolean
        val cursor: String?
        if (head.firstOrNull() == "v2") {
            complete = head.getOrNull(1) == "1"
            cursor = head.getOrNull(2)?.takeIf { it.isNotBlank() }
        } else {
            complete = true
            cursor = null
        }
        val entries = lines.drop(1).mapNotNull { line ->
            val f = line.split('\t')
            if (f.size < 6 || f[0].isBlank()) null
            else ProfilePostEntry(
                f[0], f[1].toIntOrNull() ?: 0, f[2].toIntOrNull() ?: 0,
                f[3].toIntOrNull() ?: 0, f[4].toIntOrNull() ?: 0, f[5].toIntOrNull() ?: 0,
                f.getOrNull(6)?.toLongOrNull() ?: 0L, f.getOrNull(7)?.toLongOrNull() ?: 0L
            )
        }
        Stored(entries, complete, cursor)
    }.getOrNull()

    fun save(context: PlatformContext, did: String, entries: List<ProfilePostEntry>, complete: Boolean, cursor: String?) {
        val safeCursor = cursor?.takeIf { '\t' !in it && '\n' !in it }.orEmpty()
        val text = buildString {
            append("v2\t").append(if (complete) "1" else "0").append('\t').append(safeCursor).append('\n')
            for (e in entries) {
                append(e.uri).append('\t').append(e.likes).append('\t').append(e.reposts).append('\t')
                    .append(e.saves).append('\t').append(e.replies).append('\t').append(e.kinds).append('\t')
                    .append(e.createdMs).append('\t').append(e.fetchedMs).append('\n')
            }
        }
        runCatching { PrivateFiles.write(context, FOLDER, name(did), text.encodeToByteArray()) }
    }

    fun exists(context: PlatformContext, did: String): Boolean =
        runCatching { PrivateFiles.size(context, PrivateFiles.rootUri(context) + "/" + FOLDER + "/" + name(did)) > 0L }.getOrDefault(false)
}
