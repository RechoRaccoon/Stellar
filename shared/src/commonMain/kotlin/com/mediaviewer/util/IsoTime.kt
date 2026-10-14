package com.mediaviewer.util

/** [millis] since the epoch as an ISO 8601 UTC time ("2026-10-14T12:30:00.000Z"). */
fun isoStringOf(millis: Long): String {
    val days = millis.floorDiv(86_400_000L)
    val msOfDay = millis.mod(86_400_000L)
    // Civil date from days since 1970-01-01 (Howard Hinnant's algorithm).
    val z = days + 719_468
    val era = z.floorDiv(146_097L)
    val doe = z - era * 146_097
    val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val day = doy - (153 * mp + 2) / 5 + 1
    val month = if (mp < 10) mp + 3 else mp - 9
    val year = yoe + era * 400 + if (month <= 2) 1 else 0
    fun pad(v: Long, n: Int) = v.toString().padStart(n, '0')
    val h = msOfDay / 3_600_000
    val m = msOfDay / 60_000 % 60
    val s = msOfDay / 1000 % 60
    val ms = msOfDay % 1000
    return "${pad(year, 4)}-${pad(month, 2)}-${pad(day, 2)}T${pad(h, 2)}:${pad(m, 2)}:${pad(s, 2)}.${pad(ms, 3)}Z"
}
