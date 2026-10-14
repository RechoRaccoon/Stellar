package com.mediaviewer.platform

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** A browser page has no threads: "background" work shares the page's
 *  event loop (it still never blocks it — network calls are asynchronous). */
actual val ioDispatcher: CoroutineDispatcher get() = Dispatchers.Default

actual fun currentTimeMillis(): Long = jsNow().toLong()

/** Milliseconds since the page opened, in nanoseconds (sub-ms precision). */
actual fun nanoTime(): Long = (jsPerformanceNow() * 1_000_000.0).toLong()

actual fun nowIsoString(): String = isoString(jsNow())

actual fun parseIsoInstantMillis(text: String): Long = parseIsoMillis(text, requireOffset = true)

actual fun parseIsoOffsetDateTimeMillis(text: String): Long = parseIsoMillis(text, requireOffset = true)

actual fun randomUuidString(): String = jsRandomUuid()

/** Form decoding ("+" is a space), like java.net.URLDecoder. */
actual fun urlDecode(text: String): String =
    runCatching { jsDecodeUriComponent(text.replace('+', ' ')) }.getOrElse { throw IllegalArgumentException("Bad URL encoding") }

/** Form encoding (spaces become '+'), like java.net.URLEncoder. */
actual fun urlEncode(text: String): String =
    jsEncodeUriComponent(text)
        .replace("%20", "+")
        // encodeURIComponent leaves these alone; URLEncoder doesn't.
        .replace("!", "%21").replace("'", "%27").replace("(", "%28").replace(")", "%29").replace("~", "%7E")

actual fun uriHost(url: String): String? = parseUriComponents(url).host

@Suppress("NOTHING_TO_INLINE")
actual inline fun <T> synchronizedCompat(lock: Any, block: () -> T): T = block()

actual fun <T> synchronizedMutableList(): MutableList<T> = mutableListOf()

actual object Log {
    actual fun v(tag: String?, msg: String): Int = WebLog.write("v", tag, msg, null)
    actual fun d(tag: String?, msg: String): Int = WebLog.write("d", tag, msg, null)
    actual fun d(tag: String?, msg: String, tr: Throwable?): Int = WebLog.write("d", tag, msg, tr)
    actual fun i(tag: String?, msg: String): Int = WebLog.write("i", tag, msg, null)
    actual fun i(tag: String?, msg: String, tr: Throwable?): Int = WebLog.write("i", tag, msg, tr)
    actual fun w(tag: String?, msg: String): Int = WebLog.write("w", tag, msg, null)
    actual fun w(tag: String?, msg: String, tr: Throwable?): Int = WebLog.write("w", tag, msg, tr)
    actual fun w(tag: String?, tr: Throwable?): Int = WebLog.write("w", tag, "", tr)
    actual fun e(tag: String?, msg: String): Int = WebLog.write("e", tag, msg, null)
    actual fun e(tag: String?, msg: String, tr: Throwable?): Int = WebLog.write("e", tag, msg, tr)
}

/**
 * Stellar's log in a browser: warnings and errors (failed requests among
 * them) go to the browser's console (F12 → Console), like the desktop
 * app's stellar.log. Everything else only with ?debug in the address.
 */
internal object WebLog {
    private val verbose: Boolean by lazy { runCatching { jsDebugFlag() }.getOrDefault(false) }

    fun write(level: String, tag: String?, msg: String, tr: Throwable?): Int {
        if (!verbose && level != "w" && level != "e") return 0
        val line = "${level.uppercase()}/${tag ?: "Stellar"}: $msg" + (tr?.let { "\n" + it.stackTraceToString() } ?: "")
        consoleLog(level, line)
        return line.length
    }
}

private fun jsDebugFlag(): Boolean = js("new URLSearchParams(location.search).has('debug')")

private val URI = Regex("^(?:([A-Za-z][A-Za-z0-9+.-]*):)?(?://(?:[^@/?#]*@)?(\\[[^\\]]*\\]|[^:/?#]*)(?::\\d*)?)?([^?#]*)(?:\\?[^#]*)?(?:#.*)?$")

actual fun parseUriComponents(url: String): UriComponents {
    if (url.any { it == ' ' || it == '\n' || it == '"' || it == '<' || it == '>' }) throw IllegalArgumentException("Malformed URL: $url")
    val m = URI.matchEntire(url) ?: throw IllegalArgumentException("Malformed URL: $url")
    val scheme = m.groupValues[1].ifEmpty { null }
    val hostRaw = m.groups[2]?.value
    val host = hostRaw?.takeIf { it.isNotEmpty() }
    val path = m.groupValues[3].let { p -> runCatching { jsDecodeUriComponent(p) }.getOrDefault(p) }
    return UriComponents(scheme, host, path.ifEmpty { if (scheme != null && hostRaw == null) null else "" })
}

actual fun parseIsoLocalDateTimeUtcMillis(text: String): Long = parseIsoMillis(text, requireOffset = false)

actual fun utcYearOf(epochMillis: Long): Int = civilFromDays(floorDiv(epochMillis, DAY_MS)).first

actual fun <T> concurrentSetOf(): MutableSet<T> = LinkedHashSet()

actual fun defaultLanguageCode(): String = jsLanguage().substringBefore('-').lowercase().ifBlank { "en" }

// ── ISO-8601 dates without java.time ──────────────────────────────────────

private const val DAY_MS = 86_400_000L

private fun floorDiv(a: Long, b: Long): Long { val q = a / b; return if ((a % b != 0L) && ((a < 0) != (b < 0))) q - 1 else q }

/** Days since 1970-01-01 for a proleptic Gregorian date (Howard Hinnant's algorithm). */
internal fun daysFromCivil(y0: Int, m: Int, d: Int): Long {
    val y = if (m <= 2) y0 - 1 else y0
    val era = (if (y >= 0) y else y - 399) / 400
    val yoe = y - era * 400
    val doy = (153 * (m + (if (m > 2) -3 else 9)) + 2) / 5 + d - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era.toLong() * 146097 + doe - 719468
}

/** (year, month, day) for days since 1970-01-01. */
internal fun civilFromDays(z0: Long): Triple<Int, Int, Int> {
    val z = z0 + 719468
    val era = (if (z >= 0) z else z - 146096) / 146097
    val doe = (z - era * 146097).toInt()
    val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
    val y = yoe + era.toInt() * 400
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val d = doy - (153 * mp + 2) / 5 + 1
    val m = mp + (if (mp < 10) 3 else -9)
    return Triple(if (m <= 2) y + 1 else y, m, d)
}

private val ISO = Regex("^([+-]?\\d{4,9})-(\\d{2})-(\\d{2})[Tt ](\\d{2}):(\\d{2})(?::(\\d{2})(?:[.,](\\d{1,9}))?)?(Z|z|[+-]\\d{2}(?::?\\d{2})?)?$")

/** Epoch millis of an ISO date-time; with [requireOffset] it must end in Z
 *  or an offset (like Instant / OffsetDateTime), otherwise it must not
 *  (LocalDateTime, read as UTC). Throws when it can't be read. */
internal fun parseIsoMillis(text: String, requireOffset: Boolean): Long {
    val m = ISO.matchEntire(text.trim()) ?: throw IllegalArgumentException("Text '$text' could not be parsed")
    val g = m.groupValues
    val offsetText = g[8]
    if (requireOffset && offsetText.isEmpty()) throw IllegalArgumentException("Text '$text' has no offset")
    if (!requireOffset && offsetText.isNotEmpty()) throw IllegalArgumentException("Text '$text' has an offset")
    val year = g[1].toInt(); val month = g[2].toInt(); val day = g[3].toInt()
    val hour = g[4].toInt(); val minute = g[5].toInt(); val second = g[6].ifEmpty { "0" }.toInt()
    if (month !in 1..12 || day !in 1..31 || hour > 23 || minute > 59 || second > 60) throw IllegalArgumentException("Text '$text' is out of range")
    val millis = g[7].ifEmpty { "0" }.padEnd(3, '0').take(3).toInt()
    var offsetMs = 0L
    if (offsetText.isNotEmpty() && offsetText != "Z" && offsetText != "z") {
        val sign = if (offsetText[0] == '-') -1 else 1
        val digits = offsetText.substring(1).replace(":", "")
        val oh = digits.take(2).toInt()
        val om = digits.drop(2).ifEmpty { "0" }.toInt()
        offsetMs = sign * (oh * 3_600_000L + om * 60_000L)
    }
    return daysFromCivil(year, month, day) * DAY_MS + hour * 3_600_000L + minute * 60_000L + second * 1000L + millis - offsetMs
}

/** "2026-09-29T17:04:05.123Z" (like Instant.toString(): no millis when they're zero). */
internal fun isoString(epochMillis: Double): String {
    val ms = epochMillis.toLong()
    val days = floorDiv(ms, DAY_MS)
    val (y, mo, d) = civilFromDays(days)
    val inDay = ms - days * DAY_MS
    val h = (inDay / 3_600_000L).toInt()
    val mi = ((inDay / 60_000L) % 60).toInt()
    val s = ((inDay / 1000L) % 60).toInt()
    val milli = (inDay % 1000L).toInt()
    fun p(n: Int, w: Int = 2) = n.toString().padStart(w, '0')
    return "${p(y, 4)}-${p(mo)}-${p(d)}T${p(h)}:${p(mi)}:${p(s)}" + (if (milli != 0) "." + p(milli, 3) else "") + "Z"
}
