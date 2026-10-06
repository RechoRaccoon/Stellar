package com.mediaviewer.util

import com.mediaviewer.network.RockskyApi
import com.mediaviewer.network.RockskyMatchedSongDto
import com.mediaviewer.platform.PlatformContext
import com.mediaviewer.platform.currentTimeMillis
import com.mediaviewer.platform.nanoTime
import com.mediaviewer.platform.nowIsoString
import com.mediaviewer.platform.sharedPreferences
import com.mediaviewer.repository.BlueskyRepository
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
    RETRY
}

/** An app the scrobbler can listen to, for Settings › Integrations › Apps. */
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
    val apps: List<ScrobbleApp> = emptyList()
)

/**
 * "Scrobble Music to Rocksky" (a supporter feature): the part that turns a
 * finished listen into records in your own AT Protocol repo — the same four
 * records, with the same contents, that Rocksky's server writes when its
 * own app scrobbles (apps/api/src/nowplaying/nowplaying.service.ts in
 * github.com/tsirysndr/rocksky):
 *
 *  - `app.rocksky.song`, `app.rocksky.artist`, `app.rocksky.album` — once
 *    each, the first time you play that song / artist / album;
 *  - `app.rocksky.scrobble` — one for every listen, dated when it began.
 *
 * Rocksky's app doesn't write these itself: it hands the listen to
 * Rocksky's server (app.rocksky.scrobble.createScrobble), which needs a
 * Rocksky sign-in Stellar doesn't have. Stellar writes them straight to
 * your repo with your Bluesky session instead. Rocksky watches the network
 * for `app.rocksky.scrobble` records from anyone (crates/jetstream), so the
 * listen shows up on Rocksky the same way.
 *
 * Before writing, the song is looked up with Rocksky's public matcher
 * (app.rocksky.song.matchSong), which supplies what a music app's
 * notification doesn't: album art, MusicBrainz ids, ISRC, release date,
 * genres. Title, artist and album stay exactly as your player gave them.
 *
 * Shared code, so it's compiled and checked for both platforms; only
 * Android has the listener that feeds it (androidMain/.../scrobble).
 */
object RockskyScrobbler {
    /**
     * The apps listed under Settings › Integrations › Apps, in the order
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

    /** Rocksky's stand-in for "no album art" (Last.fm's blank cover): its
     *  records always carry an art URL. */
    private const val DEFAULT_ALBUM_ART = "https://lastfm.freetls.fastly.net/i/u/300x300/2a96cbd8b46e442fc41c2b86b821562f.png"

    private const val PREFS = "rocksky_scrobble_known"
    private val rocksky by lazy { RockskyApi() }

    /** Rocksky's own tidying of names: trimmed, and the curly apostrophe
     *  made straight so "Guns N’ Roses" and "Guns N' Roses" are one artist. */
    private fun canonical(text: String): String = text.trim().replace('’', '\'')

    /**
     * Writes [track] to the repo of [did]. Never throws. Call from a
     * background thread; it can take ten seconds or so.
     */
    suspend fun upload(context: PlatformContext, did: String, track: ScrobbleTrack): ScrobbleUploadOutcome {
        return try {
            val prefs = PreferencesManager(context)
            var token = prefs.bskyAccessJwt.first().orEmpty()
            val sessionDid = prefs.bskyDid.first().orEmpty()
            if (token.isBlank() || sessionDid != did) return ScrobbleUploadOutcome.AUTH
            val repo = BlueskyRepository().apply { updateServiceUrl(prefs.bskyServiceUrl.first()) }

            val title = canonical(track.title)
            val artist = canonical(track.artist)
            if (title.isEmpty() || artist.isEmpty()) return ScrobbleUploadOutcome.REJECTED
            // (Rocksky's server needs an album and album artist too; its app
            // fills them in the same way when a player gives none.)
            val album = canonical(track.album).ifEmpty { title }
            val albumArtist = canonical(track.albumArtist).ifEmpty { artist }

            val match = withTimeoutOrNull(12_000) {
                runCatching { rocksky.matchSong(title, artist, track.album.trim().ifEmpty { null }) }.getOrNull()
                    ?.takeIf { it.isSuccessful }?.body()
            }
            val fields = Fields(title, artist, album, albumArtist, track, match)

            /** One write, with the session renewed once if it has run out. */
            suspend fun put(collection: String, record: Map<String, Any>): Result<String> {
                var result = repo.putRepoRecord(token, did, collection, newTid(), record)
                val message = result.exceptionOrNull()?.message.orEmpty()
                if (result.isFailure && isAuthError(message)) {
                    val refreshJwt = prefs.bskyRefreshJwt.first().orEmpty()
                    val refreshed = if (refreshJwt.isBlank()) null else repo.refreshToken(refreshJwt).getOrNull()
                    if (refreshed != null && refreshed.did == did) {
                        prefs.saveBskySession(refreshed.accessJwt, refreshed.refreshJwt, refreshed.did, refreshed.handle)
                        token = refreshed.accessJwt
                        result = repo.putRepoRecord(token, did, collection, newTid(), record)
                    }
                }
                return result
            }

            // The song, its artist and its album: once each per account, as
            // Rocksky does. (A failure here is logged by Rocksky and passed
            // over; the listen itself still counts.)
            val known = context.sharedPreferences(PREFS)
            suspend fun once(kind: String, key: String, collection: String, record: () -> Map<String, Any>) {
                val name = "$kind:$did"
                val seen = known.getStringSet(name, null) ?: emptySet()
                val id = key.lowercase()
                if (id in seen) return
                if (put(collection, record()).isSuccess) {
                    // (Kept to a few thousand: past that it simply starts over.)
                    known.edit().putStringSet(name, if (seen.size >= 4000) setOf(id) else seen + id).apply()
                }
            }
            once("songs", "$title\n$artist\n$album", "app.rocksky.song") { fields.song() }
            once("artists", albumArtist, "app.rocksky.artist") { fields.artist() }
            once("albums", "$album\n$albumArtist", "app.rocksky.album") { fields.album() }

            val result = put("app.rocksky.scrobble", fields.scrobble())
            if (result.isSuccess) return ScrobbleUploadOutcome.DONE
            val message = result.exceptionOrNull()?.message.orEmpty()
            when {
                isAuthError(message) -> ScrobbleUploadOutcome.AUTH
                // (The PDS read it and said no: sending it again won't help.)
                message.startsWith("putRecord failed: 400") || message.startsWith("putRecord failed: 413") ||
                    message.startsWith("putRecord failed: 422") -> ScrobbleUploadOutcome.REJECTED
                else -> ScrobbleUploadOutcome.RETRY
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Throwable) {
            ScrobbleUploadOutcome.RETRY
        }
    }

    private fun isAuthError(message: String): Boolean =
        message.startsWith("putRecord failed: 401") || message.contains("ExpiredToken", true) ||
            message.contains("InvalidToken", true) || message.contains("AuthMissing", true)

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
