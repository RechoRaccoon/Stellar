package com.mediaviewer.platform

actual abstract class PlatformContext

/** The single app context on a computer (there's no Android-style Context). */
object DesktopContext : PlatformContext()

actual abstract class PlatformUri {
    /** The file:// or https:// URL this points at. */
    abstract val urlString: String
    actual abstract override fun toString(): String
    override fun equals(other: Any?): Boolean = other is PlatformUri && other.urlString == urlString
    override fun hashCode(): Int = urlString.hashCode()
}

class DesktopUri(override val urlString: String) : PlatformUri() {
    override fun toString(): String = urlString

    companion object {
        fun of(file: java.io.File): DesktopUri = DesktopUri(file.toFileUri())
    }
}

/** The local file [this] points at, or null for a remote uri. */
val PlatformUri.file: java.io.File? get() = fileOfUri(toString())

/** A rendered image on a computer: its size and PNG-encoded pixels. */
actual class PlatformBitmap(val width: Int, val height: Int, val pngBytes: ByteArray)

actual typealias IOException = java.io.IOException
/** java.util.concurrent.ConcurrentHashMap (wrapped: its keySet() type
 *  doesn't match MutableMap's as a plain typealias would need). */
actual class ConcurrentHashMap<K, V> private constructor(
    private val map: java.util.concurrent.ConcurrentHashMap<K, V>
) : MutableMap<K, V> by map {
    actual constructor() : this(java.util.concurrent.ConcurrentHashMap<K, V>())
    actual fun putIfAbsent(key: K, value: V): V? = map.putIfAbsent(key, value)
    override fun equals(other: Any?): Boolean = map == other
    override fun hashCode(): Int = map.hashCode()
    override fun toString(): String = map.toString()
}
actual typealias AtomicInteger = java.util.concurrent.atomic.AtomicInteger
actual typealias AtomicLong = java.util.concurrent.atomic.AtomicLong
actual typealias AtomicBoolean = java.util.concurrent.atomic.AtomicBoolean
