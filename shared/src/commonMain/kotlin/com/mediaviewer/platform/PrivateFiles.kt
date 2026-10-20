package com.mediaviewer.platform

/**
 * Files kept in the app's own private storage: only Stellar can read them,
 * they're encrypted at rest with the device (Android's file-based
 * encryption; iOS "complete unless open" data protection) and they're
 * removed with the app. Used for archived posts, soundboard sounds and VRM
 * backgrounds. Uris returned are plain "file://" strings (no spaces in the
 * path), usable as image/video sources.
 */
expect object PrivateFiles {
    /** The "file://…" prefix everything saved here starts with (no trailing
     *  slash): a file's uri is this + "/" + folder + "/" + name. It differs
     *  from device to device, which is what a restored backup has to adjust. */
    fun rootUri(context: PlatformContext): String

    /** Saves [bytes] as [folder]/[name]; its uri, or null if it failed. */
    fun write(context: PlatformContext, folder: String, name: String, bytes: ByteArray): String?

    /** Downloads [url] straight into [folder]/[name]; its uri, or null. */
    suspend fun download(context: PlatformContext, url: String, folder: String, name: String): String?

    /** Copies a picked file ([uri]) into [folder]/[name]; its uri, or null. */
    fun copyIn(context: PlatformContext, uri: PlatformUri, folder: String, name: String): String?

    fun read(context: PlatformContext, uri: String): ByteArray?

    /** Bytes on disk, or 0. */
    fun size(context: PlatformContext, uri: String): Long

    /** Deletes one file previously returned by this object. */
    fun delete(context: PlatformContext, uri: String)

    /** Deletes [folder] and everything in it. */
    fun deleteFolder(context: PlatformContext, folder: String)

    /** The picked file's name as the system shows it ("airhorn.mp3"), if known. */
    fun displayName(context: PlatformContext, uri: PlatformUri): String?

    // ── Private Sync: files are sent and fetched a piece at a time ──
    // These work on both of the app's own folders, this one and the
    // pictures drafts and notes keep (see [mediaRootUri]), and nowhere else.

    /** The "file://…" prefix of the folder drafts' and notes' pictures are
     *  copied into (LocalPlatform.importMedia), no trailing slash. */
    fun mediaRootUri(context: PlatformContext): String

    /** Up to [length] bytes of the file at [uri] from [offset] (fewer at
     *  its end), or null if it isn't one of ours or can't be read. */
    fun readRange(context: PlatformContext, uri: String, offset: Long, length: Int): ByteArray?

    /** Writes [bytes] at the end of the file at [uri] (made, with its
     *  folders, if needed; emptied first when [restart]). False if it isn't
     *  one of ours or the write failed. */
    fun appendBytes(context: PlatformContext, uri: String, bytes: ByteArray, restart: Boolean): Boolean

    /** Bytes on disk of either folder's file, or -1 when there's none. */
    fun ownSize(context: PlatformContext, uri: String): Long
}
