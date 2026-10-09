package com.mediaviewer.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.posix.SEEK_END
import platform.posix.SEEK_SET
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseeko
import platform.posix.ftello
import platform.posix.fwrite

/** Runs [com.mediaviewer.util.MetadataScrub.mp4] on a file, in place. */
@OptIn(ExperimentalForeignApi::class)
internal object IosSeekableFile {
    fun scrub(path: String): Boolean {
        val f = fopen(path, "r+b") ?: return false
        try {
            fseeko(f, 0, SEEK_END)
            val length = ftello(f)
            return com.mediaviewer.util.MetadataScrub.mp4(object : com.mediaviewer.util.MetadataScrub.Seekable {
                override val size: Long = length
                override fun read(at: Long, count: Int): ByteArray {
                    val out = ByteArray(count)
                    if (count == 0) return out
                    fseeko(f, at, SEEK_SET)
                    val got = out.usePinned { fread(it.addressOf(0), 1u, count.toULong(), f) }.toInt()
                    if (got != count) throw IOException("Short read")
                    return out
                }
                override fun write(at: Long, bytes: ByteArray) {
                    if (bytes.isEmpty()) return
                    fseeko(f, at, SEEK_SET)
                    val put = bytes.usePinned { fwrite(it.addressOf(0), 1u, bytes.size.toULong(), f) }.toInt()
                    if (put != bytes.size) throw IOException("Short write")
                }
            })
        } finally {
            fclose(f)
        }
    }
}
