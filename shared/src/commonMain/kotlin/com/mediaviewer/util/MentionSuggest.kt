package com.mediaviewer.util

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The composer's @mention autocomplete: given what's been typed after "@",
 * the handle the person most likely means. Mutuals come first, then the
 * accounts you follow, then whoever else Bluesky's typeahead finds; only a
 * handle that actually starts with what's typed counts (the rest of it is
 * shown dimmed after the caret).
 *
 * Lookups go through [search] (set by MainViewModel) and are cached per
 * prefix, so typing a handle letter by letter costs at most one request per
 * new letter, and backspacing costs none.
 */
object MentionSuggest {
    class Candidate(val handle: String, val mutual: Boolean, val following: Boolean, val displayName: String = "")

    /** Bluesky's actor typeahead, signed in (so it already leans toward the
     *  people you follow). Null while signed out. */
    var search: (suspend (String) -> List<Candidate>)? = null

    private val cache = LruMap<String, String>(200)
    private val misses = LruMap<String, Boolean>(200)
    private val lock = Mutex()

    /** Whose follows the answers are ranked by; a change (switching
     *  accounts) forgets what was learned. */
    var account: () -> String = { "" }
    private var cachedFor = ""

    /** The best handle starting with [typed] (no "@"), or null. */
    suspend fun complete(typed: String): String? {
        val q = typed.lowercase()
        if (q.isEmpty() || q.length > 64) return null
        lock.withLock {
            val who = account()
            if (who != cachedFor) { cache.clear(); misses.clear(); cachedFor = who }
            cache[q]?.let { return it }
            if (misses[q] == true) return null
            // A longer prefix of something already found needs no request.
            for (n in q.length - 1 downTo 1) {
                val known = cache[q.substring(0, n)] ?: continue
                if (known.startsWith(q)) { cache[q] = known; return known }
                break
            }
        }
        val found = runCatching { search?.invoke(q) }.getOrNull() ?: return null
        val best = found
            .filter { it.handle.lowercase().startsWith(q) && it.handle.lowercase() != q }
            // Stable sort: inside each group the typeahead's own order stays.
            .sortedBy { if (it.mutual) 0 else if (it.following) 1 else 2 }
            .firstOrNull()?.handle?.lowercase()
        lock.withLock { if (best != null) cache[q] = best else misses[q] = true }
        return best
    }

    private val accountCache = LruMap<String, Candidate>(200)
    private val accountMisses = LruMap<String, Boolean>(200)

    /**
     * The account the person most likely means by [typed] (a handle's start,
     * or a display name's — any of its words — with or without "@"), or
     * null. A handle match beats a name match; then mutuals, the accounts
     * you follow, and whoever else.
     */
    suspend fun account(typed: String): Candidate? {
        val q = typed.trim().removePrefix("@").lowercase()
        if (q.isEmpty() || q.length > 64) return null
        lock.withLock {
            val who = account()
            if (who != cachedFor) { cache.clear(); misses.clear(); accountCache.clear(); accountMisses.clear(); cachedFor = who }
            accountCache[q]?.let { return it }
            if (accountMisses[q] == true) return null
        }
        val found = runCatching { search?.invoke(q) }.getOrNull() ?: return null
        fun nameMatches(c: Candidate): Boolean {
            val name = c.displayName.lowercase()
            return name.startsWith(q) || name.split(' ', '-', '_', '.').any { it.startsWith(q) }
        }
        val best = found
            .filter { it.handle.lowercase().startsWith(q) || nameMatches(it) }
            .sortedWith(compareBy({ if (it.handle.lowercase().startsWith(q)) 0 else 1 }, { if (it.mutual) 0 else if (it.following) 1 else 2 }))
            .firstOrNull()
        lock.withLock { if (best != null) accountCache[q] = best else accountMisses[q] = true }
        return best
    }
}
