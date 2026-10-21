package com.mediaviewer.util

/**
 * A plain .zip, written in memory: every file stored as it is (pictures
 * are compressed already, so squeezing them again saves nothing), with
 * UTF-8 names and folders made from the "/" in each name. Opens in every
 * system's own file manager.
 */
class ZipWriter {
    private class Entry(val name: ByteArray, val crc: Int, val size: Int, val offset: Int)

    private val out = ByteBuilder()
    private val entries = ArrayList<Entry>()
    private val used = HashSet<String>()

    /** Adds [bytes] as [path] ("All/1 smile.png"). A name already used gets
     *  " (2)", " (3)"… before its extension. */
    fun add(path: String, bytes: ByteArray) {
        var name = path
        if (name in used) {
            val dot = path.lastIndexOf('.').takeIf { it > path.lastIndexOf('/') } ?: path.length
            val base = path.substring(0, dot); val ext = path.substring(dot)
            var n = 2
            while ("$base ($n)$ext" in used) n++
            name = "$base ($n)$ext"
        }
        used += name
        val nameBytes = name.encodeToByteArray()
        val crc = crc32(bytes)
        val offset = out.size
        out.int(0x04034b50); out.short(20); out.short(0x0800); out.short(0); out.short(0); out.short(0x21)
        out.int(crc); out.int(bytes.size); out.int(bytes.size); out.short(nameBytes.size); out.short(0)
        out.bytes(nameBytes); out.bytes(bytes)
        entries += Entry(nameBytes, crc, bytes.size, offset)
    }

    /** The finished file. */
    fun finish(): ByteArray {
        val dirStart = out.size
        for (e in entries) {
            out.int(0x02014b50); out.short(20); out.short(20); out.short(0x0800); out.short(0); out.short(0); out.short(0x21)
            out.int(e.crc); out.int(e.size); out.int(e.size); out.short(e.name.size); out.short(0); out.short(0)
            out.short(0); out.short(0); out.int(0); out.int(e.offset); out.bytes(e.name)
        }
        val dirSize = out.size - dirStart
        out.int(0x06054b50); out.short(0); out.short(0); out.short(entries.size); out.short(entries.size)
        out.int(dirSize); out.int(dirStart); out.short(0)
        return out.toByteArray()
    }

    private class ByteBuilder {
        private var buf = ByteArray(64 * 1024)
        var size = 0
            private set

        private fun room(n: Int) {
            if (size + n <= buf.size) return
            var cap = buf.size
            while (cap < size + n) cap *= 2
            buf = buf.copyOf(cap)
        }
        fun short(v: Int) { room(2); buf[size++] = v.toByte(); buf[size++] = (v shr 8).toByte() }
        fun int(v: Int) { short(v and 0xFFFF); short((v ushr 16) and 0xFFFF) }
        fun bytes(b: ByteArray) { room(b.size); b.copyInto(buf, size); size += b.size }
        fun toByteArray(): ByteArray = buf.copyOf(size)
    }

    private companion object {
        val TABLE = IntArray(256) { n ->
            var c = n
            repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1 }
            c
        }

        fun crc32(bytes: ByteArray): Int {
            var c = -1
            for (b in bytes) c = TABLE[(c xor b.toInt()) and 0xFF] xor (c ushr 8)
            return c.inv()
        }
    }
}
