package com.mediaviewer.util

import com.mediaviewer.platform.civilFromDays

/** The browser has no java.time: the few patterns Stellar uses are
 *  formatted here, with the browser's own month/day names and time zone. */
actual object DateText {
    actual fun format(epochMillis: Long, pattern: String, utc: Boolean): String {
        val offsetMin = if (utc) 0 else jsTimezoneOffsetMinutes(epochMillis.toDouble())
        val local = epochMillis + offsetMin * 60_000L
        val days = local.floorDiv(86_400_000L)
        val (y, mo, d) = civilFromDays(days)
        val inDay = local - days * 86_400_000L
        val h24 = (inDay / 3_600_000L).toInt()
        val mi = ((inDay / 60_000L) % 60).toInt()
        val s = ((inDay / 1000L) % 60).toInt()
        val weekday = ((days % 7 + 11) % 7).toInt() // 0 = Sunday (1970-01-01 was a Thursday)
        val out = StringBuilder()
        var i = 0
        while (i < pattern.length) {
            val c = pattern[i]
            if (c == '\'') {
                val end = pattern.indexOf('\'', i + 1).let { if (it < 0) pattern.length else it }
                if (end == i + 1) out.append('\'') else out.append(pattern, i + 1, end)
                i = end + 1
                continue
            }
            if (!c.isLetter()) { out.append(c); i++; continue }
            var n = 1
            while (i + n < pattern.length && pattern[i + n] == c) n++
            fun num(v: Int) = out.append(v.toString().padStart(n, '0'))
            when (c) {
                'y', 'u' -> if (n == 2) out.append((y % 100).toString().padStart(2, '0')) else num(y)
                'M', 'L' -> when {
                    n >= 4 -> out.append(monthName(mo, long = true))
                    n == 3 -> out.append(monthName(mo, long = false))
                    else -> num(mo)
                }
                'd' -> num(d)
                'H' -> num(h24)
                'h' -> num(((h24 + 11) % 12) + 1)
                'm' -> num(mi)
                's' -> num(s)
                'a' -> out.append(if (h24 < 12) "AM" else "PM")
                'E' -> out.append(dayName(weekday, long = n >= 4))
                else -> repeat(n) { out.append(c) }
            }
            i += n
        }
        return out.toString()
    }

    actual fun groupedInteger(n: Long): String = jsGroupedInteger(n.toDouble())

    actual fun utcHourToLocal(hourUtc: Int): Int {
        val offsetMin = jsTimezoneOffsetMinutes(com.mediaviewer.platform.currentTimeMillis().toDouble())
        return (((hourUtc * 60 + offsetMin) % 1440 + 1440) % 1440) / 60
    }

    private fun monthName(month: Int, long: Boolean): String = jsMonthName(month - 1, long)
    private fun dayName(weekday: Int, long: Boolean): String = jsDayName(weekday, long)
}

/** Minutes to add to UTC for local time at [epochMillis] (Date's offset, negated). */
private fun jsTimezoneOffsetMinutes(epochMillis: Double): Int = js("-new Date(epochMillis).getTimezoneOffset()")
private fun jsGroupedInteger(n: Double): String = js("n.toLocaleString(undefined, { maximumFractionDigits: 0 })")
private fun jsMonthName(month: Int, long: Boolean): String = js("new Date(Date.UTC(2020, month, 15)).toLocaleString(undefined, { month: long ? 'long' : 'short', timeZone: 'UTC' })")
private fun jsDayName(weekday: Int, long: Boolean): String = js("new Date(Date.UTC(2020, 7, 2 + weekday)).toLocaleString(undefined, { weekday: long ? 'long' : 'short', timeZone: 'UTC' })")
