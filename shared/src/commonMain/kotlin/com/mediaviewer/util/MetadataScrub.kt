package com.mediaviewer.util

/**
 * Takes personal metadata out of every file Stellar uploads, without
 * touching the picture or the video itself.
 *
 * Phones and editing apps tuck extra information into media files: where a
 * photo or video was taken (GPS), when, the phone's make and model, the
 * software, sometimes the owner's name or comments. Bluesky keeps uploaded
 * files as they are, and anyone can download them, so none of that leaves
 * the phone:
 *  - JPEG: EXIF/GPS, XMP, IPTC and comments (see [JpegMeta]).
 *  - PNG: text chunks (tEXt, zTXt, iTXt — XMP lives there too), eXIf, tIME.
 *  - WebP: the EXIF and XMP chunks.
 *  - GIF: comments and XMP.
 *  - MP4 / MOV: the user-data and metadata boxes (location, make, model,
 *    software, dates, XMP), and the creation/modification times in the
 *    movie, track and media headers are zeroed. Done in place, box by box,
 *    so nothing in the file moves and the video is untouched.
 * Colour profiles and everything needed to show the picture are kept.
 */
object MetadataScrub {
    /** [bytes] without personal metadata (the same bytes if there's none,
     *  or the format isn't one of the above). */
    fun image(bytes: ByteArray): ByteArray = runCatching {
        when {
            JpegMeta.isJpeg(bytes) -> JpegMeta.stripMetadata(bytes)
            isPng(bytes) -> png(bytes)
            isWebp(bytes) -> webp(bytes)
            isGif(bytes) -> gif(bytes)
            else -> null
        }
    }.getOrNull() ?: bytes

    // ── PNG ──

    private val PNG_SIG = byteArrayOf(0x89.toByte(), 'P'.b, 'N'.b, 'G'.b, 0x0D, 0x0A, 0x1A, 0x0A)
    private val PNG_DROP = setOf("tEXt", "zTXt", "iTXt", "eXIf", "tIME")
    private fun isPng(b: ByteArray) = b.size > 8 && (0 until 8).all { b[it] == PNG_SIG[it] }

    private fun png(b: ByteArray): ByteArray? {
        val out = Out()
        out.add(b, 0, 8)
        var i = 8
        while (i + 12 <= b.size) {
            val len = be32(b, i)
            if (len < 0 || i + 12 + len > b.size) return null
            val type = b.decodeToString(i + 4, i + 8)
            if (type !in PNG_DROP) out.add(b, i, 12 + len)
            i += 12 + len
            if (type == "IEND") return out.bytes()
        }
        return null
    }

    // ── WebP ──

    private fun isWebp(b: ByteArray) = b.size > 12 && b.decodeToString(0, 4) == "RIFF" && b.decodeToString(8, 12) == "WEBP"

    private fun webp(b: ByteArray): ByteArray? {
        val body = Out()
        var i = 12
        var dropped = false
        while (i + 8 <= b.size) {
            val type = b.decodeToString(i, i + 4)
            val len = le32(b, i + 4)
            val padded = len + (len and 1)
            if (len < 0 || i + 8 + len > b.size) return null
            if (type == "EXIF" || type == "XMP ") {
                dropped = true
            } else {
                val chunk = b.copyOfRange(i, minOf(b.size, i + 8 + padded))
                // VP8X says which extras follow: EXIF and XMP are gone now.
                if (type == "VP8X" && chunk.size > 8) chunk[8] = (chunk[8].toInt() and 0x08.inv() and 0x04.inv()).toByte()
                body.add(chunk, 0, chunk.size)
            }
            i += 8 + padded
        }
        if (!dropped) return null
        val rest = body.bytes()
        val out = ByteArray(12 + rest.size)
        b.copyInto(out, 0, 0, 12)
        val riff = out.size - 8
        out[4] = riff.toByte(); out[5] = (riff shr 8).toByte(); out[6] = (riff shr 16).toByte(); out[7] = (riff shr 24).toByte()
        rest.copyInto(out, 12)
        return out
    }

    // ── GIF ──

    private fun isGif(b: ByteArray) = b.size > 13 && b.decodeToString(0, 4) == "GIF8"

    private fun gif(b: ByteArray): ByteArray? {
        val out = Out()
        var i = 13
        val flags = b[10].toInt() and 0xFF
        if (flags and 0x80 != 0) i += 3 * (1 shl ((flags and 0x07) + 1))
        if (i > b.size) return null
        out.add(b, 0, i)
        fun subBlocksEnd(from: Int): Int {
            var p = from
            while (p < b.size) {
                val n = b[p].toInt() and 0xFF
                p += 1 + n
                if (n == 0) return p
            }
            return -1
        }
        while (i < b.size) {
            when (b[i].toInt() and 0xFF) {
                0x3B -> { out.add(b, i, 1); return out.bytes() }
                0x21 -> {
                    if (i + 2 > b.size) return null
                    val label = b[i + 1].toInt() and 0xFF
                    val end = subBlocksEnd(i + 2).takeIf { it > 0 } ?: return null
                    val xmp = label == 0xFF && i + 14 <= b.size && b.decodeToString(i + 3, i + 11) == "XMP Data"
                    if (label != 0xFE && !xmp) out.add(b, i, end - i)
                    i = end
                }
                0x2C -> {
                    if (i + 10 > b.size) return null
                    var p = i + 10
                    val lf = b[i + 9].toInt() and 0xFF
                    if (lf and 0x80 != 0) p += 3 * (1 shl ((lf and 0x07) + 1))
                    p += 1 // LZW minimum code size
                    val end = subBlocksEnd(p).takeIf { it > 0 } ?: return null
                    out.add(b, i, end - i)
                    i = end
                }
                else -> return null
            }
        }
        return null
    }

    // ── MP4 / MOV ──

    /** A file opened for reading and writing in place. */
    interface Seekable {
        val size: Long
        fun read(at: Long, count: Int): ByteArray
        fun write(at: Long, bytes: ByteArray)
    }

    private val CONTAINERS = setOf("moov", "trak", "mdia", "minf", "edts")
    private val BLANK = setOf("udta", "meta")
    private val TIMED = setOf("mvhd", "tkhd", "mdhd")
    private val XMP_UUID = byteArrayOf(
        0xBE.toByte(), 0x7A, 0xCF.toByte(), 0xCB.toByte(), 0x97.toByte(), 0xA9.toByte(), 0x42, 0xE8.toByte(),
        0x9C.toByte(), 0x71, 0x99.toByte(), 0x94.toByte(), 0x91.toByte(), 0xE3.toByte(), 0xAF.toByte(), 0xAC.toByte()
    )

    /** Scrubs an MP4/MOV file in place. False if it isn't one this can read. */
    fun mp4(file: Seekable): Boolean = runCatching {
        if (file.size < 16) return false
        val first = file.read(4, 4).decodeToString()
        if (first != "ftyp" && first != "moov" && first != "wide" && first != "mdat" && first != "free") return false
        walk(file, 0, file.size, topLevel = true)
        true
    }.getOrDefault(false)

    private fun walk(f: Seekable, start: Long, end: Long, topLevel: Boolean) {
        var pos = start
        while (pos + 8 <= end) {
            val head = f.read(pos, 16.coerceAtMost((end - pos).toInt()))
            var size = be32(head, 0).toLong() and 0xFFFFFFFFL
            val type = head.decodeToString(4, 8)
            var header = 8
            if (size == 1L && head.size >= 16) { size = be64(head, 8); header = 16 }
            else if (size == 0L) size = end - pos
            if (size < header || pos + size > end) return
            when {
                type in CONTAINERS -> walk(f, pos + header, pos + size, topLevel = false)
                type in BLANK && !topLevel -> blank(f, pos, size, header)
                type == "meta" && topLevel -> blank(f, pos, size, header)
                type == "uuid" && size >= header + 16 && f.read(pos + header, 16).contentEquals(XMP_UUID) -> blank(f, pos, size, header)
                type in TIMED -> zeroTimes(f, pos + header)
            }
            pos += size
        }
    }

    /** The box becomes a "free" (ignored) box full of zeros — same size,
     *  so nothing after it moves. */
    private fun blank(f: Seekable, pos: Long, size: Long, header: Int) {
        f.write(pos + 4, "free".encodeToByteArray())
        var p = pos + header
        val stop = pos + size
        val zeros = ByteArray(64 * 1024)
        while (p < stop) {
            val n = minOf(zeros.size.toLong(), stop - p).toInt()
            f.write(p, if (n == zeros.size) zeros else ByteArray(n))
            p += n
        }
    }

    /** mvhd / tkhd / mdhd: version, flags, then creation and modification
     *  times (32-bit for version 0, 64-bit for version 1). */
    private fun zeroTimes(f: Seekable, body: Long) {
        val version = f.read(body, 1)[0].toInt()
        f.write(body + 4, ByteArray(if (version == 1) 16 else 8))
    }

    // ── helpers ──

    private val Char.b: Byte get() = code.toByte()
    private fun be32(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)
    private fun le32(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or ((b[at + 2].toInt() and 0xFF) shl 16) or ((b[at + 3].toInt() and 0xFF) shl 24)
    private fun be64(b: ByteArray, at: Int): Long =
        ((be32(b, at).toLong() and 0xFFFFFFFFL) shl 32) or (be32(b, at + 4).toLong() and 0xFFFFFFFFL)

    private class Out {
        private val parts = ArrayList<ByteArray>()
        fun add(b: ByteArray, from: Int, count: Int) { parts += b.copyOfRange(from, from + count) }
        fun bytes(): ByteArray {
            val out = ByteArray(parts.sumOf { it.size })
            var o = 0
            for (p in parts) { p.copyInto(out, o); o += p.size }
            return out
        }
    }
}
