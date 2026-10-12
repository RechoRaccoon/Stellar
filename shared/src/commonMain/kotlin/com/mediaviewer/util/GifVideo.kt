package com.mediaviewer.util

/**
 * An animated GIF turned into video frames, for posting it the way Bluesky
 * takes GIFs: as a looping video. The GIF is decoded here (the same on
 * Android and iOS), each frame composited the way a browser shows it
 * (transparency over black, the GIF's own disposal rules and timing),
 * scaled to the video's size, and handed to the platform's H.264 encoder
 * with its time. Very short GIFs are played through a few times so the
 * clip is at least a second long (it loops anyway, so it looks the same).
 */
object GifVideo {
    /** Video size for a [gw]×[gh] GIF: within 1080 on the long side, at
     *  least 240 on the short side, both multiples of [align] (what the
     *  encoder needs; the post carries the GIF's exact shape anyway). */
    fun targetSize(gw: Int, gh: Int, align: Int = 2): Pair<Int, Int> {
        val w0 = gw.coerceAtLeast(1).toDouble()
        val h0 = gh.coerceAtLeast(1).toDouble()
        var scale = minOf(1.0, 1080.0 / maxOf(w0, h0))
        if (minOf(w0, h0) * scale < 240.0) scale = minOf(240.0 / minOf(w0, h0), 1080.0 / maxOf(w0, h0))
        val a = align.coerceAtLeast(2).toDouble()
        fun r(v: Double) = (((v / a) + 0.5).toInt().coerceAtLeast(1)) * a.toInt()
        return r(w0 * scale) to r(h0 * scale)
    }

    /**
     * Calls [emit] with each frame (ARGB, [w]×[h], reused between calls)
     * and its time in ms, then once more with the last frame at the end
     * time so the last frame keeps its length. Returns the end time.
     * Throws when the GIF can't be read.
     */
    fun frames(gif: ByteArray, w: Int, h: Int, emit: (IntArray, Long) -> Unit): Long {
        // How long one pass takes (and that there's something to show).
        var passMs = 0L
        var count = 0
        GifDecoder.decode(gif) { _, _, _, delay -> passMs += delay; count++ }
        if (count == 0) error("This GIF can't be read")
        val passes = if (passMs >= 1000L) 1 else ((1000L + passMs - 1) / passMs.coerceAtLeast(1L)).toInt().coerceIn(1, 20)
        val out = IntArray(w * h)
        var t = 0L
        repeat(passes) {
            GifDecoder.decode(gif) { canvas, cw, ch, delay ->
                scale(canvas, cw, ch, out, w, h)
                emit(out, t)
                t += delay
            }
        }
        emit(out, t)
        return t
    }

    /** Bilinear scale of [src] (sw×sh) into [dst] (dw×dh). */
    private fun scale(src: IntArray, sw: Int, sh: Int, dst: IntArray, dw: Int, dh: Int) {
        val xr = sw.toFloat() / dw
        val yr = sh.toFloat() / dh
        for (y in 0 until dh) {
            val fy = ((y + 0.5f) * yr - 0.5f).coerceIn(0f, (sh - 1).toFloat())
            val y0 = fy.toInt(); val y1 = minOf(y0 + 1, sh - 1); val wy = fy - y0
            for (x in 0 until dw) {
                val fx = ((x + 0.5f) * xr - 0.5f).coerceIn(0f, (sw - 1).toFloat())
                val x0 = fx.toInt(); val x1 = minOf(x0 + 1, sw - 1); val wx = fx - x0
                val a = src[y0 * sw + x0]; val b = src[y0 * sw + x1]
                val c = src[y1 * sw + x0]; val d = src[y1 * sw + x1]
                fun ch(sh2: Int): Int {
                    val ca = (a shr sh2) and 0xFF; val cb = (b shr sh2) and 0xFF
                    val cc = (c shr sh2) and 0xFF; val cd = (d shr sh2) and 0xFF
                    val top = ca + (cb - ca) * wx
                    val bottom = cc + (cd - cc) * wx
                    return (top + (bottom - top) * wy + 0.5f).toInt().coerceIn(0, 255)
                }
                dst[y * dw + x] = (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
            }
        }
    }
}

/**
 * A plain GIF decoder: every frame, composited onto the full canvas
 * (opaque; transparent areas are black), with its delay in ms (a delay
 * under 20 ms counts as 100 ms, as browsers do).
 */
object GifDecoder {
    fun decode(b: ByteArray, onFrame: (canvas: IntArray, width: Int, height: Int, delayMs: Long) -> Unit) {
        if (!GifInfo.isGif(b) || b.size < 13) error("Not a GIF")
        var p = 6
        fun u8(): Int = if (p < b.size) (b[p++].toInt() and 0xFF) else { p++; 0 }
        fun u16(): Int { val lo = u8(); val hi = u8(); return lo or (hi shl 8) }
        val width = u16().coerceAtLeast(1)
        val height = u16().coerceAtLeast(1)
        if (width.toLong() * height > 25_000_000L) error("This GIF is too big")
        val flags = u8()
        u8() // background colour index
        u8() // aspect
        fun readTable(size: Int): IntArray = IntArray(size) {
            val r = u8(); val g = u8(); val bl = u8()
            (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
        }
        val globalTable = if (flags and 0x80 != 0) readTable(1 shl ((flags and 0x07) + 1)) else null
        val black = 0xFF000000.toInt()
        val canvas = IntArray(width * height) { black }
        var saved: IntArray? = null
        var disposal = 0
        var transparent = -1
        var delay = 0

        val prefix = ShortArray(4096)
        val suffix = ByteArray(4096)
        val stack = ByteArray(4097)

        while (p < b.size) {
            when (u8()) {
                0x21 -> {
                    val label = u8()
                    if (label == 0xF9) {
                        val size = u8()
                        val start = p
                        val packed = u8()
                        delay = u16()
                        val ti = u8()
                        disposal = (packed shr 2) and 0x07
                        transparent = if (packed and 0x01 != 0) ti else -1
                        p = start + size
                    }
                    // Sub-blocks (the rest of any extension).
                    while (p < b.size) { val n = u8(); if (n == 0) break; p += n }
                }
                0x2C -> {
                    val ix = u16(); val iy = u16(); val iw = u16(); val ih = u16()
                    val f = u8()
                    val table = (if (f and 0x80 != 0) readTable(1 shl ((f and 0x07) + 1)) else globalTable) ?: IntArray(256) { black }
                    val interlaced = f and 0x40 != 0
                    val minCode = u8().coerceIn(2, 11)
                    // The frame's compressed data, joined up.
                    var dataLen = 0
                    run {
                        var q = p
                        while (q < b.size) { val n = b[q].toInt() and 0xFF; q++; if (n == 0) break; dataLen += n; q += n }
                    }
                    val data = ByteArray(dataLen)
                    var at = 0
                    while (p < b.size) {
                        val n = u8(); if (n == 0) break
                        val take = minOf(n, b.size - p)
                        b.copyInto(data, at, p, p + take); at += take; p += n
                    }
                    val pixels = ByteArray(iw * ih)
                    lzw(data, minCode, pixels, prefix, suffix, stack)

                    if (disposal == 3) saved = canvas.copyOf()
                    // Row order: interlaced GIFs store rows in four passes.
                    val rowOrder = IntArray(ih)
                    if (interlaced) {
                        var r = 0
                        for ((start, step) in listOf(0 to 8, 4 to 8, 2 to 4, 1 to 2)) {
                            var y = start
                            while (y < ih && r < ih) { rowOrder[r++] = y; y += step }
                        }
                    } else for (i in 0 until ih) rowOrder[i] = i
                    for (r in 0 until ih) {
                        val cy = iy + rowOrder[r]
                        if (cy !in 0 until height) continue
                        for (x in 0 until iw) {
                            val cx = ix + x
                            if (cx !in 0 until width) continue
                            val idx = pixels[r * iw + x].toInt() and 0xFF
                            if (idx == transparent) continue
                            canvas[cy * width + cx] = if (idx < table.size) table[idx] else black
                        }
                    }
                    onFrame(canvas, width, height, (delay * 10L).let { if (it < 20L) 100L else it })
                    // What happens to this frame before the next one.
                    when (disposal) {
                        2 -> for (y in iy until minOf(iy + ih, height)) for (x in ix until minOf(ix + iw, width)) {
                            if (y >= 0 && x >= 0) canvas[y * width + x] = black
                        }
                        3 -> saved?.copyInto(canvas)
                    }
                    saved = null
                    disposal = 0; transparent = -1; delay = 0
                }
                else -> return // 0x3B: the end (or a broken file: what's been read is kept)
            }
        }
    }

    /** LZW-decodes [data] into [out] (colour indices). Missing pixels stay 0. */
    private fun lzw(data: ByteArray, minCode: Int, out: ByteArray, prefix: ShortArray, suffix: ByteArray, stack: ByteArray) {
        val clear = 1 shl minCode
        val eoi = clear + 1
        var codeSize = minCode + 1
        var next = clear + 2
        for (i in 0 until clear) { prefix[i] = -1; suffix[i] = i.toByte() }
        var old = -1
        var first = 0
        var datum = 0
        var bits = 0
        var o = 0
        for (byte in data) {
            datum = datum or ((byte.toInt() and 0xFF) shl bits)
            bits += 8
            while (bits >= codeSize) {
                var code = datum and ((1 shl codeSize) - 1)
                datum = datum ushr codeSize
                bits -= codeSize
                if (code == clear) { codeSize = minCode + 1; next = clear + 2; old = -1; continue }
                if (code == eoi) return
                if (old == -1) {
                    if (code >= clear) continue
                    if (o < out.size) out[o++] = suffix[code]
                    old = code; first = code
                    continue
                }
                val inCode = code
                var sp = 0
                if (code >= next) { stack[sp++] = first.toByte(); code = old }
                while (code > eoi && sp < 4096) { stack[sp++] = suffix[code]; code = prefix[code].toInt() }
                first = suffix[code].toInt() and 0xFF
                stack[sp++] = first.toByte()
                if (next < 4096) {
                    prefix[next] = old.toShort()
                    suffix[next] = first.toByte()
                    next++
                    if (next == (1 shl codeSize) && codeSize < 12) codeSize++
                }
                while (sp > 0 && o < out.size) out[o++] = stack[--sp]
                if (o >= out.size) return
                old = inCode
            }
        }
    }
}
