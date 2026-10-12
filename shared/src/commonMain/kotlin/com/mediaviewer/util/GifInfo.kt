package com.mediaviewer.util

/**
 * Reads just enough of a GIF to tell whether it's animated (more than one
 * frame) and its size — straight from the bytes, so it doesn't matter what
 * the file is called or what type the system says it is.
 */
object GifInfo {
    fun isGif(b: ByteArray): Boolean =
        b.size >= 6 && b[0] == 'G'.code.toByte() && b[1] == 'I'.code.toByte() && b[2] == 'F'.code.toByte() && b[3] == '8'.code.toByte()

    fun size(b: ByteArray): Pair<Int, Int> {
        if (!isGif(b) || b.size < 10) return 0 to 0
        fun u16(i: Int) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
        return u16(6) to u16(8)
    }

    /** Frames in the GIF, counting up to [limit] (2 is enough to know it moves). */
    fun frameCount(b: ByteArray, limit: Int = Int.MAX_VALUE): Int {
        if (!isGif(b) || b.size < 13) return 0
        fun at(i: Int) = if (i < b.size) b[i].toInt() and 0xFF else -1
        var p = 13
        val flags = at(10)
        if (flags and 0x80 != 0) p += 3 * (1 shl ((flags and 0x07) + 1))
        fun skipSubBlocks() {
            while (p < b.size) {
                val n = at(p); p++
                if (n <= 0) break
                p += n
            }
        }
        var frames = 0
        while (p < b.size && frames < limit) {
            when (at(p)) {
                0x21 -> { p += 2; skipSubBlocks() }            // extension
                0x2C -> {                                        // image
                    frames++
                    val f = at(p + 9)
                    p += 10
                    if (f >= 0 && f and 0x80 != 0) p += 3 * (1 shl ((f and 0x07) + 1))
                    p++                                          // LZW code size
                    skipSubBlocks()
                }
                else -> return frames                            // 0x3B trailer, or broken
            }
        }
        return frames
    }

    fun isAnimated(b: ByteArray): Boolean = frameCount(b, limit = 2) >= 2
}
