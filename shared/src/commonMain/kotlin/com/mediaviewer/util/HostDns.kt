package com.mediaviewer.util

/** TEMPORARY diagnostic: the IPs the app itself resolves for [host],
 *  or null when the platform can't answer (only Android answers today).
 *  Cardyb's icon fetches 400 for every link in the app while outside
 *  replications answer 200 — this splits stale-DNS from a request
 *  difference. Remove with the diagnostic call site. */
expect suspend fun appResolvedIps(host: String): List<String>?
