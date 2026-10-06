package com.mediaviewer.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.mediaviewer.platform.IosContext
import com.mediaviewer.platform.MediaBridge
import com.mediaviewer.platform.PlatformUri
import com.mediaviewer.platform.PrivateFiles
import com.mediaviewer.platform.deleteLocalFile
import com.mediaviewer.platform.localFileExists
import com.mediaviewer.platform.randomUuidString
import com.mediaviewer.platform.sharedPreferences
import com.mediaviewer.stream.IosSoundboard
import com.mediaviewer.ui.compat.ActivityResultContracts
import com.mediaviewer.ui.compat.rememberLauncherForActivityResult
import com.mediaviewer.util.rememberHapticTap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.math.roundToInt

/** VRM mode's scenes (Activity → Scene). */
internal enum class VrmStageScene(val label: String, val card: String?) {
    STARTING_SOON("Starting Soon", "Starting Soon"),
    VRM("VRM", null),
    BE_RIGHT_BACK("Be Right Back", "Be Right Back")
}

/** One soundboard button: its name and its file in the app's own storage. */
internal data class VrmSound(val id: String, val name: String, val path: String)

/** The soundboard, remembered on this device (the same "vrm_activity"
 *  settings as Android). */
internal object VrmSoundStore {
    private const val PREFS = "vrm_activity"
    private const val KEY = "sounds"
    private const val FOLDER = "vrm_sounds"

    private fun folderPath(): String = PrivateFiles.rootUri(IosContext).removePrefix("file://") + "/" + FOLDER

    fun load(): List<VrmSound> = runCatching {
        val raw = IosContext.sharedPreferences(PREFS).getString(KEY, null) ?: return emptyList()
        Json.parseToJsonElement(raw).jsonArray.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            // Only the file's name is trusted: the folder the app lives in
            // gets a new address whenever iOS updates or re-installs it.
            val file = o["path"]?.jsonPrimitive?.content?.substringAfterLast('/').orEmpty()
            val path = folderPath() + "/" + file
            if (file.isBlank() || !localFileExists(path)) null
            else VrmSound(o["id"]?.jsonPrimitive?.content.orEmpty(), o["name"]?.jsonPrimitive?.content.orEmpty(), path)
        }
    }.getOrDefault(emptyList())

    fun save(sounds: List<VrmSound>) {
        val array = buildJsonArray {
            sounds.forEach { s -> add(buildJsonObject { put("id", s.id); put("name", s.name); put("path", s.path) }) }
        }
        IosContext.sharedPreferences(PREFS).edit().putString(KEY, array.toString()).apply()
    }

    /** Copies the picked audio file in; null if it couldn't be read. */
    fun import(uri: PlatformUri, name: String): VrmSound? {
        val id = randomUuidString()
        val extension = PrivateFiles.displayName(IosContext, uri)?.substringAfterLast('.', "")
            ?.lowercase()?.filter { it.isLetterOrDigit() }?.take(5).orEmpty()
        val stored = PrivateFiles.copyIn(IosContext, uri, FOLDER, if (extension.isEmpty()) id else "$id.$extension") ?: return null
        return VrmSound(id, name, stored.removePrefix("file://"))
    }

    fun delete(sound: VrmSound) {
        deleteLocalFile(sound.path)
    }
}

internal const val SCENE_FADE_MS = 450
/** How long the longest effect runs (so captures keep following it). */
internal const val EFFECT_MAX_MS = (DM_EFFECT_MAX_SECONDS * 1000).toLong()
/** The capture copy of the stage layer is this many times smaller each
 *  way than the screen: plenty for a card or confetti in a video, and
 *  quick enough to read back twenty times a second. */
private const val STAGE_COPY_SHRINK = 3

/**
 * What VRM mode's Activity popup controls, drawn over the avatar and under
 * the buttons: the "Starting Soon" / "Be Right Back" scene cards (faded in
 * and out, gone entirely on the VRM scene) and the effect animations.
 *
 * Everything here is also recorded into [layer] (full size, for photos)
 * and [small] (reduced, for recordings and the stream) — that's how it
 * gets into captures.
 */
@Composable
internal fun VrmStageLayer(
    scene: VrmStageScene,
    effect: DmEffect?,
    effectKey: Int,
    layer: GraphicsLayer,
    small: GraphicsLayer,
    modifier: Modifier = Modifier,
    /** Your profile colours, for the effects that wear them. */
    effectColors: List<Color> = emptyList()
) {
    Box(
        modifier.fillMaxSize().drawWithContent {
            layer.record { this@drawWithContent.drawContent() }
            val w = (size.width / STAGE_COPY_SHRINK).toInt().coerceAtLeast(1)
            val h = (size.height / STAGE_COPY_SHRINK).toInt().coerceAtLeast(1)
            small.record(size = IntSize(w, h)) {
                scale(1f / STAGE_COPY_SHRINK, 1f / STAGE_COPY_SHRINK, pivot = Offset.Zero) { this@drawWithContent.drawContent() }
            }
            drawLayer(layer)
        }
    ) {
        for (s in VrmStageScene.entries) {
            val text = s.card ?: continue
            AnimatedVisibility(
                visible = scene == s,
                enter = fadeIn(tween(SCENE_FADE_MS)),
                exit = fadeOut(tween(SCENE_FADE_MS))
            ) { StellarSceneCard(text) }
        }
        if (effect != null && effectKey > 0) DmEffectLayer(effect, effectKey, Modifier.fillMaxSize(), colors = effectColors)
    }
}

/**
 * The Activity popup: Scene, Soundboard and Effects, all on show at once.
 * The soundboard and the effects each scroll on their own.
 */
@Composable
internal fun VrmActivityDialog(
    tint: Color,
    scene: VrmStageScene,
    onScene: (VrmStageScene) -> Unit,
    onEffect: (DmEffect) -> Unit,
    onDismiss: () -> Unit
) {
    val tap = rememberHapticTap()
    var sounds by remember { mutableStateOf(VrmSoundStore.load()) }
    fun update(next: List<VrmSound>) { sounds = next; VrmSoundStore.save(next) }

    // Naming a new sound (pending) or renaming one (renaming).
    var pending by remember { mutableStateOf<PlatformUri?>(null) }
    var renaming by remember { mutableStateOf<VrmSound?>(null) }
    var nameDraft by remember { mutableStateOf("") }
    var importFailed by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            nameDraft = MediaBridge.pathOf(uri).substringAfterLast('/').substringBeforeLast('.').take(24)
            importFailed = false
            pending = uri
        }
    }

    var dragging by remember { mutableStateOf(false) }
    var renameBounds by remember { mutableStateOf(Rect.Zero) }
    var deleteBounds by remember { mutableStateOf(Rect.Zero) }
    var overRename by remember { mutableStateOf(false) }
    var overDelete by remember { mutableStateOf(false) }

    VrmScrim({ tap(); onDismiss() }, alpha = 0.3f) {
        VrmPanel(tint, Modifier.padding(horizontal = 16.dp).widthIn(max = 440.dp).fillMaxWidth()) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 16.dp)) {
                VrmPopupHeader("Activity", tint = tint, onClose = onDismiss)

                ActivityHeading("Scene")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    VrmStageScene.entries.forEach { s ->
                        ActivityButton(label = s.label, tint = tint, selected = s == scene, modifier = Modifier.weight(1f)) { tap(); onScene(s) }
                    }
                }

                // While a sound is being dragged, its heading makes way for
                // the two places it can be dropped.
                if (dragging) {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 6.dp).height(30.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        DropTarget("Rename", Icons.Default.Edit, Color.White, overRename, Modifier.weight(1f).onGloballyPositioned { renameBounds = it.boundsInRoot() })
                        DropTarget("Delete", Icons.Default.Delete, Color(0xFFFF6B61), overDelete, Modifier.weight(1f).onGloballyPositioned { deleteBounds = it.boundsInRoot() })
                    }
                } else {
                    ActivityHeading("Soundboard")
                }
                Box(Modifier.fillMaxWidth().heightIn(max = 142.dp).verticalScroll(rememberScrollState())) {
                    SoundGrid(
                        sounds = sounds,
                        tint = tint,
                        onPlay = { tap(); IosSoundboard.play(it.path) },
                        onAdd = { tap(); picker.launch(arrayOf("audio/*")) },
                        onMove = { from, to ->
                            if (from in sounds.indices && to in sounds.indices && from != to) {
                                update(sounds.toMutableList().apply { add(to, removeAt(from)) })
                            }
                        },
                        onDragState = { active, center ->
                            dragging = active
                            overRename = active && center != null && renameBounds.contains(center)
                            overDelete = active && center != null && deleteBounds.contains(center)
                        },
                        onDrop = { sound, center ->
                            when {
                                renameBounds.contains(center) -> { nameDraft = sound.name; renaming = sound }
                                deleteBounds.contains(center) -> {
                                    VrmSoundStore.delete(sound)
                                    update(sounds.filterNot { it.id == sound.id })
                                }
                            }
                        }
                    )
                }

                ActivityHeading("Effects")
                Box(Modifier.fillMaxWidth().heightIn(max = 136.dp).verticalScroll(rememberScrollState())) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        DmEffect.entries.chunked(3).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                row.forEach { effect ->
                                    ActivityButton(label = effect.label, tint = tint, modifier = Modifier.weight(1f)) { tap(); onEffect(effect) }
                                }
                                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }
            }
        }

        if (pending != null || renaming != null) {
            fun close() { pending = null; renaming = null }
            VrmScrim({ close() }) {
                VrmPanel(tint, Modifier.padding(horizontal = 40.dp).widthIn(max = 340.dp).fillMaxWidth()) {
                    Column(Modifier.padding(18.dp)) {
                        Text(if (renaming != null) "Rename Sound" else "Add Sound", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(12.dp))
                        VrmTextField(value = nameDraft, onValue = { nameDraft = it.take(24) }, placeholder = "Name", tint = tint)
                        if (importFailed) {
                            Text("Couldn't read that file", color = Color(0xFFFF6B61), fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                        }
                        Spacer(Modifier.height(14.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(
                                Modifier.weight(1f).height(42.dp).clip(RoundedCornerShape(12.dp))
                                    .border(1.dp, tint.copy(alpha = 0.6f), RoundedCornerShape(12.dp)).clickable { tap(); close() },
                                contentAlignment = Alignment.Center
                            ) { Text("Cancel", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
                            val ready = nameDraft.isNotBlank()
                            Box(
                                Modifier.weight(1f).height(42.dp).clip(RoundedCornerShape(12.dp))
                                    .background(lerp(tint, Color.Black, 0.12f).copy(alpha = if (ready) 1f else 0.4f))
                                    .clickable(enabled = ready) {
                                        tap()
                                        val name = nameDraft.trim()
                                        val old = renaming
                                        val uri = pending
                                        if (old != null) {
                                            update(sounds.map { if (it.id == old.id) it.copy(name = name) else it })
                                            close()
                                        } else if (uri != null) {
                                            val added = VrmSoundStore.import(uri, name)
                                            if (added != null) { update(sounds + added); close() } else importFailed = true
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) { Text("Save", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActivityHeading(text: String) {
    Text(
        text, color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 12.dp, bottom = 6.dp).height(30.dp).padding(top = 12.dp)
    )
}

@Composable
private fun DropTarget(label: String, icon: ImageVector, color: Color, active: Boolean, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier.height(30.dp).clip(shape)
            .background(color.copy(alpha = if (active) 0.32f else 0.1f))
            .border(1.dp, color.copy(alpha = if (active) 0.9f else 0.4f), shape),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** One wide button of the popup. */
@Composable
private fun ActivityButton(label: String, tint: Color, modifier: Modifier = Modifier, selected: Boolean = false, onClick: (() -> Unit)?) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier.height(BUTTON_HEIGHT).clip(shape)
            .background(if (selected) lerp(tint, Color.Black, 0.12f) else Color.White.copy(alpha = 0.08f))
            .border(1.dp, (if (selected) lerp(tint, Color.White, 0.4f) else tint).copy(alpha = if (selected) 0.8f else 0.3f), shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

private val BUTTON_HEIGHT = 40.dp
private val GRID_GAP = 8.dp

/**
 * The soundboard: three wide buttons a row, "Add Sound" always last. Tap
 * plays; press and hold picks a sound up — drag it over another to move it
 * there, or onto Rename / Delete above.
 */
@Composable
private fun SoundGrid(
    sounds: List<VrmSound>,
    tint: Color,
    onPlay: (VrmSound) -> Unit,
    onAdd: () -> Unit,
    onMove: (from: Int, to: Int) -> Unit,
    /** Dragging or not, and where the dragged button's centre is (root). */
    onDragState: (active: Boolean, center: Offset?) -> Unit,
    onDrop: (VrmSound, center: Offset) -> Unit
) {
    val density = LocalDensity.current
    val soundsNow by rememberUpdatedState(sounds)
    val onMoveNow by rememberUpdatedState(onMove)
    val onDragStateNow by rememberUpdatedState(onDragState)
    val onDropNow by rememberUpdatedState(onDrop)
    var gridOrigin by remember { mutableStateOf(Offset.Zero) }
    var draggedId by remember { mutableStateOf<String?>(null) }
    // The dragged button's top-left inside the grid, in pixels.
    var dragPosition by remember { mutableStateOf(Offset.Zero) }

    BoxWithConstraints(Modifier.fillMaxWidth().onGloballyPositioned { gridOrigin = it.positionInRoot() }) {
        val gapPx = with(density) { GRID_GAP.toPx() }
        val cellW = (constraints.maxWidth - gapPx * 2f) / 3f
        val cellH = with(density) { BUTTON_HEIGHT.toPx() }
        val cellWDp = with(density) { cellW.toDp() }
        val cells = sounds.size + 1
        val rows = (cells + 2) / 3
        fun slot(index: Int) = Offset((index % 3) * (cellW + gapPx), (index / 3) * (cellH + gapPx))

        Box(Modifier.fillMaxWidth().height(BUTTON_HEIGHT * rows + GRID_GAP * (rows - 1))) {
            sounds.forEachIndexed { index, sound ->
                key(sound.id) {
                    val held = draggedId == sound.id
                    val at = if (held) dragPosition else slot(index)
                    ActivityButton(
                        label = sound.name, tint = tint, selected = held, onClick = null,
                        modifier = Modifier
                            .zIndex(if (held) 1f else 0f)
                            .offset { IntOffset(at.x.roundToInt(), at.y.roundToInt()) }
                            .width(cellWDp)
                            .graphicsLayer { if (held) { scaleX = 1.06f; scaleY = 1.06f; alpha = 0.92f } }
                            .pointerInput(sound.id) {
                                fun center() = gridOrigin + dragPosition + Offset(cellW / 2f, cellH / 2f)
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        val i = soundsNow.indexOfFirst { it.id == sound.id }
                                        dragPosition = slot(maxOf(0, i))
                                        draggedId = sound.id
                                        onDragStateNow(true, center())
                                    },
                                    onDrag = { change, amount ->
                                        change.consume()
                                        dragPosition += amount
                                        // Over another sound's place: move there.
                                        val list = soundsNow
                                        val from = list.indexOfFirst { it.id == sound.id }
                                        val cx = dragPosition.x + cellW / 2f
                                        val cy = dragPosition.y + cellH / 2f
                                        if (from >= 0 && cy >= 0f && cx >= 0f) {
                                            val column = (cx / (cellW + gapPx)).toInt().coerceIn(0, 2)
                                            val row = (cy / (cellH + gapPx)).toInt()
                                            val to = row * 3 + column
                                            if (to != from && to in list.indices) onMoveNow(from, to)
                                        }
                                        onDragStateNow(true, center())
                                    },
                                    onDragEnd = {
                                        val dropped = soundsNow.firstOrNull { it.id == sound.id }
                                        val where = center()
                                        draggedId = null
                                        onDragStateNow(false, null)
                                        if (dropped != null) onDropNow(dropped, where)
                                    },
                                    onDragCancel = {
                                        draggedId = null
                                        onDragStateNow(false, null)
                                    }
                                )
                            }
                            .clickable { onPlay(sound) }
                    )
                }
            }
            val addAt = slot(sounds.size)
            val shape = RoundedCornerShape(12.dp)
            Row(
                Modifier.offset { IntOffset(addAt.x.roundToInt(), addAt.y.roundToInt()) }
                    .width(cellWDp).height(BUTTON_HEIGHT).clip(shape)
                    .border(1.dp, Color.White.copy(alpha = 0.3f), shape)
                    .clickable(onClick = onAdd),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Add, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text("Add Sound", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
        }
    }
}
