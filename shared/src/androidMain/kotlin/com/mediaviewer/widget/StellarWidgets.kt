// check:jvm
package com.mediaviewer.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.mediaviewer.R
import com.mediaviewer.platform.WidgetChat
import com.mediaviewer.util.AppLinks
import com.mediaviewer.util.LocalData
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Stellar's three home-screen widgets (a supporter benefit): your DMs with
 * their unread counts, your calendar's upcoming events, and one note in
 * full. Each is a bubble in your own two profile colors, titled in the
 * app's font, with a list that scrolls by itself — so every one can be
 * resized to any shape. Tapping a chat, the events or the note opens
 * Stellar right there (see [AppLinks]).
 *
 * The Note widget chooses its note on the widget itself: a newly placed
 * one is a list of all your notes, and tapping one turns the widget into
 * that note ("Change" in its corner brings the list back). Each Note
 * widget remembers its own, so several can show different notes.
 *
 * A widget is drawn by the launcher from RemoteViews, so none of the app's
 * Compose UI can run in it; everything here reads plain saved data:
 *  - chats: a snapshot the app (while open) and the background check
 *    (while closed) write to the "stellar_widgets" preferences,
 *  - events and notes: the app's own on-device data ("supporter_local").
 */
object StellarWidgets {
    const val KIND_DMS = "dms"
    const val KIND_EVENTS = "events"
    const val KIND_NOTE = "note"
    const val EXTRA_KIND = "stellar_widget_kind"
    private const val PREFS = "stellar_widgets"
    private const val KEY_CHATS = "chats"
    /** The note last sent to the widgets from inside the app (Notes → a
     *  note → Widget): every Note widget switches to it. Blank = none. */
    const val KEY_NOTE_ID = "note_id"
    /** + a widget's id: the note that one widget shows. Not there = it
     *  hasn't been chosen yet, and the widget lists your notes instead. */
    private const val KEY_NOTE_OF = "note_id:"
    /** What [KEY_NOTE_ID] was when the widgets last acted on it. */
    private const val KEY_NOTE_SEEN = "note_id_seen"
    private const val KEY_NOTE_MIGRATED = "note_per_widget"
    /** A note in the list was tapped / "Change" was tapped. */
    const val ACTION_PICK_NOTE = "com.mediaviewer.widget.PICK_NOTE"
    const val ACTION_CHOOSE_NOTE = "com.mediaviewer.widget.CHOOSE_NOTE"
    const val EXTRA_NOTE_ID = "stellar_widget_note"

    private fun provider(kind: String): Class<out AppWidgetProvider> = when (kind) {
        KIND_DMS -> DmWidgetProvider::class.java
        KIND_EVENTS -> EventsWidgetProvider::class.java
        else -> NoteWidgetProvider::class.java
    }

    private fun ids(context: Context, kind: String): IntArray =
        AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, provider(kind)))

    fun hasDmWidget(context: Context): Boolean = try { ids(context, KIND_DMS).isNotEmpty() } catch (_: Exception) { false }

    /** The chat list as just read (by the app or the background check). */
    fun saveChats(context: Context, chats: List<WidgetChat>) {
        val arr = JSONArray()
        chats.take(40).forEach { c ->
            arr.put(JSONObject().apply {
                put("id", c.convoId); put("name", c.name); put("text", c.text); put("unread", c.unread)
                if (c.avatarUrl != null) put("avatar", c.avatarUrl)
                put("streak", c.streak); put("group", c.isGroup)
                if (c.groupAvatars.isNotEmpty()) put("members", JSONArray(c.groupAvatars))
            })
        }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val text = arr.toString()
        if (prefs.getString(KEY_CHATS, null) == text) return
        prefs.edit().putString(KEY_CHATS, text).apply()
        refresh(context, KIND_DMS)
    }

    internal fun chats(context: Context): List<WidgetChat> = try {
        val arr = JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CHATS, null) ?: "[]")
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val members = o.optJSONArray("members")
            WidgetChat(
                o.optString("id"), o.optString("name"), o.optString("text"), o.optInt("unread"),
                o.optString("avatar").takeIf { it.isNotBlank() },
                streak = o.optInt("streak"),
                groupAvatars = if (members == null) emptyList() else (0 until members.length()).map { members.optString(it) }.filter { it.isNotBlank() },
                isGroup = o.optBoolean("group")
            )
        }
    } catch (_: Exception) {
        emptyList()
    }

    /** Widgets are a supporter benefit: everyone else's show a short note. */
    internal fun isSupporter(context: Context): Boolean {
        val did = context.getSharedPreferences("self_profile_color", Context.MODE_PRIVATE).getString("did", null) ?: return false
        val dids = context.getSharedPreferences("stellar_supporters", Context.MODE_PRIVATE).getString("dids", null)?.split(',') ?: return false
        return did in dids
    }

    /** Your two profile colors (banner's, profile picture's), as the app
     *  last worked them out — or Stellar's own dark blue before it has. */
    private fun profileColors(context: Context): Pair<Int, Int> {
        val did = context.getSharedPreferences("self_profile_color", Context.MODE_PRIVATE).getString("did", null)
        val store = context.getSharedPreferences("profile_colors", Context.MODE_PRIVATE)
        val b = if (did != null) store.getInt("b:$did", 0) else 0
        val a = if (did != null) store.getInt("a:$did", 0) else 0
        return if (b == 0 || a == 0) Color.rgb(34, 38, 92) to Color.rgb(20, 22, 54) else b to a
    }

    private fun mix(c: Int, other: Int, t: Float): Int = Color.rgb(
        (Color.red(c) + (Color.red(other) - Color.red(c)) * t).roundToInt().coerceIn(0, 255),
        (Color.green(c) + (Color.green(other) - Color.green(c)) * t).roundToInt().coerceIn(0, 255),
        (Color.blue(c) + (Color.blue(other) - Color.blue(c)) * t).roundToInt().coerceIn(0, 255)
    )

    private fun openIntent(context: Context, link: String?): Intent =
        (context.packageManager.getLaunchIntentForPackage(context.packageName) ?: Intent()).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (link != null) putExtra(AppLinks.EXTRA, link)
        }

    internal fun update(context: Context, mgr: AppWidgetManager, id: Int, kind: String) {
        try {
            syncNoteChoices(context)
            val views = RemoteViews(context.packageName, R.layout.widget_stellar_list)
            // The bubble: three plain shapes colored here — the first
            // profile color, the second fading in across it, and a bright
            // rim (all deepened so white text reads on any colors). Shapes
            // are drawn by the launcher at whatever size the widget is, so
            // the outline stays crisp when it's resized or dragged around.
            val colors = profileColors(context)
            views.setInt(R.id.widget_bg, "setColorFilter", mix(colors.first, Color.BLACK, 0.5f))
            views.setInt(R.id.widget_bg, "setImageAlpha", 242)
            views.setInt(R.id.widget_bg_fade, "setColorFilter", mix(colors.second, Color.BLACK, 0.68f))
            views.setInt(R.id.widget_bg_fade, "setImageAlpha", 242)
            views.setInt(R.id.widget_bg_rim, "setColorFilter", mix(colors.first, Color.WHITE, 0.4f))
            views.setInt(R.id.widget_bg_rim, "setImageAlpha", 215)

            val supporter = isSupporter(context)
            val chats = if (kind == KIND_DMS) chats(context) else emptyList()
            val unread = chats.sumOf { it.unread }
            // The Note widget: its note, or — until one is chosen — the list.
            val note = if (kind == KIND_NOTE) noteFor(context, id) else null
            val choosing = kind == KIND_NOTE && note == null && supporter
            val title = when (kind) {
                KIND_DMS -> "DMs"
                KIND_EVENTS -> "Upcoming Events"
                else -> if (choosing) "Choose a Note" else note?.optString("title")?.takeIf { it.isNotBlank() } ?: "Note"
            }
            views.setTextViewText(R.id.widget_title, title)
            views.setViewVisibility(R.id.widget_switch, if (note != null && supporter) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.widget_badge, if (supporter && unread > 0) View.VISIBLE else View.GONE)
            if (unread > 0) views.setTextViewText(R.id.widget_badge, if (unread > 99) "99+" else unread.toString())

            // The list: one adapter per widget (the data Uri keeps them apart).
            val service = Intent(context, StellarWidgetService::class.java).apply {
                putExtra(EXTRA_KIND, kind)
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                data = Uri.parse("stellarwidget://$kind/$id")
            }
            views.setRemoteAdapter(R.id.widget_list, service)
            views.setEmptyView(R.id.widget_list, R.id.widget_empty)
            views.setTextViewText(
                R.id.widget_empty,
                when {
                    !supporter -> "A Stellar Supporter benefit.\nOpen Stellar to find out more."
                    kind == KIND_DMS -> "Open Stellar to load your chats."
                    kind == KIND_EVENTS -> "Nothing coming up."
                    note != null -> "This note is empty."
                    else -> "No notes yet.\nWrite one in Stellar and it will be listed here."
                }
            )

            // A row's tap opens Stellar on that chat / event / note; the
            // rest of the widget opens the matching page.
            val pageLink = when (kind) {
                KIND_DMS -> "dms"
                KIND_EVENTS -> "calendar"
                else -> note?.optString("id")?.takeIf { it.isNotBlank() }?.let { "note:$it" } ?: "notes"
            }
            val mutable = if (android.os.Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val template = if (choosing) {
                // Choosing: a row's tap doesn't open anything — it tells this
                // widget which note to become (see NoteWidgetProvider).
                PendingIntent.getBroadcast(
                    context, id, noteIntent(context, ACTION_PICK_NOTE, id), PendingIntent.FLAG_UPDATE_CURRENT or mutable
                )
            } else PendingIntent.getActivity(
                context, id, openIntent(context, null).apply { data = Uri.parse("stellarwidget://row/$kind/$id") },
                PendingIntent.FLAG_UPDATE_CURRENT or mutable
            )
            views.setPendingIntentTemplate(R.id.widget_list, template)
            if (kind == KIND_NOTE) views.setOnClickPendingIntent(
                R.id.widget_switch,
                PendingIntent.getBroadcast(
                    context, id + 200_000, noteIntent(context, ACTION_CHOOSE_NOTE, id),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            val page = PendingIntent.getActivity(
                context, id + 100_000, openIntent(context, pageLink).apply { data = Uri.parse("stellarwidget://page/$kind/$id") },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_header, page)
            views.setOnClickPendingIntent(R.id.widget_empty, page)

            mgr.updateAppWidget(id, views)
            mgr.notifyAppWidgetViewDataChanged(id, R.id.widget_list)
        } catch (e: Exception) {
            android.util.Log.e("StellarWidgets", "update failed", e)
        }
    }

    fun refresh(context: Context, kind: String) {
        try {
            syncNoteChoices(context)
            val mgr = AppWidgetManager.getInstance(context)
            ids(context, kind).forEach { update(context, mgr, it, kind) }
        } catch (_: Exception) {
        }
    }

    fun refreshAll(context: Context) {
        refresh(context, KIND_DMS); refresh(context, KIND_EVENTS); refresh(context, KIND_NOTE)
    }

    // ── On-device data ──────────────────────────────────────────────────

    private fun localJson(context: Context, key: String): JSONArray = try {
        JSONArray(context.getSharedPreferences(LocalData.PREFS, Context.MODE_PRIVATE).getString(key, null) ?: "[]")
    } catch (_: Exception) {
        JSONArray()
    }

    internal fun todayKey(): Int {
        val c = Calendar.getInstance()
        return c.get(Calendar.YEAR) * 10000 + (c.get(Calendar.MONTH) + 1) * 100 + c.get(Calendar.DAY_OF_MONTH)
    }

    /** Your events from today on plus the major holidays (when they're
     *  switched on in Supporter Settings), soonest first. */
    internal fun upcomingEvents(context: Context): List<JSONObject> {
        val arr = localJson(context, "calendar_events")
        val today = todayKey()
        val own = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.filter { it.optInt("day") >= today }
        val major = context.getSharedPreferences(LocalData.PREFS, Context.MODE_PRIVATE).getBoolean(LocalData.KEY_HOLIDAYS_MAJOR, true)
        val holidays = com.mediaviewer.util.Holidays.upcoming(today, major, minor = false)
            .filter { h -> own.none { it.optInt("day") == h.day && it.optString("title").trim().equals(h.title, ignoreCase = true) } }
            .map { JSONObject().put("day", it.day).put("minute", -1).put("title", it.title).put("holiday", true) }
        return (own + holidays)
            .sortedWith(compareBy({ it.optInt("day") }, { it.optBoolean("holiday") }, { it.optInt("minute", -1) }))
            .take(60)
    }

    /** All your notes, the most recently edited first. */
    internal fun notes(context: Context): List<JSONObject> {
        val arr = localJson(context, "notes")
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.sortedByDescending { it.optLong("updatedAt") }
    }

    /** The note the widget [widgetId] shows, or null when none has been
     *  chosen for it (or the one that was has since been deleted). */
    internal fun noteFor(context: Context, widgetId: Int): JSONObject? {
        val picked = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_NOTE_OF + widgetId, null)
        if (picked.isNullOrBlank()) return null
        return notes(context).firstOrNull { it.optString("id") == picked }
    }

    /** Sent to [NoteWidgetProvider] by a tap on the widget [widgetId]. */
    private fun noteIntent(context: Context, action: String, widgetId: Int): Intent =
        Intent(context, NoteWidgetProvider::class.java).apply {
            this.action = action
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            // (Keeps each widget's own intent apart from the others'.)
            data = Uri.parse("stellarwidget://note/$action/$widgetId")
        }

    /** Makes the widget [widgetId] show [noteId]; blank = back to the list. */
    fun chooseNote(context: Context, widgetId: Int, noteId: String) {
        val edit = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (noteId.isBlank()) edit.remove(KEY_NOTE_OF + widgetId) else edit.putString(KEY_NOTE_OF + widgetId, noteId)
        edit.commit()
        try {
            update(context, AppWidgetManager.getInstance(context), widgetId, KIND_NOTE)
        } catch (_: Exception) {
        }
    }

    fun forgetNoteWidget(context: Context, widgetId: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_NOTE_OF + widgetId).apply()
    }

    /**
     * Keeps each Note widget's own choice in step with two things that
     * happen outside the widget:
     *
     *  - **Coming from the version before per-widget notes.** Then there
     *    was one choice for all Note widgets (or none: the newest note).
     *    The first time this runs after the app has been updated, every
     *    Note widget already on the home screen keeps showing what it
     *    showed, instead of turning into a list.
     *  - **"Widget" on a note inside the app** (Notes → a note → Widget).
     *    That sends the note to every Note widget; switching it off again
     *    (or deleting the note) sends the widgets that showed it back to
     *    the list.
     */
    private fun syncNoteChoices(context: Context) {
        try {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val fromApp = prefs.getString(KEY_NOTE_ID, null).orEmpty()
            val migrated = prefs.getBoolean(KEY_NOTE_MIGRATED, false)
            val seen = prefs.getString(KEY_NOTE_SEEN, null)
            if (migrated && seen == fromApp) return
            val widgets = ids(context, KIND_NOTE)
            val edit = prefs.edit()
            if (!migrated) {
                // (A fresh install has nothing to carry over.)
                val info = context.packageManager.getPackageInfo(context.packageName, 0)
                if (info.firstInstallTime != info.lastUpdateTime) {
                    val before = fromApp.ifBlank { notes(context).firstOrNull()?.optString("id").orEmpty() }
                    if (before.isNotBlank()) widgets.forEach { if (!prefs.contains(KEY_NOTE_OF + it)) edit.putString(KEY_NOTE_OF + it, before) }
                }
                edit.putBoolean(KEY_NOTE_MIGRATED, true)
            } else if (fromApp.isNotBlank()) {
                widgets.forEach { edit.putString(KEY_NOTE_OF + it, fromApp) }
            } else if (!seen.isNullOrBlank()) {
                widgets.forEach { if (prefs.getString(KEY_NOTE_OF + it, null) == seen) edit.remove(KEY_NOTE_OF + it) }
            }
            edit.putString(KEY_NOTE_SEEN, fromApp).commit()
        } catch (e: Exception) {
            android.util.Log.e("StellarWidgets", "syncing note choices failed", e)
        }
    }
}

abstract class StellarWidgetProvider(private val kind: String) : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { StellarWidgets.update(context, appWidgetManager, it, kind) }
        // (The DMs widget keeps the background check running.)
        if (kind == StellarWidgets.KIND_DMS) try {
            com.mediaviewer.worker.StellarNotificationScheduler.schedule(context)
            com.mediaviewer.worker.StellarNotificationScheduler.checkNow(context)
        } catch (_: Exception) {
        }
    }

}

class DmWidgetProvider : StellarWidgetProvider(StellarWidgets.KIND_DMS)
class EventsWidgetProvider : StellarWidgetProvider(StellarWidgets.KIND_EVENTS)
class NoteWidgetProvider : StellarWidgetProvider(StellarWidgets.KIND_NOTE) {
    /** Taps on the widget that change what it shows: a note in the list
     *  (the widget becomes that note) and "Change" (back to the list). */
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        when (intent.action) {
            StellarWidgets.ACTION_PICK_NOTE -> {
                val note = intent.getStringExtra(StellarWidgets.EXTRA_NOTE_ID).orEmpty()
                if (id != AppWidgetManager.INVALID_APPWIDGET_ID && note.isNotBlank()) StellarWidgets.chooseNote(context, id, note)
            }
            StellarWidgets.ACTION_CHOOSE_NOTE -> {
                if (id != AppWidgetManager.INVALID_APPWIDGET_ID) StellarWidgets.chooseNote(context, id, "")
            }
            else -> super.onReceive(context, intent)
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        appWidgetIds.forEach { StellarWidgets.forgetNoteWidget(context, it) }
    }
}

/** Supplies the rows of each widget's list. */
class StellarWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        Factory(
            applicationContext, intent.getStringExtra(StellarWidgets.EXTRA_KIND) ?: StellarWidgets.KIND_DMS,
            intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        )

    private class Factory(private val context: Context, private val kind: String, private val widgetId: Int) : RemoteViewsFactory {
        private var chats: List<WidgetChat> = emptyList()
        private var events: List<JSONObject> = emptyList()
        private var noteId: String = ""
        private var lines: List<String> = emptyList()
        /** The Note widget before a note is chosen: all of them, to pick from. */
        private var choices: List<JSONObject> = emptyList()
        private val avatars = HashMap<String, Bitmap?>()
        private var today = StellarWidgets.todayKey()

        override fun onCreate() {}
        override fun onDestroy() { avatars.clear() }

        // Runs on a background (binder) thread: loading here is fine.
        override fun onDataSetChanged() {
            today = StellarWidgets.todayKey()
            if (!StellarWidgets.isSupporter(context)) { chats = emptyList(); events = emptyList(); lines = emptyList(); choices = emptyList(); return }
            when (kind) {
                StellarWidgets.KIND_DMS -> {
                    chats = StellarWidgets.chats(context)
                    // Every picture is kept under its own address, so a row
                    // can only ever get the picture that belongs to it.
                    chats.flatMap { listOfNotNull(it.avatarUrl) + it.groupAvatars }.distinct().take(40).forEach { url ->
                        if (avatars[url] == null) avatars[url] = loadAvatar(url)
                    }
                }
                StellarWidgets.KIND_EVENTS -> events = StellarWidgets.upcomingEvents(context)
                else -> {
                    val note = StellarWidgets.noteFor(context, widgetId)
                    // No note chosen for this widget (yet): the list of them.
                    choices = if (note == null) StellarWidgets.notes(context) else emptyList()
                    noteId = note?.optString("id").orEmpty()
                    // Pictures in a note are left out here (text only).
                    lines = note?.optString("body").orEmpty().split('\n')
                        .filterNot { it.trim().startsWith("![") }
                        .dropLastWhile { it.isBlank() }
                }
            }
        }

        override fun getCount(): Int = when (kind) {
            StellarWidgets.KIND_DMS -> chats.size
            StellarWidgets.KIND_EVENTS -> events.size
            else -> if (noteId.isBlank()) choices.size else lines.size
        }

        override fun getViewAt(position: Int): RemoteViews? = try {
            when (kind) {
                StellarWidgets.KIND_DMS -> chatRow(chats[position])
                StellarWidgets.KIND_EVENTS -> eventRow(events[position])
                else -> if (noteId.isBlank()) choiceRow(choices[position]) else lineRow(lines[position])
            }
        } catch (_: Exception) {
            null
        }

        private fun fillIn(link: String): Intent = Intent().putExtra(AppLinks.EXTRA, link)

        private fun chatRow(chat: WidgetChat): RemoteViews {
            val row = RemoteViews(context.packageName, R.layout.widget_stellar_row_chat)
            row.setTextViewText(R.id.row_title, chat.name.ifBlank { "Chat" })
            row.setTextViewText(R.id.row_text, chat.text.ifBlank { if (chat.unread > 0) "New message" else "" })
            row.setViewVisibility(R.id.row_badge, if (chat.unread > 0) View.VISIBLE else View.GONE)
            if (chat.unread > 0) row.setTextViewText(R.id.row_badge, if (chat.unread > 99) "99+" else chat.unread.toString())
            val avatar = if (chat.isGroup) groupAvatar(chat) else chat.avatarUrl?.let { avatars[it] } ?: initialAvatar(chat.name)
            row.setImageViewBitmap(R.id.row_avatar, avatar)
            // The streak, as the DM list shows it.
            row.setViewVisibility(R.id.row_streak, if (chat.streak > 0) View.VISIBLE else View.GONE)
            if (chat.streak > 0) row.setTextViewText(R.id.row_streak, "\uD83D\uDD25 " + chat.streak)
            row.setOnClickFillInIntent(R.id.row_root, fillIn("dm:" + chat.convoId))
            return row
        }

        private fun eventRow(ev: JSONObject): RemoteViews {
            val row = RemoteViews(context.packageName, R.layout.widget_stellar_row_event)
            val day = ev.optInt("day")
            row.setTextViewText(R.id.row_chip, dayLabel(day))
            row.setTextViewText(R.id.row_title, ev.optString("title"))
            val minute = ev.optInt("minute", -1)
            row.setTextViewText(
                R.id.row_text,
                if (minute < 0) "All day" else {
                    val h = minute / 60
                    val m = minute % 60
                    "${if (h % 12 == 0) 12 else h % 12}:${if (m < 10) "0$m" else m} ${if (h < 12) "AM" else "PM"}"
                }
            )
            row.setTextViewText(R.id.row_countdown, countdown(day))
            // A tap opens the Calendar on this event's day.
            row.setOnClickFillInIntent(R.id.row_root, fillIn("calendar:$day"))
            return row
        }

        private fun dayNumber(day: Int): Long =
            com.mediaviewer.util.CalendarMath.daysFromCivil(day / 10000, day / 100 % 100, day % 100)

        private fun countdown(day: Int): String {
            val diff = dayNumber(day) - dayNumber(today)
            return when {
                diff <= 0L -> "Today"
                diff == 1L -> "In 1 Day"
                else -> "In $diff Days"
            }
        }

        private fun dayLabel(day: Int): String {
            if (day == today) return "Today"
            val c = Calendar.getInstance()
            c.add(Calendar.DAY_OF_YEAR, 1)
            val tomorrow = c.get(Calendar.YEAR) * 10000 + (c.get(Calendar.MONTH) + 1) * 100 + c.get(Calendar.DAY_OF_MONTH)
            if (day == tomorrow) return "Tomorrow"
            val months = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
            return months[(day / 100 % 100 - 1).coerceIn(0, 11)] + " " + (day % 100)
        }

        /** One note to choose from: its title, and its first line of text. */
        private fun choiceRow(note: JSONObject): RemoteViews {
            val row = RemoteViews(context.packageName, R.layout.widget_stellar_row_pick)
            val firstLine = note.optString("body").split('\n')
                .map { it.trim().trimStart('#', '-', '*', '>', ' ').replace("**", "").replace("~~", "").replace("`", "") }
                .firstOrNull { it.isNotBlank() && !it.startsWith("![") && !it.startsWith("[ ]") && !it.startsWith("[x]") }
                .orEmpty()
            val title = note.optString("title").trim()
            row.setTextViewText(R.id.row_title, title.ifBlank { firstLine.ifBlank { "Untitled note" } })
            row.setTextViewText(R.id.row_text, if (title.isBlank()) "" else firstLine)
            row.setViewVisibility(R.id.row_text, if (title.isBlank() || firstLine.isBlank()) View.GONE else View.VISIBLE)
            row.setOnClickFillInIntent(R.id.row_root, Intent().putExtra(StellarWidgets.EXTRA_NOTE_ID, note.optString("id")))
            return row
        }

        /** One line of the note, with the markdown it starts with turned
         *  into how it reads: headings bigger and bold, bullets, ticked /
         *  unticked boxes, quotes. */
        private fun lineRow(raw: String): RemoteViews {
            val row = RemoteViews(context.packageName, R.layout.widget_stellar_row_line)
            val line = raw.trimEnd()
            val trimmed = line.trimStart()
            var size = 13f
            var bold = false
            var struck = false
            val text: String = when {
                trimmed.startsWith("### ") -> { size = 14f; bold = true; trimmed.drop(4) }
                trimmed.startsWith("## ") -> { size = 16f; bold = true; trimmed.drop(3) }
                trimmed.startsWith("# ") -> { size = 18f; bold = true; trimmed.drop(2) }
                trimmed.startsWith("- [ ] ") || trimmed.startsWith("* [ ] ") -> "☐  " + trimmed.drop(6)
                trimmed.startsWith("- [x] ") || trimmed.startsWith("- [X] ") || trimmed.startsWith("* [x] ") || trimmed.startsWith("* [X] ") -> {
                    struck = true; "☑  " + trimmed.drop(6)
                }
                trimmed.startsWith("- ") || trimmed.startsWith("* ") -> "•  " + trimmed.drop(2)
                trimmed.startsWith("> ") -> "▎ " + trimmed.drop(2)
                else -> line
            }
            // (Inline markers are dropped rather than shown as symbols.)
            val clean = text.replace("**", "").replace("~~", "").replace("`", "")
            val styled = SpannableString(clean)
            if (bold && clean.isNotEmpty()) styled.setSpan(StyleSpan(Typeface.BOLD), 0, clean.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (struck && clean.length > 3) styled.setSpan(StrikethroughSpan(), 3, clean.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            row.setTextViewText(R.id.row_line, styled)
            row.setTextViewTextSize(R.id.row_line, TypedValue.COMPLEX_UNIT_SP, size)
            row.setOnClickFillInIntent(R.id.row_line, fillIn(if (noteId.isNotBlank()) "note:$noteId" else "notes"))
            return row
        }

        override fun getLoadingView(): RemoteViews? = null
        override fun getViewTypeCount(): Int = 4
        override fun getItemId(position: Int): Long = position.toLong()
        override fun hasStableIds(): Boolean = false

        /** A small round copy of a profile picture. */
        private fun loadAvatar(url: String): Bitmap? = try {
            val small = url.replace("/img/avatar/", "/img/avatar_thumbnail/")
            val conn = java.net.URL(small).openConnection().apply { connectTimeout = 6000; readTimeout = 6000 }
            val src = conn.getInputStream().use { BitmapFactory.decodeStream(it) }
            if (src == null) null else circle(src, 96)
        } catch (_: Exception) {
            null
        }

        private fun circle(src: Bitmap, size: Int): Bitmap {
            val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val side = min(src.width, src.height)
            val square = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
            val scaled = Bitmap.createScaledBitmap(square, size, size, true)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = BitmapShader(scaled, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
            Canvas(out).drawCircle(size / 2f, size / 2f, size / 2f, paint)
            return out
        }

        /** A group chat: two of its members' pictures, overlapped. */
        private fun groupAvatar(chat: WidgetChat): Bitmap {
            val pictures = chat.groupAvatars.mapNotNull { avatars[it] }
            if (pictures.isEmpty()) return initialAvatar(chat.name)
            if (pictures.size == 1) return pictures[0]
            val size = 96
            val small = (size * 0.66f).roundToInt()
            val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            canvas.drawBitmap(pictures[0], null, RectF(0f, 0f, small.toFloat(), small.toFloat()), paint)
            canvas.drawBitmap(pictures[1], null, RectF((size - small).toFloat(), (size - small).toFloat(), size.toFloat(), size.toFloat()), paint)
            return out
        }

        /** No picture (or it couldn't load): the name's first letter. */
        private fun initialAvatar(name: String): Bitmap {
            val size = 96
            val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            canvas.drawCircle(size / 2f, size / 2f, size / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 255, 255, 255) })
            val letter = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE; textSize = size * 0.46f; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD
            }
            canvas.drawText(letter, size / 2f, size / 2f - (paint.descent() + paint.ascent()) / 2f, paint)
            return out
        }
    }
}
