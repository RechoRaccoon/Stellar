package com.mediaviewer.platform

actual abstract class PlatformContext

/** The single app context in a browser (there's no Android-style Context). */
object WebContext : PlatformContext()

actual abstract class PlatformUri {
    /** The file:// (Stellar's own storage, see WebFiles) or https:// URL. */
    abstract val urlString: String
    actual abstract override fun toString(): String
    override fun equals(other: Any?): Boolean = other is PlatformUri && other.urlString == urlString
    override fun hashCode(): Int = urlString.hashCode()
}

class WebUri(override val urlString: String) : PlatformUri() {
    override fun toString(): String = urlString
}

/** The path in [WebFiles] this uri points at, or null for a web address. */
val PlatformUri.webPath: String? get() = webPathOf(toString())

fun webPathOf(uri: String): String? = if (uri.startsWith("file:")) WebFiles.normalize(uri.removePrefix("file:")) else null

/** A rendered image in the browser: its size and PNG-encoded pixels. */
actual class PlatformBitmap(val width: Int, val height: Int, val pngBytes: ByteArray)

actual open class IOException : Exception {
    actual constructor() : super()
    actual constructor(message: String?) : super(message)
    actual constructor(message: String?, cause: Throwable?) : super(message, cause)
}

/** The page has one thread: a plain map is already "concurrent". */
actual class ConcurrentHashMap<K, V> private constructor(
    private val map: LinkedHashMap<K, V>
) : MutableMap<K, V> by map {
    actual constructor() : this(LinkedHashMap<K, V>())
    actual fun putIfAbsent(key: K, value: V): V? = map[key] ?: run { map[key] = value; null }
    override fun equals(other: Any?): Boolean = map == other
    override fun hashCode(): Int = map.hashCode()
    override fun toString(): String = map.toString()
}

actual class AtomicInteger actual constructor(initialValue: Int) {
    private var value = initialValue
    actual constructor() : this(0)
    actual fun get(): Int = value
    actual fun set(newValue: Int) { value = newValue }
    actual fun incrementAndGet(): Int = ++value
    actual fun decrementAndGet(): Int = --value
    actual fun getAndIncrement(): Int = value++
    actual fun getAndDecrement(): Int = value--
    actual fun getAndSet(newValue: Int): Int { val old = value; value = newValue; return old }
    actual fun addAndGet(delta: Int): Int { value += delta; return value }
    actual fun compareAndSet(expectedValue: Int, newValue: Int): Boolean {
        if (value != expectedValue) return false
        value = newValue
        return true
    }
    override fun toString(): String = value.toString()
}

actual class AtomicLong actual constructor(initialValue: Long) {
    private var value = initialValue
    actual constructor() : this(0L)
    actual fun get(): Long = value
    actual fun set(newValue: Long) { value = newValue }
    actual fun incrementAndGet(): Long = ++value
    actual fun getAndIncrement(): Long = value++
    actual fun addAndGet(delta: Long): Long { value += delta; return value }
    actual fun getAndSet(newValue: Long): Long { val old = value; value = newValue; return old }
    actual fun compareAndSet(expectedValue: Long, newValue: Long): Boolean {
        if (value != expectedValue) return false
        value = newValue
        return true
    }
    override fun toString(): String = value.toString()
}

actual class AtomicBoolean actual constructor(initialValue: Boolean) {
    private var value = initialValue
    actual constructor() : this(false)
    actual fun get(): Boolean = value
    actual fun set(newValue: Boolean) { value = newValue }
    actual fun getAndSet(newValue: Boolean): Boolean { val old = value; value = newValue; return old }
    actual fun compareAndSet(expectedValue: Boolean, newValue: Boolean): Boolean {
        if (value != expectedValue) return false
        value = newValue
        return true
    }
    override fun toString(): String = value.toString()
}
