package com.mediaviewer.util.crypto

/**
 * The few cryptographic pieces Stellar's private sync needs, in plain
 * common Kotlin so every platform (Android, iOS, desktop, web) encrypts
 * exactly alike: SHA-256, HMAC-SHA256, HKDF-SHA256 (RFC 5869) and
 * XChaCha20-Poly1305 (RFC 8439 + draft-irtf-cfrg-xchacha). Checked against
 * the published test vectors (tools/vault-crypto-test).
 *
 * Nothing here is clever: these are straight transcriptions of the specs.
 * Random bytes come from the platform's secure generator
 * ([com.mediaviewer.platform.secureRandomBytes]).
 */
object Sha256 {
    private val K = intArrayOf(
        0x428a2f98, 0x71374491, -0x4a3f0431, -0x164a245b, 0x3956c25b, 0x59f111f1, -0x6dc07d5c, -0x54e3a12b,
        -0x27f85568, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, -0x7f214e02, -0x6423f959, -0x3e640e8c,
        -0x1b64963f, -0x1041b87a, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        -0x67c1aeae, -0x57ce3993, -0x4ffcd838, -0x40a68039, -0x391ff40d, -0x2a586eb9, 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, -0x7e3d36d2, -0x6d8dd37b,
        -0x5d40175f, -0x57e599b5, -0x3db47490, -0x3893ae5d, -0x2e6d17e7, -0x2966f9dc, -0xbf1ca7b, 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, -0x7b3787ec, -0x7338fdf8, -0x6f410006, -0x5baf9315, -0x41065c09, -0x398e870e
    )

    fun hash(data: ByteArray): ByteArray {
        val h = intArrayOf(
            0x6a09e667, -0x4498517b, 0x3c6ef372, -0x5ab00ac6, 0x510e527f, -0x64fa9774, 0x1f83d9ab, 0x5be0cd19
        )
        val bitLen = data.size.toLong() * 8
        val padded = ByteArray(((data.size + 9 + 63) / 64) * 64)
        data.copyInto(padded)
        padded[data.size] = 0x80.toByte()
        for (i in 0 until 8) padded[padded.size - 1 - i] = (bitLen ushr (8 * i)).toByte()
        val w = IntArray(64)
        var off = 0
        while (off < padded.size) {
            for (t in 0 until 16) w[t] = be32(padded, off + t * 4)
            for (t in 16 until 64) {
                val s0 = w[t - 15].rotateRight(7) xor w[t - 15].rotateRight(18) xor (w[t - 15] ushr 3)
                val s1 = w[t - 2].rotateRight(17) xor w[t - 2].rotateRight(19) xor (w[t - 2] ushr 10)
                w[t] = w[t - 16] + s0 + w[t - 7] + s1
            }
            var a = h[0]; var b = h[1]; var c = h[2]; var d = h[3]
            var e = h[4]; var f = h[5]; var g = h[6]; var hh = h[7]
            for (t in 0 until 64) {
                val s1 = e.rotateRight(6) xor e.rotateRight(11) xor e.rotateRight(25)
                val ch = (e and f) xor (e.inv() and g)
                val t1 = hh + s1 + ch + K[t] + w[t]
                val s0 = a.rotateRight(2) xor a.rotateRight(13) xor a.rotateRight(22)
                val maj = (a and b) xor (a and c) xor (b and c)
                val t2 = s0 + maj
                hh = g; g = f; f = e; e = d + t1; d = c; c = b; b = a; a = t1 + t2
            }
            h[0] += a; h[1] += b; h[2] += c; h[3] += d; h[4] += e; h[5] += f; h[6] += g; h[7] += hh
            off += 64
        }
        val out = ByteArray(32)
        for (i in 0 until 8) putBe32(out, i * 4, h[i])
        return out
    }

    fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val k = if (key.size > 64) hash(key) else key
        val ipad = ByteArray(64 + data.size)
        val opad = ByteArray(64 + 32)
        for (i in 0 until 64) {
            val kb = if (i < k.size) k[i].toInt() else 0
            ipad[i] = (kb xor 0x36).toByte()
            opad[i] = (kb xor 0x5c).toByte()
        }
        data.copyInto(ipad, 64)
        hash(ipad).copyInto(opad, 64)
        return hash(opad)
    }

    /** HKDF-SHA256: [length] bytes from [ikm]. */
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..255 * 32)
        val prk = hmac(if (salt.isEmpty()) ByteArray(32) else salt, ikm)
        val out = ByteArray(length)
        var prev = ByteArray(0)
        var pos = 0
        var counter = 1
        while (pos < length) {
            prev = hmac(prk, prev + info + byteArrayOf(counter.toByte()))
            val n = minOf(32, length - pos)
            prev.copyInto(out, pos, 0, n)
            pos += n; counter++
        }
        return out
    }

    private fun be32(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
            ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)

    private fun putBe32(b: ByteArray, o: Int, v: Int) {
        b[o] = (v ushr 24).toByte(); b[o + 1] = (v ushr 16).toByte(); b[o + 2] = (v ushr 8).toByte(); b[o + 3] = v.toByte()
    }
}

/** Thrown when a ciphertext doesn't open: wrong key, or it was changed. */
class VaultDecryptException : Exception("Couldn't decrypt (wrong key, or the data was changed)")

object XChaCha20Poly1305 {
    const val KEY_BYTES = 32
    const val NONCE_BYTES = 24
    const val TAG_BYTES = 16

    /** ciphertext ‖ tag for [plain] under [key] and [nonce] (24 bytes). */
    fun seal(key: ByteArray, nonce: ByteArray, plain: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray {
        require(key.size == KEY_BYTES && nonce.size == NONCE_BYTES)
        val sub = hChaCha20(key, nonce)
        val n12 = ByteArray(12).also { nonce.copyInto(it, 4, 16, 24) }
        val out = ByteArray(plain.size + TAG_BYTES)
        ChaCha20.xor(sub, n12, 1, plain, 0, plain.size, out, 0)
        val tag = tag(sub, n12, aad, out, plain.size)
        tag.copyInto(out, plain.size)
        return out
    }

    /** The plaintext, or [VaultDecryptException]. */
    fun open(key: ByteArray, nonce: ByteArray, sealed: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray {
        require(key.size == KEY_BYTES && nonce.size == NONCE_BYTES)
        if (sealed.size < TAG_BYTES) throw VaultDecryptException()
        val sub = hChaCha20(key, nonce)
        val n12 = ByteArray(12).also { nonce.copyInto(it, 4, 16, 24) }
        val len = sealed.size - TAG_BYTES
        val expect = tag(sub, n12, aad, sealed, len)
        var diff = 0
        for (i in 0 until TAG_BYTES) diff = diff or (expect[i].toInt() xor sealed[len + i].toInt())
        if (diff != 0) throw VaultDecryptException()
        val out = ByteArray(len)
        ChaCha20.xor(sub, n12, 1, sealed, 0, len, out, 0)
        return out
    }

    private fun tag(key: ByteArray, n12: ByteArray, aad: ByteArray, ct: ByteArray, ctLen: Int): ByteArray {
        val block0 = ChaCha20.block(key, n12, 0)
        val p = Poly1305(block0.copyOf(32))
        p.update(aad, 0, aad.size); p.pad16(aad.size)
        p.update(ct, 0, ctLen); p.pad16(ctLen)
        val lens = ByteArray(16)
        putLe64(lens, 0, aad.size.toLong()); putLe64(lens, 8, ctLen.toLong())
        p.update(lens, 0, 16)
        return p.finish()
    }

    /** HChaCha20: the subkey for the nonce's first 16 bytes. */
    internal fun hChaCha20(key: ByteArray, nonce: ByteArray): ByteArray {
        val s = IntArray(16)
        s[0] = 0x61707865; s[1] = 0x3320646e; s[2] = 0x79622d32; s[3] = 0x6b206574
        for (i in 0 until 8) s[4 + i] = le32(key, i * 4)
        for (i in 0 until 4) s[12 + i] = le32(nonce, i * 4)
        ChaCha20.rounds(s)
        val out = ByteArray(32)
        for (i in 0 until 4) putLe32(out, i * 4, s[i])
        for (i in 0 until 4) putLe32(out, 16 + i * 4, s[12 + i])
        return out
    }
}

internal object ChaCha20 {
    fun rounds(x: IntArray) {
        repeat(10) {
            qr(x, 0, 4, 8, 12); qr(x, 1, 5, 9, 13); qr(x, 2, 6, 10, 14); qr(x, 3, 7, 11, 15)
            qr(x, 0, 5, 10, 15); qr(x, 1, 6, 11, 12); qr(x, 2, 7, 8, 13); qr(x, 3, 4, 9, 14)
        }
    }

    private fun qr(x: IntArray, a: Int, b: Int, c: Int, d: Int) {
        x[a] += x[b]; x[d] = (x[d] xor x[a]).rotateLeft(16)
        x[c] += x[d]; x[b] = (x[b] xor x[c]).rotateLeft(12)
        x[a] += x[b]; x[d] = (x[d] xor x[a]).rotateLeft(8)
        x[c] += x[d]; x[b] = (x[b] xor x[c]).rotateLeft(7)
    }

    private fun init(key: ByteArray, n12: ByteArray, counter: Int): IntArray {
        val s = IntArray(16)
        s[0] = 0x61707865; s[1] = 0x3320646e; s[2] = 0x79622d32; s[3] = 0x6b206574
        for (i in 0 until 8) s[4 + i] = le32(key, i * 4)
        s[12] = counter
        for (i in 0 until 3) s[13 + i] = le32(n12, i * 4)
        return s
    }

    fun block(key: ByteArray, n12: ByteArray, counter: Int): ByteArray {
        val s = init(key, n12, counter)
        val x = s.copyOf()
        rounds(x)
        val out = ByteArray(64)
        for (i in 0 until 16) putLe32(out, i * 4, x[i] + s[i])
        return out
    }

    fun xor(key: ByteArray, n12: ByteArray, counter: Int, src: ByteArray, srcOff: Int, len: Int, dst: ByteArray, dstOff: Int) {
        val s = init(key, n12, counter)
        val x = IntArray(16)
        val ks = ByteArray(64)
        var done = 0
        while (done < len) {
            s.copyInto(x)
            rounds(x)
            for (i in 0 until 16) putLe32(ks, i * 4, x[i] + s[i])
            val n = minOf(64, len - done)
            for (i in 0 until n) dst[dstOff + done + i] = (src[srcOff + done + i].toInt() xor ks[i].toInt()).toByte()
            done += n
            s[12]++
        }
    }
}

/** Poly1305 with 26-bit limbs (the poly1305-donna 32-bit layout). */
internal class Poly1305(key: ByteArray) {
    private val r0: Long; private val r1: Long; private val r2: Long; private val r3: Long; private val r4: Long
    private val pad = LongArray(4)
    private var h0 = 0L; private var h1 = 0L; private var h2 = 0L; private var h3 = 0L; private var h4 = 0L
    private val buf = ByteArray(16)
    private var bufLen = 0

    init {
        val t0 = le32(key, 0).toLong() and 0xffffffffL
        val t1 = le32(key, 4).toLong() and 0xffffffffL
        val t2 = le32(key, 8).toLong() and 0xffffffffL
        val t3 = le32(key, 12).toLong() and 0xffffffffL
        r0 = t0 and 0x3ffffff
        r1 = ((t0 ushr 26) or (t1 shl 6)) and 0x3ffff03
        r2 = ((t1 ushr 20) or (t2 shl 12)) and 0x3ffc0ff
        r3 = ((t2 ushr 14) or (t3 shl 18)) and 0x3f03fff
        r4 = (t3 ushr 8) and 0x00fffff
        for (i in 0 until 4) pad[i] = le32(key, 16 + i * 4).toLong() and 0xffffffffL
    }

    fun update(m: ByteArray, off: Int, len: Int) {
        var i = off
        val end = off + len
        if (bufLen > 0) {
            while (bufLen < 16 && i < end) buf[bufLen++] = m[i++]
            if (bufLen == 16) { block(buf, 0, 1L shl 24); bufLen = 0 }
        }
        while (end - i >= 16) { block(m, i, 1L shl 24); i += 16 }
        while (i < end) buf[bufLen++] = m[i++]
    }

    /** Zero padding up to a multiple of 16 after [len] bytes (the AEAD's layout). */
    fun pad16(len: Int) {
        val rem = len % 16
        if (rem != 0) update(ByteArray(16 - rem), 0, 16 - rem)
    }

    private fun block(m: ByteArray, o: Int, hibit: Long) {
        val t0 = le32(m, o).toLong() and 0xffffffffL
        val t1 = le32(m, o + 4).toLong() and 0xffffffffL
        val t2 = le32(m, o + 8).toLong() and 0xffffffffL
        val t3 = le32(m, o + 12).toLong() and 0xffffffffL
        h0 += t0 and 0x3ffffff
        h1 += ((t0 ushr 26) or (t1 shl 6)) and 0x3ffffff
        h2 += ((t1 ushr 20) or (t2 shl 12)) and 0x3ffffff
        h3 += ((t2 ushr 14) or (t3 shl 18)) and 0x3ffffff
        h4 += (t3 ushr 8) or hibit
        val s1 = r1 * 5; val s2 = r2 * 5; val s3 = r3 * 5; val s4 = r4 * 5
        val d0 = h0 * r0 + h1 * s4 + h2 * s3 + h3 * s2 + h4 * s1
        var d1 = h0 * r1 + h1 * r0 + h2 * s4 + h3 * s3 + h4 * s2
        var d2 = h0 * r2 + h1 * r1 + h2 * r0 + h3 * s4 + h4 * s3
        var d3 = h0 * r3 + h1 * r2 + h2 * r1 + h3 * r0 + h4 * s4
        var d4 = h0 * r4 + h1 * r3 + h2 * r2 + h3 * r1 + h4 * r0
        var c = d0 ushr 26; h0 = d0 and 0x3ffffff
        d1 += c; c = d1 ushr 26; h1 = d1 and 0x3ffffff
        d2 += c; c = d2 ushr 26; h2 = d2 and 0x3ffffff
        d3 += c; c = d3 ushr 26; h3 = d3 and 0x3ffffff
        d4 += c; c = d4 ushr 26; h4 = d4 and 0x3ffffff
        h0 += c * 5; c = h0 ushr 26; h0 = h0 and 0x3ffffff
        h1 += c
    }

    fun finish(): ByteArray {
        if (bufLen > 0) {
            buf[bufLen] = 1
            for (i in bufLen + 1 until 16) buf[i] = 0
            block(buf, 0, 0L)
        }
        var c = h1 ushr 26; h1 = h1 and 0x3ffffff
        h2 += c; c = h2 ushr 26; h2 = h2 and 0x3ffffff
        h3 += c; c = h3 ushr 26; h3 = h3 and 0x3ffffff
        h4 += c; c = h4 ushr 26; h4 = h4 and 0x3ffffff
        h0 += c * 5; c = h0 ushr 26; h0 = h0 and 0x3ffffff
        h1 += c
        // h - p, picked in constant time.
        var g0 = h0 + 5; c = g0 ushr 26; g0 = g0 and 0x3ffffff
        var g1 = h1 + c; c = g1 ushr 26; g1 = g1 and 0x3ffffff
        var g2 = h2 + c; c = g2 ushr 26; g2 = g2 and 0x3ffffff
        var g3 = h3 + c; c = g3 ushr 26; g3 = g3 and 0x3ffffff
        val g4 = h4 + c - (1L shl 26)
        val mask = (g4 ushr 63) - 1 // all ones when g4 >= 0 (h >= p)
        h0 = (h0 and mask.inv()) or (g0 and mask)
        h1 = (h1 and mask.inv()) or (g1 and mask)
        h2 = (h2 and mask.inv()) or (g2 and mask)
        h3 = (h3 and mask.inv()) or (g3 and mask)
        h4 = (h4 and mask.inv()) or (g4 and mask and 0x3ffffff)
        val f0 = ((h0) or (h1 shl 26)) and 0xffffffffL
        val f1 = ((h1 ushr 6) or (h2 shl 20)) and 0xffffffffL
        val f2 = ((h2 ushr 12) or (h3 shl 14)) and 0xffffffffL
        val f3 = ((h3 ushr 18) or (h4 shl 8)) and 0xffffffffL
        var acc = f0 + pad[0]
        val out = ByteArray(16)
        putLe32(out, 0, acc.toInt())
        acc = f1 + pad[1] + (acc ushr 32); putLe32(out, 4, acc.toInt())
        acc = f2 + pad[2] + (acc ushr 32); putLe32(out, 8, acc.toInt())
        acc = f3 + pad[3] + (acc ushr 32); putLe32(out, 12, acc.toInt())
        return out
    }
}

internal fun le32(b: ByteArray, o: Int): Int =
    (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
        ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

internal fun putLe32(b: ByteArray, o: Int, v: Int) {
    b[o] = v.toByte(); b[o + 1] = (v ushr 8).toByte(); b[o + 2] = (v ushr 16).toByte(); b[o + 3] = (v ushr 24).toByte()
}

internal fun putLe64(b: ByteArray, o: Int, v: Long) {
    for (i in 0 until 8) b[o + i] = (v ushr (8 * i)).toByte()
}
