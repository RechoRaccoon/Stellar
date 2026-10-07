package com.mediaviewer.util

import com.mediaviewer.network.RockskyApi
import com.mediaviewer.network.RockskyMatchedSongDto
import com.mediaviewer.platform.PlatformContext
import com.mediaviewer.platform.currentTimeMillis
import com.mediaviewer.platform.nanoTime
import com.mediaviewer.platform.nowIsoString
import com.mediaviewer.repository.BlueskyRepository
import com.mediaviewer.json.JSONObject
import com.mediaviewer.network.PlainHttp
import com.mediaviewer.network.bodyString
import com.mediaviewer.network.isSuccessful
import com.mediaviewer.worker.BlueskyBlobResolver
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** One listen, as the phone's music apps reported it. */
data class ScrobbleTrack(
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String,
    /** Milliseconds; 0 when the player didn't say. */
    val durationMs: Long,
    /** When the listen began, seconds since 1970. */
    val timestampSeconds: Long
)

enum class ScrobbleUploadOutcome {
    /** Written (or already there). */
    DONE,
    /** Not signed in as the account the listen belongs to: keep it, stop for now. */
    AUTH,
    /** The server refused this one for good: set it aside. */
    REJECTED,
    /** No connection or a server hiccup: try again later. */
    RETRY,
    /** The account's server said "too many writes for now": wait a good while. */
    LIMITED
}

/**
 * What's already in an account's repo, so nothing is written twice — the
 * job Rocksky's own tools give a local "dedup index" (sdk/typescript/src/
 * dedup.ts). Each entry stands for one record: an artist, an album, a
 * song, or one listen of a song at one second.
 */
interface ScrobbleIndex {
    fun has(key: String): Boolean
    fun addAll(keys: List<String>)
}

/** The song a music app is playing right now, for the "Listening to" status. */
class NowPlayingTrack(
    val title: String, val artist: String, val album: String,
    val durationMs: Long, val positionMs: Long,
    /** The player's name, e.g. "Spotify". */
    val source: String
)

/** One imported history file and how far along it is. */
data class ScrobbleImport(val id: Long, val name: String, val done: Int, val total: Int, val failed: Int)

/** An app the scrobbler can listen to, for Settings › Integrations › Scrobble Settings. */
data class ScrobbleApp(val packageName: String, val label: String, val on: Boolean)

/** How "Scrobble Music to Rocksky" is doing, for its row in Settings. */
data class ScrobblerStatus(
    /** False where the phone can't do this at all (iOS). */
    val supported: Boolean = false,
    val enabled: Boolean = false,
    /** Android's "notification access" has been given to Stellar. */
    val access: Boolean = false,
    /** Android has the listener connected right now. */
    val connected: Boolean = false,
    /** Android 13+: the notification permission still has to be asked for. */
    val needsNotificationPermission: Boolean = false,
    /** Listens saved on the phone, waiting to be sent. */
    val queued: Int = 0,
    val error: String? = null,
    val apps: List<ScrobbleApp> = emptyList(),
    /** "Scrobble after …% or …:… of the track", whichever comes first. */
    val percent: Int = RockskyScrobbler.DEFAULT_PERCENT,
    val seconds: Int = RockskyScrobbler.DEFAULT_SECONDS,
    /** History files being imported, in the order they'll be worked on. */
    val imports: List<ScrobbleImport> = emptyList(),
    /** A picked file is still being read. */
    val importReading: Boolean = false,
    /** Why the last picked file couldn't be imported, or how the import is held up. */
    val importMessage: String? = null,
    /** "Work in Background" is on. */
    val importBackground: Boolean = false,
    /** The phone may still pause background work to save battery. */
    val importBatteryRestricted: Boolean = false
)

/**
 * "Scrobble Music to Rocksky" (a supporter feature): the part that turns a
 * listen into records in your own AT Protocol repo.
 *
 * This is the way Rocksky's official SDK and its `rocksky import` command
 * do it (sdk/typescript/src/agent.ts in github.com/tsirysndr/rocksky):
 * signed in with your Bluesky account, they write straight to your PDS —
 *
 *  - `app.rocksky.artist`, `app.rocksky.album`, `app.rocksky.song` — once
 *    each, in that order, the first time that artist / album / song turns
 *    up (what's already in your repo is looked up first, see [syncIndex]);
 *  - `app.rocksky.scrobble` — one for every listen, dated when it began;
 *  - `app.rocksky.actor.status` (always the one record, "self") — what
 *    you're listening to right now, removed again when the music stops.
 *
 * Rocksky watches the network for these records from anyone, so they show
 * up on Rocksky the same as its own. (Rocksky's Android app takes another
 * road: it signs in to Rocksky's server, which then writes the records for
 * it. That sign-in is Rocksky's own and can't be borrowed by another app;
 * the SDK's road is the one meant for everyone else.)
 *
 * Before writing, the song is looked up with Rocksky's public matcher
 * (app.rocksky.song.matchSong), which supplies what a music app's
 * notification doesn't: album art, MusicBrainz ids, ISRC, release date,
 * genres. The records carry the same fields Rocksky's server fills in
 * (apps/api/src/nowplaying/nowplaying.service.ts).
 *
 * Shared code, so it's compiled and checked for both platforms; only
 * Android has the listener and the importer that feed it
 * (androidMain/.../scrobble).
 */
object RockskyScrobbler {
    /**
     * The apps listed under Settings › Integrations › Scrobble Settings, in the order
     * they're shown: the five asked for first, then the rest of what
     * Rocksky's own app lists (apps/app/src/screens/Account/Scrobbling.tsx).
     * The last four don't play music — they recognise what's playing
     * around you, and Rocksky scrobbles what they find.
     */
    val KNOWN_APPS: List<Pair<String, String>> = listOf(
        "com.spotify.music" to "Spotify",
        "com.google.android.apps.youtube.music" to "YouTube Music",
        "com.google.android.youtube" to "YouTube",
        "com.apple.android.music" to "Apple Music",
        "com.soundcloud.android" to "SoundCloud",
        "com.aspiro.tidal" to "Tidal",
        "deezer.android.app" to "Deezer",
        "com.shazam.android" to "Shazam / Auto Shazam",
        "com.google.android.as" to "Pixel Now Playing (legacy)",
        "com.google.intelligence.sense" to "Pixel Ambient Services",
        "com.kieronquinn.app.pixelambientmusic" to "Ambient Music Mod",
        "com.mrsep.musicrecognizer" to "Audile"
    )

    /**
     * Other builds of the same players: patched YouTube and YouTube Music
     * (ReVanced and its relatives, and the old Vanced) are installed under
     * names of their own. Switching on "YouTube" or "YouTube Music" covers
     * these too.
     */
    val APP_ALIASES: Map<String, List<String>> = mapOf(
        "com.google.android.youtube" to listOf(
            "app.revanced.android.youtube", "app.rvx.android.youtube", "anddea.youtube",
            "app.morphe.android.youtube", "com.vanced.android.youtube"
        ),
        "com.google.android.apps.youtube.music" to listOf(
            "app.revanced.android.apps.youtube.music", "app.rvx.android.apps.youtube.music", "anddea.youtube.music",
            "app.morphe.android.apps.youtube.music", "com.vanced.android.apps.youtube.music"
        )
    )

    /** Rocksky's own timing: a listen counts after half the song or four
     *  minutes of it, whichever comes first. */
    const val DEFAULT_PERCENT = 50
    const val DEFAULT_SECONDS = 240

    /** Rocksky's stand-in for "no album art" (Last.fm's blank cover): its
     *  records always carry an art URL. */
    private const val DEFAULT_ALBUM_ART = "https://lastfm.freetls.fastly.net/i/u/300x300/2a96cbd8b46e442fc41c2b86b821562f.png"

    private val rocksky by lazy { RockskyApi() }

    /** Rocksky's own tidying of names: trimmed, and the curly apostrophe
     *  made straight so "Guns N’ Roses" and "Guns N' Roses" are one artist. */
    private fun canonical(text: String): String = text.trim().replace('’', '\'')

    /**
     * A signed-in account to write to: the Bluesky session Stellar already
     * has, renewed once (and saved) if it turns out to have run out.
     */
    private class Session(val did: String, val prefs: PreferencesManager, val repo: BlueskyRepository, var token: String) {
        suspend fun <T> call(write: suspend (String) -> Result<T>): Result<T> {
            var result = write(token)
            if (result.isFailure && isAuthError(result.exceptionOrNull()?.message.orEmpty())) {
                val refreshJwt = prefs.bskyRefreshJwt.first().orEmpty()
                val refreshed = if (refreshJwt.isBlank()) null else repo.refreshToken(refreshJwt).getOrNull()
                if (refreshed != null && refreshed.did == did) {
                    prefs.saveBskySession(refreshed.accessJwt, refreshed.refreshJwt, refreshed.did, refreshed.handle)
                    token = refreshed.accessJwt
                    result = write(token)
                }
            }
            return result
        }
    }

    /** Null unless Stellar is signed in as [did] right now. */
    private suspend fun session(context: PlatformContext, did: String): Session? {
        val prefs = PreferencesManager(context)
        val token = prefs.bskyAccessJwt.first().orEmpty()
        if (token.isBlank() || did.isBlank() || prefs.bskyDid.first().orEmpty() != did) return null
        return Session(did, prefs, BlueskyRepository().apply { updateServiceUrl(prefs.bskyServiceUrl.first()) }, token)
    }

    // ── Pacing ───────────────────────────────────────────────────────────
    // Bluesky's servers allow an account about 5,000 "points" of writing an
    // hour, and every record written costs 3. A history import would use
    // that up in minutes, so its writes are spaced out — the same sum
    // Rocksky's SDK does (5,000 × 0.9 ÷ 3 = 1,500 an hour), less a little
    // kept back for the songs you're scrobbling live meanwhile.
    private const val IMPORT_WRITES_PER_HOUR = 1400
    private var nextWriteAt = 0L
    private suspend fun waitForWriteSlot() {
        val now = currentTimeMillis()
        val at = maxOf(now, nextWriteAt)
        nextWriteAt = at + 3_600_000L / IMPORT_WRITES_PER_HOUR
        if (at > now) delay(at - now)
    }

    // ── What's already there ─────────────────────────────────────────────
    // The names Rocksky itself goes by (sdk/typescript/src/hash.ts): a song
    // is "title - artist - album", an album "album - album artist", an
    // artist its name — all in lower case.
    private fun artistKey(did: String, name: String) = "$did|A|" + name.lowercase()
    private fun albumKey(did: String, album: String, albumArtist: String) = "$did|L|" + "$album - $albumArtist".lowercase()
    private fun songKey(did: String, title: String, artist: String, album: String) = "$did|S|" + "$title - $artist - $album".lowercase()
    private fun playKey(did: String, title: String, artist: String, album: String, seconds: Long) =
        "$did|P|" + "$title - $artist - $album".lowercase() + "|" + seconds
    private fun syncedKey(did: String, collection: String) = "$did|synced|$collection"

    private fun isoSeconds(iso: String): Long? =
        (runCatching { com.mediaviewer.platform.parseIsoInstantMillis(iso) }.getOrNull()
            ?: runCatching { com.mediaviewer.platform.parseIsoOffsetDateTimeMillis(iso) }.getOrNull())?.floorDiv(1000L)

    /**
     * Reads which artists, albums, songs and listens [did]'s repo already
     * holds into [index] — whoever wrote them (Rocksky, another scrobbler,
     * Stellar on another phone). The first time it reads everything, a
     * hundred records a request; after that only what's new since, newest
     * first, stopping at the first page it has seen all of. False if it
     * couldn't finish (no connection): nothing is lost, it carries on next
     * time.
     */
    suspend fun syncIndex(did: String, index: ScrobbleIndex): Boolean {
        return try {
            val pds = BlueskyBlobResolver.pdsEndpoint(did)
            for (collection in listOf("app.rocksky.artist", "app.rocksky.album", "app.rocksky.song", "app.rocksky.scrobble")) {
                val caughtUp = index.has(syncedKey(did, collection))
                var cursor: String? = null
                while (true) {
                    val query = mutableListOf("repo" to did, "collection" to collection, "limit" to "100")
                    cursor?.let { query += "cursor" to it }
                    val response = PlainHttp.get("$pds/xrpc/com.atproto.repo.listRecords", query)
                    if (!response.isSuccessful) return false
                    val body = JSONObject(response.bodyString())
                    val records = body.optJSONArray("records")
                    val keys = ArrayList<String>()
                    for (i in 0 until (records?.length() ?: 0)) {
                        val v = records?.optJSONObject(i)?.optJSONObject("value") ?: continue
                        val title = v.optString("title"); val artist = v.optString("artist"); val album = v.optString("album")
                        val key = when (collection) {
                            "app.rocksky.artist" -> v.optString("name").takeIf { it.isNotEmpty() }?.let { artistKey(did, it) }
                            "app.rocksky.album" -> if (title.isNotEmpty() && artist.isNotEmpty()) albumKey(did, title, artist) else null
                            "app.rocksky.song" -> if (title.isNotEmpty() && artist.isNotEmpty() && album.isNotEmpty()) songKey(did, title, artist, album) else null
                            else -> isoSeconds(v.optString("createdAt"))?.takeIf { title.isNotEmpty() && artist.isNotEmpty() }
                                ?.let { playKey(did, title, artist, album, it) }
                        } ?: continue
                        if (!index.has(key)) keys += key
                    }
                    index.addAll(keys)
                    cursor = body.optString("cursor").takeIf { it.isNotBlank() }
                    if (cursor == null || (records?.length() ?: 0) == 0) break
                    if (caughtUp && keys.isEmpty()) break
                }
                index.addAll(listOf(syncedKey(did, collection)))
            }
            true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Throwable) {
            false
        }
    }

    /** True once [syncIndex] has read [did]'s whole repo at least once. */
    fun isIndexed(did: String, index: ScrobbleIndex): Boolean = index.has(syncedKey(did, "app.rocksky.scrobble"))

    // ── YouTube titles ───────────────────────────────────────────────────
    private val VIDEO_NOISE = Regex(
        "\\s*[(\\[]\\s*(official\\s+)?(music\\s+video|video|audio|visuali[sz]er|lyric\\s+video|lyrics?(\\s+video)?|m/?v|hd|hq|4k[^)\\]]*)\\s*[)\\]]",
        RegexOption.IGNORE_CASE
    )

    /** "Song (Official Music Video) [HD]" → "Song": a video's title with
     *  the parts that aren't the song's name taken off. */
    fun cleanVideoTitle(raw: String): String =
        raw.replace(VIDEO_NOISE, "").replace(Regex("\\s{2,}"), " ").trim().ifEmpty { raw.trim() }

    /** "Artist - Topic" (YouTube's automatic artist channels) → "Artist". */
    fun cleanChannelName(raw: String): String =
        raw.trim().replace(Regex("\\s+-\\s+Topic$", RegexOption.IGNORE_CASE), "").trim().ifEmpty { raw.trim() }

    private fun letters(text: String) = text.lowercase().filter { it.isLetterOrDigit() }

    /**
     * Writes [track] to the repo of [did]. Never throws. Call from a
     * background thread; it can take ten seconds or so.
     *
     * [history] is for imported listens: the writes are paced (see above),
     * and — as `rocksky import` does — the song goes by the name Rocksky's
     * matcher knows it under where the matcher recognises it, since an
     * export often has a looser title than a player would give.
     */
    suspend fun upload(
        context: PlatformContext, did: String, track: ScrobbleTrack, index: ScrobbleIndex, history: Boolean = false
    ): ScrobbleUploadOutcome {
        return try {
            val session = session(context, did) ?: return ScrobbleUploadOutcome.AUTH

            var title = canonical(track.title)
            var artist = canonical(track.artist)
            if (title.isEmpty() || artist.isEmpty()) return ScrobbleUploadOutcome.REJECTED

            val asked = if (history) cleanVideoTitle(title) else title
            var match = withTimeoutOrNull(12_000) {
                runCatching { rocksky.matchSong(asked, artist, track.album.trim().ifEmpty { null }) }.getOrNull()
                    ?.takeIf { it.isSuccessful }?.body()
            }?.takeIf { !it.title.isNullOrBlank() }
            var album = canonical(track.album)
            var albumArtist = canonical(track.albumArtist)
            if (history && match != null) {
                // Only a match that is plainly the same song is believed.
                val theirs = letters(match.title.orEmpty())
                val ours = letters(asked)
                if (theirs.isNotEmpty() && ours.isNotEmpty() && (theirs.contains(ours) || ours.contains(theirs))) {
                    title = canonical(match.title.orEmpty())
                    artist = canonical(match.artist.orEmpty()).ifEmpty { artist }
                    if (albumArtist.isEmpty()) albumArtist = canonical(match.albumArtist.orEmpty())
                    if (album.isEmpty()) album = canonical(match.album.orEmpty())
                } else {
                    match = null
                    title = asked
                }
            }
            // (Rocksky's records need an album and album artist; its app
            // fills them in the same way when a player gives none.)
            if (album.isEmpty()) album = title
            if (albumArtist.isEmpty()) albumArtist = artist

            // This very listen is already in the repo: nothing to do.
            val play = playKey(did, title, artist, album, track.timestampSeconds)
            if (index.has(play)) return ScrobbleUploadOutcome.DONE

            val fields = Fields(title, artist, album, albumArtist, track, match)
            suspend fun put(collection: String, record: Map<String, Any>): Result<String> {
                if (history) waitForWriteSlot()
                return session.call { token -> session.repo.putRepoRecord(token, did, collection, newTid(), record) }
            }

            // The artist, the album and the song, in that order and once
            // each per account. (A failure here is passed over, as Rocksky
            // does; the listen itself still counts.)
            suspend fun once(key: String, collection: String, record: () -> Map<String, Any>) {
                if (index.has(key)) return
                if (put(collection, record()).isSuccess) index.addAll(listOf(key))
            }
            once(artistKey(did, albumArtist), "app.rocksky.artist") { fields.artist() }
            once(albumKey(did, album, albumArtist), "app.rocksky.album") { fields.album() }
            once(songKey(did, title, artist, album), "app.rocksky.song") { fields.song() }

            val result = put("app.rocksky.scrobble", fields.scrobble())
            if (result.isSuccess) {
                index.addAll(listOf(play))
                return ScrobbleUploadOutcome.DONE
            }
            val message = result.exceptionOrNull()?.message.orEmpty()
            when {
                isAuthError(message) -> ScrobbleUploadOutcome.AUTH
                message.contains("failed: 429") || message.contains("RateLimit", true) -> ScrobbleUploadOutcome.LIMITED
                // (The PDS read it and said no: sending it again won't help.)
                message.contains("failed: 400") || message.contains("failed: 413") ||
                    message.contains("failed: 422") -> ScrobbleUploadOutcome.REJECTED
                else -> ScrobbleUploadOutcome.RETRY
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Throwable) {
            ScrobbleUploadOutcome.RETRY
        }
    }

    private fun isAuthError(message: String): Boolean =
        message.contains("failed: 401") || message.contains("ExpiredToken", true) ||
            message.contains("InvalidToken", true) || message.contains("AuthMissing", true)

    // ── "Listening to" ───────────────────────────────────────────────────

    /**
     * Sets [did]'s official Rocksky status to "listening to [track]" — the
     * `app.rocksky.actor.status` record, as the SDK's setNowPlaying and
     * Rocksky's own server write it. It says when the song began and when
     * the status runs out by itself (the end of the song, plus a little),
     * so it never lingers if the phone drops off the network mid-song.
     */
    suspend fun setNowPlaying(context: PlatformContext, did: String, track: NowPlayingTrack): Boolean {
        return try {
            val session = session(context, did) ?: return false
            val title = canonical(track.title)
            val artist = canonical(track.artist)
            if (title.isEmpty() || artist.isEmpty()) return false
            val match = withTimeoutOrNull(6_000) {
                runCatching { rocksky.matchSong(title, artist, track.album.trim().ifEmpty { null }) }.getOrNull()
                    ?.takeIf { it.isSuccessful }?.body()
            }
            val duration = track.durationMs.takeIf { it > 0 } ?: match?.duration?.takeIf { it > 0 } ?: 0L
            val now = currentTimeMillis()
            val position = track.positionMs.coerceAtLeast(0)
            val view = LinkedHashMap<String, Any>().apply {
                put("name", title)
                put("artist", artist)
                canonical(track.album).takeIf { it.isNotEmpty() }?.let { put("album", it) }
                put("albumCoverUrl", match?.albumArt?.takeIf { it.startsWith("http") } ?: DEFAULT_ALBUM_ART)
                put("durationMs", duration)
                put("source", track.source.ifBlank { "Stellar" })
                match?.mbId?.takeIf { it.isNotBlank() }?.let { put("recordingMbId", it) }
                match?.trackNumber?.takeIf { it > 0 }?.let { put("trackNumber", it) }
            }
            val left = if (duration > 0) (duration - position).coerceAtLeast(0) + NOW_PLAYING_GRACE_MS else 10 * 60_000L
            val record = LinkedHashMap<String, Any>().apply {
                put("\$type", STATUS)
                put("track", view)
                put("startedAt", isoFromSeconds((now - position) / 1000))
                put("expiresAt", isoFromSeconds((now + left) / 1000))
            }
            session.call { token -> session.repo.putRepoRecord(token, did, STATUS, "self", record) }.isSuccess
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Throwable) {
            false
        }
    }

    /** Takes the "listening to" status away again (the music stopped). */
    suspend fun clearNowPlaying(context: PlatformContext, did: String): Boolean {
        return try {
            val session = session(context, did) ?: return false
            session.call { token -> session.repo.deleteRepoRecord(token, did, STATUS, "self") }.isSuccess
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Throwable) {
            false
        }
    }

    private const val STATUS = "app.rocksky.actor.status"
    /** How long past the end of a song its status is still believed. */
    const val NOW_PLAYING_GRACE_MS = 30_000L

    /**
     * Deletes one listen — the `app.rocksky.scrobble` record at [uri] —
     * from the signed-in account's repo. Rocksky notices and takes it out
     * of the history. Null on success, otherwise what went wrong.
     */
    suspend fun deleteScrobble(context: PlatformContext, uri: String): String? {
        return try {
            val parts = uri.removePrefix("at://").split('/')
            if (parts.size != 3 || parts[1] != "app.rocksky.scrobble") return "This listen can't be deleted from here."
            val session = session(context, parts[0]) ?: return "Sign in to the account this listen belongs to."
            val result = session.call { token -> session.repo.deleteRepoRecord(token, parts[0], parts[1], parts[2]) }
            if (result.isSuccess) null else "Couldn't delete it. Check your connection and try again."
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Throwable) {
            "Couldn't delete it. Check your connection and try again."
        }
    }

    /** What goes into the four records: your player's title / artist /
     *  album, plus whatever Rocksky's matcher knew. */
    private class Fields(
        val title: String, val artist: String, val album: String, val albumArtist: String,
        val track: ScrobbleTrack, val match: RockskyMatchedSongDto?
    ) {
        private val art: String = match?.albumArt?.takeIf { it.startsWith("http") } ?: DEFAULT_ALBUM_ART
        private val duration: Long = track.durationMs.takeIf { it > 0 } ?: match?.duration?.takeIf { it > 0 } ?: 1L
        private val tags: List<String> = match?.genres?.filter { it.isNotBlank() }.orEmpty()
        private val artists: List<Map<String, Any>>? = match?.mbArtists
            ?.mapNotNull { a ->
                val name = a.name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                if (a.mbid.isNullOrBlank()) mapOf<String, Any>("name" to name) else mapOf<String, Any>("name" to name, "mbid" to a.mbid)
            }?.takeIf { it.isNotEmpty() }
        private val releaseDate: String? = match?.releaseDate?.let { raw ->
            when {
                Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(raw) -> raw + "T00:00:00.000Z"
                Regex("^\\d{4}-\\d{2}-\\d{2}T.*").matches(raw) -> raw
                else -> null
            }
        }

        private fun MutableMap<String, Any>.opt(name: String, value: Any?) {
            if (value != null && !(value is String && value.isBlank())) put(name, value)
        }

        fun artist(): Map<String, Any> = LinkedHashMap<String, Any>().apply {
            put("\$type", "app.rocksky.artist")
            put("name", albumArtist)
            put("createdAt", nowIsoString())
            opt("pictureUrl", match?.artistPicture?.takeIf { it.startsWith("http") })
            put("tags", tags)
        }

        fun album(): Map<String, Any> = LinkedHashMap<String, Any>().apply {
            put("\$type", "app.rocksky.album")
            put("title", album)
            put("artist", albumArtist)
            opt("year", match?.year)
            opt("releaseDate", releaseDate)
            put("createdAt", nowIsoString())
            put("albumArtUrl", art)
        }

        fun song(): Map<String, Any> = LinkedHashMap<String, Any>().apply {
            put("\$type", "app.rocksky.song")
            put("title", title)
            put("artist", artist)
            opt("artists", artists)
            put("album", album)
            put("albumArtist", albumArtist)
            put("duration", duration)
            opt("releaseDate", releaseDate)
            opt("year", match?.year)
            put("albumArtUrl", art)
            opt("composer", match?.composer)
            opt("trackNumber", match?.trackNumber?.takeIf { it > 0 })
            put("discNumber", match?.discNumber?.takeIf { it > 0 } ?: 1)
            opt("copyrightMessage", match?.copyrightMessage)
            put("createdAt", nowIsoString())
            opt("spotifyLink", match?.spotifyLink?.takeIf { it.startsWith("http") })
            put("tags", tags)
            opt("mbid", match?.mbId)
            opt("isrc", match?.isrc)
        }

        fun scrobble(): Map<String, Any> = LinkedHashMap<String, Any>().apply {
            put("\$type", "app.rocksky.scrobble")
            put("title", title)
            put("albumArtist", albumArtist)
            put("albumArtUrl", art)
            put("artist", artist)
            opt("artists", artists)
            put("album", album)
            put("duration", duration)
            put("trackNumber", match?.trackNumber?.takeIf { it > 0 } ?: 1)
            put("discNumber", match?.discNumber?.takeIf { it > 0 } ?: 1)
            opt("releaseDate", releaseDate)
            opt("year", match?.year)
            opt("composer", match?.composer)
            opt("copyrightMessage", match?.copyrightMessage)
            // The listen's own time, not "now": a scrobble saved while
            // offline is still dated when it was played.
            put("createdAt", isoFromSeconds(track.timestampSeconds))
            opt("spotifyLink", match?.spotifyLink?.takeIf { it.startsWith("http") })
            put("tags", tags)
            opt("mbid", match?.mbId)
            opt("isrc", match?.isrc)
        }
    }

    /** "2026-06-02T13:15:09.000Z" for a time in seconds since 1970 (UTC). */
    internal fun isoFromSeconds(seconds: Long): String {
        if (seconds <= 0) return nowIsoString()
        val (y, m, d) = CalendarMath.civilFromDays(seconds.floorDiv(86400L))
        val rest = seconds.mod(86400L).toInt()
        fun two(v: Int) = v.toString().padStart(2, '0')
        return y.toString().padStart(4, '0') + "-" + two(m) + "-" + two(d) +
            "T" + two(rest / 3600) + ":" + two(rest / 60 % 60) + ":" + two(rest % 60) + ".000Z"
    }

    /** A timestamp id (TID): the record key Rocksky gives every record
     *  (TID.nextStr()) — thirteen characters that sort by time. */
    private var lastTid = 0L
    private fun newTid(): String {
        val chars = "234567abcdefghijklmnopqrstuvwxyz"
        var micros = currentTimeMillis() * 1000 + (nanoTime() / 1000).mod(1000L)
        // (Never the same one twice, even within a microsecond.)
        if (micros <= lastTid) micros = lastTid + 1
        lastTid = micros
        var v = (micros shl 10) or kotlin.random.Random.nextLong(0, 1024)
        val out = CharArray(13)
        for (i in 12 downTo 0) { out[i] = chars[(v and 31).toInt()]; v = v ushr 5 }
        return out.concatToString()
    }
}
