package com.mediaviewer.ui

import com.mediaviewer.platform.PlatformUri as Uri

import com.mediaviewer.platform.PlatformUri
import com.mediaviewer.ui.compat.BackHandler
import com.mediaviewer.ui.compat.rememberLauncherForActivityResult
import com.mediaviewer.ui.compat.PickVisualMediaRequest
import com.mediaviewer.ui.compat.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.mediaviewer.model.AuthorInfo
import com.mediaviewer.model.ProfileData
import com.mediaviewer.util.rememberHapticTap
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.toArgb

/**
 * Item 19: the profile page's edit popup, centered over the (blurred)
 * profile. Rows:
 *  1. avatar (left) and banner (right), side by side, same height — tap
 *     either to pick a new picture from your photos;
 *  2. the whole bio;
 *  3. display name and handle.
 * Every text is editable in place (tap it and type). Close / Save at the
 * bottom; Save uploads whatever changed and closes on success.
 */
@Composable
fun EditProfileDialog(
    author: AuthorInfo,
    profile: ProfileData?,
    tint: Color,
    liquidGlass: Boolean,
    onDismiss: () -> Unit,
    onSave: (displayName: String, bio: String, handle: String, avatar: Uri?, banner: Uri?, onDone: (String?) -> Unit) -> Unit
) {
    val tap = rememberHapticTap()
    var displayName by remember { mutableStateOf(author.displayName) }
    var bio by remember { mutableStateOf(profile?.description.orEmpty()) }
    var handle by remember { mutableStateOf(author.handle) }
    var newAvatar by remember { mutableStateOf<Uri?>(null) }
    var newBanner by remember { mutableStateOf<Uri?>(null) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    BackHandler(enabled = !saving, onBack = onDismiss)

    // ── Supporter customizations (one record: icon shape, effect, colors) ──
    val supporter = com.mediaviewer.util.Supporter.active
    val savedStyle = remember { com.mediaviewer.util.ProfileStyles.peek(author.did) ?: com.mediaviewer.util.ProfileStyle() }
    var style by remember { mutableStateOf(savedStyle) }
    // The colors the profile has without any picked: what the pickers start from.
    val autoColors = ProfileColors(
        banner = rememberDominantColor(profile?.bannerUrl ?: author.avatarUrl ?: ""),
        avatar = rememberDominantColor(author.avatarUrl ?: "")
    )
    var colorPicker by remember { mutableStateOf(0) }
    var effectMenu by remember { mutableStateOf(false) }

    val pickAvatar = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) newAvatar = uri }
    val pickBanner = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) newBanner = uri }
    val imagesOnly = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)

    val panelColor = lerp(Color(0xFF111114), tint, 0.22f)
    val fieldColor = Color.White.copy(alpha = 0.07f)
    val shape = RoundedCornerShape(24.dp)

    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f))
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null
            ) { if (!saving) onDismiss() }
            .imePadding(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier.padding(horizontal = 18.dp).widthIn(max = 440.dp).fillMaxWidth()
                .clip(shape)
                .background(panelColor.copy(alpha = if (liquidGlass) 0.9f else 1f))
                .border(1.dp, tint.copy(alpha = 0.55f), shape)
                .clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null
                ) {}
                .heightIn(max = 640.dp)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Text("Edit Profile", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))

            // Row 1: avatar | banner, same height.
            val rowH = 96.dp
            Row(Modifier.fillMaxWidth().height(rowH), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                // (Square straight away when the supporter option below is on.)
                val iconShape = if (supporter && style.squareIcon) RoundedCornerShape(percent = 24) else CircleShape
                Box(
                    Modifier.size(rowH).clip(iconShape).background(fieldColor)
                        .border(1.dp, tint.copy(0.5f), iconShape)
                        .clickable(enabled = !saving) { tap(); pickAvatar.launch(imagesOnly) },
                    contentAlignment = Alignment.Center
                ) {
                    AsyncImage(
                        model = newAvatar ?: author.avatarUrl, contentDescription = "Avatar",
                        contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()
                    )
                    EditPictureBadge()
                }
                Box(
                    Modifier.weight(1f).height(rowH).clip(RoundedCornerShape(16.dp)).background(
                        Brush.linearGradient(listOf(tint.copy(0.55f), Color.Black))
                    ).border(1.dp, tint.copy(0.5f), RoundedCornerShape(16.dp))
                        .clickable(enabled = !saving) { tap(); pickBanner.launch(imagesOnly) },
                    contentAlignment = Alignment.Center
                ) {
                    val bannerModel: Any? = newBanner ?: profile?.bannerUrl
                    if (bannerModel != null) {
                        AsyncImage(model = bannerModel, contentDescription = "Banner", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                    EditPictureBadge()
                }
            }
            Spacer(Modifier.height(12.dp))

            // Row 2: bio.
            EditField(
                value = bio, onValue = { bio = it.take(256) }, label = "Bio", tint = tint,
                singleLine = false, minHeight = 96.dp, counter = "${bio.length}/256", enabled = !saving
            )
            Spacer(Modifier.height(10.dp))

            // Row 3: display name + handle.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f)) {
                    EditField(value = displayName, onValue = { displayName = it.take(64) }, label = "Display name", tint = tint, enabled = !saving)
                }
                Box(Modifier.weight(1f)) {
                    EditField(
                        value = handle, onValue = { handle = it.trim().removePrefix("@") }, label = "Handle", tint = tint,
                        keyboardType = KeyboardType.Uri, prefix = "@", enabled = !saving
                    )
                }
            }
            if (!handle.equals(author.handle, ignoreCase = true)) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Changing your handle needs a handle your account can use (e.g. another name.bsky.social, or a domain you've verified).",
                    color = Color.White.copy(0.5f), fontSize = 11.sp
                )
            }
            // Supporter options. Everyone sees them (in the supporter pink
            // until you are one); tapping one then opens the Support page.
            Spacer(Modifier.height(12.dp))
            val optionsShape = RoundedCornerShape(14.dp)
            fun gate(action: () -> Unit) {
                tap()
                if (supporter) action() else { onDismiss(); com.mediaviewer.util.Supporter.openPage() }
            }
            Column(
                Modifier.fillMaxWidth().clip(optionsShape).background(Color.White.copy(alpha = 0.07f))
                    .border(1.dp, (if (supporter) tint else SupporterPink).copy(alpha = 0.4f), optionsShape)
            ) {
                @Composable
                fun OptionRow(label: String, onClick: () -> Unit, trailing: @Composable () -> Unit) {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable(enabled = !saving, onClick = onClick)
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            label, color = Color.White, fontSize = 14.sp,
                            modifier = Modifier.weight(1f).supporterShine(!supporter)
                        )
                        trailing()
                    }
                }
                OptionRow("Square Profile Icon", { gate { style = style.copy(squareIcon = !style.squareIcon) } }) {
                    Box(Modifier.supporterShine(!supporter, recolor = false)) {
                        Switch(
                            checked = supporter && style.squareIcon,
                            onCheckedChange = { gate { style = style.copy(squareIcon = it) } },
                            enabled = !saving,
                            modifier = Modifier.size(width = 36.dp, height = 22.dp).scale(0.7f),
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = if (supporter) lerp(tint, Color.White, 0.25f) else SupporterPink,
                                uncheckedThumbColor = Color.White.copy(alpha = 0.6f), uncheckedTrackColor = Color.White.copy(alpha = 0.1f)
                            )
                        )
                    }
                }
                HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                OptionRow("Profile Effect", { gate { effectMenu = true } }) {
                    Box {
                        Text(
                            com.mediaviewer.util.ProfileStyles.effectLabel(style.effect),
                            color = lerp(tint, Color.White, 0.55f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.supporterShine(!supporter)
                        )
                        DropdownMenu(expanded = effectMenu, onDismissRequest = { effectMenu = false }) {
                            com.mediaviewer.util.ProfileStyles.EFFECTS.forEach { (id, label) ->
                                DropdownMenuItem(
                                    text = { Text(label, fontWeight = if (id == style.effect) FontWeight.SemiBold else FontWeight.Normal) },
                                    onClick = { style = style.copy(effect = id); effectMenu = false }
                                )
                            }
                        }
                    }
                }
                HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                val shownA = style.colorA?.let { Color(it) } ?: autoColors.banner
                val shownB = style.colorB?.let { Color(it) } ?: autoColors.avatar
                @Composable
                fun Swatch(color: Color) {
                    Box(
                        Modifier.size(24.dp).clip(CircleShape).background(color)
                            .border(1.5.dp, Color.White.copy(alpha = 0.7f), CircleShape)
                    )
                }
                OptionRow("Profile Color 1", { gate { colorPicker = 1 } }) { Swatch(shownA) }
                HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                OptionRow("Profile Color 2", { gate { colorPicker = 2 } }) { Swatch(shownB) }
                if (style.colorA != null || style.colorB != null) {
                    HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                    OptionRow("Reset Colors", { gate { style = style.copy(colorA = null, colorB = null) } }) {}
                }
            }
            if (colorPicker != 0) {
                val first = colorPicker == 1
                ColorWheelDialog(
                    initial = if (first) (style.colorA?.let { Color(it) } ?: autoColors.banner) else (style.colorB?.let { Color(it) } ?: autoColors.avatar),
                    title = if (first) "Profile Color 1" else "Profile Color 2",
                    onDismiss = { colorPicker = 0 },
                    onPick = { picked ->
                        // Picking one fixes both (the other keeps what it shows now).
                        val a = if (first) picked.toArgb() else (style.colorA ?: autoColors.banner.toArgb())
                        val b = if (first) (style.colorB ?: autoColors.avatar.toArgb()) else picked.toArgb()
                        style = style.copy(colorA = a, colorB = b)
                        colorPicker = 0
                    }
                )
            }
            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = Color(0xFFFF8A80), fontSize = 12.sp)
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier.weight(1f).height(46.dp).clip(RoundedCornerShape(23.dp))
                        .border(1.dp, tint.copy(0.6f), RoundedCornerShape(23.dp))
                        .clickable(enabled = !saving) { tap(); onDismiss() },
                    contentAlignment = Alignment.Center
                ) { Text("Close", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
                Box(
                    Modifier.weight(1f).height(46.dp).clip(RoundedCornerShape(23.dp))
                        .background(lerp(tint, Color.Black, 0.12f))
                        .clickable(enabled = !saving) {
                            tap(); saving = true; error = null
                            onSave(displayName.trim(), bio, handle, newAvatar, newBanner) { err ->
                                val saveStyle = com.mediaviewer.util.ProfileStyles.saver
                                if (err != null || !supporter || style == savedStyle || saveStyle == null) {
                                    saving = false
                                    if (err == null) onDismiss() else error = err
                                } else {
                                    // The customizations: one more small record.
                                    saveStyle(style) { styleErr ->
                                        saving = false
                                        if (styleErr == null) onDismiss() else error = styleErr
                                    }
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (saving) CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                    else Text("Save", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun EditPictureBadge() {
    Box(
        Modifier.size(30.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.5f)),
        contentAlignment = Alignment.Center
    ) { Icon(Icons.Default.AddPhotoAlternate, contentDescription = "Change picture", tint = Color.White, modifier = Modifier.size(17.dp)) }
}

@Composable
private fun EditField(
    value: String,
    onValue: (String) -> Unit,
    label: String,
    tint: Color,
    singleLine: Boolean = true,
    minHeight: androidx.compose.ui.unit.Dp = 0.dp,
    counter: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    prefix: String? = null,
    enabled: Boolean = true
) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(Color.White.copy(alpha = 0.07f))
            .border(1.dp, tint.copy(alpha = 0.3f), shape).padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label.uppercase(), color = tint.let { lerp(it, Color.White, 0.45f) }, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, modifier = Modifier.weight(1f))
            if (counter != null) Text(counter, color = Color.White.copy(0.4f), fontSize = 10.sp)
        }
        Spacer(Modifier.height(3.dp))
        Row(verticalAlignment = Alignment.Top) {
            // Same text style as the field itself, so the prefix ("@") sits
            // on the handle's own baseline instead of dropping below it.
            if (prefix != null) Text(prefix, style = TextStyle(color = Color.White.copy(0.5f), fontSize = 14.sp, lineHeight = 19.sp))
            BasicTextField(
                value = value, onValueChange = onValue, singleLine = singleLine, enabled = enabled,
                textStyle = TextStyle(color = Color.White, fontSize = 14.sp, lineHeight = 19.sp),
                cursorBrush = SolidColor(Color.White),
                keyboardOptions = KeyboardOptions(
                    keyboardType = keyboardType,
                    capitalization = if (keyboardType == KeyboardType.Text) KeyboardCapitalization.Sentences else KeyboardCapitalization.None,
                    imeAction = if (singleLine) ImeAction.Done else ImeAction.Default
                ),
                modifier = Modifier.weight(1f).heightIn(min = minHeight)
            )
        }
    }
}
