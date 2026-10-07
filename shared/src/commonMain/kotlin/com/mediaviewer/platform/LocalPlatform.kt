package com.mediaviewer.platform

/**
 * Small platform hooks behind the supporter features: keeping picked media
 * in the app's own storage (drafts, notes, folder covers), the timer's
 * alarm sound, Battery Saver's frame-rate cap, and Android's background
 * notification check.
 */
/** One chat, as the DMs widget shows it. */
data class WidgetChat(
    val convoId: String,
    val name: String,
    val text: String,
    val unread: Int,
    val avatarUrl: String?,
    /** The chat's streak as the DM list shows it (0 = none). */
    val streak: Int = 0,
    /** Group chats: its members' pictures (the widget shows two, overlapped,
     *  like the DM list does). */
    val groupAvatars: List<String> = emptyList(),
    val isGroup: Boolean = false
)

/** The chat list as the DMs widget shows it: no chats with accounts you've
 *  blocked, each with its streak, group chats with their members' pictures. */
fun widgetChats(list: List<com.mediaviewer.model.DmConversation>): List<WidgetChat> =
    list.filter { it.convoId.isNotBlank() }
        .filterNot { !it.isGroup && com.mediaviewer.util.BlockedAccounts.isHidden(it.member.did) }
        .map {
            WidgetChat(
                convoId = it.convoId,
                name = it.member.displayName.ifBlank { it.member.handle },
                text = it.lastMessageText, unread = it.unreadCount,
                avatarUrl = if (it.isGroup) null else it.member.avatarUrl,
                streak = if (it.isGroup) 0 else runCatching {
                    com.mediaviewer.util.DmStreaks.shown(com.mediaviewer.util.LocalData.dmStreak(it.convoId))
                }.getOrDefault(0),
                groupAvatars = if (it.isGroup) it.groupMembers.mapNotNull { m -> m.avatarUrl }.take(2) else emptyList(),
                isGroup = it.isGroup
            )
        }

expect object LocalPlatform {
    /** Copies the picked file at [uri] into the app's private storage (so a
     *  draft or note keeps it even if the original is moved or deleted) and
     *  returns where the copy lives, or null if it couldn't be read. */
    fun importMedia(context: PlatformContext, uri: PlatformUri, folder: String): PlatformUri?

    /** A [PlatformUri] for a saved uri string (file://, content://, https://). */
    fun parseUri(text: String): PlatformUri

    /** Deletes a file previously returned by [importMedia]. */
    fun deleteMedia(context: PlatformContext, uri: String)

    /** Battery Saver: caps the screen's refresh rate while on. */
    fun setBatterySaver(context: PlatformContext, on: Boolean)

    /** Plays [wav] (a complete WAV file) on a loop until [stopSound]. */
    fun playLoopingWav(context: PlatformContext, wav: ByteArray)
    fun stopSound()

    /** Starts or stops the background notification check to match the
     *  Supporter Settings toggles (Android only; a no-op elsewhere). Asks
     *  for the notification permission when it's being switched on. */
    fun syncNotifications(context: PlatformContext, requestPermission: Boolean)

    /** Redraws Stellar's home-screen widgets (DMs, Upcoming Events, Note).
     *  [dms] is the chat list as the app has it right now, or null when
     *  only the on-device data (events, notes) changed. */
    fun updateWidgets(context: PlatformContext, dms: List<WidgetChat>?)

    // ── "Scrobble Music to Rocksky" (Android; elsewhere "not supported") ──

    /** How the scrobbler is set and doing, for its row in Settings. */
    fun scrobblerStatus(context: PlatformContext): com.mediaviewer.util.ScrobblerStatus

    /** Switches scrobbling on (for the account [did]) or off. */
    fun setScrobblerEnabled(context: PlatformContext, on: Boolean, did: String)

    /** Chooses whether the app [packageName] is scrobbled. */
    fun setScrobblerApp(context: PlatformContext, packageName: String, on: Boolean)

    /** Opens the phone's "notification access" page for Stellar — the
     *  permission that lets it see what other apps are playing. */
    fun openScrobblerAccessSettings(context: PlatformContext)

    /** "Scrobble after [percent]% or [seconds] of the track", whichever comes first. */
    fun setScrobblerThreshold(context: PlatformContext, percent: Int, seconds: Int)

    /** Reads a Spotify / YouTube history file and queues its listens to be
     *  sent to Rocksky for the account [did]. Returns at once. */
    fun importScrobbleHistory(context: PlatformContext, uri: PlatformUri, did: String)

    /** Takes an imported file off the list; what it hasn't sent yet isn't sent. */
    fun cancelScrobbleImport(context: PlatformContext, id: Long)

    /** "Work in Background": keep importing while Stellar is closed. */
    fun setScrobbleImportBackground(context: PlatformContext, on: Boolean)

    /** Asks the phone to let Stellar run in the background without being
     *  paused to save battery (Android's own prompt, or its settings page). */
    fun requestScrobbleBatteryExemption(context: PlatformContext)
}
