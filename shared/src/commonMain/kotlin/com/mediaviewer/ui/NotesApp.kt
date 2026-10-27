package com.mediaviewer.ui

import androidx.compose.foundation.background
import com.mediaviewer.platform.ioDispatcher
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import com.mediaviewer.ui.compat.horizontalScroll
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import com.mediaviewer.ui.compat.navBarSpace
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import com.mediaviewer.ui.theme.CircleShape
import com.mediaviewer.ui.theme.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Icon
import com.mediaviewer.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.mediaviewer.platform.LocalPlatform
import com.mediaviewer.ui.compat.ActivityResultContracts
import com.mediaviewer.ui.compat.BackHandler
import com.mediaviewer.ui.compat.LocalContext
import com.mediaviewer.ui.compat.PickVisualMediaRequest
import com.mediaviewer.ui.compat.rememberLauncherForActivityResult
import com.mediaviewer.util.DateText
import com.mediaviewer.util.LocalData
import com.mediaviewer.util.NoteEntry
import com.mediaviewer.util.rememberHapticTap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.material.icons.filled.Check
import androidx.compose.ui.text.style.TextAlign

private val noteImageLine = Regex("""^!\[[^\]]*\]\(([^)]+)\)\s*$""")

/** "- [ ] task" / "- [x] done" (also with *), after any indent. */
private val checklistLine = Regex("""^(\s*)[-*] \[([ xX])\] ?(.*)$""")
/** "1. first" */
private val numberedLine = Regex("""^(\s*)(\d+)\. (.*)$""")
/** What a list row starts with: a checkbox, a bullet or a number. */
private val listPrefix = Regex("""^(\s*)(?:([-*]) \[[ xX]\] |([-*]) |(\d+)\. )""")

/**
 * Return pressed on a list row: the new row starts with the same marker
 * ("- [ ] ", "- ", the next number), so a list can be typed straight
 * through. Return on a row that has nothing but its marker ends the list
 * instead (the marker is removed). Anything else is passed on untouched.
 */
internal fun continueMarkdownList(old: TextFieldValue, new: TextFieldValue): TextFieldValue {
    if (new.text.length != old.text.length + 1 || !new.selection.collapsed) return new
    val caret = new.selection.start
    if (caret <= 0 || caret > new.text.length || new.text[caret - 1] != '\n') return new
    // Exactly one new line was typed, right before the caret.
    if (new.text.removeRange(caret - 1, caret) != old.text) return new
    val lineStart = if (caret >= 2) new.text.lastIndexOf('\n', caret - 2) + 1 else 0
    val line = new.text.substring(lineStart, caret - 1)
    val m = listPrefix.find(line) ?: return new
    if (line.substring(m.value.length).isBlank()) {
        val text = new.text.removeRange(lineStart, caret)
        return TextFieldValue(text, TextRange(lineStart))
    }
    val indent = m.groupValues[1]
    val next = when {
        m.groupValues[2].isNotEmpty() -> indent + m.groupValues[2] + " [ ] "
        m.groupValues[3].isNotEmpty() -> indent + m.groupValues[3] + " "
        else -> indent + ((m.groupValues[4].toIntOrNull() ?: 0) + 1) + ". "
    }
    val text = new.text.substring(0, caret) + next + new.text.substring(caret)
    return TextFieldValue(text, TextRange(caret + next.length))
}

/** [body] with the checklist row on line [lineIndex] ticked or unticked. */
internal fun toggleChecklistLine(body: String, lineIndex: Int): String {
    val lines = body.split('\n').toMutableList()
    val line = lines.getOrNull(lineIndex) ?: return body
    val m = checklistLine.find(line) ?: return body
    val open = line.indexOf('[', m.groupValues[1].length)
    if (open < 0 || open + 1 >= line.length) return body
    val done = line[open + 1] != ' '
    lines[lineIndex] = line.substring(0, open + 1) + (if (done) ' ' else 'x') + line.substring(open + 2)
    return lines.joinToString("\n")
}

/** Markdown as plain text (for previews): markers and list prefixes gone. */
internal fun markdownPlain(text: String): String = text.lineSequence().joinToString("\n") { raw ->
    var line = raw.trim()
    checklistLine.find(raw)?.let { line = it.groupValues[3] }
    line = line.replace(Regex("""^#{1,6}\s+"""), "").replace(Regex("""^([-*>]|\d+\.)\s+"""), "")
    if (noteImageLine.matches(line)) "" else markdownInline(line).text
}

/** True when [text] uses markdown that's worth rendering. */
internal fun looksLikeMarkdown(text: String): Boolean =
    text.contains("**") || text.contains("~~") || text.contains('`') ||
        text.lineSequence().any { l ->
            val t = l.trimStart()
            checklistLine.containsMatchIn(l) || numberedLine.containsMatchIn(l) ||
                t.startsWith("# ") || t.startsWith("## ") || t.startsWith("### ") ||
                t.startsWith("- ") || t.startsWith("* ") || t.startsWith("> ") ||
                Regex("""(^|\s)[*_][^*_\s][^*_]*[*_](\s|$|[.,!?])""").containsMatchIn(l)
        }

/** Inline markdown: **bold**, *italic* / _italic_, `code`, ~~strike~~. */
internal fun markdownInline(text: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    fun closing(token: String, from: Int): Int = text.indexOf(token, from).let { if (it > from) it else -1 }
    while (i < text.length) {
        val rest = text.length - i
        when {
            rest >= 4 && text.startsWith("**", i) && closing("**", i + 2) > 0 -> {
                val end = closing("**", i + 2)
                pushStyle(SpanStyle(fontWeight = FontWeight.Bold)); append(markdownInline(text.substring(i + 2, end))); pop()
                i = end + 2
            }
            rest >= 4 && text.startsWith("~~", i) && closing("~~", i + 2) > 0 -> {
                val end = closing("~~", i + 2)
                pushStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)); append(markdownInline(text.substring(i + 2, end))); pop()
                i = end + 2
            }
            text[i] == '`' && closing("`", i + 1) > 0 -> {
                val end = closing("`", i + 1)
                pushStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = Color.White.copy(alpha = 0.12f)))
                append(text.substring(i + 1, end)); pop()
                i = end + 1
            }
            (text[i] == '*' || text[i] == '_') && closing(text[i].toString(), i + 1) > 0 -> {
                val end = closing(text[i].toString(), i + 1)
                pushStyle(SpanStyle(fontStyle = FontStyle.Italic)); append(markdownInline(text.substring(i + 1, end))); pop()
                i = end + 1
            }
            else -> { append(text[i]); i++ }
        }
    }
}

/** A note, rendered: headings, lists, quotes, inline styles and pictures. */
@Composable
internal fun MarkdownView(
    body: String, tint: Color, modifier: Modifier = Modifier,
    /** A checklist row was tapped (its line number in [body]). */
    onToggleCheck: ((lineIndex: Int) -> Unit)? = null,
    /** Where each line of [body] starts, down the page (in pixels). */
    onLineTop: ((lineIndex: Int, y: Int) -> Unit)? = null
) {
    val tap = rememberHapticTap()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        body.split('\n').forEachIndexed { lineIndex, raw ->
            Box(Modifier.fillMaxWidth().then(if (onLineTop != null) Modifier.onPlaced { onLineTop(lineIndex, it.positionInParent().y.toInt()) } else Modifier)) {
            val line = raw.trimEnd()
            val image = noteImageLine.find(line.trim())
            val check = checklistLine.find(line)
            val numbered = numberedLine.find(line)
            when {
                check != null -> {
                    val done = check.groupValues[2] != " "
                    val accent = lerp(tint, Color.White, 0.5f)
                    Row(
                        Modifier.fillMaxWidth().padding(start = (check.groupValues[1].length * 6).dp)
                            .clip(RoundedCornerShape(8.dp))
                            .then(if (onToggleCheck != null) Modifier.clickable { tap(); onToggleCheck(lineIndex) } else Modifier)
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Box(
                            Modifier.padding(start = 2.dp, end = 9.dp, top = 2.dp).size(17.dp).clip(RoundedCornerShape(5.dp))
                                .background(if (done) accent.copy(alpha = 0.9f) else Color.Transparent)
                                .border(1.5.dp, accent.copy(alpha = if (done) 0.9f else 0.7f), RoundedCornerShape(5.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            if (done) Icon(Icons.Default.Check, contentDescription = "Done", tint = Color.Black.copy(alpha = 0.8f), modifier = Modifier.size(13.dp))
                        }
                        Text(
                            markdownInline(check.groupValues[3]),
                            color = Color.White.copy(alpha = if (done) 0.5f else 0.95f), fontSize = 15.sp, lineHeight = 21.sp,
                            textDecoration = if (done) TextDecoration.LineThrough else null
                        )
                    }
                }
                numbered != null -> Row(Modifier.padding(start = (numbered.groupValues[1].length * 6).dp)) {
                    Text(numbered.groupValues[2] + ".", color = lerp(tint, Color.White, 0.5f), fontSize = 15.sp, lineHeight = 21.sp, modifier = Modifier.padding(start = 4.dp, end = 8.dp))
                    Text(markdownInline(numbered.groupValues[3]), color = Color.White.copy(alpha = 0.95f), fontSize = 15.sp, lineHeight = 21.sp)
                }
                image != null -> AsyncImage(
                    model = image.groupValues[1].let { if (it.startsWith("http")) it else LocalPlatform.parseUri(it) },
                    contentDescription = null, contentScale = ContentScale.FillWidth,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(14.dp))
                )
                line.isBlank() -> Spacer(Modifier.height(6.dp))
                line.startsWith("### ") -> Text(markdownInline(line.drop(4)), color = Color.White, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
                line.startsWith("## ") -> Text(markdownInline(line.drop(3)), color = Color.White, fontSize = 19.sp, lineHeight = 25.sp, fontWeight = FontWeight.Bold)
                line.startsWith("# ") -> Text(markdownInline(line.drop(2)), color = Color.White, fontSize = 23.sp, lineHeight = 29.sp, fontWeight = FontWeight.Bold)
                line.startsWith("- ") || line.startsWith("* ") -> Row {
                    Text("•", color = lerp(tint, Color.White, 0.5f), fontSize = 15.sp, lineHeight = 21.sp, modifier = Modifier.padding(start = 4.dp, end = 8.dp))
                    Text(markdownInline(line.drop(2)), color = Color.White.copy(alpha = 0.95f), fontSize = 15.sp, lineHeight = 21.sp)
                }
                line.startsWith("> ") -> Row(Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
                    Box(Modifier.width(3.dp).fillMaxSize().background(lerp(tint, Color.White, 0.4f), RoundedCornerShape(2.dp)))
                    Text(
                        markdownInline(line.drop(2)), color = Color.White.copy(alpha = 0.8f), fontSize = 15.sp, lineHeight = 21.sp,
                        fontStyle = FontStyle.Italic, modifier = Modifier.padding(start = 10.dp)
                    )
                }
                else -> Text(markdownInline(line), color = Color.White.copy(alpha = 0.95f), fontSize = 15.sp, lineHeight = 21.sp)
            }
            }
        }
    }
}

/** Launchpad → Notes: folders along the top, your notes under them, and an
 *  editor with markdown formatting and pictures. Everything stays on this
 *  device. */
@Composable
fun NotesPage(tint: Color, liquidGlass: Boolean, onClose: () -> Unit) {
    var open by remember { mutableStateOf<NoteEntry?>(null) }
    val appContext = LocalContext.current
    // (Starts on the folder you were last on, on this device.)
    var folder by remember { mutableStateOf(com.mediaviewer.util.LastFolders.notes(appContext)) }
    val knownFolders = LocalData.noteFolders
    if (folder != null && knownFolders.isNotEmpty() && knownFolders.none { it.id == folder }) folder = null
    LaunchedEffect(folder) { if (folder == null || knownFolders.isNotEmpty()) com.mediaviewer.util.LastFolders.saveNotes(appContext, folder) }
    // Opened from a home-screen widget: straight to that note.
    val wanted = LocalOverlays.openNoteId
    LaunchedEffect(wanted) {
        if (wanted != null) {
            LocalData.notes.firstOrNull { it.id == wanted }?.let { open = it }
            LocalOverlays.openNoteId = null
        }
    }
    val current = open
    if (current != null) {
        NoteEditor(current, tint, liquidGlass, onDone = { open = null })
    } else {
        NotesList(folder, { folder = it }, tint, liquidGlass, onOpen = { open = it }, onClose = onClose)
    }
}

@Composable
private fun NotesList(
    folder: String?, onFolder: (String?) -> Unit,
    tint: Color, liquidGlass: Boolean,
    onOpen: (NoteEntry) -> Unit, onClose: () -> Unit
) {
    val tap = rememberHapticTap()
    val folders = LocalData.noteFolders
    val notes = LocalData.notes
    val shown = remember(notes, folder) { if (folder == null) notes else notes.filter { it.folderId == folder } }
    var newFolder by remember { mutableStateOf(false) }
    var folderName by remember { mutableStateOf("") }
    val context = LocalContext.current

    LaunchAppPage(
        "Notes", tint, liquidGlass, onClose,
        actions = { AppBubble(Icons.Default.Add, "New note", liquidGlass, tint, { onOpen(NoteEntry(folderId = folder)) }) }
    ) {
        // Folders: tap to filter; hold one to delete it (its notes are kept).
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LocalChip("All Notes", folder == null, tint, { onFolder(null) })
            folders.forEach { f ->
                val armKey = "notefolder:" + f.id
                val confirming = ReallyPrompt.isArmed(armKey)
                LocalChip(
                    if (confirming) "Really?" else f.name, folder == f.id, tint,
                    onClick = {
                        if (confirming) { ReallyPrompt.disarm(); if (folder == f.id) onFolder(null); LocalData.deleteNoteFolder(f.id) }
                        else onFolder(f.id)
                    },
                    onLongClick = { ReallyPrompt.arm(armKey) },
                    modifier = Modifier.reallyTarget(armKey)
                )
            }
            LocalChip("+ Folder", false, tint, { newFolder = !newFolder })
        }
        if (newFolder) {
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                LocalTextField(folderName, { folderName = it.take(40) }, "Folder name", tint, Modifier.weight(1f), capitalization = KeyboardCapitalization.Words)
                Spacer(Modifier.width(8.dp))
                LocalPillButton(
                    "Create", liquidGlass, tint,
                    { val made = LocalData.createNoteFolder(folderName); folderName = ""; newFolder = false; onFolder(made.id) },
                    enabled = folderName.isNotBlank(), height = 44.dp
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        if (shown.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text("No notes here yet. Tap + to write one.", color = Color.White.copy(alpha = 0.5f), fontSize = 14.sp)
            }
        } else {
            LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(shown, key = { it.id }) { note ->
                    val shape = RoundedCornerShape(18.dp)
                    val armKey = "note:" + note.id
                    val confirming = ReallyPrompt.isArmed(armKey)
                    val row: @Composable () -> Unit = {
                        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    note.title.ifBlank { note.body.lineSequence().firstOrNull { it.isNotBlank() }?.take(60) ?: "Untitled" },
                                    color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                                val snippet = note.body.lineSequence().filter { it.isNotBlank() && !it.trim().startsWith("![") }
                                    .joinToString(" ").take(120)
                                if (snippet.isNotBlank()) Text(
                                    snippet, color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, lineHeight = 16.sp,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    DateText.format(note.updatedAt, "MMM d, h:mm a") +
                                        (folders.firstOrNull { it.id == note.folderId }?.let { " · " + it.name } ?: ""),
                                    color = lerp(tint, Color.White, 0.5f).copy(alpha = 0.8f), fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp)
                                )
                            }
                            Box(
                                Modifier.reallyTarget(armKey).clip(RoundedCornerShape(11.dp))
                                    .background(if (confirming) Color.White.copy(alpha = 0.12f) else Color.Transparent)
                                    .clickable {
                                        tap()
                                        ReallyPrompt.tap(armKey) {
                                            note.body.lines().mapNotNull { noteImageLine.find(it.trim())?.groupValues?.get(1) }
                                                .forEach { LocalPlatform.deleteMedia(context, it) }
                                            LocalData.deleteNote(note.id)
                                        }
                                    }
                                    .padding(horizontal = 9.dp, vertical = 7.dp)
                            ) {
                                if (confirming) Text("Really?", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                else Icon(Icons.Default.Close, contentDescription = "Delete note", tint = Color.White.copy(alpha = 0.5f), modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                    val m = Modifier.fillMaxWidth().clip(shape).clickable { tap(); onOpen(note) }
                    if (liquidGlass) LiquidGlassSurface(m, shape = shape, tint = tint) { row() }
                    else Box(m.background(Color.White.copy(alpha = 0.07f)).border(1.dp, Color.White.copy(alpha = 0.1f), shape)) { row() }
                }
            }
        }
    }
}

@Composable
private fun NoteEditor(initial: NoteEntry, tint: Color, liquidGlass: Boolean, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tap = rememberHapticTap()
    var id by remember { mutableStateOf(initial.id) }
    var title by remember { mutableStateOf(initial.title) }
    // (The caret starts at the top: one at the end made the page scroll
    // to the bottom when writing began, and the first press and hold
    // jumped there and picked everything.)
    var body by remember { mutableStateOf(TextFieldValue(initial.body, TextRange(0))) }
    // One scroll for reading and writing, so switching keeps your place.
    val bodyScroll = rememberScrollState()
    var folderId by remember { mutableStateOf(initial.folderId) }
    // A new note opens for writing; an existing one opens as it reads.
    var preview by remember { mutableStateOf(initial.id.isNotBlank() && initial.body.isNotBlank()) }
    val folders = LocalData.noteFolders

    // Switching between writing and reading keeps the same line at the top
    // (the two are laid out a little differently, so the same scroll amount
    // would land elsewhere): where each line starts in both, in pixels.
    var editLayout by remember { mutableStateOf<androidx.compose.ui.text.TextLayoutResult?>(null) }
    val viewLineTops = remember { HashMap<Int, Int>() }
    var anchorLine by remember { mutableStateOf<Int?>(null) }
    fun lineOfOffset(offset: Int): Int {
        val t = body.text
        var n = 0
        for (i in 0 until offset.coerceIn(0, t.length)) if (t[i] == '\n') n++
        return n
    }
    fun topLineNow(): Int {
        val y = bodyScroll.value
        return if (preview) viewLineTops.entries.filter { it.value <= y + 2 }.maxByOrNull { it.value }?.key ?: 0
        else editLayout?.let { l -> lineOfOffset(l.getLineStart(l.getLineForVerticalPosition(y.toFloat()))) } ?: 0
    }
    fun switchMode() { anchorLine = topLineNow(); preview = !preview }
    LaunchedEffect(preview) {
        val line = anchorLine ?: return@LaunchedEffect
        anchorLine = null
        // (Once the other view has been laid out.)
        androidx.compose.runtime.withFrameNanos { }
        androidx.compose.runtime.withFrameNanos { }
        val y = if (preview) {
            viewLineTops.entries.filter { it.key <= line }.maxByOrNull { it.key }?.value ?: 0
        } else {
            val l = editLayout ?: return@LaunchedEffect
            var start = 0
            repeat(line) { start = body.text.indexOf('\n', start).let { i -> if (i < 0) body.text.length else i + 1 } }
            l.getLineTop(l.getLineForOffset(start.coerceIn(0, body.text.length))).toInt()
        }
        bodyScroll.scrollTo(if (line == 0) 0 else y)
    }

    // The keyboard: the page doesn't shrink for it (that moved the words
    // under a finger the moment it came up, mid-press, so the first
    // selection jumped and missed). The note just gets room to scroll past
    // it, and the formatting row rides up on top of it.
    val density = androidx.compose.ui.platform.LocalDensity.current
    val keyboardLift = (WindowInsets.ime.getBottom(density) -
        WindowInsets.navBarSpace.getBottom(density)).coerceAtLeast(0)
    // The press that starts writing (or picks a word) mustn't move the note.
    // A field scrolls to its caret as it takes focus — and the caret used to
    // sit wherever it was left (the very top, for a note just opened), so
    // the note jumped up mid-press and the press picked the wrong words, or
    // all of them. While the note isn't being written in, its caret is kept
    // on the first line showing, so taking focus never moves anything.
    var bodyFocused by remember { mutableStateOf(false) }
    val padTopPx = with(density) { 12.dp.roundToPx() }
    LaunchedEffect(preview) {
        if (preview) return@LaunchedEffect
        androidx.compose.runtime.snapshotFlow { Triple(bodyScroll.value, bodyFocused, editLayout) }.collect { (y, focused, l) ->
            if (focused || l == null) return@collect
            val at = l.getOffsetForPosition(androidx.compose.ui.geometry.Offset(0f, (y - padTopPx).coerceAtLeast(0).toFloat() + 4f))
            if (body.selection != TextRange(at)) body = body.copy(selection = TextRange(at))
        }
    }
    // Typing: the line being written stays in view — above the keyboard
    // and the formatting row riding on it, or the page's bottom — however
    // the new line came (Return, wrapping, pasting). Only as the words
    // change, never for a press or a selection.
    var panelHeight by remember { mutableStateOf(0) }
    var panelTop by remember { mutableStateOf(0) }
    var rootHeight by remember { mutableStateOf(0) }
    val formatRowPx = with(density) { 46.dp.roundToPx() }
    val imeBottom = WindowInsets.ime.getBottom(density)
    LaunchedEffect(body.text) {
        if (preview || !bodyFocused) return@LaunchedEffect
        // (Once the new text has been laid out.)
        androidx.compose.runtime.withFrameNanos { }
        val l = editLayout ?: return@LaunchedEffect
        val caret = body.selection.end.coerceIn(0, body.text.length)
        val margin = with(density) { 24.dp.roundToPx() }
        val rect = l.getCursorRect(caret)
        val caretBottom = rect.bottom.toInt() + padTopPx
        val caretTop = rect.top.toInt() + padTopPx
        val shown = if (keyboardLift > 0) minOf(panelHeight, rootHeight - imeBottom - panelTop - formatRowPx)
            else panelHeight
        if (shown <= 0) return@LaunchedEffect
        if (caretBottom + margin > bodyScroll.value + shown) bodyScroll.scrollTo(caretBottom + margin - shown)
        else if (caretTop < bodyScroll.value) bodyScroll.scrollTo((caretTop - margin).coerceAtLeast(0))
    }

    fun save() {
        if (id.isBlank() && title.isBlank() && body.text.isBlank()) return
        id = LocalData.saveNote(NoteEntry(id = id, folderId = folderId, title = title.trim(), body = body.text)).id
    }
    // Saved shortly after every change, and again on the way out — a note
    // can't be lost by leaving the page.
    LaunchedEffect(title, body.text, folderId) {
        if (title == initial.title && body.text == initial.body && folderId == initial.folderId && id == initial.id) return@LaunchedEffect
        delay(700)
        save()
    }
    val latestSave by rememberUpdatedState(::save)
    DisposableEffect(Unit) { onDispose { latestSave() } }

    fun wrap(token: String) {
        val t = body.text
        val a = body.selection.min.coerceIn(0, t.length)
        val b = body.selection.max.coerceIn(a, t.length)
        val next = t.substring(0, a) + token + t.substring(a, b) + token + t.substring(b)
        body = TextFieldValue(next, if (a == b) TextRange(a + token.length) else TextRange(a + token.length, b + token.length))
    }
    fun prefixLine(prefix: String) {
        val t = body.text
        val caret = body.selection.min.coerceIn(0, t.length)
        val lineStart = t.lastIndexOf('\n', (caret - 1).coerceAtLeast(0)).let { if (caret == 0) 0 else it + 1 }
        val has = t.startsWith(prefix, lineStart)
        val next = if (has) t.removeRange(lineStart, lineStart + prefix.length) else t.substring(0, lineStart) + prefix + t.substring(lineStart)
        val shift = if (has) -prefix.length else prefix.length
        body = TextFieldValue(next, TextRange((caret + shift).coerceIn(0, next.length)))
    }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val kept = withContext(ioDispatcher) { LocalPlatform.importMedia(context, uri, "notes")?.toString() } ?: return@launch
            val t = body.text
            val caret = body.selection.max.coerceIn(0, t.length)
            val insert = (if (caret > 0 && t[caret - 1] != '\n') "\n" else "") + "![]($kept)\n"
            body = TextFieldValue(t.substring(0, caret) + insert + t.substring(caret), TextRange(caret + insert.length))
        }
    }

    BackHandler(onBack = onDone)
    LaunchAppPage(
        "Note", tint, liquidGlass, onDone,
        actions = {
            AppBubble(
                if (preview) Icons.Default.Edit else Icons.Default.Visibility, if (preview) "Edit" else "Preview",
                liquidGlass, tint, { switchMode() }
            )
        },
        resizeForKeyboard = false,
        // The page's title is the note's own title: tap it to rename.
        titleContent = {
            val themeFamily = androidx.compose.material3.LocalTextStyle.current.fontFamily
            val fieldStyle = TextStyle(color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center).let { if (it.fontFamily == null) it.copy(fontFamily = themeFamily) else it }
            BasicTextField(
                value = title, onValueChange = { title = it.replace("\n", " ").take(120) },
                singleLine = true,
                textStyle = fieldStyle,
                cursorBrush = SolidColor(Color.White),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.Center) {
                        if (title.isEmpty()) Text("Note", color = Color.White.copy(alpha = 0.45f), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        inner()
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
    ) {
        // The note's folder: tap to move it to the next one.
        Row(Modifier.padding(top = 6.dp, bottom = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LocalChip("No folder", folderId == null, tint, { folderId = null })
            folders.forEach { f -> LocalChip(f.name, folderId == f.id, tint, { folderId = f.id }) }
        }
        val shape = RoundedCornerShape(20.dp)
        val panel: @Composable () -> Unit = {
            if (preview) {
                MarkdownView(
                    body.text.ifBlank { "*Nothing written yet.*" }, tint,
                    Modifier.fillMaxSize().verticalScroll(bodyScroll).padding(horizontal = 14.dp, vertical = 12.dp),
                    // Checklists tick straight from the reading view.
                    onToggleCheck = { line ->
                        val next = toggleChecklistLine(body.text, line)
                        if (next != body.text) body = TextFieldValue(next, body.selection.let { TextRange(it.start.coerceAtMost(next.length), it.end.coerceAtMost(next.length)) })
                    },
                    onLineTop = { line, y -> viewLineTops[line] = y }
                )
            } else {
                // Written in the reading view's own letters: the same size and
                // color, headings, bold and italics shown as they read (the
                // marks themselves dimmed); only pictures and checkboxes stay
                // as text while writing.
                val styled = remember(tint) { markdownEditTransformation(tint) }
                // (The field is inside the scrolling box rather than scrolling
                // itself, at least as tall as the panel, so a press anywhere
                // below the words still writes.)
                val minHeight = with(density) { panelHeight.toDp() }
                Box(Modifier.fillMaxSize().verticalScroll(bodyScroll)) {
                BasicTextField(
                    // Return on a list / checklist row carries the marker on.
                    value = body, onValueChange = { body = continueMarkdownList(body, it) },
                    // (The reading view's letters: its font too, not the system's.)
                    textStyle = androidx.compose.material3.LocalTextStyle.current.merge(TextStyle(color = Color.White.copy(alpha = 0.95f), fontSize = 15.sp, lineHeight = 21.sp)),
                    onTextLayout = { editLayout = it },
                    visualTransformation = styled,
                    cursorBrush = SolidColor(Color.White),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    decorationBox = { inner ->
                        Box {
                            if (body.text.isEmpty()) Text("Start writing… (markdown works: **bold**, *italic*, # heading, - list, - [ ] checklist)", color = Color.White.copy(alpha = 0.35f), fontSize = 15.sp, lineHeight = 21.sp)
                            inner()
                        }
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = minHeight)
                        .onFocusChanged { f -> bodyFocused = f.isFocused }
                        .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 12.dp + with(density) { keyboardLift.toDp() } + if (keyboardLift > 0) 48.dp else 0.dp)
                )
                }
            }
        }
        val m = Modifier.fillMaxWidth().weight(1f).onGloballyPositioned { c ->
            panelHeight = c.size.height
            panelTop = c.positionInRoot().y.toInt()
            rootHeight = c.findRootCoordinates().size.height
        }
        if (liquidGlass) LiquidGlassSurface(m, shape = shape, tint = tint, contentAlignment = Alignment.TopStart) { panel() }
        else Box(m.clip(shape).background(Color.White.copy(alpha = 0.06f)).border(1.dp, Color.White.copy(alpha = 0.1f), shape)) { panel() }

        if (!preview) {
            // Formatting, right above the keyboard (in thumb's reach).
            Row(
                Modifier.fillMaxWidth().offset { androidx.compose.ui.unit.IntOffset(0, -keyboardLift) }
                    .padding(top = 8.dp).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                LocalChip("Bold", false, tint, { tap(); wrap("**") }, filled = true)
                LocalChip("Italic", false, tint, { wrap("*") }, filled = true)
                LocalChip("Heading", false, tint, { prefixLine("# ") }, filled = true)
                LocalChip("List", false, tint, { prefixLine("- ") }, filled = true)
                LocalChip("Checklist", false, tint, { prefixLine("- [ ] ") }, filled = true)
                LocalChip("Quote", false, tint, { prefixLine("> ") }, filled = true)
                LocalChip("Code", false, tint, { wrap("`") }, filled = true)
                LocalChip("Image", false, tint, { imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, filled = true)
                // Sends this note to the home-screen Note widget(s) (tap
                // again: a widget showing it goes back to its list of
                // notes). A note can also be chosen on the widget itself.
                val onWidget = id.isNotBlank() && LocalData.widgetNoteId == id
                LocalChip("Widget", onWidget, tint, {
                    save()
                    if (id.isNotBlank()) LocalData.updateWidgetNote(if (onWidget) "" else id)
                }, filled = true)
            }
        }
    }
}


/**
 * The note editor's look: the markdown being written shown as it will
 * read — # headings at their sizes, **bold**, *italic*, `code`, quotes in
 * italics — with the marks themselves dimmed. Only styles: every letter
 * stays where it is, so the caret and selection are untouched.
 */
internal fun markdownEditTransformation(tint: Color): androidx.compose.ui.text.input.VisualTransformation {
    val dim = SpanStyle(color = Color.White.copy(alpha = 0.35f))
    val accent = SpanStyle(color = lerp(tint, Color.White, 0.5f))
    val bold = Regex("\\*\\*(.+?)\\*\\*")
    val italic = Regex("(?<![*\\w])\\*(?!\\*)(.+?)(?<!\\*)\\*(?![*\\w])")
    val code = Regex("`([^`]+)`")
    return androidx.compose.ui.text.input.VisualTransformation { text ->
        val raw = text.text
        val out = buildAnnotatedString {
            append(raw)
            var start = 0
            for (line in raw.split('\n')) {
                val end = start + line.length
                fun mark(len: Int) { if (len > 0) addStyle(dim, start, (start + len).coerceAtMost(end)) }
                when {
                    line.startsWith("### ") -> { addStyle(SpanStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.White), start, end); mark(4) }
                    line.startsWith("## ") -> { addStyle(SpanStyle(fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Color.White), start, end); mark(3) }
                    line.startsWith("# ") -> { addStyle(SpanStyle(fontSize = 23.sp, fontWeight = FontWeight.Bold, color = Color.White), start, end); mark(2) }
                    line.startsWith("> ") -> { addStyle(SpanStyle(fontStyle = FontStyle.Italic, color = Color.White.copy(alpha = 0.8f)), start, end); addStyle(accent, start, start + 1) }
                    line.startsWith("- ") || line.startsWith("* ") -> addStyle(accent, start, start + 1)
                }
                for (m in bold.findAll(line)) {
                    addStyle(SpanStyle(fontWeight = FontWeight.Bold), start + m.range.first, start + m.range.last + 1)
                    addStyle(dim, start + m.range.first, start + m.range.first + 2)
                    addStyle(dim, start + m.range.last - 1, start + m.range.last + 1)
                }
                for (m in italic.findAll(line)) {
                    addStyle(SpanStyle(fontStyle = FontStyle.Italic), start + m.range.first, start + m.range.last + 1)
                    addStyle(dim, start + m.range.first, start + m.range.first + 1)
                    addStyle(dim, start + m.range.last, start + m.range.last + 1)
                }
                for (m in code.findAll(line)) {
                    addStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = Color.White.copy(alpha = 0.08f)), start + m.range.first, start + m.range.last + 1)
                }
                start = end + 1
            }
        }
        androidx.compose.ui.text.input.TransformedText(out, androidx.compose.ui.text.input.OffsetMapping.Identity)
    }
}
