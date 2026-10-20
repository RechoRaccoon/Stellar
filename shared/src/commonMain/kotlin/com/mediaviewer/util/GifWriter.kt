package com.mediaviewer.util

/**
 * An animated GIF (GIF89a) writer in plain Kotlin, so it works the same on
 * every platform (the web included). The palette work and LZW stage are the
 * desktop/Android encoder's (see GifEncoder there); this one adds
 * see-through pixels — what custom emoji need — and writes into memory.
 *
 * Each frame is a whole canvas with its own palette. A frame with any
 * see-through pixel (alpha under half) keeps 255 colors and gives the last
 * palette slot to "see-through"; each frame then replaces the one before
 * (disposal "restore to background") so nothing smears between frames.
 */
class GifWriter(private val width: Int, private val height: Int) {
    private val out = GifByteSink()
    private val opaqueQuantizer = GifPaletteQuantizer(256)
    private val clearQuantizer = GifPaletteQuantizer(255)
    private var frames = 0

    init {
        out.ascii("GIF89a")
        out.short(width); out.short(height)
        out.byte(0x00) // no global color table
        out.byte(0x00) // background color index
        out.byte(0x00) // pixel aspect ratio
        // Loop forever (NETSCAPE2.0).
        out.byte(0x21); out.byte(0xFF); out.byte(11)
        out.ascii("NETSCAPE2.0")
        out.byte(3); out.byte(1); out.short(0); out.byte(0)
    }

    /** One frame: [argb] is [width]×[height], shown for [delayMs]. */
    fun addFrame(argb: IntArray, delayMs: Int, ditherStrength: Float = 0.75f) {
        require(argb.size >= width * height)
        val n = width * height
        var anyClear = false
        var sr = 0L; var sg = 0L; var sb = 0L; var opaque = 0
        for (i in 0 until n) {
            val p = argb[i]
            if ((p ushr 24) < 128) anyClear = true
            else { sr += (p shr 16) and 0xFF; sg += (p shr 8) and 0xFF; sb += p and 0xFF; opaque++ }
        }
        // See-through pixels take the frame's average color while the
        // palette is made (so they don't pull it anywhere new), then the
        // see-through slot.
        val fill = if (opaque > 0) (0xFF shl 24) or ((sr / opaque).toInt() shl 16) or ((sg / opaque).toInt() shl 8) or (sb / opaque).toInt()
            else 0xFF000000.toInt()
        val pixels = IntArray(n) { i -> val p = argb[i]; if ((p ushr 24) < 128) fill else p or (0xFF shl 24) }
        val indices = ByteArray(n)
        val table = (if (anyClear) clearQuantizer else opaqueQuantizer).quantize(pixels, width, height, indices, ditherStrength)
        if (anyClear) for (i in 0 until n) if ((argb[i] ushr 24) < 128) indices[i] = CLEAR_INDEX.toByte()

        // Graphic control: timing, and the see-through slot.
        out.byte(0x21); out.byte(0xF9); out.byte(4)
        out.byte(if (anyClear) (2 shl 2) or 0x01 else (1 shl 2))
        out.short((delayMs / 10).coerceIn(2, 65535))
        out.byte(if (anyClear) CLEAR_INDEX else 0)
        out.byte(0)
        // Image descriptor + its own 256-entry table.
        out.byte(0x2C)
        out.short(0); out.short(0); out.short(width); out.short(height)
        out.byte(0x87)
        out.bytes(table, 0, table.size)
        for (k in table.size until 768) out.byte(0)
        GifLzwEncoder(indices, 8).encode(out)
        frames++
    }

    val frameCount: Int get() = frames

    /** The finished file. */
    fun finish(): ByteArray {
        out.byte(0x3B)
        return out.toByteArray()
    }

    private companion object { const val CLEAR_INDEX = 255 }
}

/** A growable byte buffer (what the LZW stage writes into). */
class GifByteSink {
    private var buf = ByteArray(16 * 1024)
    var size = 0
        private set

    private fun ensure(extra: Int) {
        if (size + extra <= buf.size) return
        var n = buf.size * 2
        while (n < size + extra) n *= 2
        buf = buf.copyOf(n)
    }

    fun write(b: Int) { ensure(1); buf[size++] = b.toByte() }
    fun write(b: ByteArray, off: Int, len: Int) { ensure(len); b.copyInto(buf, size, off, off + len); size += len }
    fun byte(b: Int) = write(b)
    fun bytes(b: ByteArray, off: Int, len: Int) = write(b, off, len)
    fun short(v: Int) { write(v and 0xFF); write((v shr 8) and 0xFF) }
    fun ascii(s: String) { for (c in s) write(c.code) }
    fun toByteArray(): ByteArray = buf.copyOf(size)
}

/**
 * Builds one frame's 256-color palette and maps the frame onto it.
 * Reuses its working buffers across frames (one instance per encoder).
 */
internal class GifPaletteQuantizer(private val maxColors: Int = 256) {
    private companion object {
        const val BINS = 32 * 32 * 32 // 5 bits per channel
        // Perceptual channel weights for color distance.
        const val WR = 3; const val WG = 4; const val WB = 2
        const val ERR_CAP = 40
    }

    private val count = IntArray(BINS)
    private val sumR = LongArray(BINS)
    private val sumG = LongArray(BINS)
    private val sumB = LongArray(BINS)
    private val lut = IntArray(BINS)

    // Palette (0..255 per channel) + how many entries are in use.
    private val palR = IntArray(256)
    private val palG = IntArray(256)
    private val palB = IntArray(256)
    private var palSize = 0

    fun quantize(pixels: IntArray, w: Int, h: Int, outIdx: ByteArray, ditherStrength: Float): ByteArray {
        if (tryExact(pixels, outIdx)) return colorTable()
        buildPalette(pixels)
        lut.fill(-1)
        if (ditherStrength <= 0f) mapPlain(pixels, outIdx) else mapDithered(pixels, w, h, outIdx, ditherStrength)
        return colorTable()
    }

    private fun colorTable(): ByteArray {
        val tab = ByteArray(256 * 3)
        for (i in 0 until palSize) {
            tab[i * 3] = palR[i].toByte(); tab[i * 3 + 1] = palG[i].toByte(); tab[i * 3 + 2] = palB[i].toByte()
        }
        return tab
    }

    /** 256 colors or fewer: store them exactly — no loss at all. */
    private fun tryExact(pixels: IntArray, outIdx: ByteArray): Boolean {
        val map = HashMap<Int, Int>(512)
        var lastColor = -1
        var lastIndex = 0
        for (i in pixels.indices) {
            val c = pixels[i] and 0xFFFFFF
            if (c == lastColor) { outIdx[i] = lastIndex.toByte(); continue }
            var idx = map[c]
            if (idx == null) {
                if (map.size >= maxColors) return false
                idx = map.size
                map[c] = idx
            }
            lastColor = c; lastIndex = idx
            outIdx[i] = idx.toByte()
        }
        palSize = map.size
        for ((c, idx) in map) {
            palR[idx] = (c shr 16) and 0xFF; palG[idx] = (c shr 8) and 0xFF; palB[idx] = c and 0xFF
        }
        return true
    }

    // ── Palette: variance-based median cut over a 5-bit histogram, then k-means ──

    private fun buildPalette(pixels: IntArray) {
        count.fill(0)
        sumR.fill(0L); sumG.fill(0L); sumB.fill(0L)
        for (p in pixels) {
            val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
            val bin = ((r shr 3) shl 10) or ((g shr 3) shl 5) or (b shr 3)
            count[bin]++; sumR[bin] += r.toLong(); sumG[bin] += g.toLong(); sumB[bin] += b.toLong()
        }
        // Non-empty bins with their exact average colors.
        var n = 0
        for (i in 0 until BINS) if (count[i] > 0) n++
        val br = FloatArray(n); val bg = FloatArray(n); val bb = FloatArray(n); val bw = FloatArray(n)
        var k = 0
        for (i in 0 until BINS) {
            val c = count[i]
            if (c > 0) {
                br[k] = sumR[i].toFloat() / c; bg[k] = sumG[i].toFloat() / c; bb[k] = sumB[i].toFloat() / c
                bw[k] = c.toFloat(); k++
            }
        }
        val order = IntArray(n) { it }

        // Boxes are [start, end) ranges of `order`.
        val boxStart = IntArray(maxColors); val boxEnd = IntArray(maxColors)
        val boxErr = DoubleArray(maxColors)
        var boxes = 1
        boxStart[0] = 0; boxEnd[0] = n; boxErr[0] = boxError(order, 0, n, br, bg, bb, bw)

        while (boxes < maxColors) {
            // Split whichever box holds the most color error.
            var target = -1; var bestErr = 0.0
            for (i in 0 until boxes) {
                if (boxEnd[i] - boxStart[i] > 1 && boxErr[i] > bestErr) { bestErr = boxErr[i]; target = i }
            }
            if (target < 0) break
            val s = boxStart[target]; val e = boxEnd[target]
            val axis = widestAxis(order, s, e, br, bg, bb, bw)
            val values = when (axis) { 0 -> br; 1 -> bg; else -> bb }
            sortRange(order, s, e, values)
            // Weighted median.
            var total = 0f
            for (j in s until e) total += bw[order[j]]
            var acc = 0f
            var split = s + 1
            for (j in s until e - 1) {
                acc += bw[order[j]]
                if (acc >= total / 2f) { split = j + 1; break }
                split = j + 2
            }
            split = split.coerceIn(s + 1, e - 1)
            boxEnd[target] = split
            boxErr[target] = boxError(order, s, split, br, bg, bb, bw)
            boxStart[boxes] = split; boxEnd[boxes] = e
            boxErr[boxes] = boxError(order, split, e, br, bg, bb, bw)
            boxes++
        }

        // Each box's weighted average is a palette entry.
        val pr = FloatArray(boxes); val pg = FloatArray(boxes); val pb = FloatArray(boxes)
        for (i in 0 until boxes) {
            var w = 0f; var r = 0f; var g = 0f; var b = 0f
            for (j in boxStart[i] until boxEnd[i]) {
                val o = order[j]; val ww = bw[o]
                w += ww; r += br[o] * ww; g += bg[o] * ww; b += bb[o] * ww
            }
            if (w > 0f) { pr[i] = r / w; pg[i] = g / w; pb[i] = b / w }
        }

        // k-means refinement: pull every entry to the true center of the
        // colors that actually map to it.
        val assign = IntArray(n)
        repeat(3) {
            for (j in 0 until n) assign[j] = nearestF(br[j], bg[j], bb[j], pr, pg, pb, boxes)
            val ar = FloatArray(boxes); val ag = FloatArray(boxes); val ab = FloatArray(boxes); val aw = FloatArray(boxes)
            for (j in 0 until n) {
                val a = assign[j]; val ww = bw[j]
                ar[a] += br[j] * ww; ag[a] += bg[j] * ww; ab[a] += bb[j] * ww; aw[a] += ww
            }
            for (i in 0 until boxes) if (aw[i] > 0f) { pr[i] = ar[i] / aw[i]; pg[i] = ag[i] / aw[i]; pb[i] = ab[i] / aw[i] }
        }

        palSize = boxes
        for (i in 0 until boxes) {
            palR[i] = (pr[i] + 0.5f).toInt().coerceIn(0, 255)
            palG[i] = (pg[i] + 0.5f).toInt().coerceIn(0, 255)
            palB[i] = (pb[i] + 0.5f).toInt().coerceIn(0, 255)
        }
    }

    private fun boxError(order: IntArray, s: Int, e: Int, r: FloatArray, g: FloatArray, b: FloatArray, w: FloatArray): Double {
        var tw = 0.0; var sr = 0.0; var sg = 0.0; var sb = 0.0; var sq = 0.0
        for (j in s until e) {
            val o = order[j]; val ww = w[o].toDouble()
            val rr = r[o].toDouble(); val gg = g[o].toDouble(); val bbv = b[o].toDouble()
            tw += ww; sr += rr * ww; sg += gg * ww; sb += bbv * ww
            sq += ww * (WR * rr * rr + WG * gg * gg + WB * bbv * bbv)
        }
        if (tw <= 0.0) return 0.0
        return sq - (WR * sr * sr + WG * sg * sg + WB * sb * sb) / tw
    }

    private fun widestAxis(order: IntArray, s: Int, e: Int, r: FloatArray, g: FloatArray, b: FloatArray, w: FloatArray): Int {
        var tw = 0.0; val sum = DoubleArray(3); val sq = DoubleArray(3)
        for (j in s until e) {
            val o = order[j]; val ww = w[o].toDouble()
            tw += ww
            sum[0] += r[o] * ww; sum[1] += g[o] * ww; sum[2] += b[o] * ww
            sq[0] += r[o].toDouble() * r[o] * ww; sq[1] += g[o].toDouble() * g[o] * ww; sq[2] += b[o].toDouble() * b[o] * ww
        }
        if (tw <= 0.0) return 1
        val vr = WR * (sq[0] - sum[0] * sum[0] / tw)
        val vg = WG * (sq[1] - sum[1] * sum[1] / tw)
        val vb = WB * (sq[2] - sum[2] * sum[2] / tw)
        return if (vr >= vg && vr >= vb) 0 else if (vg >= vb) 1 else 2
    }

    /** Sorts order[s, e) by values[order[j]]. */
    private fun sortRange(order: IntArray, s: Int, e: Int, values: FloatArray) {
        val sorted = (s until e).map { order[it] }.sortedBy { values[it] }
        for (j in sorted.indices) order[s + j] = sorted[j]
    }

    private fun nearestF(r: Float, g: Float, b: Float, pr: FloatArray, pg: FloatArray, pb: FloatArray, size: Int): Int {
        var best = 0; var bestD = Float.MAX_VALUE
        for (i in 0 until size) {
            val dr = pr[i] - r; val dg = pg[i] - g; val db = pb[i] - b
            val d = WR * dr * dr + WG * dg * dg + WB * db * db
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }

    // ── Mapping ──

    private fun nearest(r: Int, g: Int, b: Int): Int {
        val key = ((r shr 3) shl 10) or ((g shr 3) shl 5) or (b shr 3)
        val cached = lut[key]
        if (cached >= 0) return cached
        // Nearest to the center of this 5-bit cell.
        val cr = ((r shr 3) shl 3) + 4; val cg = ((g shr 3) shl 3) + 4; val cb = ((b shr 3) shl 3) + 4
        var best = 0; var bestD = Int.MAX_VALUE
        for (i in 0 until palSize) {
            val dr = palR[i] - cr; val dg = palG[i] - cg; val db = palB[i] - cb
            val d = WR * dr * dr + WG * dg * dg + WB * db * db
            if (d < bestD) { bestD = d; best = i }
        }
        lut[key] = best
        return best
    }

    private fun mapPlain(pixels: IntArray, outIdx: ByteArray) {
        for (i in pixels.indices) {
            val p = pixels[i]
            outIdx[i] = nearest((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF).toByte()
        }
    }

    /** Serpentine Floyd–Steinberg. Errors are kept ×16 in integer rows. */
    private fun mapDithered(pixels: IntArray, w: Int, h: Int, outIdx: ByteArray, strength: Float) {
        val s256 = (strength.coerceIn(0f, 1f) * 256f).toInt()
        var cur = IntArray((w + 2) * 3)
        var next = IntArray((w + 2) * 3)
        for (y in 0 until h) {
            next.fill(0)
            val ltr = (y and 1) == 0
            var x = if (ltr) 0 else w - 1
            val dir = if (ltr) 1 else -1
            for (step in 0 until w) {
                val i = y * w + x
                val p = pixels[i]
                val e = (x + 1) * 3
                val r = (((p shr 16) and 0xFF) + (cur[e] shr 4)).coerceIn(0, 255)
                val g = (((p shr 8) and 0xFF) + (cur[e + 1] shr 4)).coerceIn(0, 255)
                val b = ((p and 0xFF) + (cur[e + 2] shr 4)).coerceIn(0, 255)
                val idx = nearest(r, g, b)
                outIdx[i] = idx.toByte()
                // Capped so a color the palette can't get near doesn't
                // smear bright speckles across its neighbors.
                val er = (((r - palR[idx]) * s256) shr 8).coerceIn(-ERR_CAP, ERR_CAP)
                val eg = (((g - palG[idx]) * s256) shr 8).coerceIn(-ERR_CAP, ERR_CAP)
                val eb = (((b - palB[idx]) * s256) shr 8).coerceIn(-ERR_CAP, ERR_CAP)
                if (er != 0 || eg != 0 || eb != 0) {
                    val ahead = (x + 1 + dir) * 3
                    val behindBelow = (x + 1 - dir) * 3
                    // → 7/16, ↙ 3/16, ↓ 5/16, ↘ 1/16 (mirrored on odd rows).
                    cur[ahead] += er * 7; cur[ahead + 1] += eg * 7; cur[ahead + 2] += eb * 7
                    next[behindBelow] += er * 3; next[behindBelow + 1] += eg * 3; next[behindBelow + 2] += eb * 3
                    next[e] += er * 5; next[e + 1] += eg * 5; next[e + 2] += eb * 5
                    next[ahead] += er; next[ahead + 1] += eg; next[ahead + 2] += eb
                }
                x += dir
            }
            val t = cur; cur = next; next = t
        }
    }
}

/**
 * GIF-style LZW compressor (lossless). A faithful port of the classic
 * algorithm used by the Unix `compress` utility and the widely used
 * public-domain Java GIF encoder (Weiner/Poskanzer lineage).
 */
internal class GifLzwEncoder(
    private val pixels: ByteArray, private val colorDepth: Int
) {
    private val EOF = -1
    private val BITS = 12
    private val HSIZE = 5003
    private val masks = intArrayOf(
        0x0000, 0x0001, 0x0003, 0x0007, 0x000F, 0x001F, 0x003F, 0x007F, 0x00FF,
        0x01FF, 0x03FF, 0x07FF, 0x0FFF, 0x1FFF, 0x3FFF, 0x7FFF, 0xFFFF
    )

    private var initCodeSize = 0
    private var curPixelIdx = 0
    private var nBits = 0
    private var maxCode = 0
    private var maxMaxCode = 1 shl BITS
    private val htab = IntArray(HSIZE)
    private val codeTab = IntArray(HSIZE)
    private var hSize = HSIZE
    private var freeEnt = 0
    private var clearFlg = false
    private var gInitBits = 0
    private var clearCode = 0
    private var eofCode = 0
    private var curAccum = 0
    private var curBits = 0
    private var aCount = 0
    private val accum = ByteArray(256)
    private lateinit var outStream: GifByteSink

    private fun nextPixel(): Int {
        if (curPixelIdx >= pixels.size) return EOF
        return (pixels[curPixelIdx++].toInt() and 0xFF)
    }

    private fun maxCode(nBits: Int) = (1 shl nBits) - 1

    fun encode(out: GifByteSink) {
        outStream = out
        initCodeSize = maxOf(2, colorDepth)
        out.write(initCodeSize)
        curPixelIdx = 0
        compress(initCodeSize + 1, out)
        out.write(0) // block terminator
    }

    private fun compress(initBitsIn: Int, out: GifByteSink) {
        gInitBits = initBitsIn
        clearFlg = false
        nBits = gInitBits
        maxCode = maxCode(nBits)
        clearCode = 1 shl (initBitsIn - 1)
        eofCode = clearCode + 1
        freeEnt = clearCode + 2
        aCount = 0

        var ent = nextPixel()
        var hshift = 0
        var fcode = hSize
        while (fcode < 65536) { fcode *= 2; hshift++ }
        hshift = 8 - hshift
        val hshiftFinal = hshift
        val hsizeReg = hSize
        clearHash(hsizeReg)
        output(clearCode)

        outer@ while (true) {
            val c = nextPixel()
            if (c == EOF) break
            val fcode2 = (c shl BITS) + ent
            var i = (c shl hshiftFinal) xor ent
            if (htab[i] == fcode2) {
                ent = codeTab[i]
                continue
            } else if (htab[i] >= 0) {
                var disp = hsizeReg - i
                if (i == 0) disp = 1
                while (true) {
                    i -= disp
                    if (i < 0) i += hsizeReg
                    if (htab[i] == fcode2) { ent = codeTab[i]; continue@outer }
                    if (htab[i] < 0) break
                }
            }
            output(ent)
            ent = c
            if (freeEnt < maxMaxCode) {
                codeTab[i] = freeEnt++
                htab[i] = fcode2
            } else {
                clearHash(hsizeReg)
                freeEnt = clearCode + 2
                clearFlg = true
                output(clearCode)
                nBits = gInitBits
                maxCode = maxCode(nBits)
            }
        }
        output(ent)
        output(eofCode)
    }

    private fun clearHash(size: Int) { for (i in 0 until size) htab[i] = -1 }

    private fun output(codeIn: Int) {
        curAccum = curAccum and masks[curBits]
        curAccum = if (curBits > 0) curAccum or (codeIn shl curBits) else codeIn
        curBits += nBits
        while (curBits >= 8) {
            charOut((curAccum and 0xFF).toByte())
            curAccum = curAccum ushr 8
            curBits -= 8
        }
        if (freeEnt > maxCode || clearFlg) {
            if (clearFlg) {
                nBits = gInitBits
                maxCode = maxCode(nBits)
                clearFlg = false
            } else {
                nBits++
                maxCode = if (nBits == BITS) maxMaxCode else maxCode(nBits)
            }
        }
        if (codeIn == eofCode) {
            while (curBits > 0) {
                charOut((curAccum and 0xFF).toByte())
                curAccum = curAccum ushr 8
                curBits -= 8
            }
            flushChar()
        }
    }

    private fun charOut(c: Byte) {
        accum[aCount++] = c
        if (aCount >= 254) flushChar()
    }

    private fun flushChar() {
        if (aCount > 0) {
            outStream.write(aCount)
            outStream.write(accum, 0, aCount)
            aCount = 0
        }
    }
}
