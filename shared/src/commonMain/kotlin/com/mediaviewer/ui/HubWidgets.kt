package com.mediaviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.mediaviewer.model.AuthorInfo
import com.mediaviewer.model.LeafletBlog
import com.mediaviewer.util.CalendarMath
import com.mediaviewer.util.DateText
import com.mediaviewer.util.LocalData
import com.mediaviewer.util.dayLabel
import com.mediaviewer.util.rememberHapticTap
import com.mediaviewer.util.timeLabel
import com.mediaviewer.util.countdownLabel

/** Every Hub blog card is this size: a portrait page. */
internal val HUB_BLOG_CARD_WIDTH = 168.dp
internal val HUB_BLOG_CARD_PAGE_HEIGHT = 238.dp

/** A blog's page: a soft, dark version of the author's colour, lighter at
 *  the top. The reader and the Hub's blog cards both use it, so a card
 *  looks like the page it opens. */
internal fun blogPageBrush(tint: Color): Brush {
    val soft = lerp(Color(0xFF141418), tint, 0.30f)
    return Brush.verticalGradient(listOf(lerp(soft, Color.White, 0.05f), soft, lerp(soft, Color.Black, 0.35f)))
}

/**
 * A blog in the Hub's Blogs row: one fixed, portrait, page-like card that
 * reads like the blog itself does once opened — its cover along the top
 * (cropped to a fixed band, so a huge or tiny cover never changes the
 * card's size; blogs without one simply get more room for text), the
 * author and date, the title, the tagline, and the first lines of the text
 * fading out at the bottom.
 */
@Composable
internal fun HubBlogCard(
    blog: LeafletBlog,
    author: AuthorInfo,
    tint: Color,
    liquidGlass: Boolean,
    onOpen: () -> Unit,
    onOpenAuthor: () -> Unit
) {
    val tap = rememberHapticTap()
    val shape = RoundedCornerShape(18.dp)
    val accent = lerp(tint, Color.White, 0.55f)
    val date = remember(blog.createdAt) {
        if (blog.createdAt.isBlank()) "" else runCatching {
            DateText.format(com.mediaviewer.platform.parseIsoInstantMillis(blog.createdAt), "MMM d, yyyy")
        }.getOrDefault("")
    }
    val tagline = blog.description?.trim().orEmpty()
    // The opening of the text, as flowing prose (no blank lines, and never
    // the title or tagline repeated).
    val opening = remember(blog.bodyText, blog.title, tagline) {
        markdownPlain(blog.bodyText).lineSequence().map { it.trim() }
            .filter { it.isNotEmpty() && !it.equals(blog.title.trim(), ignoreCase = true) && !it.equals(tagline, ignoreCase = true) }
            .joinToString(" ").take(420)
    }
    val page = remember(tint) { blogPageBrush(tint) }
    Column(
        Modifier.size(width = HUB_BLOG_CARD_WIDTH, height = HUB_BLOG_CARD_PAGE_HEIGHT)
            .clip(shape)
            // The same page the blog opens onto (BlogDetailOverlay).
            .background(page)
            .border(1.dp, lerp(tint, Color.White, 0.3f).copy(alpha = 0.35f), shape)
            .clickable { tap(); onOpen() }
    ) {
        val cover = blog.thumbnailUrl
        if (!cover.isNullOrBlank()) {
            Box(Modifier.fillMaxWidth().height(84.dp)) {
                AsyncImage(
                    model = cover, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                // Eases the picture into the page below it.
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.55f))
                    )
                )
            }
        } else {
            // No cover: a thin band in the blog's color, like a book's spine.
            Box(
                Modifier.fillMaxWidth().height(6.dp)
                    .background(Brush.horizontalGradient(listOf(tint.copy(alpha = 0.9f), accent.copy(alpha = 0.7f), tint.copy(alpha = 0.9f))))
            )
        }
        Column(Modifier.fillMaxSize().padding(start = 11.dp, end = 11.dp, top = 9.dp, bottom = 0.dp)) {
            // (Whose blog it is shows in the bubble above the card.)
            Spacer(Modifier.height(7.dp))
            Text(
                blog.title.ifBlank { "Untitled" }, color = Color.White, fontSize = 15.sp, lineHeight = 18.sp,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Serif,
                fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis
            )
            if (tagline.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    tagline, color = accent.copy(alpha = 0.95f), fontSize = 11.sp, lineHeight = 14.sp,
                    fontStyle = FontStyle.Italic, maxLines = 2, overflow = TextOverflow.Ellipsis
                )
            }
            if (date.isNotEmpty()) {
                Spacer(Modifier.height(5.dp))
                Text(date.uppercase(), color = Color.White.copy(alpha = 0.5f), fontSize = 8.sp, lineHeight = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp)
            }
            Spacer(Modifier.height(6.dp))
            HorizontalDivider(color = accent.copy(alpha = 0.35f), thickness = 0.5.dp)
            Spacer(Modifier.height(6.dp))
            // The text takes whatever height is left and fades out, the
            // way a page continues below the fold.
            Text(
                opening, color = Color.White.copy(alpha = 0.78f), fontSize = 10.sp, lineHeight = 14.sp,
                overflow = TextOverflow.Clip,
                modifier = Modifier.fillMaxWidth().weight(1f)
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        drawRect(
                            Brush.verticalGradient(0f to Color.Black, 0.55f to Color.Black, 1f to Color.Transparent),
                            blendMode = BlendMode.DstIn
                        )
                    }
            )
        }
    }
}

/**
 * Customize Hub → Add → Widgets → "Upcoming Events" (supporters): your
 * calendar's next events and their dates, in a short list that scrolls on
 * its own. Tapping it opens the Calendar.
 */
@Composable
internal fun HubUpcomingEventsSection(
    liquidGlass: Boolean,
    tint: Color,
    backdrop: GlassBackdrop?,
    onOpenCalendar: () -> Unit,
    maxHeight: Dp = 176.dp,
    /** An event was tapped: the Calendar opens on its day (yyyymmdd). */
    onOpenDay: (Int) -> Unit = { onOpenCalendar() }
) {
    val tap = rememberHapticTap()
    val all = LocalData.calendarEvents
    val today = remember(all) { CalendarMath.todayKey() }
    val showMajor = LocalData.majorHolidays
    val events = remember(all, today, showMajor) { LocalData.upcomingAgenda(60) }
    val label = lerp(tint, Color.White, 0.35f)
    val accent = vividAccent(tint)
    Spacer(Modifier.height(14.dp))
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        HorizontalDivider(modifier = Modifier.weight(1f), color = tint.copy(alpha = 0.6f))
        Text(
            "Upcoming Events", color = label, fontSize = 12.sp, lineHeight = 12.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 10.dp)
        )
        HorizontalDivider(modifier = Modifier.weight(1f), color = tint.copy(alpha = 0.6f))
    }
    Spacer(Modifier.height(8.dp))
    val shape = RoundedCornerShape(18.dp)
    val body: @Composable () -> Unit = {
        if (events.isEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.CalendarMonth, contentDescription = null, tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text("Nothing coming up. Tap to add an event.", color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
            }
        } else {
            LazyColumn(
                Modifier.fillMaxWidth().heightIn(max = maxHeight).padding(vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(events, key = { it.id }) { ev ->
                    val isToday = ev.day == today
                    Row(
                        Modifier.fillMaxWidth().clickable { tap(); onOpenDay(ev.day) }.padding(horizontal = 12.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // The date, as a small tile.
                        Column(
                            Modifier.width(58.dp).clip(RoundedCornerShape(10.dp))
                                .background(if (isToday) accent.copy(alpha = 0.5f) else Color.Black.copy(alpha = 0.25f))
                                .padding(vertical = 5.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                ev.dayLabel(today), color = Color.White, fontSize = 11.sp, lineHeight = 13.sp,
                                fontWeight = FontWeight.Bold, maxLines = 1
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                ev.title, color = Color.White, fontSize = 14.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                            Text(ev.timeLabel(), color = label.copy(alpha = 0.9f), fontSize = 11.sp, lineHeight = 13.sp, maxLines = 1)
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            ev.countdownLabel(today), color = if (isToday) Color.White else label.copy(alpha = 0.9f),
                            fontSize = 11.sp, lineHeight = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1
                        )
                    }
                }
            }
        }
    }
    val m = Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(shape).clickable { tap(); onOpenCalendar() }
    if (liquidGlass) LiquidGlassSurface(m, shape = shape, tint = tint, backdrop = backdrop) { body() }
    else Box(m.background(Color.White.copy(alpha = 0.07f)).border(1.dp, Color.White.copy(alpha = 0.1f), shape)) { body() }
}
