// This file is adapted from Rocksky (https://github.com/tsirysndr/rocksky),
// apps/app/modules/rocksky-scrobbler — RecognitionParser.kt — Copyright (c)
// 2025 Tsiry Sandratraina, used under the Mozilla Public License 2.0
// (https://mozilla.org/MPL/2.0/). This file stays available under those terms.
package com.mediaviewer.scrobble

import android.app.Notification
import android.service.notification.StatusBarNotification
import org.json.JSONObject

/**
 * Song-recognition apps (Shazam, the Pixel's Now Playing, Audile) don't
 * play music — they announce what they heard in a notification. This reads
 * the song out of those announcements, and only out of those: the known
 * "here's your match" notifications of the known apps, never any other
 * notification's text.
 */
object RecognitionParser {
    val ambientPackages = setOf("com.google.intelligence.sense", "com.google.android.as", "com.kieronquinn.app.pixelambientmusic")

    fun parse(sbn: StatusBarNotification): JSONObject? {
        val n = sbn.notification
        val extras = n.extras
        val channel = n.channelId
        var album = ""
        var duration = 0L
        val pair = when {
            sbn.packageName == "com.shazam.android" && channel in setOf("notification_shazam_match_v1", "notification_shazam_foreground_match_v2") && !n.actions.isNullOrEmpty() ->
                extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() to extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            sbn.packageName in ambientPackages && channel == "com.google.intelligence.sense.ambientmusic.MusicNotificationChannel" -> {
                // The Pixel's notification is one "title by artist" line in the
                // phone's language. (Its second line can be "tap to see
                // history", so that is never taken as the artist.)
                val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
                parseAmbientTitle(title) ?: return null
            }
            sbn.packageName == "com.mrsep.musicrecognizer" && channel in setOf("com.mrsep.musicrecognizer.result", "com.mrsep.musicrecognizer.foreground_result", "com.mrsep.musicrecognizer.enqueued_result") -> {
                val prefix = "com.mrsep.musicrecognizer.track_metadata."
                album = extras.getString(prefix + "album").orEmpty()
                duration = extras.getLong(prefix + "duration", 0).coerceAtLeast(0)
                extras.getString(prefix + "title") to extras.getString(prefix + "artist")
            }
            else -> return null
        }
        val title = pair.first?.trim().orEmpty()
        val artist = pair.second?.trim().orEmpty()
        if (title.isBlank() || artist.isBlank()) return null
        return JSONObject().put("title", title).put("artist", artist).put("album", album)
            .put("albumArtist", artist).put("duration", duration)
    }

    internal fun parseAmbientTitle(title: String): Pair<String, String>? {
        for (separator in listOf(" by ", " par ", " von ", " de ", " di ", " por ")) {
            val index = title.lastIndexOf(separator)
            if (index > 0 && index + separator.length < title.length) return title.substring(0, index) to title.substring(index + separator.length)
        }
        return null
    }
}
