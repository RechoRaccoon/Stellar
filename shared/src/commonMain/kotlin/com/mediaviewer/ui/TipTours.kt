package com.mediaviewer.ui

import androidx.compose.ui.unit.dp

/**
 * Every first-time walkthrough's words and layout (the engine is Tips.kt).
 *
 * Writing them:
 *  - Say what a thing is, then what it does: "This is the save button.
 *    Saved posts can be sorted into folders." For a feature the reader can
 *    use, "You can …". Never orders ("Make something."), never shorthand
 *    labels ("Settings: …"), never comparisons that need the other note to
 *    make sense.
 *  - Explain what helps someone use the screen — where things are and the
 *    gestures nobody would guess. Leave small, obvious things alone.
 *  - Put each piece of text near what it's about. A row of buttons gets one
 *    note per button, stacked like stairs so no line crosses any text
 *    (each note starts beside its own button and reaches away from the
 *    buttons still below it); light the whole bar, not each button.
 *
 * Anchor ids (Modifier.tipAnchor):
 *   hub.feeds  hub.launchpad  hub.launchpad.label  hub.timeline  hub.explore  hub.post  hub.settings
 *   tl.author  tl.follow  tl.text  tl.actions  tl.like  tl.save  tl.repost  tl.quote  tl.download  tl.gif  tl.send  tl.more
 *   ex.feeds  ex.kinds  ex.refresh  ex.layout
 *   profile.tabs  profile.kinds  profile.bar  profile.refresh  profile.layout  profile.addto  profile.dm  profile.share  profile.sort  profile.more
 *   search.bar  search.tabs
 *   compose.tools  compose.thread  compose.post
 *   title.tabs  title.bar
 *   capture.bar (VRM mode and the Camera page; its buttons are fractions of it, below)
 */
object TipTours {
    const val HUB = "hub"
    const val TIMELINE = "timeline"
    const val EXPLORE = "explore"
    const val PROFILE = "profile"
    const val SEARCH = "search"
    const val COMPOSE = "compose"
    const val TITLE = "title"
    const val BLOG = "blog"
    const val DMS = "dms"
    const val VRM = "vrm"
    const val CAMERA = "camera"

    fun byId(id: String): TipTour? = when (id) {
        HUB -> hub()
        TIMELINE -> timeline
        EXPLORE -> explore
        PROFILE -> profile
        SEARCH -> search
        COMPOSE -> compose
        TITLE -> title
        BLOG -> blog
        DMS -> dms
        VRM -> vrm
        CAMERA -> camera
        else -> null
    }

    // The capture bar's buttons: mic 48 · mode 48 · capture 72 · Activity 48 ·
    // Settings/Flip 48, 14dp apart (320dp across, 72dp tall).
    private const val CAP_MIC = "capture.bar@0,0.17,0.15,0.83"
    private const val CAP_MODE = "capture.bar@0.194,0.17,0.344,0.83"
    private const val CAP_SHUTTER = "capture.bar@0.3875,0,0.6125,1"
    private const val CAP_ACTIVITY = "capture.bar@0.656,0.17,0.806,0.83"
    private const val CAP_RIGHT = "capture.bar@0.85,0.17,1,0.83"

    /** A note standing above [anchor] at [height], starting at its left edge
     *  and reaching right (for the left-hand buttons of a bar). */
    private fun stairRight(anchor: String, height: Int, width: Int, text: String) = TipNote(
        text = text, place = TipPlace.Above(anchor, gap = height.dp, pin = 0f, shift = (-6).dp),
        anchors = listOf(anchor), width = width.dp
    )

    /** The same, ending at its right edge and reaching left. */
    private fun stairLeft(anchor: String, height: Int, width: Int, text: String) = TipNote(
        text = text, place = TipPlace.Above(anchor, gap = height.dp, pin = 1f, shift = 6.dp),
        anchors = listOf(anchor), width = width.dp
    )

    // ── Hub ──────────────────────────────────────────────────────────────

    private val launchpadNames = mapOf(
        com.mediaviewer.util.HubLayout.LP_INBOX to "**Inbox** has your notifications",
        com.mediaviewer.util.HubLayout.LP_PROFILE to "**Profile** opens your profile",
        com.mediaviewer.util.HubLayout.LP_DMS to "**DMs** has your messages",
        com.mediaviewer.util.HubLayout.LP_SAVED to "**Saved Posts** has the posts you've saved",
        com.mediaviewer.util.HubLayout.LP_HISTORY to "**History** has the posts you've viewed",
        com.mediaviewer.util.HubLayout.LP_FRIENDS to "**From Friends** has the posts friends have sent you",
        com.mediaviewer.util.HubLayout.LP_ARCHIVED to "**Archived** has the posts you've archived",
        com.mediaviewer.util.HubLayout.LP_CALENDAR to "**Calendar** has your events",
        com.mediaviewer.util.HubLayout.LP_NOTES to "**Notes** has your notes",
        com.mediaviewer.util.HubLayout.LP_CALCULATOR to "**Calculator** opens a calculator",
        com.mediaviewer.util.HubLayout.LP_TIMER to "**Timer** opens a timer"
    )

    /** The Launchpad's first page, read top left to bottom right. */
    private fun launchpadText(): String {
        val items = com.mediaviewer.util.HubLayout.launchpad.firstOrNull().orEmpty().mapNotNull { launchpadNames[it] }
        val list = when (items.size) {
            0 -> ""
            1 -> items[0] + ". "
            else -> items.dropLast(1).joinToString(", ") + ", and " + items.last() + ". "
        }
        return "This is the Launchpad!! $list" +
            "Swipe it sideways for more, and press and hold any button to move it."
    }

    private fun hub() = TipTour(HUB, listOf(
        TipStep(notes = listOf(
            TipNote(
                title = "Welcome to the Hub!!",
                text = "This is your home page. You can add, remove, and rearrange its rows to make it your own.",
                place = TipPlace.Screen(0.5f, 0.42f), width = 300.dp
            )
        )),
        TipStep(notes = listOf(
            TipNote(
                text = "These are your **feeds**. Each one is a custom feed or algorithm made by someone on Bluesky. Tap one to select it, or press and hold one to move it.",
                place = TipPlace.Below("hub.feeds", gap = 60.dp, pin = 0.5f),
                anchors = listOf("hub.feeds"), width = 310.dp
            )
        )),
        TipStep(
            highlights = listOf("hub.launchpad.label+hub.launchpad"),
            notes = listOf(
                TipNote(
                    text = launchpadText(),
                    place = TipPlace.Below("hub.launchpad", gap = 56.dp, pin = 0.5f),
                    anchors = listOf("hub.launchpad"), width = 320.dp
                )
            )
        ),
        TipStep(
            highlights = listOf("hub.timeline+hub.explore"),
            notes = listOf(
                TipNote(
                    text = "**Timeline** opens the selected feed in fullscreen, one post at a time.",
                    place = TipPlace.Above("hub.timeline", gap = 70.dp, pin = 0f),
                    anchors = listOf("hub.timeline"), width = 175.dp
                ),
                TipNote(
                    text = "**Explore** opens the selected feed as a grid, so you can browse lots of posts at once.",
                    place = TipPlace.Above("hub.explore", gap = 176.dp, pin = 1f),
                    anchors = listOf("hub.explore"), width = 190.dp
                )
            )
        ),
        TipStep(notes = listOf(
            TipNote(
                text = "This is the **post button**. You can create posts, blogs, textshots, and polls here!!",
                place = TipPlace.Above("hub.post", gap = 176.dp, pin = 1f),
                anchors = listOf("hub.post"), width = 205.dp
            ),
            TipNote(
                text = "This is **Settings**. You can customize your Hub, and the rest of Stellar, here.",
                place = TipPlace.Above("hub.settings", gap = 70.dp, pin = 0f),
                anchors = listOf("hub.settings"), width = 195.dp
            )
        ))
    ))

    // ── Timeline ─────────────────────────────────────────────────────────

    private val timeline = TipTour(TIMELINE, listOf(
        TipStep(
            notes = listOf(
                TipNote(
                    title = "This is Timeline mode",
                    text = "It shows the selected feed in fullscreen, one post at a time.",
                    place = TipPlace.Screen(0.5f, 0.33f), width = 300.dp
                ),
                TipNote(text = "Swipe **down** to return to the Hub", place = TipPlace.Screen(0.5f, 0.12f), width = 260.dp),
                TipNote(text = "Swipe **up** to open the comments", place = TipPlace.Screen(0.5f, 0.79f), width = 260.dp)
            ),
            anims = listOf(
                TipAnimSpec(TipAnim.SWIPE_SIDEWAYS, 0.5f, 0.52f, 110.dp, caption = "Swipe **left or right** to go to the next or previous post")
            )
        ),
        TipStep(
            highlights = listOf("tl.author+tl.follow", "tl.text"),
            notes = listOf(
                TipNote(
                    text = "You can tap here to open this creator's **profile**.",
                    place = TipPlace.Below("tl.author", gap = 64.dp, pin = 0f),
                    anchors = listOf("tl.author"), width = 200.dp
                ),
                TipNote(
                    text = "You can tap here to **follow** and unfollow them!!",
                    place = TipPlace.Below("tl.follow", gap = 150.dp, pin = 1f),
                    anchors = listOf("tl.follow"), width = 190.dp
                ),
                TipNote(
                    text = "This is the post's **text**. Tap it to see the full text, along with the post's stats and the date it was posted.",
                    place = TipPlace.Above("tl.text", gap = 60.dp, pin = 0.5f),
                    anchors = listOf("tl.text"), width = 300.dp
                )
            )
        ),
        TipStep(
            highlights = listOf("tl.actions"),
            notes = listOf(
                TipNote(
                    title = "The interaction bar",
                    text = "",
                    place = TipPlace.Above("tl.actions", gap = 380.dp, pin = 0.5f), width = 300.dp
                ),
                stairRight("tl.like", 290, 300, "This is the **like** button. You can also double tap a post to like it."),
                stairRight("tl.save", 215, 290, "This is the **save** button. Saved posts can be sorted into folders."),
                stairRight("tl.repost", 140, 250, "This is the **repost** button. It shares the post with your followers."),
                stairRight("tl.quote", 65, 210, "This is the **quote** button. It reposts with your own text added.")
            )
        ),
        TipStep(
            highlights = listOf("tl.actions"),
            notes = listOf(
                stairLeft("tl.more", 290, 300, "This is the **more** button, with options like show more or less like this, add to list, report, and block."),
                stairLeft("tl.send", 215, 270, "This is the **send** button. You can send the post to someone in your DMs."),
                stairLeft("tl.gif", 140, 240, "This saves the post's media as a **GIF**."),
                stairLeft("tl.download", 65, 210, "This is the **download** button. It saves the post's media to your device.")
            )
        ),
        TipStep(
            notes = listOf(
                TipNote(text = "Some helpful gestures", title = null, place = TipPlace.Screen(0.5f, 0.11f), width = 300.dp)
            ),
            anims = listOf(
                TipAnimSpec(TipAnim.DOUBLE_TAP_LIKE, 0.27f, 0.27f, 100.dp, caption = "**Double tap** to like a post"),
                TipAnimSpec(TipAnim.HOLD_WHEEL, 0.73f, 0.27f, 100.dp, caption = "**Press and hold** for quick shortcuts, then slide to one and let go"),
                TipAnimSpec(TipAnim.ZOOM, 0.27f, 0.6f, 100.dp, caption = "**Pinch** with two fingers or **double tap and drag** to zoom in and out"),
                TipAnimSpec(TipAnim.THREE_FINGERS, 0.73f, 0.6f, 100.dp, caption = "**Spread three fingers** to hide the buttons, and pinch them back to show them again")
            )
        ),
        TipStep(
            notes = listOf(
                TipNote(
                    text = "**Pinch in** with two fingers to switch to Explore mode, where you can browse lots of posts at once.",
                    place = TipPlace.Screen(0.5f, 0.66f), width = 290.dp
                )
            ),
            anims = listOf(TipAnimSpec(TipAnim.PINCH_EXPLORE, 0.5f, 0.4f, 170.dp))
        )
    ))

    // ── Explore ──────────────────────────────────────────────────────────

    private val explore = TipTour(EXPLORE, listOf(
        TipStep(
            notes = listOf(
                TipNote(
                    title = "This is Explore mode",
                    text = "Tap any post to open it in Timeline mode, or pinch out to go back to the post you were on.",
                    place = TipPlace.Screen(0.5f, 0.6f), width = 300.dp
                )
            ),
            anims = listOf(TipAnimSpec(TipAnim.TAP, 0.5f, 0.37f, 90.dp))
        ),
        TipStep(notes = listOf(
            TipNote(
                text = "These are your **feeds**. You can switch between them here.",
                place = TipPlace.Below("ex.feeds", gap = 110.dp, pin = 0.5f),
                anchors = listOf("ex.feeds"), width = 280.dp
            )
        )),
        TipStep(notes = listOf(
            TipNote(
                text = "You can **filter** the feed by post type, like images, videos, and text posts.",
                place = TipPlace.Below("ex.kinds", gap = 70.dp, pin = 0.5f),
                anchors = listOf("ex.kinds"), width = 290.dp
            )
        )),
        TipStep(
            highlights = listOf("ex.refresh+ex.layout"),
            notes = listOf(
                TipNote(
                    text = "This is the **refresh** button. It loads the newest posts.",
                    place = TipPlace.Above("ex.refresh", gap = 70.dp, pin = 1f, shift = 6.dp),
                    anchors = listOf("ex.refresh"), width = 175.dp
                ),
                TipNote(
                    text = "This is the **layout** button. It switches between grid and list layouts.",
                    place = TipPlace.Above("ex.layout", gap = 160.dp, pin = 0f, shift = (-6).dp),
                    anchors = listOf("ex.layout"), width = 185.dp
                )
            )
        )
    ))

    // ── Profiles ─────────────────────────────────────────────────────────

    private val profile = TipTour(PROFILE, listOf(
        TipStep(
            highlights = listOf("profile.tabs+profile.kinds"),
            notes = listOf(
                TipNote(
                    text = "These tabs switch between their **posts, reposts, and likes**, plus their blogs, reviews, and music when they have them.",
                    place = TipPlace.Above("profile.tabs", gap = 50.dp, pin = 0.5f),
                    anchors = listOf("profile.tabs"), width = 310.dp
                ),
                TipNote(
                    text = "This row **filters** their posts by type, like images, videos, and text posts.",
                    place = TipPlace.Below("profile.kinds", gap = 60.dp, pin = 0.5f),
                    anchors = listOf("profile.kinds"), width = 300.dp
                )
            )
        ),
        TipStep(
            highlights = listOf("profile.bar"),
            notes = listOf(
                stairRight("profile.refresh", 290, 290, "This is the **refresh** button. It reloads their profile."),
                stairRight("profile.layout", 215, 280, "This is the **layout** button. It switches between grid and list layouts."),
                stairRight("profile.addto", 140, 250, "This is the **Add To** button. You can add them to one of your lists."),
                stairRight("profile.dm", 65, 220, "This is the **message** button. Press and hold it to start a group chat.")
            )
        ),
        TipStep(
            highlights = listOf("profile.bar"),
            notes = listOf(
                stairLeft("profile.more", 215, 280, "This is the **more** button. It has their QR code, and you can report or block them here."),
                stairLeft("profile.sort", 140, 250, "This is the **sort** button. You can see their most liked, reposted, saved, or commented posts first."),
                stairLeft("profile.share", 65, 220, "This is the **share** button. You can send their profile to someone in your DMs.")
            )
        )
    ))

    // ── Search ───────────────────────────────────────────────────────────

    private val search = TipTour(SEARCH, listOf(
        TipStep(notes = listOf(
            TipNote(
                text = "This is the **search bar**. You can search for people, posts, feeds, and starter packs.",
                place = TipPlace.Below("search.bar", gap = 150.dp, pin = 0.5f),
                anchors = listOf("search.bar"), width = 300.dp
            )
        )),
        TipStep(notes = listOf(
            TipNote(
                text = "These **tabs** switch between the different kinds of results.",
                place = TipPlace.Below("search.tabs", gap = 80.dp, pin = 0.5f),
                anchors = listOf("search.tabs"), width = 290.dp
            )
        ))
    ))

    // ── Posting ──────────────────────────────────────────────────────────

    private val compose = TipTour(COMPOSE, listOf(
        TipStep(
            highlights = listOf("compose.tools+compose.thread", "compose.post"),
            notes = listOf(
                TipNote(
                    text = "You can attach media, write a blog, create a textshot, add content labels, and create polls!!",
                    place = TipPlace.Above("compose.tools", gap = 90.dp, pin = 0f),
                    anchors = listOf("compose.tools"), width = 260.dp
                ),
                TipNote(
                    text = "You can add more posts underneath to create a **thread**!!",
                    place = TipPlace.Above("compose.thread", gap = 200.dp, pin = 1f),
                    anchors = listOf("compose.thread"), width = 200.dp
                ),
                TipNote(
                    text = "When you're ready, tap here to **post** it.",
                    place = TipPlace.Below("compose.post", gap = 50.dp, pin = 1f),
                    anchors = listOf("compose.post"), width = 200.dp
                )
            )
        )
    ))

    // ── Title pages and blogs ────────────────────────────────────────────

    private val title = TipTour(TITLE, listOf(
        TipStep(notes = listOf(
            TipNote(
                text = "**Summary** shows the details of this title. The tabs next to it are reviews from people you follow.",
                place = TipPlace.Below("title.tabs", gap = 80.dp, pin = 0.5f),
                anchors = listOf("title.tabs"), width = 300.dp
            )
        )),
        TipStep(notes = listOf(
            TipNote(
                text = "You can write your own **review** of this title, or add it to your **backlog**. On someone's review, you can also like it and comment on it.",
                place = TipPlace.Above("title.bar", gap = 90.dp, pin = 0.5f),
                anchors = listOf("title.bar"), width = 310.dp
            )
        ))
    ))

    private val blog = TipTour(BLOG, listOf(
        TipStep(notes = listOf(
            TipNote(
                title = "This is a blog",
                text = "Blogs are long-form posts made on Bluesky. You can write your own from the Hub's post button.",
                place = TipPlace.Screen(0.5f, 0.45f), width = 300.dp
            )
        ))
    ))

    // ── DMs ──────────────────────────────────────────────────────────────

    private val dms = TipTour(DMS, listOf(
        TipStep(
            notes = listOf(TipNote(title = "Messages", text = "", place = TipPlace.Screen(0.5f, 0.22f), width = 290.dp)),
            anims = listOf(
                TipAnimSpec(TipAnim.HOLD_REACT, 0.28f, 0.46f, 110.dp, caption = "**Press and hold** a message to react to it"),
                TipAnimSpec(TipAnim.SWIPE_REPLY, 0.72f, 0.46f, 110.dp, caption = "**Swipe** a message sideways to reply to it")
            )
        )
    ))

    // ── VRM mode and the Camera page (the same bar) ─────────────────────

    /** One note per button. Tiers (low to high): capture, the 4th button,
     *  mode, the 5th button, mic — so no line crosses another's text. */
    private fun captureBar(fourth: String, right: String) = TipStep(
        highlights = listOf("capture.bar"),
        notes = listOf(
            stairRight(CAP_MIC, 420, 190, "This is the **microphone**. Tap it to mute or unmute yourself."),
            stairLeft(CAP_RIGHT, 330, 190, right),
            stairRight(CAP_MODE, 240, 170, "This switches between **photo, video, and live**."),
            stairLeft(CAP_ACTIVITY, 150, 150, fourth),
            TipNote(
                text = "This is the **capture** button.",
                place = TipPlace.Above(CAP_SHUTTER, gap = 40.dp, pin = 0.5f),
                anchors = listOf(CAP_SHUTTER), width = 120.dp
            )
        )
    )

    private val vrm = TipTour(VRM, listOf(
        TipStep(notes = listOf(
            TipNote(
                title = "This is VRM mode",
                text = "VRM mode lets you import your VRM models and animate them with face, hand, and body tracking!!",
                place = TipPlace.Screen(0.5f, 0.32f), width = 300.dp
            )
        )),
        captureBar(
            fourth = "This is **Activity**, with scenes, a soundboard, and effects.",
            right = "This is **Settings**, for tracking, your avatar, and the background."
        )
    ))

    private val camera = TipTour(CAMERA, listOf(
        TipStep(notes = listOf(
            TipNote(
                title = "This is the camera",
                text = "You can take photos, record videos, and go live, with scenes and effects from Activity.",
                place = TipPlace.Screen(0.5f, 0.32f), width = 300.dp
            )
        )),
        captureBar(
            fourth = "This is **Activity**, with scenes, a soundboard, and effects.",
            right = "This **flips** between the front and back cameras."
        )
    ))
}
