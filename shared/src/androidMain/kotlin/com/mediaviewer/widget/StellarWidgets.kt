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
    /** The note the Note widget shows (Notes → a note → Widget). Blank =
     *  the note edited most recently. */
    const val KEY_NOTE_ID = "note_id"
    private const val BUBBLE_MAX_PX = 220

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
            WidgetChat(o.optString("id"), o.optString("name"), o.optString("text"), o.optInt("unread"), o.optString("avatar").takeIf { it.isNotBlank() })
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

    /** The bubble: a rounded rectangle fading from one profile color to
     *  the other (both deepened, so white text reads on any colors) with a
     *  bright rim in the same hue — the app's glass panels, as a picture.
     *  Kept small on purpose: it travels to the launcher in one Binder
     *  call (see LiveLinkWidgetProvider) and is stretched there. */
    private fun bubble(widthPx: Int, heightPx: Int, colors: Pair<Int, Int>): Bitmap {
        val w = widthPx.coerceAtLeast(1)
        val h = heightPx.coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val radius = min(w, h) * 0.16f
        val stroke = (min(w, h) * 0.014f).coerceAtLeast(1.5f)
        val rect = RectF(stroke / 2f, stroke / 2f, w - stroke / 2f, h - stroke / 2f)
        val top = mix(colors.first, Color.BLACK, 0.5f)
        val bottom = mix(colors.second, Color.BLACK, 0.68f)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), top, bottom, Shader.TileMode.CLAMP)
            alpha = 242
        }
        canvas.drawRoundRect(rect, radius, radius, fill)
        val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            shader = LinearGradient(
                0f, 0f, w.toFloat(), h.toFloat(),
                mix(colors.first, Color.WHITE, 0.45f), mix(colors.second, Color.WHITE, 0.2f), Shader.TileMode.CLAMP
            )
            alpha = 215
        }
        canvas.drawRoundRect(rect, radius, radius, rim)
        return bmp
    }

    private fun openIntent(context: Context, link: String?): Intent =
        (context.packageManager.getLaunchIntentForPackage(context.packageName) ?: Intent()).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (link != null) putExtra(AppLinks.EXTRA, link)
        }

    internal fun update(context: Context, mgr: AppWidgetManager, id: Int, kind: String) {
        try {
            val views = RemoteViews(context.packageName, R.layout.widget_stellar_list)
            val options = mgr.getAppWidgetOptions(id)
            val minW = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 180).coerceAtLeast(1)
            val minH = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 180).coerceAtLeast(1)
            val aspect = minW.toFloat() / minH.toFloat()
            val (bw, bh) = if (aspect >= 1f) BUBBLE_MAX_PX to (BUBBLE_MAX_PX / aspect).roundToInt().coerceAtLeast(1)
                else (BUBBLE_MAX_PX * aspect).roundToInt().coerceAtLeast(1) to BUBBLE_MAX_PX
            views.setImageViewBitmap(R.id.widget_bg, bubble(bw, bh, profileColors(context)))

            val supporter = isSupporter(context)
            val chats = if (kind == KIND_DMS) chats(context) else emptyList()
            val unread = chats.sumOf { it.unread }
            val title = when (kind) {
                KIND_DMS -> "DMs"
                KIND_EVENTS -> "Upcoming Events"
                else -> noteFor(context)?.optString("title")?.takeIf { it.isNotBlank() } ?: "Note"
            }
            views.setTextViewText(R.id.widget_title, title)
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
                    else -> "No notes yet."
                }
            )

            // A row's tap opens Stellar on that chat / event / note; the
            // rest of the widget opens the matching page.
            val pageLink = when (kind) {
                KIND_DMS -> "dms"
                KIND_EVENTS -> "calendar"
                else -> noteFor(context)?.optString("id")?.takeIf { it.isNotBlank() }?.let { "note:$it" } ?: "notes"
            }
            val mutable = if (android.os.Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val template = PendingIntent.getActivity(
                context, id, openIntent(context, null).apply { data = Uri.parse("stellarwidget://row/$kind/$id") },
                PendingIntent.FLAG_UPDATE_CURRENT or mutable
            )
            views.setPendingIntentTemplate(R.id.widget_list, template)
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

    /** Events from today on, soonest first. */
    internal fun upcomingEvents(context: Context): List<JSONObject> {
        val arr = localJson(context, "calendar_events")
        val today = todayKey()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
            .filter { it.optInt("day") >= today }
            .sortedWith(compareBy({ it.optInt("day") }, { it.optInt("minute", -1) }))
            .take(60)
    }

    /** The note to show: the one picked in Notes, else the newest. */
    internal fun noteFor(context: Context): JSONObject? {
        val arr = localJson(context, "notes")
        val notes = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        val picked = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_NOTE_ID, null)
        return notes.firstOrNull { it.optString("id") == picked } ?: notes.maxByOrNull { it.optLong("updatedAt") }
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

    override fun onAppWidgetOptionsChanged(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle) {
        // Resized: the bubble is redrawn for the new shape.
        StellarWidgets.update(context, appWidgetManager, appWidgetId, kind)
    }
}

class DmWidgetProvider : StellarWidgetProvider(StellarWidgets.KIND_DMS)
class EventsWidgetProvider : StellarWidgetProvider(StellarWidgets.KIND_EVENTS)
class NoteWidgetProvider : StellarWidgetProvider(StellarWidgets.KIND_NOTE)

/** Supplies the rows of each widget's list. */
class StellarWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        Factory(applicationContext, intent.getStringExtra(StellarWidgets.EXTRA_KIND) ?: StellarWidgets.KIND_DMS)

    private class Factory(private val context: Context, private val kind: String) : RemoteViewsFactory {
        private var chats: List<WidgetChat> = emptyList()
        private var events: List<JSONObject> = emptyList()
        private var noteId: String = ""
        private var lines: List<String> = emptyList()
        private val avatars = HashMap<String, Bitmap?>()
        private var today = StellarWidgets.todayKey()

        override fun onCreate() {}
        override fun onDestroy() { avatars.clear() }

        // Runs on a background (binder) thread: loading here is fine.
        override fun onDataSetChanged() {
            today = StellarWidgets.todayKey()
            if (!StellarWidgets.isSupporter(context)) { chats = emptyList(); events = emptyList(); lines = emptyList(); return }
            when (kind) {
                StellarWidgets.KIND_DMS -> {
                    chats = StellarWidgets.chats(context)
                    chats.mapNotNull { it.avatarUrl }.distinct().take(24).forEach { url ->
                        if (!avatars.containsKey(url)) avatars[url] = loadAvatar(url)
                    }
                }
                StellarWidgets.KIND_EVENTS -> events = StellarWidgets.upcomingEvents(context)
                else -> {
                    val note = StellarWidgets.noteFor(context)
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
            else -> lines.size
        }

        override fun getViewAt(position: Int): RemoteViews? = try {
            when (kind) {
                StellarWidgets.KIND_DMS -> chatRow(chats[position])
                StellarWidgets.KIND_EVENTS -> eventRow(events[position])
                else -> lineRow(lines[position])
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
            val avatar = chat.avatarUrl?.let { avatars[it] } ?: initialAvatar(chat.name)
            row.setImageViewBitmap(R.id.row_avatar, avatar)
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
            row.setOnClickFillInIntent(R.id.row_root, fillIn("calendar"))
            return row
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
        override fun getViewTypeCount(): Int = 3
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
