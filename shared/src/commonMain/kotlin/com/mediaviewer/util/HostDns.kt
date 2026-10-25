package com.mediaviewer.util

/** TEMPORARY diagnostic: the IPs the app itself resolves for [host],
 *  or null when the platform can't answer (only Android answers today).
 *  Cardyb's icon fetches 400 for every link in the app while outside
 *  replications answer 200 — this splits stale-DNS from a request
 *  difference. Remove with the diagnostic call site. */
expect suspend fun appResolvedIps(host: String): List<String>?

/** TEMPORARY diagnostic: the last cardyb request the app's HTTP client
 *  actually sent (method + URL + headers), or null. The app resolves
 *  the good cardyb IPs and still gets 400s, so the wire request itself
 *  is the last unknown. Remove with the diagnostic call site. */
expect fun lastCardybRequestLine(): String?
