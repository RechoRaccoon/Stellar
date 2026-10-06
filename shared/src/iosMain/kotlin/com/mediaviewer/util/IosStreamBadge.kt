package com.mediaviewer.util

import com.mediaviewer.platform.IosContext
import com.mediaviewer.repository.BlueskyRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Bluesky's "Live" badge for a stream started from Stellar (the Go Live
 * popup's optional Stream link): put on your profile, linking to the
 * stream, while it runs, and taken down when it ends. The same record
 * Android's LiveLinkManager.setStreamLive writes.
 */
object IosStreamBadge {
    /** The longest Bluesky lets a Live status run; renewed hourly while live. */
    private const val MAX_DURATION_MINUTES = 240

    suspend fun setLive(streamUrl: String, title: String): Result<Unit> = withContext(Dispatchers.IO) {
        var result = withFreshToken { repo, token, did ->
            repo.setLiveNowStatus(token, did, streamUrl, title, MAX_DURATION_MINUTES)
        }.map { }
        // Going live often happens right as the phone switches its network
        // over to the stream upload — one retry covers a dropped request.
        if (result.isFailure && result.exceptionOrNull()?.message?.contains("setLiveNowStatus failed") != true) {
            delay(2_000)
            result = withFreshToken { repo, token, did ->
                repo.setLiveNowStatus(token, did, streamUrl, title, MAX_DURATION_MINUTES)
            }.map { }
        }
        result
    }

    suspend fun clear(): Result<Unit> = withContext(Dispatchers.IO) {
        withFreshToken { repo, token, did -> repo.clearLiveNowStatus(token, did) }
    }

    /**
     * Turns whatever was typed in the "Stream link" field into a full URL:
     *  - `https://twitch.tv/name`, `twitch.tv/name`, `www.youtube.com/@name`
     *    → https:// added where missing;
     *  - a bare channel name (`name` or `@name`) → the platform is taken
     *    from the RTMP server the stream goes to (Twitch, YouTube, Kick),
     *    defaulting to Twitch.
     * Null for blank input.
     */
    fun normalizeStreamLink(input: String, rtmpServer: String): String? {
        val raw = input.trim()
        if (raw.isBlank()) return null
        if (raw.startsWith("http://", true) || raw.startsWith("https://", true)) return tidyUrl(raw)
        if (raw.contains('.') && !raw.startsWith("@")) return tidyUrl("https://$raw")
        val name = raw.removePrefix("@").trim('/')
        if (name.isBlank()) return null
        val server = rtmpServer.lowercase()
        return when {
            "youtube" in server -> "https://www.youtube.com/@$name/live"
            "kick" in server -> "https://kick.com/$name"
            else -> "https://twitch.tv/$name"
        }
    }

    /** Scheme and host lower-cased (Bluesky matches its list of live-badge
     *  services against the host as written); bare youtube.com / twitch.tv
     *  get their usual form. The path is left exactly as typed. */
    private fun tidyUrl(url: String): String {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd < 0) return url
        val scheme = url.substring(0, schemeEnd).lowercase()
        val rest = url.substring(schemeEnd + 3)
        val cut = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }.let { if (it < 0) rest.length else it }
        var host = rest.substring(0, cut).lowercase()
        if (host == "youtube.com" || host == "m.youtube.com") host = "www.youtube.com"
        if (host == "www.twitch.tv" || host == "m.twitch.tv") host = "twitch.tv"
        return "https://$host${rest.substring(cut)}".let { if (scheme == "http" || scheme == "https") it else url }
    }

    /** Runs [block] with the signed-in session, refreshing it once if it
     *  has gone stale. */
    private suspend fun <T> withFreshToken(
        block: suspend (repo: BlueskyRepository, token: String, did: String) -> Result<T>
    ): Result<T> {
        val p = PreferencesManager(IosContext)
        val repo = BlueskyRepository().apply { updateServiceUrl(p.bskyServiceUrl.first()) }
        val token = p.bskyAccessJwt.first().orEmpty()
        val did = p.bskyDid.first().orEmpty()
        if (token.isBlank() || did.isBlank()) return Result.failure(IllegalStateException("Not logged in to Bluesky"))
        val first = block(repo, token, did)
        val message = first.exceptionOrNull()?.message
        val isAuthError = message != null && (message.contains("401") || message.contains("400") ||
            message.contains("ExpiredToken", true) || message.contains("InvalidToken", true))
        if (first.isSuccess || !isAuthError) return first
        val refreshJwt = p.bskyRefreshJwt.first().orEmpty()
        if (refreshJwt.isBlank()) return first
        val refreshed = repo.refreshToken(refreshJwt).getOrNull() ?: return first
        p.saveBskySession(refreshed.accessJwt, refreshed.refreshJwt, refreshed.did, refreshed.handle)
        return block(repo, refreshed.accessJwt, refreshed.did)
    }
}
