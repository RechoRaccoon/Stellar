// Runs Stellar's private-sync crypto against the published test vectors:
//   sh tools/vault-crypto-test/run.sh
import com.mediaviewer.util.crypto.*

fun hex(s: String) = s.replace(" ", "").replace("\n", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
fun ByteArray.hex() = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
var failed = 0
fun check(name: String, got: String, want: String) {
    val ok = got == want.replace(" ", "").replace("\n", "").lowercase()
    if (!ok) failed++
    println((if (ok) "ok   " else "FAIL ") + name + if (!ok) "\n  got  $got\n  want $want" else "")
}

fun main() {
    check("sha256 empty", Sha256.hash(ByteArray(0)).hex(), "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
    check("sha256 abc", Sha256.hash("abc".encodeToByteArray()).hex(), "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
    check("sha256 448", Sha256.hash("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray()).hex(),
        "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1")
    check("sha256 1M a", Sha256.hash(ByteArray(1_000_000) { 'a'.code.toByte() }).hex(),
        "cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0")
    // RFC 4231 case 2
    check("hmac rfc4231-2", Sha256.hmac("Jefe".encodeToByteArray(), "what do ya want for nothing?".encodeToByteArray()).hex(),
        "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843")
    // RFC 4231 case 6 (long key)
    check("hmac rfc4231-6", Sha256.hmac(ByteArray(131) { 0xaa.toByte() }, "Test Using Larger Than Block-Size Key - Hash Key First".encodeToByteArray()).hex(),
        "60e431591ee0b67f0d8a26aacbf5b77f8e0bc6213728c5140546040f0ee37f54")
    // RFC 5869 case 1
    check("hkdf rfc5869-1", Sha256.hkdf(hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b"), hex("000102030405060708090a0b0c"), hex("f0f1f2f3f4f5f6f7f8f9"), 42).hex(),
        "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865")
    // XChaCha20-Poly1305 (draft-irtf-cfrg-xchacha-03, A.3.1)
    val pt = "Ladies and Gentlemen of the class of '99: If I could offer you only one tip for the future, sunscreen would be it.".encodeToByteArray()
    val aad = hex("50515253c0c1c2c3c4c5c6c7")
    val key = hex("808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f")
    val iv = hex("404142434445464748494a4b4c4d4e4f5051525354555657")
    val sealed = XChaCha20Poly1305.seal(key, iv, pt, aad)
    check("xchacha seal", sealed.hex(),
        "bd6d179d3e83d43b9576579493c0e939572a1700252bfaccbed2902c21396cbb" +
        "731c7f1b0b4aa6440bf3a82f4eda7e39ae64c6708c54c216cb96b72e1213b452" +
        "2f8c9ba40db5d945b11b69b982c1bb9e3f3fac2bc369488f76b2383565d3fff9" +
        "21f9664c97637da9768812f615c68b13b52e" + "c0875924c1c7987947deafd8780acf49")
    val opened = XChaCha20Poly1305.open(key, iv, sealed, aad)
    check("xchacha open", opened.hex(), pt.hex())
    // Any changed byte, or the wrong extra data, must not open.
    for (pos in listOf(0, 50, sealed.size - 1)) {
        val bad = sealed.copyOf(); bad[pos] = (bad[pos].toInt() xor 1).toByte()
        val refused = runCatching { XChaCha20Poly1305.open(key, iv, bad, aad) }.exceptionOrNull() is VaultDecryptException
        check("xchacha tamper @$pos", refused.toString(), "true")
    }
    val wrongAad = runCatching { XChaCha20Poly1305.open(key, iv, sealed, hex("00")) }.exceptionOrNull() is VaultDecryptException
    check("xchacha wrong aad", wrongAad.toString(), "true")
    // Lengths around the 16- and 64-byte boundaries round-trip.
    for (n in listOf(0, 1, 15, 16, 17, 63, 64, 65, 1000, 4 * 1024 * 1024 + 3)) {
        val m = ByteArray(n) { (it * 31 + 7).toByte() }
        val back = XChaCha20Poly1305.open(key, iv, XChaCha20Poly1305.seal(key, iv, m), ByteArray(0))
        check("roundtrip $n", back.contentEquals(m).toString(), "true")
    }
    val t0 = System.nanoTime()
    XChaCha20Poly1305.seal(key, iv, ByteArray(32 * 1024 * 1024))
    println("32 MiB sealed in " + (System.nanoTime() - t0) / 1_000_000 + " ms")
    mergeTests()
    if (failed > 0) { println("$failed FAILED"); kotlin.system.exitProcess(1) } else println("all passed")
}
