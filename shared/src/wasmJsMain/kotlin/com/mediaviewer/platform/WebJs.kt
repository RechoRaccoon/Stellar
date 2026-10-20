@file:OptIn(kotlin.wasm.unsafe.UnsafeWasmMemoryApi::class)

package com.mediaviewer.platform

import kotlin.wasm.unsafe.withScopedMemoryAllocator

/*
 * The few browser calls the web version needs that kotlinx-browser doesn't
 * cover, and fast byte copies between JavaScript and Kotlin. A ByteArray
 * can't be handed to JavaScript as is; going byte by byte through the
 * boundary is slow for anything the size of a picture, so bytes travel
 * through a scratch block of the Wasm module's memory instead (the same
 * way Compose's own resource loading does it).
 */

/** A JavaScript Uint8Array (or any typed array view). */
external interface JsBytes : JsAny

/** crypto.getRandomValues: throws (no fallback) where it's missing. */
internal fun jsSecureRandom(n: Int): JsBytes = js("crypto.getRandomValues(new Uint8Array(n))")

private fun jsBytesLength(a: JsBytes): Int = js("a.length")

private fun jsCopyToWasm(src: JsBytes, dstAddr: Int, size: Int): Unit =
    js("new Uint8Array(wasmExports.memory.buffer, dstAddr, size).set(src)")

private fun jsCopyFromWasm(srcAddr: Int, size: Int): JsBytes =
    js("new Uint8Array(wasmExports.memory.buffer, srcAddr, size).slice()")

/** Copies a Uint8Array into a new ByteArray. */
fun JsBytes.toByteArray(): ByteArray {
    val size = jsBytesLength(this)
    if (size == 0) return ByteArray(0)
    // In slices, so a big file never needs one huge scratch block.
    val out = ByteArray(size)
    var at = 0
    while (at < size) {
        val n = minOf(CHUNK, size - at)
        withScopedMemoryAllocator { allocator ->
            val mem = allocator.allocate(n)
            jsCopyToWasm(jsSubarray(this, at, at + n), mem.address.toInt(), n)
            for (i in 0 until n) out[at + i] = (mem + i).loadByte()
        }
        at += n
    }
    return out
}

/** Copies a ByteArray into a new Uint8Array. */
fun ByteArray.toJsBytes(): JsBytes {
    if (isEmpty()) return jsNewBytes(0)
    val out = jsNewBytes(size)
    var at = 0
    while (at < size) {
        val n = minOf(CHUNK, size - at)
        withScopedMemoryAllocator { allocator ->
            val mem = allocator.allocate(n)
            for (i in 0 until n) (mem + i).storeByte(this[at + i])
            jsSetAt(out, jsCopyFromWasm(mem.address.toInt(), n), at)
        }
        at += n
    }
    return out
}

private const val CHUNK = 4 * 1024 * 1024

private fun jsSubarray(a: JsBytes, from: Int, to: Int): JsBytes = js("a.subarray(from, to)")
private fun jsNewBytes(size: Int): JsBytes = js("new Uint8Array(size)")
private fun jsSetAt(dst: JsBytes, src: JsBytes, at: Int): Unit = js("dst.set(src, at)")

/** console.log / warn / error. */
internal fun consoleLog(level: String, text: String): Unit = js("""{
    if (level === 'e') console.error(text);
    else if (level === 'w') console.warn(text);
    else console.log(text);
}""")

internal fun jsNow(): Double = js("Date.now()")
internal fun jsPerformanceNow(): Double = js("performance.now()")
internal fun jsRandomUuid(): String = js("""(typeof crypto !== 'undefined' && crypto.randomUUID) ? crypto.randomUUID() :
    'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, c => { const r = Math.random() * 16 | 0; return (c === 'x' ? r : (r & 3 | 8)).toString(16); })""")
internal fun jsLanguage(): String = js("(navigator.language || 'en')")
internal fun jsDecodeUriComponent(s: String): String = js("decodeURIComponent(s)")
internal fun jsEncodeUriComponent(s: String): String = js("encodeURIComponent(s)")

/** A tiny JS object for passing several values at once. */
internal fun jsObject(): JsAny = js("({})")
internal fun jsSet(o: JsAny, key: String, value: JsAny?): Unit = js("o[key] = value")
internal fun jsGet(o: JsAny, key: String): JsAny? = js("o[key]")
internal fun jsGetString(o: JsAny, key: String): String? = js("(o[key] == null ? null : String(o[key]))")
internal fun jsGetInt(o: JsAny, key: String): Int = js("(o[key] | 0)")
internal fun jsGetNumber(o: JsAny, key: String): Double = js("(+o[key] || 0)")
internal fun jsGetBool(o: JsAny, key: String): Boolean = js("!!o[key]")

/** encodeURIComponent, for the network code. */
fun jsEncodeUriComponentPublic(s: String): String = jsEncodeUriComponent(s)
