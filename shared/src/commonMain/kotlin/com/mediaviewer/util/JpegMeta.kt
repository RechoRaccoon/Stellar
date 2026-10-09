package com.mediaviewer.util

/**
 * Small, lossless JPEG helpers for uploads (pure Kotlin, both platforms).
 *
 * A photo straight off a phone carries EXIF: the camera, the time and very
 * often the GPS position it was taken at. Bluesky keeps the uploaded file
 * as-is on the PDS, where anyone can download it, so Stellar takes that
 * metadata out before uploading — without re-encoding the picture, so not
 * a pixel changes. (Bluesky's own app strips it too.)
 */
object JpegMeta {
    fun isJpeg(bytes: ByteArray): Boolean =
        bytes.size > 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()

    /**
     * The EXIF orientation (1–8), or 1 when there is none. 1 means the
     * pixels are already upright; anything else needs the picture turned,
     * which [stripMetadata] can't do — callers re-encode instead.
     */
    fun orientation(bytes: ByteArray): Int {
        if (!isJpeg(bytes)) return 1
        var i = 2
        while (i + 4 <= bytes.size) {
            if (bytes[i] != 0xFF.toByte()) return 1
            val marker = bytes[i + 1].toInt() and 0xFF
            if (marker == 0xD8 || marker in 0xD0..0xD7 || marker == 0x01) { i += 2; continue }
            if (marker == 0xDA || marker == 0xD9) return 1
            val len = u16(bytes, i + 2, true)
            if (len < 2) return 1
            if (marker == 0xE1 && i + 4 + 6 <= bytes.size && isExifHeader(bytes, i + 4)) {
                return readOrientation(bytes, i + 10, i + 2 + len).takeIf { it in 1..8 } ?: 1
            }
            i += 2 + len
        }
        return 1
    }

    /**
     * The JPEG without its EXIF/XMP (APP1), IPTC (APP13) and comment
     * segments. Colour profiles (APP2) and everything else are kept. Null
     * when the file isn't a JPEG this can read safely.
     */
    fun stripMetadata(bytes: ByteArray): ByteArray? {
        if (!isJpeg(bytes)) return null
        val out = ArrayList<ByteArray>()
        out += byteArrayOf(0xFF.toByte(), 0xD8.toByte())
        var i = 2
        while (i + 4 <= bytes.size) {
            if (bytes[i] != 0xFF.toByte()) return null
            val marker = bytes[i + 1].toInt() and 0xFF
            if (marker == 0xFF) { i += 1; continue } // fill byte
            if (marker == 0xDA) {
                // Start of scan: the picture data runs to the end of the file.
                out += bytes.copyOfRange(i, bytes.size)
                return concat(out)
            }
            if (marker == 0xD9) { out += byteArrayOf(0xFF.toByte(), 0xD9.toByte()); return concat(out) }
            val len = u16(bytes, i + 2, true)
            if (len < 2 || i + 2 + len > bytes.size) return null
            val drop = marker == 0xE1 || marker == 0xED || marker == 0xFE
            if (!drop) out += bytes.copyOfRange(i, i + 2 + len)
            i += 2 + len
        }
        return null
    }

    private fun isExifHeader(b: ByteArray, at: Int): Boolean =
        b[at] == 'E'.code.toByte() && b[at + 1] == 'x'.code.toByte() && b[at + 2] == 'i'.code.toByte() &&
            b[at + 3] == 'f'.code.toByte() && b[at + 4] == 0.toByte() && b[at + 5] == 0.toByte()

    /** Tag 0x0112 in IFD0 of the TIFF block that starts at [tiff]. */
    private fun readOrientation(b: ByteArray, tiff: Int, end: Int): Int {
        if (tiff + 8 > end || end > b.size) return 1
        val big = when {
            b[tiff] == 'M'.code.toByte() && b[tiff + 1] == 'M'.code.toByte() -> true
            b[tiff] == 'I'.code.toByte() && b[tiff + 1] == 'I'.code.toByte() -> false
            else -> return 1
        }
        val ifd = tiff + u32(b, tiff + 4, big)
        if (ifd + 2 > end) return 1
        val count = u16(b, ifd, big)
        for (n in 0 until count) {
            val e = ifd + 2 + n * 12
            if (e + 12 > end) return 1
            if (u16(b, e, big) == 0x0112) return u16(b, e + 8, big)
        }
        return 1
    }

    private fun u16(b: ByteArray, at: Int, big: Boolean): Int {
        if (at + 2 > b.size) return 0
        val x = b[at].toInt() and 0xFF
        val y = b[at + 1].toInt() and 0xFF
        return if (big) (x shl 8) or y else (y shl 8) or x
    }

    private fun u32(b: ByteArray, at: Int, big: Boolean): Int {
        if (at + 4 > b.size) return 0
        val v = IntArray(4) { b[at + it].toInt() and 0xFF }
        return if (big) (v[0] shl 24) or (v[1] shl 16) or (v[2] shl 8) or v[3]
        else (v[3] shl 24) or (v[2] shl 16) or (v[1] shl 8) or v[0]
    }

    private fun concat(parts: List<ByteArray>): ByteArray {
        val out = ByteArray(parts.sumOf { it.size })
        var o = 0
        for (p in parts) { p.copyInto(out, o); o += p.size }
        return out
    }
}
