package com.mediaviewer.util

import com.mediaviewer.platform.PlatformContext
import com.mediaviewer.platform.SharedPreferences
import com.mediaviewer.platform.sharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Small app-wide switches that any screen can read directly as Compose
 * state (no plumbing through every screen's parameter list), persisted in
 * plain SharedPreferences. Initialised once from MainActivity.onCreate.
 */
object UiToggles {
    private const val PREFS = "ui_toggles"
    private const val KEY_DEBUG_OVERLAY = "debug_overlay"
    private const val KEY_HAPTICS = "haptics_enabled"
    private const val KEY_HIDE_POST_STATS = "hide_post_stats"
    private const val KEY_HIDE_POST_DATE = "hide_post_date"
    private const val KEY_LIKE_ANIMATION = "like_animation"
    private const val KEY_TAGGED_SORT = "tagged_sort"
    private const val KEY_SHOW_TAGGING_STATUS = "show_tagging_status"
    private const val KEY_TAG_WHEN_VIEWED = "tag_when_viewed"
    private const val KEY_SQUARE_CORNERS = "square_corners"
    private const val KEY_PROFILE_LAYOUT = "profile_layout"
    private const val KEY_STATUS_POSITION = "status_position"
    private const val KEY_SHOW_TRANSLATION_STATUS = "show_translation_status"
    private const val KEY_LOADING_ANIMATION = "loading_animation"
    private const val KEY_AUDIO_VISUALIZER = "audio_visualizer"
    private const val KEY_VISUALIZER_DURING_CALLS = "audio_visualizer_during_calls" // old on/off switch
    private const val KEY_VISUALIZER_CALL_MODE = "audio_visualizer_call_mode"
    private const val KEY_VISUALIZER_PERMISSION_ASKED = "audio_visualizer_permission_asked"
    private const val KEY_STARRY_BACKGROUND = "starry_background"
    private const val KEY_STAR_FRAME_RATE = "starry_background_fps"
    private const val KEY_LANDSCAPE_BAR_SCALE = "landscape_bar_scale"
    private const val KEY_LANDSCAPE_COLUMNS = "landscape_columns"
    private const val KEY_SIDE_LIST_WIDTH = "side_list_width_dp"
    private const val KEY_LANDSCAPE_LIST_COLUMNS = "landscape_list_columns"
    private const val KEY_SIDE_LIST_SCALE = "side_list_scale"
    private const val KEY_HUB_SCALE = "hub_scale"
    private const val KEY_HOME_SCALE = "home_scale"
    private const val KEY_HUB_LEFT_TITLES = "hub_left_titles"
    private const val KEY_HUB_HEADER_ALIGN = "hub_header_align"
    private const val KEY_HUB_DIVIDER_HEADERS = "hub_divider_headers"
    private const val KEY_HUB_HEADER_LINES = "hub_header_lines"
    private const val KEY_DEV_GRADIENT_BUTTONS = "dev_gradient_buttons"
    private const val KEY_STATUS_AMOUNT = "status_amount_" // + unit name
    private const val KEY_STATUS_UNIT = "status_unit"
    // Override App Colors is saved per signed-in account: these are the old
    // shared keys, now only read once to carry an existing choice over to
    // the first account that opens the app (see setAccount).
    private const val KEY_OVERRIDE_COLORS = "override_app_colors"
    private const val KEY_OVERRIDE_COLOR = "override_app_color"
    private const val KEY_OVERRIDE_ACTIVE_DID = "override_active_did"
    private const val KEY_OVERRIDE_LEGACY_CLAIMED = "override_legacy_claimed"
    private fun acct(key: String, did: String) = "$key@" + did.ifBlank { "signed_out" }
    private const val KEY_DEV_TOOLS_UNLOCKED = "dev_tools_unlocked"
    private const val KEY_DEV_FORCE_SCAN_BUBBLE = "dev_force_scan_bubble"
    private const val KEY_SCAN_BUBBLE_DISMISSED = "scan_bubble_dismissed"
    private const val KEY_EMOJI_NOTICE_SEEN = "emoji_public_notice_seen"
    private const val KEY_DM_SCALE = "dm_scale"
    private const val KEY_PERFORMANCE_MODE = "performance_mode"
    private const val KEY_CUSTOMIZE_HUB_COLLAPSED = "customize_hub_collapsed"
    private const val KEY_COLLAPSED_SETTINGS_SECTIONS = "collapsed_settings_sections"

    /** Settings → UI Customization → "Loading Animation". */
    enum class LoadingAnimation(val label: String) {
        /** The old page fades into a drifting starfield with the Stellar
         *  logo, which then fades away to reveal the loaded page. First in
         *  the list (and the default). */
        SPACE("Stellar"),
        /** The retro pixel-matrix wipe. */
        PIXELS("Pixels"),
        /** The screen shatters like glass from where you tapped. */
        SHATTER("Shatter"),
        /** Pages open immediately and fill in as their data arrives. */
        NONE("None")
    }

    private var prefs: SharedPreferences? = null

    /** Settings → App Functionality → "FPS Overlay": the frame-rate readout
     *  beside the camera cutout. (Stored under its old "debug overlay" key.) */
    var debugOverlay by mutableStateOf(false)
        private set

    /** Settings → Media Tagging → "Show Tagging Status" (under Tag Media When
     *  Liked): the like-tagging queue as a status bubble on the timeline. */
    var showTaggingStatus by mutableStateOf(true)
        private set
    /** Settings → Media Tagging → Tag Media When Viewed: every post looked
     *  at in Timeline mode is tagged (like Tag Media When Liked). */
    var tagWhenViewed by mutableStateOf(false)
        private set
    /** Settings → UI Customization → Squared-Off Corners: every rounded
     *  corner in the app is square (see ui/theme/Shapes.kt) — profile icons
     *  and the camera notch bubble stay round. */
    var squareCorners by mutableStateOf(false)
        private set
    /** Settings → Dev Tools → Profile Layout (portrait and landscape):
     *  1 — the name pills, picture and Follow over the banner;
     *  2 — the banner from the very top of the screen, its picture and name
     *      pills in a row of their own under it, Follow up by Back;
     *  3 — like 2, but the picture centered on the banner's bottom edge,
     *      the name to its left and the handle to its right, under the banner;
     *  4 — like 2, compact: the picture tucked up against the banner, the
     *      name, handle, status, counts and bio beside it. */
    var profileLayout by mutableStateOf(DEFAULT_PROFILE_LAYOUT)
        private set

    /** Dev Tools › Status Position: where Layout 4's status floats over the
     *  picture, to compare —
     *  1 — its bottom at the picture's top right (it grows upward);
     *  2 — centered where a three-line status is centered;
     *  3 — its top where a three-line status's top is (it grows downward);
     *  4 — as 1, a little higher. */
    var statusPosition by mutableStateOf(4)
        private set

    /** Settings → App Functionality → "Show Translation Status" (under
     *  Translate To): the "Translated X to Y" bubble on the timeline. */
    var showTranslationStatus by mutableStateOf(true)
        private set

    /** Settings → App Functionality → "Haptics": every vibration the app
     *  makes (taps, drags, effects). On by default. */
    var hapticsEnabled by mutableStateOf(true)
        private set

    fun updateHapticsEnabled(enabled: Boolean) {
        hapticsEnabled = enabled
        prefs?.edit()?.putBoolean(KEY_HAPTICS, enabled)?.apply()
    }

    /** Settings → App Functionality → "Hide Post Stats": the like / repost /
     *  save / comment counts at the bottom of a post's text bubble. */
    var hidePostStats by mutableStateOf(false)
        private set
    /** "Hide Post Date" (offered once the stats are hidden): with both, the
     *  whole row is gone. */
    var hidePostDate by mutableStateOf(false)
        private set

    fun updateHidePostStats(hide: Boolean) {
        hidePostStats = hide
        prefs?.edit()?.putBoolean(KEY_HIDE_POST_STATS, hide)?.apply()
    }

    /** Search › Tagged's Sort: 0 most liked (default), 1 most reposted,
     *  2 most saved, 3 most comments, 4 most recently uploaded, 5 most
     *  recently tagged, 6 oldest first. Remembered. */
    var taggedSort by mutableStateOf(0)
        private set

    fun updateTaggedSort(sort: Int) {
        taggedSort = sort.coerceIn(0, 6)
        prefs?.edit()?.putInt(KEY_TAGGED_SORT, taggedSort)?.apply()
    }

    /** Settings › UI Customization › "Like Animation": the heart that
     *  flies up when liking — None, 3D Heart (default) or 2D Heart. */
    enum class LikeAnimation(val label: String) { NONE("None"), HEART_3D("3D Heart"), HEART_2D("2D Heart") }
    var likeAnimation by mutableStateOf(LikeAnimation.HEART_3D)
        private set

    fun updateLikeAnimation(value: LikeAnimation) {
        likeAnimation = value
        prefs?.edit()?.putString(KEY_LIKE_ANIMATION, value.name)?.apply()
    }

    fun updateHidePostDate(hide: Boolean) {
        hidePostDate = hide
        prefs?.edit()?.putBoolean(KEY_HIDE_POST_DATE, hide)?.apply()
    }

    /** Which loading transition plays (default: Space). */
    var loadingAnimation by mutableStateOf(if (com.mediaviewer.platform.currentPlatform == com.mediaviewer.platform.PlatformKind.WEB) LoadingAnimation.NONE else LoadingAnimation.SPACE)
        private set

    /** Settings → UI Customization → "Audio Visualizer": bars above the
     *  feed's interaction bar that move to whatever music is playing.
     *  On by default everywhere except the web. */
    var audioVisualizer by mutableStateOf(com.mediaviewer.platform.currentPlatform != com.mediaviewer.platform.PlatformKind.WEB)
        private set

    /** Settings → UI Customization → "Starry Background": the twinkling
     *  stars / shooting stars behind every page (on by default). */
    var starryBackground by mutableStateOf(true)
        private set

    /** Settings → Starry Background → "Frame Rate Cap": how often the stars
     *  (twinkles, shooting stars) redraw. Low by default so the screen can
     *  drop to its idle refresh rate when nothing else is moving. */
    var starFrameRate by mutableStateOf(30)
        private set

    /** Landscape: how big the post's text bubble and interaction bar are
     *  drawn, as set by pinching on the bar (1 = the standard size). */
    var landscapeBarScale by mutableStateOf(1f)
        private set
    // Pinching can make it smaller than the standard size too; near the
    // standard size it locks onto it (with a haptic tick).
    /** Landscape: how many posts across the post grids are (Explore,
     *  profiles, Search) — the slider under a profile's side list. */
    var landscapeColumns by mutableStateOf(LANDSCAPE_DEFAULT_COLUMNS)
        private set
    const val LANDSCAPE_MIN_COLUMNS = 1
    const val LANDSCAPE_DEFAULT_COLUMNS = 5
    const val LANDSCAPE_MAX_COLUMNS = 10

    /** Landscape: how wide the side list on the left of every split page
     *  is (Hub, Search, profiles, Explore, DMs), in dp — dragged with the
     *  handle on its edge. */
    var sideListWidthDp by mutableStateOf(SIDE_LIST_DEFAULT_DP)
        private set
    const val SIDE_LIST_DEFAULT_DP = 340
    const val SIDE_LIST_MIN_DP = 200
    const val SIDE_LIST_MAX_DP = 560

    /** While dragging: [save] = false just moves it; the drag's end saves. */
    fun updateSideListWidth(dp: Int, save: Boolean) {
        sideListWidthDp = dp.coerceIn(SIDE_LIST_MIN_DP, SIDE_LIST_MAX_DP)
        if (save) prefs?.edit()?.putInt(KEY_SIDE_LIST_WIDTH, sideListWidthDp)?.apply()
    }

    /** The status popup's "Clear After": the number last typed for each
     *  unit (Minutes 30, Hours 24, Days 7 until changed) and the unit last
     *  picked ("NEVER" = it never clears). Kept in the backup with the rest. */
    fun statusAmount(unit: String, default: Int): Int = prefs?.getInt(KEY_STATUS_AMOUNT + unit, default) ?: default
    fun statusUnit(): String = prefs?.getString(KEY_STATUS_UNIT, null) ?: "HOURS"
    fun saveStatusDuration(unit: String, amount: Int?) {
        val e = prefs?.edit() ?: return
        e.putString(KEY_STATUS_UNIT, unit)
        if (amount != null) e.putInt(KEY_STATUS_AMOUNT + unit, amount)
        e.apply()
    }

    fun updateLandscapeColumns(columns: Int) {
        landscapeColumns = columns.coerceIn(LANDSCAPE_MIN_COLUMNS, LANDSCAPE_MAX_COLUMNS)
        prefs?.edit()?.putInt(KEY_LANDSCAPE_COLUMNS, landscapeColumns)?.apply()
    }

    /** Landscape: how many across the grids of bigger cards are — Blogs,
     *  Reviews, Music History and Collections (Lists/Feeds), on profiles
     *  and in Search — kept apart from [landscapeColumns] (posts), since a
     *  card there needs more room than a post's picture. */
    var landscapeListColumns by mutableStateOf(LANDSCAPE_DEFAULT_LIST_COLUMNS)
        private set
    const val LANDSCAPE_DEFAULT_LIST_COLUMNS = 3

    fun updateLandscapeListColumns(columns: Int) {
        landscapeListColumns = columns.coerceIn(LANDSCAPE_MIN_COLUMNS, LANDSCAPE_MAX_COLUMNS)
        prefs?.edit()?.putInt(KEY_LANDSCAPE_LIST_COLUMNS, landscapeListColumns)?.apply()
    }

    /** Landscape: how big the side list's buttons are drawn (pinch the
     *  list, or Ctrl + the mouse wheel over it). Its width is set apart,
     *  by its edge: widening it gives the same buttons more room, it
     *  doesn't blow them up. */
    var sideListScale by mutableStateOf(1f)
        private set
    const val SIDE_LIST_MIN_SCALE = 0.6f
    const val SIDE_LIST_MAX_SCALE = 2f

    /** Sets [sideListScale] now; [save] writes it (at the end of a pinch). */
    fun updateSideListScale(value: Float, save: Boolean) {
        sideListScale = value.coerceIn(SIDE_LIST_MIN_SCALE, SIDE_LIST_MAX_SCALE)
        if (save) prefs?.edit()?.putFloat(KEY_SIDE_LIST_SCALE, sideListScale)?.apply()
    }

    /** Customize Hub → Hub Settings: where each row's name sits (0 left,
     *  1 center, 2 right), whether it's the small name between two lines
     *  (the Hub's divider look) or a big heading, and — big — whether
     *  lines in the profile color run out from it to the page's edges. */
    var hubHeaderAlign by mutableStateOf(1)
        private set
    var hubDividerHeaders by mutableStateOf(true)
        private set
    var hubHeaderLines by mutableStateOf(false)
        private set

    fun updateHubHeaderAlign(align: Int) {
        hubHeaderAlign = align.coerceIn(0, 2)
        prefs?.edit()?.putInt(KEY_HUB_HEADER_ALIGN, hubHeaderAlign)?.apply()
    }
    fun updateHubDividerHeaders(on: Boolean) {
        hubDividerHeaders = on
        prefs?.edit()?.putBoolean(KEY_HUB_DIVIDER_HEADERS, on)?.apply()
    }
    fun updateHubHeaderLines(on: Boolean) {
        hubHeaderLines = on
        prefs?.edit()?.putBoolean(KEY_HUB_HEADER_LINES, on)?.apply()
    }

    /** UI Customization → Gradient Buttons: every button gets the filled popups' gradient (the one
     *  "Create" and the Home tab's buttons have) instead of glass. */
    var devGradientButtons by mutableStateOf(false)
        private set
    fun updateDevGradientButtons(on: Boolean) {
        devGradientButtons = on
        prefs?.edit()?.putBoolean(KEY_DEV_GRADIENT_BUTTONS, on)?.apply()
    }

    /** Landscape: how big the Hub's rows are (the Hub Size slider). */
    var hubScale by mutableStateOf(1f)
        private set
    const val HUB_MIN_SCALE = 0.6f
    const val HUB_MAX_SCALE = 1.6f

    fun updateHubScale(value: Float, save: Boolean) {
        hubScale = value.coerceIn(HUB_MIN_SCALE, HUB_MAX_SCALE)
        if (save) prefs?.edit()?.putFloat(KEY_HUB_SCALE, hubScale)?.apply()
    }

    /** Landscape: how big a profile's Home tab rows are (the Home Size
     *  slider). Its own size, remembered on its own like the Hub's. */
    var homeScale by mutableStateOf(1f)
        private set
    const val HOME_MIN_SCALE = 0.6f
    const val HOME_MAX_SCALE = 1.6f

    fun updateHomeScale(value: Float, save: Boolean) {
        homeScale = value.coerceIn(HOME_MIN_SCALE, HOME_MAX_SCALE)
        if (save) prefs?.edit()?.putFloat(KEY_HOME_SCALE, homeScale)?.apply()
    }

    const val LANDSCAPE_BAR_MIN_SCALE = 0.7f
    const val LANDSCAPE_BAR_MAX_SCALE = 1.8f
    val starFrameRateOptions = listOf(30, 60, 90, 120)

    /** Settings → UI Customization → "Override App Colors": everywhere the
     *  app would wear the signed-in account's profile color, it wears
     *  [overrideColor] instead (the account's own profile page keeps its
     *  real colors). */
    var overrideAppColors by mutableStateOf(false)
        private set
    /** ARGB. Defaults to the Stellar logo pink. */
    var overrideColor by mutableStateOf(0xFFFF4FA1.toInt())
        private set

    /** Settings → Audio Visualizer → "During Calls". */
    enum class VisualizerCallMode(val label: String) {
        /** The bars rest while you're on a call. */
        PAUSE("Pause"),
        /** Only the music app's own audio, so the call never moves the bars. */
        MUSIC_ONLY("Music Only"),
        /** Everything the phone plays, the call included. */
        ALL_AUDIO("All Audio")
    }

    var visualizerCallMode by mutableStateOf(VisualizerCallMode.PAUSE)
        private set

    /** Settings → hold the "Settings" tab for 10 seconds: the hidden
     *  "Dev Tools" section at the bottom of Settings. */
    var devToolsUnlocked by mutableStateOf(false)
        private set

    /** Dev Tools → the Hub's Reviews/Blogs rows are replaced by the
     *  "scan your follows" bubble, so that flow can be tried any time. */
    var devForceScanBubble by mutableStateOf(false)
        private set

    /** The Hub's "scan your follows" bubble was closed with its X — it
     *  doesn't come back (the scan stays available in Settings). */
    var scanBubbleDismissed by mutableStateOf(false)
        private set

    /** Dev Tools → "Preview Loading Animation": true while the preview is
     *  playing (AppRoot runs it). Not saved. */
    var devLoadingPreview by mutableStateOf(false)

    /** Dev Tools → "Preview Login Page": the login page shown over the app
     *  without signing out. Not saved. */
    var devLoginPreview by mutableStateOf(false)

    /** Dev Tools → "Preview Welcome Popup" / "Preview Support Popup": shows
     *  that popup now, whatever has been seen before. Not saved. */
    var devWelcomePreview by mutableStateOf(false)
    var devSupportPreview by mutableStateOf(false)

    /** Dev Tools → Repair Review Covers: set by the view model (which holds
     *  the sign-in); gives back what to tell the person. */
    var devRepairReviewCovers: (suspend () -> String)? = null

    /** Bumped to send the Hub straight to Settings → Support Stellar (a
     *  profile's "Supporter" label); the Hub sets it back to 0. */
    var supportPageRequest by mutableStateOf(0)

    /** Settings → the arrow beside "Customize Hub": its rows are folded
     *  away, leaving just the title. Remembered. */
    var customizeHubCollapsed by mutableStateOf(false)
        private set

    fun updateCustomizeHubCollapsed(collapsed: Boolean) {
        customizeHubCollapsed = collapsed
        prefs?.edit()?.putBoolean(KEY_CUSTOMIZE_HUB_COLLAPSED, collapsed)?.apply()
    }

    /** Settings: the categories folded away with the arrow beside their
     *  title, by title. Remembered. (Customize Hub's own older setting
     *  above is carried into this the first time.) */
    var collapsedSettingsSections by mutableStateOf<Set<String>>(emptySet())
        private set

    fun updateSettingsSectionCollapsed(title: String, collapsed: Boolean) {
        val next = if (collapsed) collapsedSettingsSections + title else collapsedSettingsSections - title
        collapsedSettingsSections = next
        prefs?.edit()?.putStringSet(KEY_COLLAPSED_SETTINGS_SECTIONS, next)?.apply()
    }

    /**
     * Settings → App Functionality → Performance Mode: the web version's
     * lighter drawing on every version (see LiteGlass) — no live glass blur,
     * no page re-recording every frame, popups dim instead of blurring the
     * page, a still starry sky, no video frame copies for blurred controls,
     * and no loading animation. Always on for the web.
     */
    var performanceMode by mutableStateOf(false)
        private set

    /** What [performanceMode] was when this run of the app started: what
     *  everything actually draws with (switching needs a restart). */
    var performanceModeAtStart: Boolean = false
        private set

    /** Switches Performance Mode and restarts the app to apply it. */
    fun applyPerformanceMode(context: PlatformContext, enabled: Boolean) {
        performanceMode = enabled
        // (Written at once: Android's restart ends the process right after.)
        val edit = prefs?.edit()?.putBoolean(KEY_PERFORMANCE_MODE, enabled)
        if (enabled) {
            loadingAnimation = LoadingAnimation.NONE
            edit?.putString(KEY_LOADING_ANIMATION, LoadingAnimation.NONE.name)
        }
        edit?.commit()
        // (iOS and desktop rebuild the app in place; Android relaunches.)
        performanceModeAtStart = enabled
        com.mediaviewer.ui.compat.restartApp(context)
    }

    /** How big DM messages are drawn (pinch in a chat, or Ctrl + wheel):
     *  1 = the normal size, never smaller; up to [DM_SCALE_MAX]. Kept on
     *  this device. */
    var dmScale by mutableStateOf(1f)
        private set
    const val DM_SCALE_MAX = 2.6f

    /** Sets [dmScale] now; [save] writes it (at the end of a pinch). */
    fun updateDmScale(value: Float, save: Boolean) {
        dmScale = value.coerceIn(1f, DM_SCALE_MAX)
        if (save) prefs?.edit()?.putFloat(KEY_DM_SCALE, dmScale)?.apply()
    }

    /** The one-time notice that custom emoji are public has been seen
     *  (and closed). */
    var emojiNoticeSeen by mutableStateOf(false)
        private set

    fun markEmojiNoticeSeen() {
        emojiNoticeSeen = true
        prefs?.edit()?.putBoolean(KEY_EMOJI_NOTICE_SEEN, true)?.apply()
    }

    /** Whether any loading transition/screen plays at all. */
    val loadingScreens: Boolean get() = loadingAnimation != LoadingAnimation.NONE

    /** Reads everything again from storage. For the one time the saved
     *  file changes underneath the app while it's running: a backup being
     *  imported on iOS, which rebuilds the app in place instead of
     *  restarting it like Android does. */
    fun reload(context: PlatformContext) {
        prefs = null
        init(context)
    }

    fun init(context: PlatformContext) {
        if (prefs != null) return
        val p = context.sharedPreferences(PREFS)
        prefs = p
        debugOverlay = p.getBoolean(KEY_DEBUG_OVERLAY, false)
        hapticsEnabled = p.getBoolean(KEY_HAPTICS, true)
        hidePostStats = p.getBoolean(KEY_HIDE_POST_STATS, false)
        hidePostDate = p.getBoolean(KEY_HIDE_POST_DATE, false)
        likeAnimation = p.getString(KEY_LIKE_ANIMATION, null)?.let { runCatching { LikeAnimation.valueOf(it) }.getOrNull() } ?: LikeAnimation.HEART_3D
        taggedSort = p.getInt(KEY_TAGGED_SORT, 0).coerceIn(0, 6)
        showTaggingStatus = p.getBoolean(KEY_SHOW_TAGGING_STATUS, true)
        tagWhenViewed = p.getBoolean(KEY_TAG_WHEN_VIEWED, false)
        squareCorners = p.getBoolean(KEY_SQUARE_CORNERS, false)
        profileLayout = p.getInt(KEY_PROFILE_LAYOUT, DEFAULT_PROFILE_LAYOUT).coerceIn(1, PROFILE_LAYOUTS)
        statusPosition = p.getInt(KEY_STATUS_POSITION, 4).coerceIn(1, STATUS_POSITIONS)
        showTranslationStatus = p.getBoolean(KEY_SHOW_TRANSLATION_STATUS, true)
        // (The web never has loading screens: it loads straight into the page.)
        loadingAnimation = if (com.mediaviewer.platform.currentPlatform == com.mediaviewer.platform.PlatformKind.WEB) LoadingAnimation.NONE
            else p.getString(KEY_LOADING_ANIMATION, null)?.let { name -> LoadingAnimation.entries.firstOrNull { it.name == name } }
                ?: LoadingAnimation.SPACE
        // (Off by default on the web; a choice saved in Settings always wins.)
        audioVisualizer = p.getBoolean(KEY_AUDIO_VISUALIZER, com.mediaviewer.platform.currentPlatform != com.mediaviewer.platform.PlatformKind.WEB)
        starryBackground = p.getBoolean(KEY_STARRY_BACKGROUND, true)
        starFrameRate = p.getInt(KEY_STAR_FRAME_RATE, 30).coerceIn(15, 120)
        landscapeBarScale = p.getFloat(KEY_LANDSCAPE_BAR_SCALE, 1f).coerceIn(LANDSCAPE_BAR_MIN_SCALE, LANDSCAPE_BAR_MAX_SCALE)
        landscapeColumns = p.getInt(KEY_LANDSCAPE_COLUMNS, LANDSCAPE_DEFAULT_COLUMNS).coerceIn(LANDSCAPE_MIN_COLUMNS, LANDSCAPE_MAX_COLUMNS)
        sideListWidthDp = p.getInt(KEY_SIDE_LIST_WIDTH, SIDE_LIST_DEFAULT_DP).coerceIn(SIDE_LIST_MIN_DP, SIDE_LIST_MAX_DP)
        landscapeListColumns = p.getInt(KEY_LANDSCAPE_LIST_COLUMNS, LANDSCAPE_DEFAULT_LIST_COLUMNS).coerceIn(LANDSCAPE_MIN_COLUMNS, LANDSCAPE_MAX_COLUMNS)
        sideListScale = p.getFloat(KEY_SIDE_LIST_SCALE, 1f).coerceIn(SIDE_LIST_MIN_SCALE, SIDE_LIST_MAX_SCALE)
        hubScale = p.getFloat(KEY_HUB_SCALE, 1f).coerceIn(HUB_MIN_SCALE, HUB_MAX_SCALE)
        homeScale = p.getFloat(KEY_HOME_SCALE, 1f).coerceIn(HOME_MIN_SCALE, HOME_MAX_SCALE)
        // (The old "Row Names on the Left" switch: on was big names at the left.)
        val leftTitles = p.getBoolean(KEY_HUB_LEFT_TITLES, false)
        hubHeaderAlign = p.getInt(KEY_HUB_HEADER_ALIGN, if (leftTitles) 0 else 1).coerceIn(0, 2)
        hubDividerHeaders = p.getBoolean(KEY_HUB_DIVIDER_HEADERS, !leftTitles)
        hubHeaderLines = p.getBoolean(KEY_HUB_HEADER_LINES, false)
        devGradientButtons = p.getBoolean(KEY_DEV_GRADIENT_BUTTONS, false)
        overrideDid = p.getString(KEY_OVERRIDE_ACTIVE_DID, null) ?: ""
        loadOverride(p, overrideDid)
        devToolsUnlocked = p.getBoolean(KEY_DEV_TOOLS_UNLOCKED, false)
        devForceScanBubble = p.getBoolean(KEY_DEV_FORCE_SCAN_BUBBLE, false)
        scanBubbleDismissed = p.getBoolean(KEY_SCAN_BUBBLE_DISMISSED, false)
        customizeHubCollapsed = p.getBoolean(KEY_CUSTOMIZE_HUB_COLLAPSED, false)
        emojiNoticeSeen = p.getBoolean(KEY_EMOJI_NOTICE_SEEN, false)
        dmScale = p.getFloat(KEY_DM_SCALE, 1f).coerceIn(1f, DM_SCALE_MAX)
        performanceMode = p.getBoolean(KEY_PERFORMANCE_MODE, false)
        performanceModeAtStart = performanceMode
        collapsedSettingsSections = p.getStringSet(KEY_COLLAPSED_SETTINGS_SECTIONS, null)?.toSet()
            ?: if (customizeHubCollapsed) setOf("Customize Hub") else emptySet()
        visualizerCallMode = p.getString(KEY_VISUALIZER_CALL_MODE, null)
            ?.let { name -> VisualizerCallMode.entries.firstOrNull { it.name == name } }
            ?: if (p.getBoolean(KEY_VISUALIZER_DURING_CALLS, false)) VisualizerCallMode.MUSIC_ONLY else VisualizerCallMode.PAUSE
    }

    fun updateDevToolsUnlocked(enabled: Boolean) {
        devToolsUnlocked = enabled
        if (!enabled) devForceScanBubble = false
        prefs?.edit()?.putBoolean(KEY_DEV_TOOLS_UNLOCKED, enabled)?.putBoolean(KEY_DEV_FORCE_SCAN_BUBBLE, devForceScanBubble)?.apply()
    }

    fun dismissScanBubble() {
        scanBubbleDismissed = true
        // Closing it while Dev Tools forces it on turns that off too.
        devForceScanBubble = false
        prefs?.edit()?.putBoolean(KEY_SCAN_BUBBLE_DISMISSED, true)?.putBoolean(KEY_DEV_FORCE_SCAN_BUBBLE, false)?.apply()
    }

    fun updateDevForceScanBubble(enabled: Boolean) {
        devForceScanBubble = enabled
        prefs?.edit()?.putBoolean(KEY_DEV_FORCE_SCAN_BUBBLE, enabled)?.apply()
    }

    fun updateVisualizerCallMode(value: VisualizerCallMode) {
        visualizerCallMode = value
        prefs?.edit()?.putString(KEY_VISUALIZER_CALL_MODE, value.name)?.apply()
    }

    fun updateAudioVisualizer(enabled: Boolean) {
        audioVisualizer = enabled
        prefs?.edit()?.putBoolean(KEY_AUDIO_VISUALIZER, enabled)?.apply()
    }

    /** Whose Override App Colors choice is showing. */
    private var overrideDid: String = ""

    /** The signed-in account changed (or became known): use its own
     *  Override App Colors choice. */
    fun setAccount(did: String) {
        val p = prefs ?: return
        if (did == overrideDid && p.contains(acct(KEY_OVERRIDE_COLORS, did))) return
        overrideDid = did
        p.edit().putString(KEY_OVERRIDE_ACTIVE_DID, did).commit()
        loadOverride(p, did)
    }

    private fun loadOverride(p: SharedPreferences, did: String) {
        if (!p.contains(acct(KEY_OVERRIDE_COLORS, did)) && !p.getBoolean(KEY_OVERRIDE_LEGACY_CLAIMED, false)) {
            // The old shared setting: shown while signed out, and handed to
            // the first account that signs in.
            val legacyOn = p.getBoolean(KEY_OVERRIDE_COLORS, false)
            val legacyColor = p.getInt(KEY_OVERRIDE_COLOR, 0xFFFF4FA1.toInt())
            overrideAppColors = legacyOn
            overrideColor = legacyColor
            if (did.isNotBlank()) {
                p.edit().putBoolean(KEY_OVERRIDE_LEGACY_CLAIMED, true)
                    .putBoolean(acct(KEY_OVERRIDE_COLORS, did), legacyOn)
                    .putInt(acct(KEY_OVERRIDE_COLOR, did), legacyColor)
                    .apply()
            }
            return
        }
        overrideAppColors = p.getBoolean(acct(KEY_OVERRIDE_COLORS, did), false)
        overrideColor = p.getInt(acct(KEY_OVERRIDE_COLOR, did), 0xFFFF4FA1.toInt())
    }

    fun updateOverrideAppColors(enabled: Boolean) {
        overrideAppColors = enabled
        prefs?.edit()?.putBoolean(acct(KEY_OVERRIDE_COLORS, overrideDid), enabled)
            ?.putInt(acct(KEY_OVERRIDE_COLOR, overrideDid), overrideColor)?.apply()
    }

    fun updateOverrideColor(argb: Int) {
        overrideColor = argb or 0xFF000000.toInt()
        prefs?.edit()?.putInt(acct(KEY_OVERRIDE_COLOR, overrideDid), overrideColor)
            ?.putBoolean(acct(KEY_OVERRIDE_COLORS, overrideDid), overrideAppColors)?.apply()
    }

    /** Resizes the landscape bar while pinching; [save] once the pinch ends. */
    fun setLandscapeBarScale(scale: Float, save: Boolean) {
        landscapeBarScale = scale.coerceIn(LANDSCAPE_BAR_MIN_SCALE, LANDSCAPE_BAR_MAX_SCALE)
        if (save) prefs?.edit()?.putFloat(KEY_LANDSCAPE_BAR_SCALE, landscapeBarScale)?.apply()
    }

    fun updateStarFrameRate(fps: Int) {
        starFrameRate = fps.coerceIn(15, 120)
        prefs?.edit()?.putInt(KEY_STAR_FRAME_RATE, starFrameRate)?.apply()
    }

    fun updateStarryBackground(enabled: Boolean) {
        starryBackground = enabled
        prefs?.edit()?.putBoolean(KEY_STARRY_BACKGROUND, enabled)?.apply()
    }

    /** The visualizer is on by default but needs the microphone permission
     *  to hear the phone's audio; it's asked for once, the first time the
     *  feed shows it. */
    val visualizerPermissionAsked: Boolean
        get() = prefs?.getBoolean(KEY_VISUALIZER_PERMISSION_ASKED, false) ?: true

    fun markVisualizerPermissionAsked() {
        prefs?.edit()?.putBoolean(KEY_VISUALIZER_PERMISSION_ASKED, true)?.apply()
    }

    fun updateShowTaggingStatus(enabled: Boolean) {
        showTaggingStatus = enabled
        prefs?.edit()?.putBoolean(KEY_SHOW_TAGGING_STATUS, enabled)?.apply()
    }

    const val PROFILE_LAYOUTS = 4
    const val DEFAULT_PROFILE_LAYOUT = 4
    const val STATUS_POSITIONS = 4

    fun updateStatusPosition(position: Int) {
        statusPosition = position.coerceIn(1, STATUS_POSITIONS)
        prefs?.edit()?.putInt(KEY_STATUS_POSITION, statusPosition)?.apply()
    }

    fun updateProfileLayout(layout: Int) {
        profileLayout = layout.coerceIn(1, PROFILE_LAYOUTS)
        prefs?.edit()?.putInt(KEY_PROFILE_LAYOUT, profileLayout)?.apply()
    }

    fun updateSquareCorners(enabled: Boolean) {
        squareCorners = enabled
        prefs?.edit()?.putBoolean(KEY_SQUARE_CORNERS, enabled)?.apply()
    }

    fun updateTagWhenViewed(enabled: Boolean) {
        tagWhenViewed = enabled
        prefs?.edit()?.putBoolean(KEY_TAG_WHEN_VIEWED, enabled)?.apply()
    }

    fun updateShowTranslationStatus(enabled: Boolean) {
        showTranslationStatus = enabled
        prefs?.edit()?.putBoolean(KEY_SHOW_TRANSLATION_STATUS, enabled)?.apply()
    }

    fun updateDebugOverlay(enabled: Boolean) {
        debugOverlay = enabled
        prefs?.edit()?.putBoolean(KEY_DEBUG_OVERLAY, enabled)?.apply()
    }

    fun updateLoadingAnimation(value: LoadingAnimation) {
        if (com.mediaviewer.platform.currentPlatform == com.mediaviewer.platform.PlatformKind.WEB) return
        loadingAnimation = value
        prefs?.edit()?.putString(KEY_LOADING_ANIMATION, value.name)?.apply()
    }
}
