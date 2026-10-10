package com.mediaviewer.ui

import androidx.compose.ui.unit.dp

/**
 * Every first-time walkthrough's words and layout (the engine is Tips.kt).
 *
 * Writing them: explain what helps someone *use* the screen — where things
 * are and the gestures nobody would guess — and leave the small, obvious
 * buttons to be found. Short sentences, one idea per piece of text; put the
 * text near what it's about rather than always in the middle.
 *
 * Anchor ids are given to the elements with Modifier.tipAnchor(...):
 *   hub.feeds  hub.launchpad  hub.timeline  hub.explore  hub.post  hub.settings
 *   tl.author  tl.follow  tl.text  tl.actions  tl.like  tl.save  tl.send  tl.more
 *   ex.feeds  ex.kinds  ex.refresh  ex.layout
 *   profile.tabs  profile.addto
 *   search.bar  search.tabs
 *   comments.header  comments.field
 *   compose.tools  compose.thread  compose.post
 *   title.tabs  title.bar
 *   capture.bar (VRM mode and the Camera page share it; see the fractions below)
 */
object TipTours {
    const val HUB = "hub"
    const val TIMELINE = "timeline"
    const val EXPLORE = "explore"
    const val PROFILE = "profile"
    const val SEARCH = "search"
    const val COMMENTS = "comments"
    const val COMPOSE = "compose"
    const val TITLE = "title"
    const val BLOG = "blog"
    const val DMS = "dms"
    const val VRM = "vrm"
    const val CAMERA = "camera"

    fun byId(id: String): TipTour? = when (id) {
        HUB -> hub
        TIMELINE -> timeline
        EXPLORE -> explore
        PROFILE -> profile
        SEARCH -> search
        COMMENTS -> comments
        COMPOSE -> compose
        TITLE -> title
        BLOG -> blog
        DMS -> dms
        VRM -> vrm
        CAMERA -> camera
        else -> null
    }

    // The capture bar's buttons: mic 48 · mode 48 · capture 72 · Activity/Live 48 ·
    // Settings/Flip 48, 14dp apart (320dp across, 72dp tall).
    private const val CAP_MIC = "capture.bar@0,0.17,0.15,0.83"
    private const val CAP_MODE = "capture.bar@0.194,0.17,0.344,0.83"
    private const val CAP_SHUTTER = "capture.bar@0.3875,0,0.6125,1"
    private const val CAP_FOURTH = "capture.bar@0.656,0.17,0.806,0.83"
    private const val CAP_RIGHT = "capture.bar@0.85,0.17,1,0.83"

    private val hub = TipTour(HUB, listOf(
        TipStep(notes = listOf(
            TipNote(
                title = "Welcome to the Hub",
                text = "Your home base in Stellar. Every row here can be moved, hidden or swapped for something else, so make it yours.",
                place = TipPlace.Screen(0.5f, 0.42f), width = 300.dp
            )
        )),
        TipStep(notes = listOf(
            TipNote(
                title = "Feeds",
                text = "Each one is its own stream: different topics, different algorithms. **Tap one** to pick it. Hold and drag to reorder them.",
                place = TipPlace.Above("hub.feeds", gap = 60.dp, pin = 0f, shift = 4.dp),
                anchors = listOf("hub.feeds"), width = 290.dp
            )
        )),
        TipStep(notes = listOf(
            TipNote(
                text = "**Timeline** opens the feed you picked one post at a time, full screen.",
                place = TipPlace.Above("hub.timeline", gap = 64.dp, pin = 0f),
                anchors = listOf("hub.timeline"), width = 168.dp
            ),
            TipNote(
                text = "**Explore** lays the same feed out as a grid, many posts at once.",
                place = TipPlace.Above("hub.explore", gap = 170.dp, pin = 1f),
                anchors = listOf("hub.explore"), width = 176.dp
            )
        )),
        TipStep(notes = listOf(
            TipNote(
                text = "The Launchpad: your **DMs**, Inbox, profile, saved posts, notes and more. Swipe it sideways for a second page, and hold any button to move it.",
                place = TipPlace.Below("hub.launchpad", gap = 48.dp, pin = 0.5f),
                anchors = listOf("hub.launchpad"), width = 300.dp
            )
        )),
        TipStep(notes = listOf(
            TipNote(
                text = "Make something. **Posts**, blogs, textshots and polls all start here.",
                place = TipPlace.Above("hub.post", gap = 160.dp, pin = 1f),
                anchors = listOf("hub.post"), width = 190.dp
            ),
            TipNote(
                text = "**Settings**, and Customize Hub to rearrange this page.",
                place = TipPlace.Above("hub.settings", gap = 64.dp, pin = 0f),
                anchors = listOf("hub.settings"), width = 170.dp
            )
        ))
    ))

    private val timeline = TipTour(TIMELINE, listOf(
        TipStep(
            notes = listOf(
                TipNote(title = "Timeline mode", text = "One post at a time, filling the screen.", place = TipPlace.Screen(0.5f, 0.33f)),
                TipNote(text = "Swipe **down** to go back to the Hub", place = TipPlace.Screen(0.5f, 0.11f), width = 240.dp),
                TipNote(text = "Swipe **up** for the comments", place = TipPlace.Screen(0.5f, 0.82f), width = 240.dp)
            ),
            anims = listOf(
                TipAnimSpec(TipAnim.SWIPE_SIDEWAYS, 0.5f, 0.52f, 110.dp, caption = "Swipe **sideways** for the next or previous post")
            ),
            continueY = 0.94f
        ),
        TipStep(notes = listOf(
            TipNote(
                text = "Tap their name to open their **profile**.",
                place = TipPlace.Below("tl.author", gap = 64.dp, pin = 0f),
                anchors = listOf("tl.author"), width = 190.dp
            ),
            TipNote(
                text = "**Follow** them without leaving the post.",
                place = TipPlace.Below("tl.follow", gap = 150.dp, pin = 1f),
                anchors = listOf("tl.follow"), width = 170.dp
            ),
            TipNote(
                text = "Tap the text to fold it down to one line. It stays folded on every post until you tap it again.",
                place = TipPlace.Above("tl.text", gap = 60.dp, pin = 0.5f),
                anchors = listOf("tl.text"), width = 290.dp
            )
        )),
        TipStep(
            highlights = listOf("tl.actions"),
            notes = listOf(
                TipNote(
                    text = "Between them: repost, quote, download, and save as a GIF.",
                    place = TipPlace.Above("tl.actions", gap = 290.dp, pin = 0.5f), width = 280.dp
                ),
                TipNote(
                    text = "**Like** it. Double-tapping the post works too.",
                    place = TipPlace.Above("tl.like", gap = 200.dp, pin = 0f, shift = (-4).dp),
                    anchors = listOf("tl.like"), width = 160.dp
                ),
                TipNote(
                    text = "**Save** it for later, and into folders.",
                    place = TipPlace.Above("tl.save", gap = 72.dp, pin = 0f, shift = (-4).dp),
                    anchors = listOf("tl.save"), width = 150.dp
                ),
                TipNote(
                    text = "**Send** it to someone in your DMs.",
                    place = TipPlace.Above("tl.send", gap = 136.dp, pin = 1f, shift = 4.dp),
                    anchors = listOf("tl.send"), width = 150.dp
                ),
                TipNote(
                    text = "**More**: show more or less like this, lists, report, block.",
                    place = TipPlace.Above("tl.more", gap = 200.dp, pin = 1f, shift = 4.dp),
                    anchors = listOf("tl.more"), width = 165.dp
                )
            )
        ),
        TipStep(
            notes = listOf(
                TipNote(text = "A few gestures worth knowing", place = TipPlace.Screen(0.5f, 0.11f), width = 300.dp)
            ),
            anims = listOf(
                TipAnimSpec(TipAnim.DOUBLE_TAP_LIKE, 0.27f, 0.27f, 100.dp, caption = "**Double-tap** to like"),
                TipAnimSpec(TipAnim.HOLD_WHEEL, 0.73f, 0.27f, 100.dp, caption = "**Hold** for shortcuts, slide to one, let go"),
                TipAnimSpec(TipAnim.ZOOM, 0.27f, 0.6f, 100.dp, caption = "**Pinch out** to zoom, or double-tap and drag"),
                TipAnimSpec(TipAnim.THREE_FINGERS, 0.73f, 0.6f, 100.dp, caption = "**Three fingers** apart to hide the buttons")
            )
        ),
        TipStep(
            notes = listOf(
                TipNote(
                    text = "**Pinch in** with two fingers to switch to Explore mode and see many posts at once.",
                    place = TipPlace.Screen(0.5f, 0.66f), width = 280.dp
                )
            ),
            anims = listOf(TipAnimSpec(TipAnim.PINCH_EXPLORE, 0.5f, 0.4f, 170.dp))
        )
    ))

    private val explore = TipTour(EXPLORE, listOf(
        TipStep(
            notes = listOf(
                TipNote(
                    title = "Explore mode",
                    text = "The same feed, laid out as a grid. Tap a post to open it. **Pinch out** to go back to the post you came from.",
                    place = TipPlace.Screen(0.5f, 0.62f), width = 290.dp
                )
            ),
            anims = listOf(TipAnimSpec(TipAnim.TAP, 0.5f, 0.38f, 90.dp))
        ),
        TipStep(notes = listOf(
            TipNote(
                text = "Switch between your **feeds** without going back to the Hub.",
                place = TipPlace.Below("ex.feeds", gap = 110.dp, pin = 0.5f),
                anchors = listOf("ex.feeds"), width = 260.dp
            )
        )),
        TipStep(notes = listOf(
            TipNote(
                text = "Show only **pictures, videos, text posts** and so on.",
                place = TipPlace.Below("ex.kinds", gap = 70.dp, pin = 0.5f),
                anchors = listOf("ex.kinds"), width = 260.dp
            )
        )),
        TipStep(notes = listOf(
            TipNote(
                text = "**Refresh** for newer posts.",
                place = TipPlace.Above("ex.refresh", gap = 70.dp, pin = 1f, shift = 6.dp),
                anchors = listOf("ex.refresh"), width = 140.dp
            ),
            TipNote(
                text = "**Change the layout**: columns, square tiles or a list.",
                place = TipPlace.Above("ex.layout", gap = 70.dp, pin = 0f, shift = (-6).dp),
                anchors = listOf("ex.layout"), width = 160.dp
            )
        ))
    ))

    private val profile = TipTour(PROFILE, listOf(
        TipStep(notes = listOf(
            TipNote(
                text = "Everything they've shared, sorted into **tabs**: posts, reposts, likes, and blogs, reviews and music when they have any.",
                place = TipPlace.Below("profile.tabs", gap = 90.dp, pin = 0.5f),
                anchors = listOf("profile.tabs"), width = 290.dp
            )
        )),
        TipStep(notes = listOf(
            TipNote(
                text = "**Add To** puts them in one of your lists. Next to it: refresh, layout, message and share.",
                place = TipPlace.Above("profile.addto", gap = 80.dp, pin = 0.5f),
                anchors = listOf("profile.addto"), width = 270.dp
            )
        ))
    ))

    private val search = TipTour(SEARCH, listOf(
        TipStep(
            highlights = listOf("search.bar"),
            notes = listOf(
                TipNote(
                    text = "Type up top, then choose what you're after: **posts, people, feeds** or starter packs.",
                    place = TipPlace.Below("search.tabs", gap = 70.dp, pin = 0.5f),
                    anchors = listOf("search.tabs"), width = 280.dp
                )
            )
        ),
        TipStep(notes = listOf(
            TipNote(
                title = "Tagged",
                text = "Searches the posts you've liked by **what's in their pictures**. It appears once AI Tagging (in Settings) has tagged a few.",
                place = TipPlace.Screen(0.5f, 0.45f), width = 290.dp
            )
        ))
    ))

    private val comments = TipTour(COMMENTS, listOf(
        TipStep(notes = listOf(
            TipNote(
                text = "Comments slide up over the post. **Swipe down** from here to put them away.",
                place = TipPlace.Below("comments.header", gap = 80.dp, pin = 0.5f),
                anchors = listOf("comments.header"), width = 270.dp
            )
        )),
        TipStep(notes = listOf(
            TipNote(
                text = "Add yours here. Tap **Reply** under any comment to answer that one directly.",
                place = TipPlace.Above("comments.field", gap = 80.dp, pin = 0.5f),
                anchors = listOf("comments.field"), width = 270.dp
            )
        ))
    ))

    private val compose = TipTour(COMPOSE, listOf(
        TipStep(notes = listOf(
            TipNote(
                text = "Attach pictures or a video, or make it a **Blog**, a **Textshot** (your words as a picture) or a **Poll**.",
                place = TipPlace.Above("compose.tools", gap = 84.dp, pin = 0f),
                anchors = listOf("compose.tools"), width = 250.dp
            ),
            TipNote(
                text = "**+** adds another post underneath: a thread.",
                place = TipPlace.Above("compose.thread", gap = 200.dp, pin = 1f),
                anchors = listOf("compose.thread"), width = 160.dp
            ),
            TipNote(
                text = "Send it when it's ready.",
                place = TipPlace.Below("compose.post", gap = 50.dp, pin = 1f),
                anchors = listOf("compose.post"), width = 160.dp
            )
        ))
    ))

    private val title = TipTour(TITLE, listOf(
        TipStep(notes = listOf(
            TipNote(
                text = "The **Summary** first, then every review of it from people you follow. Tap or swipe to move between them.",
                place = TipPlace.Below("title.tabs", gap = 80.dp, pin = 0.5f),
                anchors = listOf("title.tabs"), width = 280.dp
            )
        )),
        TipStep(notes = listOf(
            TipNote(
                text = "Write your own **Review**, or add it to your **Backlog**, the list of things you mean to get to.",
                place = TipPlace.Above("title.bar", gap = 80.dp, pin = 0.5f),
                anchors = listOf("title.bar"), width = 280.dp
            )
        ))
    ))

    private val blog = TipTour(BLOG, listOf(
        TipStep(notes = listOf(
            TipNote(
                title = "Blogs",
                text = "Long reads, kept on the writer's own account. Write one yourself from the Hub's **+** button.",
                place = TipPlace.Screen(0.5f, 0.45f), width = 290.dp
            )
        ))
    ))

    private val dms = TipTour(DMS, listOf(
        TipStep(
            notes = listOf(TipNote(title = "Messages", text = "Two things worth knowing in a chat:", place = TipPlace.Screen(0.5f, 0.2f), width = 290.dp)),
            anims = listOf(
                TipAnimSpec(TipAnim.HOLD_WHEEL, 0.28f, 0.48f, 100.dp, caption = "**Hold** a message to react to it"),
                TipAnimSpec(TipAnim.SWIPE_SIDEWAYS, 0.72f, 0.48f, 100.dp, caption = "**Swipe** it sideways to reply")
            )
        )
    ))

    private val vrm = TipTour(VRM, listOf(
        TipStep(notes = listOf(
            TipNote(
                title = "VRM mode",
                text = "Your avatar follows your face, and your hands and body too once you switch them on in Settings.",
                place = TipPlace.Screen(0.5f, 0.32f), width = 290.dp
            )
        )),
        TipStep(notes = listOf(
            TipNote(
                text = "**Capture**: take a photo, record, or go live.",
                place = TipPlace.Above(CAP_SHUTTER, gap = 190.dp, pin = 0.5f),
                anchors = listOf(CAP_SHUTTER), width = 200.dp
            ),
            TipNote(
                text = "Mic on or off, and **photo, video or live**.",
                place = TipPlace.Above(CAP_MIC, gap = 84.dp, pin = 0f, shift = (-6).dp),
                anchors = listOf(CAP_MIC, CAP_MODE), width = 136.dp
            ),
            TipNote(
                text = "**Activity**: scenes, sounds, effects. **Settings**: tracking, avatar, background.",
                place = TipPlace.Above(CAP_RIGHT, gap = 84.dp, pin = 1f, shift = 6.dp),
                anchors = listOf(CAP_FOURTH, CAP_RIGHT), width = 150.dp
            )
        ))
    ))

    private val camera = TipTour(CAMERA, listOf(
        TipStep(notes = listOf(
            TipNote(
                text = "**Capture**: a photo or a video, depending on the mode.",
                place = TipPlace.Above(CAP_SHUTTER, gap = 190.dp, pin = 0.5f),
                anchors = listOf(CAP_SHUTTER), width = 210.dp
            ),
            TipNote(
                text = "Mic on or off, and **photo or video**.",
                place = TipPlace.Above(CAP_MIC, gap = 84.dp, pin = 0f, shift = (-6).dp),
                anchors = listOf(CAP_MIC, CAP_MODE), width = 136.dp
            ),
            TipNote(
                text = "**Go live**, and flip between the cameras.",
                place = TipPlace.Above(CAP_RIGHT, gap = 84.dp, pin = 1f, shift = 6.dp),
                anchors = listOf(CAP_FOURTH, CAP_RIGHT), width = 150.dp
            )
        ))
    ))
}
