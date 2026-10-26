package com.mediaviewer.ui

import com.mediaviewer.ui.compat.rememberPlatformView

import com.mediaviewer.ui.compat.jformat


import com.mediaviewer.ui.compat.BackHandler
import com.mediaviewer.ui.compat.rememberLauncherForActivityResult
import com.mediaviewer.ui.compat.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import com.mediaviewer.ui.theme.CircleShape
import com.mediaviewer.ui.theme.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FormatAlignCenter
import androidx.compose.material.icons.automirrored.filled.FormatAlignLeft
import androidx.compose.material.icons.automirrored.filled.FormatAlignRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import com.mediaviewer.i18n.Text
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mediaviewer.model.DownloadProgress
import com.mediaviewer.ui.theme.*
import com.mediaviewer.util.StoredBskyAccount
import com.mediaviewer.util.rememberHapticTap
import com.mediaviewer.viewmodel.MainViewModel

/** Which of the Settings page's two tabs is showing — switched by the
 *  "Settings / Credits" control at the right end of the Hub's bottom bar. */
internal enum class SettingsTab { SETTINGS, CREDITS, SUPPORT }

/** Everything the reworked Settings page needs beyond what [SettingsSheet]
 *  already took: multiple-account handling, the tagging-model download, and
 *  the e621 download button. Bundled into one object with defaults so it
 *  travels through MainFeedScreen as a single parameter. */
data class SettingsExtras(
    val otherBskyAccounts: List<StoredBskyAccount> = emptyList(),
    val showSwitchAccountsRow: Boolean = true,
    val accountSwitching: Boolean = false,
    val taggerModelReady: Boolean = false,
    val taggerModelDownloading: Boolean = false,
    val downloadIsE621: Boolean = false,
    val onToggleShowSwitchAccountsRow: (Boolean) -> Unit = {},
    /** (handle, appPassword, onResult) — onResult gets null on success or an error message. */
    val onAddBskyAccount: (String, String, (String?) -> Unit) -> Unit = { _, _, done -> done(null) },
    val onSwitchBskyAccount: (String) -> Unit = {},
    val onRemoveBskyAccount: (String) -> Unit = {},
    val onDownloadTaggerModel: () -> Unit = {},
    val onDownloadAllE621Saved: () -> Unit = {},
    /** Data and Privacy → Blocked Accounts → View. */
    val onOpenBlockedAccounts: () -> Unit = {},
    /** Media Tagging → Export Dataset progress (see MainViewModel.DatasetExportState). */
    val datasetExportState: com.mediaviewer.viewmodel.MainViewModel.DatasetExportState = com.mediaviewer.viewmodel.MainViewModel.DatasetExportState.Idle,
    // ── Customize Hub ──
    /** Your own Bluesky lists (Customize Hub → Add list to Hub). */
    val userLists: List<com.mediaviewer.model.BskyList> = emptyList(),
    val userListsLoading: Boolean = false,
    val onEnsureUserLists: () -> Unit = {},
    /** (list URI, list name) */
    val onAddHubList: (String, String) -> Unit = { _, _ -> },
    /** (pasted list link, onDone(error or null)) */
    val onAddHubListFromUrl: (String, (String?) -> Unit) -> Unit = { _, done -> done(null) },
    /** Each Hub list row's loaded content, by list URI. */
    val hubLists: Map<String, com.mediaviewer.viewmodel.MainViewModel.HubListState> = emptyMap(),
    val onLoadHubList: (String) -> Unit = {},
    /** A Hub list row's posts scrolled to the end: load the next page. */
    val onLoadMoreHubList: (String) -> Unit = {},
    /** (list URI, list name, index) — a post tapped in a Hub list row. */
    val onOpenHubListPost: (String, String, Int) -> Unit = { _, _, _ -> },
    /** Customize Hub → Add → Profiles: who the popup lists (your follows,
     *  or the search results), searching, and the finished row. */
    val hubProfileCandidates: List<com.mediaviewer.model.AuthorInfo> = emptyList(),
    val hubProfileSearching: Boolean = false,
    val onSearchHubProfiles: (String) -> Unit = {},
    val onLoadMoreHubProfileSuggestions: () -> Unit = {},
    /** (row name, the accounts picked) */
    val onAddHubProfiles: (String, List<com.mediaviewer.model.AuthorInfo>) -> Unit = { _, _ -> },
    /** (row id, new name, its accounts) — a Profiles row's edit button. */
    val onEditHubProfiles: (String, String, List<com.mediaviewer.model.AuthorInfo>) -> Unit = { _, _, _ -> },
    // ── Dev Tools ──
    val onForceRefreshHub: () -> Unit = {},
    val onPreviewWelcome: () -> Unit = {},
    /** Media Tagging → Start Tagging → Viewed Media (History's posts). */
    val onStartTaggingViewed: () -> Unit = {},
    /** Your display name (Your Dataset's export starts as "…'s Tag Dataset"). */
    val selfDisplayName: String = ""
)

// ── Shared building blocks ──────────────────────────────────────────────────

/** The Settings page's accent (switches, sliders, picked values): your
 *  profile color — or Override App Colors' color — lifted to a readable
 *  brightness, instead of a fixed green. */
private val LocalSettingsAccent = androidx.compose.runtime.compositionLocalOf { VoteGreen }

private val BubbleShape get() = RoundedCornerShape(14.dp)
private val DangerRed = Color(0xFFEF5350)

/** The section titles' color: the same reflected profile color every rim on
 *  this page uses, lifted to a readable brightness (a dominant color sampled
 *  from an avatar is often too dark to read as text on the dark background). */
internal fun headerColorFor(tint: Color): Color {
    val hsv = FloatArray(3)
    com.mediaviewer.ui.compat.PlatformColor.colorToHSV(tint.toArgb(), hsv)
    hsv[2] = hsv[2].coerceAtLeast(0.85f)
    return Color(com.mediaviewer.ui.compat.PlatformColor.HSVToColor(hsv))
}

/**
 * One settings category: its title, an arrow beside it that folds the
 * whole category away (remembered — see UiToggles.collapsedSettingsSections),
 * and its bubbles.
 *
 * The space that sets one category apart from the next belongs to the
 * open bubbles (it's under them), not to the title: so folded categories
 * sit close together as a tidy list of titles, and an open one still has
 * room before whatever follows it.
 */
@Composable
private fun CollapsibleSection(
    title: String,
    tint: Color,
    /** The Supporter Settings title: white, with the supporter shine. */
    supporter: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    val collapsed = title in com.mediaviewer.util.UiToggles.collapsedSettingsSections
    val tap = rememberHapticTap()
    val arrowTurn by androidx.compose.animation.core.animateFloatAsState(if (collapsed) -90f else 0f, label = "settingsSectionArrow")
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                color = if (supporter) Color.White else headerColorFor(tint),
                fontSize = 26.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold,
                modifier = if (supporter) Modifier.supporterShine() else Modifier
            )
            Spacer(Modifier.width(10.dp))
            Box(
                Modifier.size(30.dp).clip(CircleShape).background(Color.White.copy(0.12f))
                    .clickable { tap(); com.mediaviewer.util.UiToggles.updateSettingsSectionCollapsed(title, !collapsed) },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    androidx.compose.material.icons.Icons.Default.KeyboardArrowDown,
                    contentDescription = if (collapsed) "Show $title" else "Hide $title",
                    tint = Color.White,
                    modifier = Modifier.size(22.dp).graphicsLayer { rotationZ = arrowTurn }
                )
            }
        }
        AnimatedVisibility(
            visible = !collapsed,
            enter = androidx.compose.animation.expandVertically() + androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.shrinkVertically() + androidx.compose.animation.fadeOut()
        ) {
            Column(
                Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 18.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                content = content
            )
        }
    }
}

/** A supporter-only switch: for supporters an ordinary switch; for everyone
 *  else it wears the supporter pink, stays off, and opens the Support page. */
@Composable
private fun SupporterSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    if (com.mediaviewer.util.Supporter.active) {
        CompactSwitch(checked, onCheckedChange)
    } else {
        Box(Modifier.supporterShine(recolor = false)) {
            Switch(
                checked = true, onCheckedChange = { com.mediaviewer.util.Supporter.openPage() },
                modifier = Modifier.size(width = 36.dp, height = 22.dp).scale(0.7f),
                colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = SupporterPink)
            )
        }
    }
}

/** Settings → "Supporter Settings" (the first category): its title wears
 *  the supporter pink with the same sweeping shine as the profile badge. */
@Composable
private fun SupporterSettingsSection(liquidGlass: Boolean, tint: Color, backdrop: GlassBackdrop?) {
    CollapsibleSection("Supporter Settings", tint, supporter = true) {
        SupporterSettingsBubbles(liquidGlass, tint, backdrop)
    }
}

/** The former Supporter Settings' bubbles: notifications, holidays and the
 *  browser's search engine. While every feature is free they sit at the end
 *  of App Functionality instead of a category of their own. */
@Composable
private fun SupporterSettingsBubbles(liquidGlass: Boolean, tint: Color, backdrop: GlassBackdrop?) {
    NotificationsBubble(liquidGlass, tint, backdrop)
    SearchEngineBubble(liquidGlass, tint, backdrop)
    HolidaysBubble(liquidGlass, tint, backdrop)
}

/** DM / Inbox notifications: a banner inside Stellar while it's open, and
 *  device notifications from a background check while it's closed. */
@Composable
private fun NotificationsBubble(liquidGlass: Boolean, tint: Color, backdrop: GlassBackdrop?) {
    val local = com.mediaviewer.util.LocalData
    val context = com.mediaviewer.ui.compat.LocalContext.current
    SettingsBubble(liquidGlass, tint, backdrop) {
        BubbleRow {
            RowLabel("DM Notifications", Modifier.weight(1f))
            SupporterSwitch(local.notifyDms) {
                local.updateNotifyDms(it)
                com.mediaviewer.platform.LocalPlatform.syncNotifications(context, requestPermission = it)
            }
        }
        BubbleDivider()
        BubbleRow {
            RowLabel("Inbox Notifications", Modifier.weight(1f))
            SupporterSwitch(local.notifyInbox) {
                local.updateNotifyInbox(it)
                com.mediaviewer.platform.LocalPlatform.syncNotifications(context, requestPermission = it)
            }
        }
    }
}

/** The Calendar's built-in days. */
@Composable
private fun HolidaysBubble(liquidGlass: Boolean, tint: Color, backdrop: GlassBackdrop?) {
    val local = com.mediaviewer.util.LocalData
    SettingsBubble(liquidGlass, tint, backdrop) {
        BubbleRow {
            RowLabel("Major Holidays", Modifier.weight(1f))
            SupporterSwitch(local.majorHolidays) { local.updateMajorHolidays(it) }
        }
        BubbleDivider()
        BubbleRow {
            RowLabel("Minor Holidays", Modifier.weight(1f))
            SupporterSwitch(local.minorHolidays) { local.updateMinorHolidays(it) }
        }
    }
}

/** Search's Web Browser tab: what the address bar searches with. */
@Composable
private fun SearchEngineBubble(liquidGlass: Boolean, tint: Color, backdrop: GlassBackdrop?) {
    val local = com.mediaviewer.util.LocalData
    val supporter = com.mediaviewer.util.Supporter.active
    SettingsBubble(liquidGlass, tint, backdrop) {
        var engineMenu by remember { mutableStateOf(false) }
        BubbleRow {
            RowLabel("Browser Search Engine", Modifier.weight(1f))
            Box {
                Text(
                    local.searchEngine.label,
                    color = if (supporter) LocalSettingsAccent.current else Color.White,
                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.supporterShine(!supporter).clickable {
                        if (supporter) engineMenu = true else com.mediaviewer.util.Supporter.openPage()
                    }
                )
                DropdownMenu(expanded = engineMenu, onDismissRequest = { engineMenu = false }) {
                    com.mediaviewer.util.LocalData.SearchEngine.values().forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.label, fontWeight = if (option == local.searchEngine) FontWeight.SemiBold else FontWeight.Normal) },
                            onClick = { local.updateSearchEngine(option); engineMenu = false }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Settings → Data and Privacy → Check for Updates: whether Stellar looks
 * for a newer release on GitHub by itself (each time it opens), and Check
 * Now. See AppUpdates. Where a version can't check (the web), the
 * [VersionBubble] stands in its place instead.
 */
@Composable
private fun UpdatesBubble(liquidGlass: Boolean, tint: Color, backdrop: GlassBackdrop?) {
    val context = com.mediaviewer.ui.compat.LocalContext.current
    var checking by remember { mutableStateOf(false) }
    SettingsBubble(liquidGlass, tint, backdrop) {
        BubbleRow {
            RowLabel("Check for Updates", Modifier.weight(1f), sub = "v" + com.mediaviewer.AppVersion.NAME)
            CompactSwitch(com.mediaviewer.util.AppUpdates.enabled) { com.mediaviewer.util.AppUpdates.updateEnabled(it) }
        }
        BubbleDivider()
        BubbleRow {
            RowLabel("Check Now", Modifier.weight(1f))
            PillButton(if (checking) "Checking…" else "Check", {
                if (!checking) {
                    checking = true
                    com.mediaviewer.util.AppUpdates.check(context, force = true) { found, err ->
                        checking = false
                        if (!found) com.mediaviewer.ui.compat.Toast.makeText(
                            context, err ?: "You're on the newest version", com.mediaviewer.ui.compat.Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            })
        }
    }
}

/**
 * Settings → Data and Privacy → Version: where this version of Stellar
 * doesn't check for updates itself (the web), the top of Data and
 * Privacy just names the version, in the same place the update check
 * sits everywhere else.
 */
@Composable
private fun VersionBubble(liquidGlass: Boolean, tint: Color, backdrop: GlassBackdrop?) {
    SettingsBubble(liquidGlass, tint, backdrop) {
        BubbleRow {
            RowLabel("Version", Modifier.weight(1f), sub = "v" + com.mediaviewer.AppVersion.NAME)
        }
    }
}

/**
 * Settings → App Functionality → Language: the language Stellar's own words
 * are shown in. The names in the menu are each language's own name, so
 * anyone can find theirs whatever the app is showing now.
 */
@Composable
private fun LanguageBubble(liquidGlass: Boolean, tint: Color, backdrop: GlassBackdrop?) {
    val scope = rememberCoroutineScope()
    val current = com.mediaviewer.i18n.Translations.language
    SettingsBubble(liquidGlass, tint, backdrop) {
        var menuOpen by remember { mutableStateOf(false) }
        BubbleRow {
            RowLabel("Language", Modifier.weight(1f))
            Box {
                Row(
                    Modifier.clip(RoundedCornerShape(50)).clickable { menuOpen = true }.padding(start = 8.dp, top = 2.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // (A language's own name is never translated.)
                    androidx.compose.material3.Text(
                        current.nativeName, color = LocalSettingsAccent.current, fontSize = 13.sp, fontWeight = FontWeight.SemiBold
                    )
                    Icon(
                        Icons.Default.ArrowDropDown, contentDescription = null,
                        tint = LocalSettingsAccent.current, modifier = Modifier.size(20.dp)
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    com.mediaviewer.i18n.AppLanguage.entries.forEach { option ->
                        DropdownMenuItem(
                            text = {
                                androidx.compose.material3.Text(
                                    option.nativeName,
                                    fontWeight = if (option == current) FontWeight.SemiBold else FontWeight.Normal
                                )
                            },
                            trailingIcon = if (option == current) {
                                { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                            } else null,
                            onClick = {
                                menuOpen = false
                                if (option != current) scope.launch { com.mediaviewer.i18n.Translations.select(option) }
                            }
                        )
                    }
                }
            }
        }
    }
}

/** One settings bubble. Rows placed inside are plain — they never draw their
 *  own glass surface/outline, only this bubble does — so a bubble holding
 *  several rows (with [BubbleDivider]s between them) reads as one shape. */
@Composable
private fun SettingsBubble(
    liquidGlass: Boolean, tint: Color, backdrop: GlassBackdrop?,
    content: @Composable ColumnScope.() -> Unit
) {
    if (liquidGlass) {
        LiquidGlassSurface(Modifier.fillMaxWidth(), shape = BubbleShape, tint = tint, backdrop = backdrop) {
            Column(Modifier.fillMaxWidth(), content = content)
        }
    } else {
        Box(Modifier.fillMaxWidth().clip(BubbleShape).background(Color.White.copy(0.04f))) {
            Column(Modifier.fillMaxWidth(), content = content)
        }
    }
}

@Composable
private fun BubbleDivider() = HorizontalDivider(color = Color.White.copy(alpha = 0.1f))

@Composable
private fun BubbleRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 40.dp).padding(horizontal = 14.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        content = content
    )
}

@Composable
private fun RowLabel(text: String, modifier: Modifier = Modifier, sub: String? = null, dim: Boolean = false, singleLine: Boolean = false) {
    Column(modifier.padding(end = 12.dp)) {
        // (singleLine: the words draw in full on one line. The default
        // overflow clips a no-wrap text at its own box edge, so the last
        // letter was cut where this label's end padding begins; Visible
        // lets the words paint over that padding instead — nothing else
        // in the row moves or resizes.)
        Text(
            text, color = if (dim) DimGray else Color.White, fontSize = 14.sp,
            maxLines = if (singleLine) 1 else Int.MAX_VALUE, softWrap = !singleLine,
            overflow = if (singleLine) TextOverflow.Visible else TextOverflow.Clip
        )
        if (sub != null) Text(sub, color = DimGray, fontSize = 11.sp, lineHeight = 13.sp)
    }
}

/** Material3's Switch has no compact size variant: pin the layout footprint
 *  to roughly two-thirds of the default via an outer Box, then scale the real
 *  Switch to fit (Modifier.scale alone wouldn't shrink the space reserved). */
@Composable
private fun CompactSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) = CompactSwitch(checked, true, onCheckedChange)

/** A switch that can be grayed out ([enabled] false: shown, can't be changed). */
@Composable
private fun CompactSwitch(checked: Boolean, enabled: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val tap = rememberHapticTap()
    Box(modifier = Modifier.size(width = 36.dp, height = 22.dp), contentAlignment = Alignment.Center) {
        Switch(
            checked = checked, onCheckedChange = { tap(); onCheckedChange(it) },
            enabled = enabled,
            modifier = Modifier.scale(0.7f).graphicsLayer { alpha = if (enabled) 1f else 0.45f },
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White, checkedTrackColor = LocalSettingsAccent.current,
                uncheckedThumbColor = DimGray, uncheckedTrackColor = Color.White.copy(0.1f)
            )
        )
    }
}

/** Full-size Material3 sliders reserve a 48dp touch target around the thumb,
 *  which is what used to make slider rows taller than toggle rows. Custom
 *  thumb/track composables avoid that reservation entirely. */
@Composable
private fun CompactSlider(value: Float, onValueChange: (Float) -> Unit, modifier: Modifier = Modifier) {
    Slider(
        value = value, onValueChange = onValueChange, valueRange = 0f..1f,
        modifier = modifier.height(20.dp),
        thumb = { Box(Modifier.size(14.dp).clip(CircleShape).background(Color.White)) },
        track = { sliderState ->
            Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.15f))) {
                Box(
                    Modifier.fillMaxHeight()
                        .fillMaxWidth(fraction = sliderState.value.coerceIn(0f, 1f))
                        .clip(RoundedCornerShape(2.dp))
                        .background(LocalSettingsAccent.current)
                )
            }
        }
    )
}

/** The small pill button used on the right of a row ("Download", "Log out",
 *  "Add", ...). */
@Composable
private fun PillButton(
    label: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, color: Color = Color.White
) {
    val tap = rememberHapticTap()
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier
            .then(
                // (Dev Tools → Gradient Buttons: filled like a popup's button.)
                if (com.mediaviewer.util.UiToggles.devGradientButtons && enabled) Modifier.popupButtonFill(LocalSettingsAccent.current, shape)
                else Modifier.clip(shape).background(if (enabled) Color.White.copy(0.12f) else Color.White.copy(0.05f))
            )
            .clickable(enabled = enabled) { tap(); onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label, color = if (enabled) color else DimGray,
            fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false
        )
    }
}

/** The white X that follows a Login pill in an inline sign-in row: closes the
 *  row again without signing in. Sized to sit level with [PillButton]. */
@Composable
private fun CancelXButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val tap = rememberHapticTap()
    Box(
        modifier.size(28.dp).clip(CircleShape)
            .background(Color.White.copy(0.12f))
            .clickable { tap(); onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Default.Close, contentDescription = "Cancel", tint = Color.White, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun ToggleBubble(
    label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit,
    liquidGlass: Boolean, tint: Color, backdrop: GlassBackdrop?
) {
    SettingsBubble(liquidGlass, tint, backdrop) {
        BubbleRow {
            RowLabel(label, Modifier.weight(1f))
            CompactSwitch(checked, onCheckedChange)
        }
    }
}

@Composable
private fun ActionBubble(
    label: String, buttonLabel: String, onClick: () -> Unit,
    liquidGlass: Boolean, tint: Color, backdrop: GlassBackdrop?,
    sub: String? = null, enabled: Boolean = true, buttonColor: Color = Color.White
) {
    SettingsBubble(liquidGlass, tint, backdrop) {
        BubbleRow {
            RowLabel(label, Modifier.weight(1f), sub = sub)
            PillButton(buttonLabel, onClick, enabled = enabled, color = buttonColor)
        }
    }
}

/** A short text field for use inside a [BubbleRow] — 30dp tall, so a row that
 *  swaps its label for input fields stays as short as any other row. */
@Composable
private fun CompactField(
    value: String, onValueChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier,
    password: Boolean = false, imeAction: ImeAction = ImeAction.Next, onDone: () -> Unit = {}
) {
    BasicTextField(
        value = value, onValueChange = onValueChange, singleLine = true,
        textStyle = LocalTextStyle.current.copy(color = Color.White, fontSize = 13.sp),
        cursorBrush = SolidColor(Color.White),
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            keyboardType = if (password) KeyboardType.Password else KeyboardType.Email,
            imeAction = imeAction
        ),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = modifier,
        decorationBox = { inner ->
            Box(
                Modifier.fillMaxWidth().height(30.dp).clip(RoundedCornerShape(8.dp))
                    .background(Color.White.copy(0.08f)).padding(horizontal = 8.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                if (value.isEmpty()) Text(placeholder, color = DimGray, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                inner()
            }
        }
    )
}

/** A running/finished export's status, shown as its own line under the
 *  row that started it: an animated bar while the file is being written,
 *  then a check (or the error) once it's done. */
@Composable
private fun ExportStatusLine(working: Boolean, message: String, success: Boolean, tint: Color) {
    val barColor = headerColorFor(tint)
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp).padding(bottom = 10.dp, top = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (working) {
                CircularProgressIndicator(Modifier.size(12.dp), color = barColor, strokeWidth = 1.5.dp)
            } else {
                Text(if (success) "✓" else "!", color = if (success) VoteGreen else DangerRed, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(8.dp))
            Text(
                message, color = if (working) Color.White.copy(alpha = 0.85f) else if (success) VoteGreen else DangerRed,
                fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis
            )
        }
        if (working) {
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
                color = barColor, trackColor = Color.White.copy(alpha = 0.12f)
            )
        }
    }
}

// ── The Settings page ───────────────────────────────────────────────────────

@Composable
internal fun SettingsPageContent(
    reducedAnimations: Boolean,
    onToggleReducedAnimations: (Boolean) -> Unit,
    hateFunBlurNsfw: Boolean,
    onToggleHateFunBlurNsfw: (Boolean) -> Unit,
    squareGridRounded: Boolean,
    onToggleSquareGridRounded: (Boolean) -> Unit,
    followerScanState: MainViewModel.FollowerScanState,
    onRescanFollowersFromScratch: () -> Unit,
    hideTextOnlyPosts: Boolean,
    onToggleHideTextOnlyPosts: (Boolean) -> Unit,
    liquidGlass: Boolean,
    onToggleLiquidGlass: (Boolean) -> Unit,
    liquidGlassIntensity: Float,
    onSetLiquidGlassIntensity: (Float) -> Unit,
    glassRimIntensity: Float,
    onSetGlassRimIntensity: (Float) -> Unit,
    glassRimVibrantSecondary: Boolean,
    onToggleGlassRimVibrantSecondary: (Boolean) -> Unit,
    translationEnabled: Boolean,
    translationTargetLang: String,
    onToggleTranslation: (Boolean) -> Unit,
    onSelectTranslationLanguage: (String) -> Unit,
    customFontName: String?,
    onPickFontFile: (com.mediaviewer.platform.PlatformUri) -> Unit,
    onResetFont: () -> Unit,
    bskyLoggedIn: Boolean,
    bskyHandle: String,
    isLoading: Boolean,
    onLoginBluesky: (String, String) -> Unit,
    onLogoutBluesky: () -> Unit,
    e621LoggedIn: Boolean,
    e621Username: String,
    onLoginE621: (String, String) -> Unit,
    onLogoutE621: () -> Unit,
    downloadOnLike: Boolean,
    onToggleDownloadOnLike: (Boolean) -> Unit,
    downloadProgress: DownloadProgress?,
    onDownloadAllLiked: () -> Unit,
    onCancelDownload: () -> Unit,
    tagPostWhenLiked: Boolean,
    onToggleTagPostWhenLiked: (Boolean) -> Unit,
    taggingRunning: Boolean,
    taggingScanned: Int,
    taggingTagged: Int,
    ownDatasetCount: Int = 0,
    onLocallyTagAllLiked: () -> Unit,
    onDeleteTaggedDatabase: () -> Unit,
    importedDatasets: List<com.mediaviewer.tagging.TagDatasetInfo>,
    /** (dataset id — null = your own, its name, where to save it) */
    onExportDataset: (String?, String, com.mediaviewer.platform.PlatformUri) -> Unit,
    onImportDataset: (com.mediaviewer.platform.PlatformUri) -> Unit,
    onDeleteImportedDataset: (String) -> Unit,
    combineListsAndPacks: Boolean,
    onToggleCombineListsPacks: (Boolean) -> Unit,
    autoAddToOnFollow: Boolean,
    onToggleAutoAddToOnFollow: (Boolean) -> Unit,
    extras: SettingsExtras,
    dominantColor: Color,
    backdrop: GlassBackdrop?,
    // Live Link widget feature (hidden behind FeatureFlags.LIVE_LINK_ENABLED)
    liveTwitchUrl: String? = null,
    liveYoutubeUrl: String? = null,
    onSaveLiveTwitchUrl: (String) -> Unit = {},
    onSaveLiveYoutubeUrl: (String) -> Unit = {},
    onCreateLiveLinkWidget: () -> Unit = {}
) {
    val tint = dominantColor
    val anyLoggedIn = bskyLoggedIn || e621LoggedIn

    androidx.compose.runtime.CompositionLocalProvider(LocalSettingsAccent provides headerColorFor(tint)) {
    SettingsColumns(
        // (Landscape: two sections side by side — see SettingsColumns.)
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).landscapeReadable(SETTINGS_LANDSCAPE_WIDTH)
            // (Landscape: the page itself keeps it just clear of the camera.)
            .padding(horizontal = if (rememberLandscape()) 0.dp else 16.dp).padding(top = if (rememberLandscape()) 4.dp else 10.dp, bottom = 10.dp)
    ) {
        // ── Supporter Settings ──────────────────────────────────────────
        // (Only shown to supporters.)
        // (While every feature is free, its bubbles live in App
        // Functionality instead — see below.)
        val showSupporterSettings = com.mediaviewer.util.Supporter.active && !com.mediaviewer.util.FeatureFlags.ALL_FEATURES_FREE
        if (showSupporterSettings) SupporterSettingsSection(liquidGlass, tint, backdrop)

        // (Customize Hub lives in the Hub itself now: its floating button.)

        // ── App Functionality ───────────────────────────────────────────
        CollapsibleSection("App Functionality", tint) {

            // NSFW Posts (posts labeled porn / sexual / nudity — see
            // AdultContentPolicy). Android: Stellar's own Show / Blur / Hide.
            // iOS: the Bluesky account's settings, managed on Bluesky's
            // website in the phone's own browser (NSFW content is only
            // enabled outside the app).
            SettingsBubble(liquidGlass, tint, backdrop) {
                BubbleRow {
                    RowLabel("NSFW Posts", Modifier.weight(1f))
                    if (com.mediaviewer.util.AdultContentPolicy.followsBluesky) {
                        val nsfwContext = com.mediaviewer.ui.compat.LocalContext.current
                        PillButton("Manage on Bluesky", { com.mediaviewer.ui.compat.openUrl(nsfwContext, com.mediaviewer.util.AdultContentPolicy.MANAGE_URL) })
                    } else {
                        var nsfwMenuOpen by remember { mutableStateOf(false) }
                        val mode = com.mediaviewer.util.AdultContentPolicy.androidMode
                        fun label(v: com.mediaviewer.util.AdultContentPolicy.Visibility) = when (v) {
                            com.mediaviewer.util.AdultContentPolicy.Visibility.SHOW -> "Show"
                            com.mediaviewer.util.AdultContentPolicy.Visibility.WARN -> "Blur"
                            com.mediaviewer.util.AdultContentPolicy.Visibility.HIDE -> "Hide"
                        }
                        Box {
                            Text(
                                label(mode), color = LocalSettingsAccent.current, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.clickable { nsfwMenuOpen = true }
                            )
                            DropdownMenu(expanded = nsfwMenuOpen, onDismissRequest = { nsfwMenuOpen = false }) {
                                com.mediaviewer.util.AdultContentPolicy.Visibility.entries.forEach { option ->
                                    DropdownMenuItem(
                                        text = { Text(label(option), fontWeight = if (option == mode) FontWeight.SemiBold else FontWeight.Normal) },
                                        onClick = {
                                            nsfwMenuOpen = false
                                            if (option != mode) {
                                                val hideChanged = option == com.mediaviewer.util.AdultContentPolicy.Visibility.HIDE ||
                                                    mode == com.mediaviewer.util.AdultContentPolicy.Visibility.HIDE
                                                com.mediaviewer.util.AdultContentPolicy.updateAndroidMode(option)
                                                // Hiding works as posts are read: the feed loads again.
                                                if (hideChanged) LocalOverlays.reloadFeed?.invoke()
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Every vibration the app makes (on by default).
            ToggleBubble("Haptics", com.mediaviewer.util.UiToggles.hapticsEnabled, { com.mediaviewer.util.UiToggles.updateHapticsEnabled(it) }, liquidGlass, tint, backdrop)

            if (com.mediaviewer.util.FeatureFlags.ALL_FEATURES_FREE) NotificationsBubble(liquidGlass, tint, backdrop)
            // Translate Post Text + Translate To share one bubble.
            PlatformFeatureGate(com.mediaviewer.platform.PlatformFeature.TRANSLATION) {
                SettingsBubble(liquidGlass, tint, backdrop) {
                    BubbleRow {
                        RowLabel("Translate Post Text", Modifier.weight(1f))
                        CompactSwitch(translationEnabled, onToggleTranslation)
                    }
                    if (translationEnabled) {
                        BubbleDivider()
                        var langMenuExpanded by remember { mutableStateOf(false) }
                        BubbleRow {
                            RowLabel("Translate To", Modifier.weight(1f))
                            Box {
                                Text(
                                    com.mediaviewer.util.TranslationManager.SUPPORTED_LANGUAGES
                                        .firstOrNull { it.first == translationTargetLang }?.second
                                        ?: com.mediaviewer.util.TranslationManager.displayNameFor(translationTargetLang),
                                    color = LocalSettingsAccent.current, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.clickable { langMenuExpanded = true }
                                )
                                DropdownMenu(expanded = langMenuExpanded, onDismissRequest = { langMenuExpanded = false }) {
                                    com.mediaviewer.util.TranslationManager.SUPPORTED_LANGUAGES.forEach { (langTag, name) ->
                                        DropdownMenuItem(
                                            text = { Text(name) },
                                            onClick = { onSelectTranslationLanguage(langTag); langMenuExpanded = false }
                                        )
                                    }
                                }
                            }
                        }
                        BubbleDivider()
                        BubbleRow {
                            RowLabel("Show Translation Status", Modifier.weight(1f))
                            CompactSwitch(com.mediaviewer.util.UiToggles.showTranslationStatus) {
                                com.mediaviewer.util.UiToggles.updateShowTranslationStatus(it)
                            }
                        }
                    }
                }
            }

            // The web version's lighter drawing, on the other versions too
            // (the web always has it). Switching restarts the app.
            if (com.mediaviewer.platform.currentPlatform != com.mediaviewer.platform.PlatformKind.WEB) {
                var pendingPerformanceMode by remember { mutableStateOf<Boolean?>(null) }
                ToggleBubble("Performance Mode", com.mediaviewer.util.UiToggles.performanceMode, { pendingPerformanceMode = it }, liquidGlass, tint, backdrop)
                pendingPerformanceMode?.let { on ->
                    val context = com.mediaviewer.ui.compat.LocalContext.current
                    androidx.compose.ui.window.Dialog(
                        onDismissRequest = { pendingPerformanceMode = null },
                        properties = com.mediaviewer.ui.compat.edgeToEdgeDialogProperties()
                    ) {
                        ConfirmPopup(
                            title = if (on) "Turn On Performance Mode?" else "Turn Off Performance Mode?",
                            message = if (on) "Stellar will run faster by skipping live blur and other heavy effects. Glass becomes a solid color, and the background holds still. Stellar restarts to switch."
                                else "Live blur and the other effects come back. Stellar restarts to switch.",
                            confirmLabel = "Restart",
                            // (The page's own backdrop: its glass blurs what's
                            // behind it, like the other Settings popups.)
                            liquidGlass = liquidGlass, tint = tint, backdrop = backdrop,
                            onConfirm = { pendingPerformanceMode = null; com.mediaviewer.util.UiToggles.applyPerformanceMode(context, on) },
                            onDismiss = { pendingPerformanceMode = null },
                            destructive = false
                        )
                    }
                }
            }

            ToggleBubble("Hide Text Only Posts", hideTextOnlyPosts, onToggleHideTextOnlyPosts, liquidGlass, tint, backdrop)

            if (bskyLoggedIn) {
                ToggleBubble("Show \"Add To\" After Following", autoAddToOnFollow, onToggleAutoAddToOnFollow, liquidGlass, tint, backdrop)
            }

            if (com.mediaviewer.util.FeatureFlags.ALL_FEATURES_FREE) SearchEngineBubble(liquidGlass, tint, backdrop)
            if (com.mediaviewer.util.FeatureFlags.ALL_FEATURES_FREE) HolidaysBubble(liquidGlass, tint, backdrop)
            // Bluesky profile/post links (a scanned QR code, a link in the
            // browser) can open in Stellar — "Set Up" explains how, with
            // shortcuts to both apps' Android settings. (iOS: a share-sheet
            // shortcut instead; the same popup explains that one.)
            var linkSetupOpen by remember { mutableStateOf(false) }
            PlatformFeatureGate(com.mediaviewer.platform.PlatformFeature.OPEN_BY_DEFAULT_LINKS) {
                ActionBubble(
                    label = "Open Bluesky Links in Stellar",
                    buttonLabel = "Set Up",
                    onClick = { linkSetupOpen = true },
                    liquidGlass = liquidGlass, tint = tint, backdrop = backdrop
                )
            }
            if (linkSetupOpen) {
                OpenLinksSetupDialog(liquidGlass = liquidGlass, tint = tint, onDismiss = { linkSetupOpen = false })
            }

            // The language Stellar's own words are shown in (last in the section).
            LanguageBubble(liquidGlass, tint, backdrop)
        }

        // ── UI Customization ────────────────────────────────────────────
        CollapsibleSection("UI Customization", tint) {

            // Override App Colors: everywhere the app would wear your profile
            // color, it wears the color picked here instead. Your own profile
            // page keeps its real colors.
            SettingsBubble(liquidGlass, tint, backdrop) {
                var showWheel by remember { mutableStateOf(false) }
                val overrideOn = com.mediaviewer.util.UiToggles.overrideAppColors
                val overrideColor = Color(com.mediaviewer.util.UiToggles.overrideColor)
                BubbleRow {
                    RowLabel("Override App Colors", Modifier.weight(1f))
                    CompactSwitch(overrideOn) { com.mediaviewer.util.UiToggles.updateOverrideAppColors(it) }
                }
                if (overrideOn) {
                    val tapColor = rememberHapticTap()
                    BubbleDivider()
                    BubbleRow(Modifier.clickable { tapColor(); showWheel = true }) {
                        RowLabel("Color", Modifier.weight(1f))
                        Text(
                            "#%06X".jformat(com.mediaviewer.util.UiToggles.overrideColor and 0xFFFFFF),
                            color = DimGray, fontSize = 12.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        )
                        Spacer(Modifier.width(10.dp))
                        Box(
                            Modifier.size(26.dp).clip(CircleShape).background(overrideColor)
                                .border(1.5.dp, Color.White.copy(alpha = 0.7f), CircleShape)
                        )
                    }
                }
                if (showWheel) {
                    ColorWheelDialog(
                        initial = overrideColor,
                        title = "App Color",
                        onDismiss = { showWheel = false },
                        onPick = {
                            com.mediaviewer.util.UiToggles.updateOverrideColor(it.toArgb())
                            showWheel = false
                        }
                    )
                }
            }
            // The heart that flies up when you like a post.
            SettingsBubble(liquidGlass, tint, backdrop) {
                var likeMenuExpanded by remember { mutableStateOf(false) }
                val likeAnim = com.mediaviewer.util.UiToggles.likeAnimation
                BubbleRow {
                    RowLabel("Like Animation", Modifier.weight(1f))
                    Box {
                        Text(
                            likeAnim.label,
                            color = LocalSettingsAccent.current, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clickable { likeMenuExpanded = true }
                        )
                        DropdownMenu(expanded = likeMenuExpanded, onDismissRequest = { likeMenuExpanded = false }) {
                            com.mediaviewer.util.UiToggles.LikeAnimation.entries.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option.label, fontWeight = if (option == likeAnim) FontWeight.SemiBold else FontWeight.Normal) },
                                    onClick = { com.mediaviewer.util.UiToggles.updateLikeAnimation(option); likeMenuExpanded = false }
                                )
                            }
                        }
                    }
                }
            }
            // Item 8: audio visualizer above the feed's interaction bar. It needs
            // the microphone permission to read the phone's audio output
            // (nothing is recorded), so turning it on asks for that first.
            val visualizerContext = com.mediaviewer.ui.compat.LocalContext.current
            val visualizerPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                com.mediaviewer.util.UiToggles.updateAudioVisualizer(granted)
            }
            PlatformFeatureGate(com.mediaviewer.platform.PlatformFeature.AUDIO_VISUALIZER) {
                SettingsBubble(liquidGlass, tint, backdrop) {
                    BubbleRow {
                        RowLabel(
                            "Audio Visualizer", Modifier.weight(1f),
                        )
                        CompactSwitch(com.mediaviewer.util.UiToggles.audioVisualizer) { on ->
                            if (!on) com.mediaviewer.util.UiToggles.updateAudioVisualizer(false)
                            else if (com.mediaviewer.util.AudioVisualizerEngine.hasPermission(visualizerContext)) com.mediaviewer.util.UiToggles.updateAudioVisualizer(true)
                            else visualizerPermission.launch("android.permission.RECORD_AUDIO")
                        }
                    }
                    // The web and computers: where the sound comes from — the
                    // web asks the browser for it (a tap), a computer looks
                    // for a loopback input.
                    val computer = com.mediaviewer.platform.currentPlatform == com.mediaviewer.platform.PlatformKind.WEB ||
                        com.mediaviewer.platform.currentPlatform == com.mediaviewer.platform.PlatformKind.DESKTOP
                    if (com.mediaviewer.util.UiToggles.audioVisualizer && computer) {
                        BubbleDivider()
                        BubbleRow {
                            Column(Modifier.weight(1f)) {
                                RowLabel("Sound Source", Modifier)
                                val st = com.mediaviewer.util.AudioVisualizerEngine.status
                                if (st.isNotBlank()) Text(st, color = Color.White.copy(alpha = 0.55f), fontSize = 11.sp, lineHeight = 14.sp)
                            }
                            Text(
                                if (com.mediaviewer.platform.currentPlatform == com.mediaviewer.platform.PlatformKind.WEB) "Listen" else "Look Again",
                                color = LocalSettingsAccent.current, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(start = 10.dp).clickable { com.mediaviewer.util.AudioVisualizerEngine.startListening(visualizerContext) }
                            )
                        }
                    }
                    if (com.mediaviewer.util.UiToggles.audioVisualizer && !computer) {
                        var callMenuExpanded by remember { mutableStateOf(false) }
                        val callMode = com.mediaviewer.util.UiToggles.visualizerCallMode
                        BubbleDivider()
                        BubbleRow {
                            RowLabel(
                                "During Calls", Modifier.weight(1f),
                            )
                            Box {
                                Text(
                                    callMode.label,
                                    color = LocalSettingsAccent.current, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.clickable { callMenuExpanded = true }
                                )
                                DropdownMenu(expanded = callMenuExpanded, onDismissRequest = { callMenuExpanded = false }) {
                                    com.mediaviewer.util.UiToggles.VisualizerCallMode.entries.forEach { option ->
                                        DropdownMenuItem(
                                            text = { Text(option.label, fontWeight = if (option == callMode) FontWeight.SemiBold else FontWeight.Normal) },
                                            onClick = { com.mediaviewer.util.UiToggles.updateVisualizerCallMode(option); callMenuExpanded = false }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // The row at the bottom of a post's text bubble: the counts and
            // the date, each hidden on its own (both = no row).
            SettingsBubble(liquidGlass, tint, backdrop) {
                BubbleRow {
                    RowLabel("Hide Post Stats", Modifier.weight(1f))
                    CompactSwitch(com.mediaviewer.util.UiToggles.hidePostStats) { com.mediaviewer.util.UiToggles.updateHidePostStats(it) }
                }
                BubbleDivider()
                BubbleRow {
                    RowLabel("Hide Post Date", Modifier.weight(1f))
                    CompactSwitch(com.mediaviewer.util.UiToggles.hidePostDate) { com.mediaviewer.util.UiToggles.updateHidePostDate(it) }
                }
            }
            // App Font: "Import" adds a .ttf/.otf to the list; the list (styled
            // like Loading Animation) shows the selected font — Audiowide by
            // default, the Original system font second, then every import, each
            // written in its own face.
            val fontContext = com.mediaviewer.ui.compat.LocalContext.current
            val fontScope = androidx.compose.runtime.rememberCoroutineScope()
            val fontPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                if (uri != null) fontScope.launch {
                    val error = com.mediaviewer.util.FontStore.import(fontContext, uri)
                    if (error != null) com.mediaviewer.ui.compat.Toast.makeText(fontContext, error, com.mediaviewer.ui.compat.Toast.LENGTH_LONG).show()
                }
            }
            SettingsBubble(liquidGlass, tint, backdrop) {
                var fontMenuExpanded by remember { mutableStateOf(false) }
                val fontStore = com.mediaviewer.util.FontStore
                val selectedFont = fontStore.selected
                BubbleRow {
                    RowLabel("App Font", Modifier.weight(1f))
                    PlatformFeatureInline(com.mediaviewer.platform.PlatformFeature.CUSTOM_FONT) {
                        PillButton("Import", { fontPickerLauncher.launch("*/*") })
                    }
                    Spacer(Modifier.width(10.dp))
                    Box {
                        Text(
                            fontStore.selectedName,
                            color = LocalSettingsAccent.current, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 120.dp).clickable { fontMenuExpanded = true }
                        )
                        DropdownMenu(expanded = fontMenuExpanded, onDismissRequest = { fontMenuExpanded = false }) {
                            fontStore.entries.forEach { option ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            option.name,
                                            fontFamily = fontStore.familyFor(option.id) ?: androidx.compose.ui.text.font.FontFamily.Default,
                                            fontWeight = if (option.id == selectedFont) FontWeight.SemiBold else FontWeight.Normal,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis
                                        )
                                    },
                                    trailingIcon = if (option.imported) {
                                        {
                                            Icon(
                                                Icons.Default.Close, contentDescription = "Remove ${option.name}",
                                                modifier = Modifier.size(18.dp).clip(CircleShape).clickable { fontStore.remove(option.id) }
                                            )
                                        }
                                    } else null,
                                    onClick = { fontStore.select(option.id); fontMenuExpanded = false }
                                )
                            }
                        }
                    }
                }
            }
            // Twinkling stars + the odd shooting star behind every page (the
            // dim profile-color background stays either way).
            SettingsBubble(liquidGlass, tint, backdrop) {
                BubbleRow {
                    RowLabel("Starry Background", Modifier.weight(1f))
                    CompactSwitch(com.mediaviewer.util.UiToggles.starryBackground) { com.mediaviewer.util.UiToggles.updateStarryBackground(it) }
                }
                if (com.mediaviewer.util.UiToggles.starryBackground) {
                    BubbleDivider()
                    var fpsMenuExpanded by remember { mutableStateOf(false) }
                    BubbleRow {
                        RowLabel(
                            "Frame Rate Cap", Modifier.weight(1f),
                        )
                        Box {
                            Text(
                                "${com.mediaviewer.util.UiToggles.starFrameRate} FPS",
                                color = LocalSettingsAccent.current, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.clickable { fpsMenuExpanded = true }
                            )
                            DropdownMenu(expanded = fpsMenuExpanded, onDismissRequest = { fpsMenuExpanded = false }) {
                                com.mediaviewer.util.UiToggles.starFrameRateOptions.forEach { option ->
                                    DropdownMenuItem(
                                        text = { Text("$option FPS", fontWeight = if (option == com.mediaviewer.util.UiToggles.starFrameRate) FontWeight.SemiBold else FontWeight.Normal) },
                                        onClick = { com.mediaviewer.util.UiToggles.updateStarFrameRate(option); fpsMenuExpanded = false }
                                    )
                                }
                            }
                        }
                    }
                }
            }
            // Which transition plays while a page loads: None (pages open
            // instantly and fill in as their data arrives), Pixels, Shatter or
            // Space (the default).
            SettingsBubble(liquidGlass, tint, backdrop) {
                var animMenuExpanded by remember { mutableStateOf(false) }
                val currentAnim = com.mediaviewer.util.UiToggles.loadingAnimation
                // (The web has no loading screens: always None there.)
                val onWeb = com.mediaviewer.platform.currentPlatform == com.mediaviewer.platform.PlatformKind.WEB
                BubbleRow {
                    RowLabel("Loading Animation", Modifier.weight(1f))
                    Box {
                        Text(
                            currentAnim.label,
                            color = if (onWeb) DimGray else LocalSettingsAccent.current, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clickable(enabled = !onWeb) { animMenuExpanded = true }
                        )
                        DropdownMenu(expanded = animMenuExpanded, onDismissRequest = { animMenuExpanded = false }) {
                            com.mediaviewer.util.UiToggles.LoadingAnimation.entries.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option.label, fontWeight = if (option == currentAnim) FontWeight.SemiBold else FontWeight.Normal) },
                                    onClick = { com.mediaviewer.util.UiToggles.updateLoadingAnimation(option); animMenuExpanded = false }
                                )
                            }
                        }
                    }
                }
            }

            // Every rounded corner in the app made square (profile icons and
            // the camera notch bubble stay round).
            ToggleBubble("Squared-Off Corners", com.mediaviewer.util.UiToggles.squareCorners, { com.mediaviewer.util.UiToggles.updateSquareCorners(it) }, liquidGlass, tint, backdrop)
            // Glass buttons, tabs and bars filled with a gradient instead (the
            // comments stay glass).
            ToggleBubble("Gradient Buttons", com.mediaviewer.util.UiToggles.devGradientButtons, { com.mediaviewer.util.UiToggles.updateDevGradientButtons(it) }, liquidGlass, tint, backdrop)
            ToggleBubble("Reduced Animations", reducedAnimations, onToggleReducedAnimations, liquidGlass, tint, backdrop)
            ToggleBubble("Rounded Grid Tiles", squareGridRounded, onToggleSquareGridRounded, liquidGlass, tint, backdrop)
        }

        // ── Integrations ────────────────────────────────────────────────
        CollapsibleSection("Integrations", tint) {

            AtProtocolAccountsBubble(
                bskyLoggedIn = bskyLoggedIn, bskyHandle = bskyHandle, isLoading = isLoading,
                onLoginBluesky = onLoginBluesky, onLogoutBluesky = onLogoutBluesky,
                extras = extras, liquidGlass = liquidGlass, tint = tint, backdrop = backdrop
            )

            PlatformFeatureGate(com.mediaviewer.platform.PlatformFeature.MUSIC_SCROBBLING) {
                RockskyScrobbleBubble(bskyLoggedIn = bskyLoggedIn, bskyHandle = bskyHandle, liquidGlass = liquidGlass, tint = tint, backdrop = backdrop)
            }

            if (com.mediaviewer.util.FeatureFlags.E621_ENABLED) {
                E621AccountBubble(
                    e621LoggedIn = e621LoggedIn, e621Username = e621Username,
                    onLoginE621 = onLoginE621, onLogoutE621 = onLogoutE621,
                    liquidGlass = liquidGlass, tint = tint, backdrop = backdrop
                )
            }

            // ── Live Link widget feature ────────────────────────────────────
            // Save a Twitch and/or YouTube channel URL here, then "Create
            // Widget" (enabled once at least one is saved) requests the
            // resizable home-screen widget be pinned. Gated behind
            // FeatureFlags.LIVE_LINK_ENABLED: unfinished, so hidden for now, but
            // left fully in place to resume from later.
            if (bskyLoggedIn && com.mediaviewer.util.FeatureFlags.LIVE_LINK_ENABLED) {
                var twitchField by remember(liveTwitchUrl) { mutableStateOf(liveTwitchUrl.orEmpty()) }
                var youtubeField by remember(liveYoutubeUrl) { mutableStateOf(liveYoutubeUrl.orEmpty()) }
                val linkColors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                    focusedBorderColor = tint, unfocusedBorderColor = DimGray,
                    cursorColor = tint, focusedLabelColor = tint, unfocusedLabelColor = DimGray
                )
                OutlinedTextField(value = twitchField, onValueChange = { twitchField = it },
                    label = { Text("Twitch channel URL", fontSize = 12.sp) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onSaveLiveTwitchUrl(twitchField) }),
                    colors = linkColors)
                OutlinedTextField(value = youtubeField, onValueChange = { youtubeField = it },
                    label = { Text("YouTube channel URL", fontSize = 12.sp) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onSaveLiveYoutubeUrl(youtubeField) }),
                    colors = linkColors)
                val widgetEnabled = twitchField.isNotBlank() || youtubeField.isNotBlank() ||
                    !liveTwitchUrl.isNullOrBlank() || !liveYoutubeUrl.isNullOrBlank()
                SettingsBubble(liquidGlass, tint, backdrop) {
                    BubbleRow {
                        RowLabel("Live Link", Modifier.weight(1f))
                        PillButton("Save", { onSaveLiveTwitchUrl(twitchField); onSaveLiveYoutubeUrl(youtubeField) })
                        Spacer(Modifier.width(6.dp))
                        PillButton("Widget", onCreateLiveLinkWidget, enabled = widgetEnabled)
                    }
                }
            }
        }

        // ── Media Tagging ───────────────────────────────────────────────
        CollapsibleSection("Media Tagging", tint) {

            // Export (beside any dataset, yours included): named first, then
            // saved wherever the person picks.
            var exportTarget by remember { mutableStateOf<Pair<String?, String>?>(null) } // (id, starting name)
            var pendingExport by remember { mutableStateOf<Pair<String?, String>?>(null) } // (id, chosen name)
            val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
                val p = pendingExport
                if (uri != null && p != null) onExportDataset(p.first, p.second, uri)
                pendingExport = null
            }
            val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) onImportDataset(uri)
            }
            // Delete (beside any dataset): asked first, like everywhere else.
            var deleteTarget by remember { mutableStateOf<com.mediaviewer.tagging.TagDatasetInfo?>(null) } // id "" = yours
            val ownName = extras.selfDisplayName.ifBlank { bskyHandle }
            val exportState = extras.datasetExportState
            val exportWorking = exportState is MainViewModel.DatasetExportState.Working

            @Composable
            fun DatasetRow(name: String, posts: Int, onExport: () -> Unit, onDelete: () -> Unit) {
                BubbleRow {
                    RowLabel(name, Modifier.weight(1f), sub = "$posts post${if (posts == 1) "" else "s"}")
                    Text(
                        "Export", color = if (exportWorking) DimGray else LocalSettingsAccent.current, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 6.dp).clickable(enabled = !exportWorking) { onExport() }
                    )
                    Text(
                        "Delete", color = DangerRed, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 8.dp).clickable(enabled = !taggingRunning) { onDelete() }
                    )
                }
            }

            PlatformFeatureGate(com.mediaviewer.platform.PlatformFeature.AI_TAGGING) {
                SettingsBubble(liquidGlass, tint, backdrop) {
                    BubbleRow {
                        RowLabel("Tag Datasets", Modifier.weight(1f))
                        PillButton("Import", { importLauncher.launch(arrayOf("application/json")) })
                        Spacer(Modifier.width(6.dp))
                        // Every dataset — yours, each imported one and the shared
                        // one — as a single file of all their posts and tags.
                        PillButton(
                            "Export All",
                            { exportTarget = com.mediaviewer.tagging.ALL_DATASETS_EXPORT_ID to "All Tag Datasets" },
                            enabled = !exportWorking && (ownDatasetCount > 0 || importedDatasets.isNotEmpty())
                        )
                    }
                    // Your own dataset first (what this device tagged, and tags
                    // added by hand), then every imported one — each with its
                    // own Export and Delete.
                    if (ownDatasetCount > 0) {
                        BubbleDivider()
                        DatasetRow(
                            "Your Dataset", ownDatasetCount,
                            onExport = { exportTarget = null to if (ownName.isBlank()) "My Tag Dataset" else "$ownName's Tag Dataset" },
                            onDelete = { deleteTarget = com.mediaviewer.tagging.TagDatasetInfo("", "Your Dataset", 0L, ownDatasetCount) }
                        )
                    }
                    importedDatasets.forEach { dataset ->
                        BubbleDivider()
                        DatasetRow(
                            dataset.name, dataset.postCount,
                            onExport = { exportTarget = dataset.id to dataset.name },
                            onDelete = { deleteTarget = dataset }
                        )
                    }
                    AnimatedVisibility(visible = exportState !is MainViewModel.DatasetExportState.Idle) {
                        when (exportState) {
                            is MainViewModel.DatasetExportState.Working ->
                                ExportStatusLine(true, exportState.stage, success = false, tint = tint)
                            is MainViewModel.DatasetExportState.Done ->
                                ExportStatusLine(false, "Exported ${exportState.postCount} post${if (exportState.postCount == 1) "" else "s"} — saved to the file you picked.", success = true, tint = tint)
                            is MainViewModel.DatasetExportState.Failed ->
                                ExportStatusLine(false, "Export failed: ${exportState.message}", success = false, tint = tint)
                            MainViewModel.DatasetExportState.Idle -> Spacer(Modifier.height(0.dp))
                        }
                    }
                }
            }

            exportTarget?.let { (id, startName) ->
                ExportDatasetNameDialog(
                    liquidGlass = liquidGlass, dominantColor = tint, backdrop = backdrop,
                    initialName = startName,
                    onConfirm = { name ->
                        val chosen = name.ifBlank { startName }
                        pendingExport = id to chosen
                        exportTarget = null
                        val fileSafeName = chosen.replace(Regex("[^A-Za-z0-9 _'-]"), "").ifBlank { "dataset" }
                        exportLauncher.launch("$fileSafeName.json")
                    },
                    onDismiss = { exportTarget = null }
                )
            }
            deleteTarget?.let { target ->
                androidx.compose.ui.window.Dialog(onDismissRequest = { deleteTarget = null }, properties = com.mediaviewer.ui.compat.edgeToEdgeDialogProperties()) {
                    Box(Modifier.fillMaxSize()) {
                        ConfirmPopup(
                            title = if (target.id.isEmpty()) "Delete Your Dataset?" else "Delete \"${target.name}\"?",
                            message = if (target.id.isEmpty()) "Every tag this device made, and every tag you added by hand, will be deleted. Imported datasets stay."
                                else "Its ${target.postCount} post${if (target.postCount == 1) "" else "s"} and their tags will be removed from this device. This can't be undone.",
                            confirmLabel = "Delete",
                            liquidGlass = liquidGlass, tint = tint, backdrop = null,
                            onConfirm = {
                                if (target.id.isEmpty()) onDeleteTaggedDatabase() else onDeleteImportedDataset(target.id)
                                deleteTarget = null
                            },
                            onDismiss = { deleteTarget = null }
                        )
                    }
                }
            }

            // The on-device model. Datasets and hand-added tags don't need
            // it: downloading it only brings in the AI's own options below.
            PlatformFeatureGate(com.mediaviewer.platform.PlatformFeature.AI_TAGGING) {
                SettingsBubble(liquidGlass, tint, backdrop) {
                    BubbleRow {
                        RowLabel("Download On-Device Tagging Model", Modifier.weight(1f))
                        PillButton(
                            label = when {
                                extras.taggerModelReady -> "Downloaded"
                                extras.taggerModelDownloading -> "Downloading…"
                                else -> "Download"
                            },
                            onClick = extras.onDownloadTaggerModel,
                            enabled = !extras.taggerModelReady && !extras.taggerModelDownloading
                        )
                    }
                    AnimatedVisibility(visible = extras.taggerModelReady) {
                        Column(Modifier.fillMaxWidth()) {
                            BubbleDivider()
                            BubbleRow {
                                RowLabel("Tag Media When Liked", Modifier.weight(1f))
                                CompactSwitch(tagPostWhenLiked, onToggleTagPostWhenLiked)
                            }
                            BubbleDivider()
                            BubbleRow {
                                RowLabel("Tag Media When Viewed", Modifier.weight(1f))
                                CompactSwitch(com.mediaviewer.util.UiToggles.tagWhenViewed) { com.mediaviewer.util.UiToggles.updateTagWhenViewed(it) }
                            }
                            BubbleDivider()
                            BubbleRow {
                                RowLabel(
                                    "Start Tagging", Modifier.weight(1f),
                                    sub = when {
                                        taggingRunning -> "$taggingScanned scanned"
                                        taggingScanned > 0 -> "$taggingTagged tagged"
                                        else -> null
                                    }
                                )
                                PillButton(
                                    if (taggingRunning) "…" else "Liked Media", onLocallyTagAllLiked,
                                    enabled = !taggingRunning && anyLoggedIn
                                )
                                Spacer(Modifier.width(6.dp))
                                PillButton(
                                    if (taggingRunning) "…" else "Viewed Media", extras.onStartTaggingViewed,
                                    enabled = !taggingRunning && bskyLoggedIn
                                )
                            }
                            if (tagPostWhenLiked || com.mediaviewer.util.UiToggles.tagWhenViewed) {
                                BubbleDivider()
                                BubbleRow {
                                    RowLabel("Show Tagging Status", Modifier.weight(1f))
                                    CompactSwitch(com.mediaviewer.util.UiToggles.showTaggingStatus) {
                                        com.mediaviewer.util.UiToggles.updateShowTaggingStatus(it)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // ── Data and Privacy ────────────────────────────────────────────
        CollapsibleSection("Data and Privacy", tint) {
            // First in the section: the update check (it used to be the last
            // bubble of App Functionality) — or, where this version doesn't
            // check for updates (the web), a bubble naming the version.
            if (com.mediaviewer.util.PlatformUpdater.checks) UpdatesBubble(liquidGlass, tint, backdrop)
            else VersionBubble(liquidGlass, tint, backdrop)
            if (bskyLoggedIn) PrivateSyncBubble(liquidGlass, tint, backdrop)
            if (bskyLoggedIn) {
                ActionBubble(
                    label = "Blocked Accounts",
                    buttonLabel = "View",
                    onClick = extras.onOpenBlockedAccounts,
                    liquidGlass = liquidGlass, tint = tint, backdrop = backdrop
                )
            }
            if (anyLoggedIn) {
                if (bskyLoggedIn) {
                    // Runs the follower scan from scratch — for picking up accounts
                    // that started posting reviews/blogs after the last scan, or
                    // that were skipped. Opening a profile already auto-subscribes
                    // it if it has any, so this is only for accounts never visited.
                    val scanning = followerScanState is MainViewModel.FollowerScanState.Scanning
                    ActionBubble(
                        label = if (scanning) "Scanning Who You Follow…" else "Scan Following for Blogs/Reviews",
                        buttonLabel = if (scanning) "…" else "Scan",
                        onClick = onRescanFollowersFromScratch,
                        liquidGlass = liquidGlass, tint = tint, backdrop = backdrop, enabled = !scanning
                    )
                }
                // The one auto-download switch covers both services (it's a
                // single shared preference) — new likes/saves get downloaded as
                // they happen, on top of the one-time buttons above.
                ToggleBubble("Auto-Download New Likes and Saves", downloadOnLike, onToggleDownloadOnLike, liquidGlass, tint, backdrop)
                if (bskyLoggedIn) {
                    val prog = downloadProgress.takeIf { !extras.downloadIsE621 }
                    val running = prog?.isRunning == true
                    ActionBubble(
                        label = "Download All Liked AT Protocol Media",
                        sub = when {
                            running -> "${prog?.count ?: 0} queued"
                            prog != null && prog.count > 0 -> "Done — ${prog.count} queued"
                            else -> null
                        },
                        buttonLabel = if (running) "Cancel" else "Download",
                        onClick = { if (running) onCancelDownload() else onDownloadAllLiked() },
                        liquidGlass = liquidGlass, tint = tint, backdrop = backdrop,
                        enabled = running || downloadProgress?.isRunning != true
                    )
                    // Every custom emoji of yours, as the files themselves,
                    // in one .zip: "All", then a folder for each of yours.
                    val emojiContext = com.mediaviewer.ui.compat.LocalContext.current
                    val emojiScope = rememberCoroutineScope()
                    var emojiZipStatus by remember { mutableStateOf<String?>(null) }
                    val emojiZipLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
                        val did = SelfProfileColors.did
                        if (uri != null && !did.isNullOrBlank()) {
                            emojiZipStatus = "Packing your emoji…"
                            emojiScope.launch {
                                val result = runCatching {
                                    val zip = kotlinx.coroutines.withContext(com.mediaviewer.platform.ioDispatcher) {
                                        com.mediaviewer.util.EmojiCloud.exportZip(com.mediaviewer.util.EmojiStore.get(emojiContext), did)
                                    } ?: error("You don't have any custom emoji yet")
                                    com.mediaviewer.platform.writeBytesToPickedFile(emojiContext, uri, zip)
                                }
                                emojiZipStatus = result.fold({ "Saved" }, { "Couldn't save them: ${it.message}" })
                                kotlinx.coroutines.delay(5000)
                                emojiZipStatus = null
                            }
                        }
                    }
                    ActionBubble(
                        label = "Download Custom Emojis",
                        sub = emojiZipStatus,
                        buttonLabel = if (emojiZipStatus == "Packing your emoji…") "…" else "Download",
                        onClick = { emojiZipLauncher.launch("Stellar-emojis.zip") },
                        liquidGlass = liquidGlass, tint = tint, backdrop = backdrop,
                        enabled = emojiZipStatus != "Packing your emoji…"
                    )
                }
                if (e621LoggedIn) {
                    val prog = downloadProgress.takeIf { extras.downloadIsE621 }
                    val running = prog?.isRunning == true
                    ActionBubble(
                        label = "Download All Saved e621 Media",
                        sub = when {
                            running -> "${prog?.count ?: 0} queued"
                            prog != null && prog.count > 0 -> "Done — ${prog.count} queued"
                            else -> null
                        },
                        buttonLabel = if (running) "Cancel" else "Download",
                        onClick = { if (running) onCancelDownload() else extras.onDownloadAllE621Saved() },
                        liquidGlass = liquidGlass, tint = tint, backdrop = backdrop,
                        enabled = running || downloadProgress?.isRunning != true
                    )
                }
            }

            // Item 7: everything local in one file — settings, VRM + Live
            // settings, the main AI-tagged dataset, Blog/Review subscriptions.
            // Importing replaces them (the tagged dataset becomes the main one)
            // and restarts the app so every screen reloads with them.
            val backupContext = com.mediaviewer.ui.compat.LocalContext.current
            val backupScope = rememberCoroutineScope()
            var backupBusy by remember { mutableStateOf(false) }
            // What the Export/Import rows show underneath while (and just after)
            // a backup file is written or read: null = nothing.
            var backupStatus by remember { mutableStateOf<Triple<Boolean, String, Boolean>?>(null) } // (working, message, success)
            LaunchedEffect(backupStatus) {
                val st = backupStatus
                if (st != null && !st.first) { kotlinx.coroutines.delay(6000); if (backupStatus == st) backupStatus = null }
            }
            var confirmingImport by remember { mutableStateOf(false) }
            LaunchedEffect(confirmingImport) {
                if (confirmingImport) { kotlinx.coroutines.delay(4000); confirmingImport = false }
            }
            val backupExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
                if (uri != null) {
                    backupBusy = true
                    backupStatus = Triple(true, "Exporting app data…", false)
                    backupScope.launch {
                        val result = runCatching { com.mediaviewer.util.AppBackup.export(backupContext, uri) }
                        backupBusy = false
                        val msg = result.getOrElse { "Export failed: ${it.message}" }
                        backupStatus = Triple(false, if (result.isSuccess) "Export complete — $msg" else msg, result.isSuccess)
                        com.mediaviewer.ui.compat.Toast.makeText(backupContext, msg, com.mediaviewer.ui.compat.Toast.LENGTH_LONG).show()
                    }
                }
            }
            val backupImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) {
                    backupBusy = true
                    backupStatus = Triple(true, "Importing app data…", false)
                    backupScope.launch {
                        val result = runCatching { com.mediaviewer.util.AppBackup.import(backupContext, uri) }
                        backupBusy = false
                        backupStatus = Triple(false, result.fold({ "Imported — restarting…" }, { "Import failed: ${it.message}" }), result.isSuccess)
                        result.onSuccess { msg ->
                            com.mediaviewer.ui.compat.Toast.makeText(backupContext, "$msg — restarting…", com.mediaviewer.ui.compat.Toast.LENGTH_LONG).show()
                            kotlinx.coroutines.delay(900)
                            com.mediaviewer.ui.compat.restartApp(backupContext)
                        }.onFailure {
                            com.mediaviewer.ui.compat.Toast.makeText(backupContext, "Import failed: ${it.message}", com.mediaviewer.ui.compat.Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            SettingsBubble(liquidGlass, tint, backdrop) {
                BubbleRow {
                    RowLabel("Export App Data", Modifier.weight(1f))
                    PillButton(if (backupBusy) "Working…" else "Export", {
                        val date = com.mediaviewer.util.DateText.format(com.mediaviewer.platform.currentTimeMillis(), "yyyy-MM-dd")
                        backupExportLauncher.launch("Stellar-backup-$date.json")
                    }, enabled = !backupBusy)
                }
                AnimatedVisibility(visible = backupStatus != null) {
                    val st = backupStatus
                    if (st != null) ExportStatusLine(st.first, st.second, st.third, tint)
                    else Spacer(Modifier.height(0.dp))
                }
                BubbleDivider()
                BubbleRow {
                    RowLabel("Import App Data", Modifier.weight(1f))
                    PillButton(
                        if (backupBusy) "…" else if (confirmingImport) "Replace?" else "Import",
                        {
                            if (confirmingImport) { confirmingImport = false; backupImportLauncher.launch(arrayOf("application/json", "*/*")) }
                            else confirmingImport = true
                        },
                        enabled = !backupBusy, color = if (confirmingImport) DangerRed else Color.White
                    )
                }
            }
        }

        // ── Dev Tools (hidden: hold the "Settings" tab for 10 seconds) ─
        if (com.mediaviewer.util.UiToggles.devToolsUnlocked) {
            CollapsibleSection("Dev Tools", tint) {
                // The local-data ownership check's latest word (see
                // LocalData.resolveLegacyData): what it found on this
                // device and what it did. A developer diagnostic — it
                // lives here, not with the everyday sync settings.
                SettingsBubble(liquidGlass, tint, backdrop) {
                    BubbleRow {
                        RowLabel("Data Ownership Check", Modifier.weight(1f))
                    }
                    Text(
                        com.mediaviewer.util.LocalData.legacyReport ?: "Not checked yet this session",
                        color = Color.White.copy(alpha = 0.55f), fontSize = 11.sp,
                        modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 10.dp)
                    )
                }
                // Battery Saver (experimental): flat buttons instead of live
                // blur, no starfield or visualizer, a 60 Hz cap and slower
                // background checks.
                SettingsBubble(liquidGlass, tint, backdrop) {
                    BubbleRow {
                        RowLabel(
                            "Battery Saver", Modifier.weight(1f),
                        )
                        CompactSwitch(com.mediaviewer.util.LocalData.batterySaver) { com.mediaviewer.util.LocalData.updateBatterySaver(it) }
                    }
                }
                // How a profile's top is laid out in portrait (see
                // UiToggles.profileLayout).
                SettingsBubble(liquidGlass, tint, backdrop) {
                    var layoutMenu by remember { mutableStateOf(false) }
                    val currentLayout = com.mediaviewer.util.UiToggles.profileLayout
                    BubbleRow {
                        RowLabel("Profile Layout", Modifier.weight(1f))
                        Box {
                            Text(
                                "Layout $currentLayout",
                                color = LocalSettingsAccent.current, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.clickable { layoutMenu = true }
                            )
                            DropdownMenu(expanded = layoutMenu, onDismissRequest = { layoutMenu = false }) {
                                (1..com.mediaviewer.util.UiToggles.PROFILE_LAYOUTS).forEach { option ->
                                    DropdownMenuItem(
                                        text = { Text("Layout $option", fontWeight = if (option == currentLayout) FontWeight.SemiBold else FontWeight.Normal) },
                                        onClick = { com.mediaviewer.util.UiToggles.updateProfileLayout(option); layoutMenu = false }
                                    )
                                }
                            }
                        }
                    }
                }
                // Where Layout 4's status floats (see UiToggles.statusPosition).
                SettingsBubble(liquidGlass, tint, backdrop) {
                    var positionMenu by remember { mutableStateOf(false) }
                    val currentPosition = com.mediaviewer.util.UiToggles.statusPosition
                    BubbleRow {
                        RowLabel("Status Position", Modifier.weight(1f))
                        Box {
                            Text(
                                "Position $currentPosition",
                                color = LocalSettingsAccent.current, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.clickable { positionMenu = true }
                            )
                            DropdownMenu(expanded = positionMenu, onDismissRequest = { positionMenu = false }) {
                                (1..com.mediaviewer.util.UiToggles.STATUS_POSITIONS).forEach { option ->
                                    DropdownMenuItem(
                                        text = { Text("Position $option", fontWeight = if (option == currentPosition) FontWeight.SemiBold else FontWeight.Normal) },
                                        onClick = { com.mediaviewer.util.UiToggles.updateStatusPosition(option); positionMenu = false }
                                    )
                                }
                            }
                        }
                    }
                }
                // Frame rate beside the camera cutout — see DebugOverlay.
                ToggleBubble(
                    "FPS Overlay", com.mediaviewer.util.UiToggles.debugOverlay,
                    { com.mediaviewer.util.UiToggles.updateDebugOverlay(it) }, liquidGlass, tint, backdrop
                )
                // Glass Theme + its Background/Outline dials + the highlight toggle
                // share one bubble; none of the rows inside draws its own outline.
                SettingsBubble(liquidGlass, tint, backdrop) {
                    BubbleRow {
                        RowLabel("Glass Theme", Modifier.weight(1f))
                        CompactSwitch(liquidGlass, onToggleLiquidGlass)
                    }
                    if (liquidGlass) {
                        BubbleDivider()
                        BubbleRow {
                            // widthIn(min=) rather than a fixed width: the app's
                            // custom font is wider, and a fixed width wrapped
                            // "Background" onto two lines.
                            Text("Background", color = Color.White, fontSize = 13.sp, maxLines = 1, softWrap = false,
                                modifier = Modifier.widthIn(min = 74.dp))
                            CompactSlider(liquidGlassIntensity, onSetLiquidGlassIntensity, Modifier.weight(1f).padding(horizontal = 10.dp))
                            Text("${(liquidGlassIntensity * 100).toInt()}%", color = DimGray, fontSize = 12.sp,
                                modifier = Modifier.width(34.dp), textAlign = TextAlign.End)
                        }
                        BubbleDivider()
                        BubbleRow {
                            Text("Outline", color = Color.White, fontSize = 13.sp, maxLines = 1, softWrap = false,
                                modifier = Modifier.widthIn(min = 74.dp))
                            CompactSlider(glassRimIntensity, onSetGlassRimIntensity, Modifier.weight(1f).padding(horizontal = 10.dp))
                            Text("${(glassRimIntensity * 100).toInt()}%", color = DimGray, fontSize = 12.sp,
                                modifier = Modifier.width(34.dp), textAlign = TextAlign.End)
                        }
                        BubbleDivider()
                        BubbleRow {
                            RowLabel("Vibrant Outline Highlight", Modifier.weight(1f))
                            CompactSwitch(glassRimVibrantSecondary, onToggleGlassRimVibrantSecondary)
                        }
                    }
                }
                SettingsBubble(liquidGlass, tint, backdrop) {
                    BubbleRow {
                        RowLabel("Show Scan Bubble in Hub", Modifier.weight(1f))
                        CompactSwitch(com.mediaviewer.util.UiToggles.devForceScanBubble) { com.mediaviewer.util.UiToggles.updateDevForceScanBubble(it) }
                    }
                    BubbleDivider()
                    BubbleRow {
                        RowLabel("Preview Loading Animation", Modifier.weight(1f))
                        PillButton("Play", { com.mediaviewer.util.UiToggles.devLoadingPreview = true })
                    }
                    BubbleDivider()
                    BubbleRow {
                        RowLabel("Preview Login Page", Modifier.weight(1f))
                        PillButton("Open", { com.mediaviewer.util.UiToggles.devLoginPreview = true })
                    }
                    BubbleDivider()
                    BubbleRow {
                        RowLabel("Force Refresh Hub", Modifier.weight(1f))
                        PillButton("Refresh", extras.onForceRefreshHub, enabled = bskyLoggedIn)
                    }
                    BubbleDivider()
                    BubbleRow {
                        RowLabel("Preview Welcome Popup", Modifier.weight(1f))
                        PillButton("Open", extras.onPreviewWelcome, enabled = bskyLoggedIn)
                    }
                    BubbleDivider()
                    BubbleRow {
                        RowLabel("Preview Support Popup", Modifier.weight(1f))
                        PillButton("Open", { com.mediaviewer.util.UiToggles.devSupportPreview = true })
                    }
                    BubbleDivider()
                    val tipContext = com.mediaviewer.ui.compat.LocalContext.current
                    BubbleRow {
                        RowLabel("Reset Tips", Modifier.weight(1f))
                        PillButton("Reset", {
                            com.mediaviewer.ui.Tips.reset()
                            com.mediaviewer.ui.compat.Toast.makeText(tipContext, "Tips reset", com.mediaviewer.ui.compat.Toast.LENGTH_SHORT).show()
                        })
                    }
                    BubbleDivider()
                    val coverContext = com.mediaviewer.ui.compat.LocalContext.current
                    val coverScope = rememberCoroutineScope()
                    BubbleRow {
                        RowLabel("Clear Cached Title Covers", Modifier.weight(1f))
                        PillButton("Clear", {
                            coverScope.launch {
                                val n = kotlinx.coroutines.withContext(com.mediaviewer.platform.ioDispatcher) {
                                    com.mediaviewer.repository.WikipediaRepository.clearCoverCache()
                                    com.mediaviewer.util.TitleCovers.clearCached(coverContext)
                                }
                                com.mediaviewer.ui.compat.Toast.makeText(coverContext, "Cleared $n cached title cover${if (n == 1) "" else "s"}", com.mediaviewer.ui.compat.Toast.LENGTH_SHORT).show()
                            }
                        }, color = DangerRed)
                    }
                    BubbleDivider()
                    BubbleRow {
                        RowLabel("Hide Dev Tools", Modifier.weight(1f))
                        PillButton("Hide", { com.mediaviewer.util.UiToggles.updateDevToolsUnlocked(false) }, color = DangerRed)
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
    }
    }
}

/** How wide the Settings page gets in landscape (two columns of sections). */
val SETTINGS_LANDSCAPE_WIDTH = 1240.dp

/**
 * The Settings page's sections, one under another in portrait. In
 * landscape they go into two columns side by side, taking turns (first
 * left, second right, …) so opening or folding one never moves the others
 * to the other column. Sections that show nothing take no turn.
 */
@Composable
private fun SettingsColumns(modifier: Modifier, content: @Composable () -> Unit) {
    if (!rememberLandscape()) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
        return
    }
    androidx.compose.ui.layout.Layout(content, modifier) { measurables, constraints ->
        val gap = 8.dp.roundToPx()
        val colGap = 20.dp.roundToPx()
        val colW = ((constraints.maxWidth - colGap) / 2).coerceAtLeast(0)
        val placeables = measurables.map { it.measure(androidx.compose.ui.unit.Constraints(maxWidth = colW)) }
        val heights = IntArray(2)
        var turn = 0
        val spots = placeables.map { p ->
            if (p.height == 0) return@map 0 to heights[0]
            val col = turn % 2
            turn++
            val y = heights[col] + if (heights[col] > 0) gap else 0
            heights[col] = y + p.height
            col to y
        }
        layout(constraints.maxWidth, maxOf(heights[0], heights[1])) {
            placeables.forEachIndexed { i, p ->
                val (col, y) = spots[i]
                p.place(if (col == 0) 0 else colW + colGap, y)
            }
        }
    }
}

/** The AT Protocol accounts bubble: the active account, every other signed-in
 *  account (each with Switch To / Log out), and an "Add additional AT
 *  Protocol account" row that turns into a handle + app password + Login row
 *  when tapped — all in one bubble that grows downward. */
@Composable
private fun AtProtocolAccountsBubble(
    bskyLoggedIn: Boolean, bskyHandle: String, isLoading: Boolean,
    onLoginBluesky: (String, String) -> Unit, onLogoutBluesky: () -> Unit,
    extras: SettingsExtras, liquidGlass: Boolean, tint: Color, backdrop: GlassBackdrop?
) {
    // Used for the first sign-in when nobody's logged in yet, and for adding
    // another account afterwards — one set of fields, two destinations.
    var adding by remember { mutableStateOf(false) }
    var handleField by remember { mutableStateOf("") }
    var passwordField by remember { mutableStateOf("") }
    var addBusy by remember { mutableStateOf(false) }
    var addError by remember { mutableStateOf<String?>(null) }

    fun cancelAdd() { adding = false; handleField = ""; passwordField = ""; addError = null }

    // Back closes an open add row instead of leaving the page.
    BackHandler(enabled = adding && bskyLoggedIn) { cancelAdd() }

    fun submit() {
        val id = handleField.trim()
        if (id.isBlank() || passwordField.isBlank() || addBusy) return
        if (!bskyLoggedIn) {
            onLoginBluesky(id, passwordField)
            return
        }
        addBusy = true
        addError = null
        extras.onAddBskyAccount(id, passwordField) { error ->
            addBusy = false
            if (error == null) {
                // Back to the normal "Add" row; the new account's own row
                // appears above it.
                adding = false; handleField = ""; passwordField = ""
            } else addError = error
        }
    }

    @Composable
    fun LoginFieldsRow(buttonLabel: String, busy: Boolean, onCancel: (() -> Unit)? = null) {
        BubbleRow {
            CompactField(handleField, { handleField = it }, "handle", Modifier.weight(1f))
            Spacer(Modifier.width(6.dp))
            CompactField(
                passwordField, { passwordField = it }, "password", Modifier.weight(1f),
                password = true, imeAction = ImeAction.Done, onDone = { submit() }
            )
            Spacer(Modifier.width(6.dp))
            PillButton(
                if (busy) "…" else buttonLabel, { submit() },
                enabled = !busy && handleField.isNotBlank() && passwordField.isNotBlank()
            )
            if (onCancel != null) {
                Spacer(Modifier.width(6.dp))
                CancelXButton(onCancel)
            }
        }
    }

    SettingsBubble(liquidGlass, tint, backdrop) {
        if (!bskyLoggedIn) {
            BubbleRow { RowLabel("Not logged in to the AT Protocol", Modifier.weight(1f), dim = true) }
            BubbleDivider()
            LoginFieldsRow("Login", isLoading)
            return@SettingsBubble
        }

        // The active account.
        BubbleRow {
            Text(
                buildAnnotatedString {
                    append("Logged in to the AT Protocol as ")
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append("@$bskyHandle") }
                },
                color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f).padding(end = 12.dp)
            )
            PillButton("Log out", onLogoutBluesky, enabled = !extras.accountSwitching, color = DangerRed)
        }

        // Every other signed-in account, sitting between the active account
        // and the add row.
        extras.otherBskyAccounts.forEach { account ->
            BubbleDivider()
            BubbleRow {
                Text(
                    "@${account.handle}", color = Color.White, fontSize = 14.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(end = 8.dp)
                )
                PillButton(
                    "Switch To", { extras.onSwitchBskyAccount(account.did) },
                    enabled = !extras.accountSwitching
                )
                Spacer(Modifier.width(6.dp))
                PillButton(
                    "Log out", { extras.onRemoveBskyAccount(account.did) },
                    enabled = !extras.accountSwitching, color = DangerRed
                )
            }
        }

        BubbleDivider()
        if (adding) {
            LoginFieldsRow("Login", addBusy, onCancel = { cancelAdd() })
            val error = addError
            if (error != null) {
                Text(
                    error, color = DangerRed, fontSize = 11.sp, lineHeight = 13.sp,
                    modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 6.dp)
                )
            }
        } else {
            BubbleRow {
                RowLabel("Add additional AT Protocol account", Modifier.weight(1f))
                PillButton("Add", { adding = true; addError = null })
            }
        }

        if (extras.otherBskyAccounts.isNotEmpty()) {
            BubbleDivider()
            BubbleRow {
                RowLabel("Show \"Switch Accounts\" row in the Hub", Modifier.weight(1f))
                CompactSwitch(extras.showSwitchAccountsRow, extras.onToggleShowSwitchAccountsRow)
            }
        }
    }
}

// ── e621 account bubble ─────────────────────────────────────────────────────

/** Settings → Integrations → "Scrobble Music to Rocksky" (a supporter
 *  feature): Stellar watches what the chosen music apps are playing, shows
 *  it as the official "Listening to" status on Rocksky straight away, and
 *  writes each listen to the signed-in account as Rocksky records. A
 *  "Settings" button to the left of the switch opens Scrobble Settings:
 *  when a listen counts, and which apps count — none do until the user
 *  turns them on.
 *
 *  Turning the switch on walks through what Android needs: first the
 *  permission to show the quiet "Stellar scrobbling" notification (Android 13
 *  and later), then the phone's "notification access" page, which is the
 *  permission that lets an app see what others are playing.
 *
 *  While it's on (or while an import is under way) the bubble grows a
 *  second part: importing a Spotify / YouTube history file, the files being
 *  imported with how far along each is, and "Work in Background". */
@Composable
private fun RockskyScrobbleBubble(bskyLoggedIn: Boolean, bskyHandle: String, liquidGlass: Boolean, tint: Color, backdrop: GlassBackdrop?) {
    val context = com.mediaviewer.ui.compat.LocalContext.current
    val scope = rememberCoroutineScope()
    val platform = com.mediaviewer.platform.LocalPlatform
    var status by remember { mutableStateOf(com.mediaviewer.util.ScrobblerStatus()) }
    var settingsOpen by remember { mutableStateOf(false) }

    suspend fun refresh() {
        status = kotlinx.coroutines.withContext(com.mediaviewer.platform.ioDispatcher) { platform.scrobblerStatus(context) }
    }
    suspend fun signedInDid(): String = kotlinx.coroutines.withContext(com.mediaviewer.platform.ioDispatcher) {
        com.mediaviewer.util.PreferencesManager(context).bskyDid.first().orEmpty()
    }
    // The permissions are granted on system pages outside Stellar, and an
    // import moves along by itself, so the row keeps checking while it's on
    // screen.
    LaunchedEffect(Unit) {
        while (true) { refresh(); kotlinx.coroutines.delay(1500) }
    }
    // Scrobbles belong to one account: after switching accounts the
    // scrobbler follows the one that is signed in now.
    LaunchedEffect(bskyLoggedIn, bskyHandle) {
        if (!bskyLoggedIn) return@LaunchedEffect
        val did = signedInDid()
        refresh()
        if (did.isNotBlank() && status.enabled) platform.setScrobblerEnabled(context, true, did)
    }

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
        // Whatever the answer, the next step is notification access:
        // scrobbling works without the notification, just less reliably.
        if (!platform.scrobblerStatus(context).access) platform.openScrobblerAccessSettings(context)
    }
    val backgroundPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
        // Then the phone is asked not to pause Stellar to save battery.
        if (platform.scrobblerStatus(context).importBatteryRestricted) platform.requestScrobbleBatteryExemption(context)
    }
    val historyPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val did = signedInDid()
            if (did.isNotBlank()) platform.importScrobbleHistory(context, uri, did)
            refresh()
        }
    }

    fun turnOn() {
        scope.launch {
            val did = signedInDid()
            if (did.isBlank()) return@launch
            platform.setScrobblerEnabled(context, true, did)
            refresh()
            when {
                status.needsNotificationPermission -> notificationPermission.launch("android.permission.POST_NOTIFICATIONS")
                !status.access -> platform.openScrobblerAccessSettings(context)
            }
        }
    }

    val on = status.enabled && bskyLoggedIn
    val sub = when {
        !bskyLoggedIn -> "Log in to Bluesky to scrobble."
        !on -> null
        !status.access -> "Needs notification access. Tap here to open it. If the switch there is grayed out, open Stellar's App info, tap the three dots, and choose \"Allow restricted settings\" first."
        status.error != null -> status.error
        status.queued > 0 -> "${status.queued} waiting to upload"
        else -> null
    }


    SettingsBubble(liquidGlass, tint, backdrop) {
        BubbleRow {
            RowLabel(
                "Scrobble Music to Rocksky",
                Modifier.weight(1f).then(
                    if (on && !status.access) Modifier.clickable { platform.openScrobblerAccessSettings(context) } else Modifier
                ),
                sub = sub
            )
            Spacer(Modifier.width(8.dp))
            PillButton("Settings", {
                if (com.mediaviewer.util.Supporter.active) settingsOpen = true else com.mediaviewer.util.Supporter.openPage()
            })
            Spacer(Modifier.width(8.dp))
            SupporterSwitch(on) { want ->
                if (want) turnOn()
                else { platform.setScrobblerEnabled(context, false, ""); scope.launch { refresh() } }
            }
        }
        // (Its own thing: importing doesn't need scrobbling switched on.)
        if (bskyLoggedIn) {
            BubbleDivider()
            BubbleRow {
                RowLabel(
                    "Import YouTube/Spotify music history to Rocksky", Modifier.weight(1f),
                    sub = if (status.importReading) "Reading the file…"
                    else status.importMessage
                )
                Spacer(Modifier.width(8.dp))
                PillButton("Import", {
                    if (!com.mediaviewer.util.Supporter.active) com.mediaviewer.util.Supporter.openPage()
                    // (Any file type: phones disagree on what a .zip or .json "is".)
                    else historyPicker.launch(arrayOf("*/*"))
                }, enabled = !status.importReading)
            }
            // Every imported file, oldest (the one being worked on) first.
            status.imports.forEach { file ->
                val finished = file.done + file.failed >= file.total
                BubbleDivider()
                BubbleRow {
                    RowLabel(
                        file.name, Modifier.weight(1f),
                        sub = when {
                            file.failed > 0 -> "${file.failed} couldn't be sent"
                            finished -> "Finished"
                            else -> null
                        }
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("(${file.done}/${file.total})", color = DimGray, fontSize = 12.sp, maxLines = 1, softWrap = false)
                    Spacer(Modifier.width(8.dp))
                    // Cancelling asks "Really?" first (a tap elsewhere puts it back).
                    val armKey = "import:" + file.id
                    PillButton(
                        when {
                            finished -> "Clear"
                            ReallyPrompt.isArmed(armKey) -> "Really?"
                            else -> "Cancel"
                        },
                        {
                            val cancel = { platform.cancelScrobbleImport(context, file.id); scope.launch { refresh() }; Unit }
                            if (finished) cancel() else ReallyPrompt.tap(armKey, cancel)
                        },
                        modifier = Modifier.reallyTarget(armKey)
                    )
                }
            }
            if (status.imports.isNotEmpty()) {
                BubbleDivider()
                BubbleRow {
                    RowLabel(
                        "Work in Background",
                        Modifier.weight(1f).then(
                            if (status.importBackground && status.importBatteryRestricted) Modifier.clickable { platform.requestScrobbleBatteryExemption(context) }
                            else Modifier
                        ),
                        sub = when {
                            !status.importBackground -> null
                            status.importBatteryRestricted -> "On, but the phone may still pause it to save battery. Tap here to allow Stellar to keep running."
                            else -> null
                        }
                    )
                    CompactSwitch(status.importBackground) { want ->
                        platform.setScrobbleImportBackground(context, want)
                        scope.launch {
                            refresh()
                            if (want) when {
                                status.needsNotificationPermission -> backgroundPermission.launch("android.permission.POST_NOTIFICATIONS")
                                status.importBatteryRestricted -> platform.requestScrobbleBatteryExemption(context)
                            }
                        }
                    }
                }
            }
        }
    }

    if (settingsOpen) {
        ScrobbleSettingsDialog(
            status = status, tint = tint,
            onThreshold = { percent, seconds -> platform.setScrobblerThreshold(context, percent, seconds); scope.launch { refresh() } },
            onToggle = { pkg, want -> platform.setScrobblerApp(context, pkg, want); scope.launch { refresh() } },
            onClose = { settingsOpen = false }
        )
    }
}

/** "4:00" for 240 seconds. */
private fun scrobbleTimeText(seconds: Int): String = "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"

/** Seconds for "4:00", "4.00" or "4" (minutes); null for anything else. */
private fun parseScrobbleTime(text: String): Int? {
    val parts = text.trim().split(':', '.')
    val minutes = parts.getOrNull(0)?.toIntOrNull() ?: return null
    val seconds = if (parts.size > 1) parts[1].toIntOrNull() ?: return null else 0
    if (parts.size > 2 || minutes < 0 || seconds !in 0..59) return null
    return (minutes * 60 + seconds).takeIf { it in 1..3600 }
}

/** One of the two small boxes in "Scrobble after (…)% or (…) of the track":
 *  tap it and type. */
@Composable
private fun ScrobbleNumberField(value: String, width: androidx.compose.ui.unit.Dp, valid: Boolean, onChange: (String) -> Unit) {
    androidx.compose.foundation.text.BasicTextField(
        value = value, onValueChange = onChange, singleLine = true,
        textStyle = LocalTextStyle.current.copy(color = if (valid) Color.White else Color(0xFFFF6B8A), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center),
        cursorBrush = SolidColor(Color.White),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
        modifier = Modifier.width(width),
        decorationBox = { inner ->
            Box(
                Modifier.fillMaxWidth().height(30.dp).clip(RoundedCornerShape(8.dp)).background(Color.White.copy(0.10f)).padding(horizontal = 6.dp),
                contentAlignment = Alignment.Center
            ) { inner() }
        }
    )
}

/** Scrobble Settings — the same centred, blurred-behind popup as Blocked
 *  Accounts: a solid panel in the page's color with a close button. On top,
 *  when a listen counts ("Scrobble after 50% or 4:00 of the track",
 *  whichever comes first — Rocksky's own rule and starting values, both
 *  editable); under it, one switch per music app. The well-known apps come
 *  first, in a fixed order, followed by any other app Stellar has noticed
 *  playing music on this phone. */
@Composable
private fun ScrobbleSettingsDialog(
    status: com.mediaviewer.util.ScrobblerStatus, tint: Color,
    onThreshold: (Int, Int) -> Unit, onToggle: (String, Boolean) -> Unit, onClose: () -> Unit
) {
    val tap = rememberHapticTap()
    var percentText by remember { mutableStateOf(status.percent.toString()) }
    var timeText by remember { mutableStateOf(scrobbleTimeText(status.seconds)) }
    val percent = percentText.trim().toIntOrNull()?.takeIf { it in 1..100 }
    val seconds = parseScrobbleTime(timeText)
    // Saved as it's typed, whenever both boxes make sense.
    LaunchedEffect(percent, seconds) {
        if (percent != null && seconds != null && (percent != status.percent || seconds != status.seconds)) onThreshold(percent, seconds)
    }

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onClose,
        properties = com.mediaviewer.ui.compat.edgeToEdgeDialogProperties()
    ) {
        com.mediaviewer.ui.compat.DialogBlurBehind(radius = 48, dimAmount = 0.45f)
        BoxWithConstraints(Modifier.fillMaxSize().imePadding(), contentAlignment = Alignment.Center) {
            val shape = RoundedCornerShape(26.dp)
            val panel = androidx.compose.ui.graphics.lerp(Color(0xFF101014), tint, 0.16f)
            Column(
                Modifier.padding(horizontal = 16.dp, vertical = 24.dp)
                    .widthIn(max = 440.dp).fillMaxWidth()
                    .heightIn(max = maxHeight * 0.82f)
                    .clip(shape)
                    .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(androidx.compose.ui.graphics.lerp(panel, tint, 0.12f).copy(alpha = 0.97f), panel.copy(alpha = 0.97f))))
                    .border(1.2.dp, androidx.compose.ui.graphics.Brush.linearGradient(listOf(tint.copy(alpha = 0.9f), Color.White.copy(alpha = 0.25f), tint.copy(alpha = 0.6f))), shape)
            ) {
                // ── Header ──
                Row(
                    Modifier.fillMaxWidth().padding(start = 8.dp, end = 10.dp, top = 10.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.size(38.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.08f))
                            .clickable { tap(); onClose() },
                        contentAlignment = Alignment.Center
                    ) { Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White, modifier = Modifier.size(19.dp)) }
                    Text(
                        "Scrobble Settings",
                        color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center, modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.size(38.dp))
                }

                // ── When a listen counts ──
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Scrobble after", color = Color.White, fontSize = 14.sp, maxLines = 1, softWrap = false)
                    Spacer(Modifier.width(6.dp))
                    ScrobbleNumberField(percentText, 44.dp, percent != null) { typed -> percentText = typed.filter { it.isDigit() }.take(3) }
                    Text("% or", color = Color.White, fontSize = 14.sp, maxLines = 1, softWrap = false, modifier = Modifier.padding(horizontal = 6.dp))
                    ScrobbleNumberField(timeText, 54.dp, seconds != null) { typed -> timeText = typed.filter { it.isDigit() || it == ':' || it == '.' }.take(5) }
                    Spacer(Modifier.width(6.dp))
                    Text("of the track", color = Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }

                Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color.White.copy(alpha = 0.08f)))
                Text(
                    "Which apps do you want Stellar to scrobble?",
                    color = androidx.compose.ui.graphics.lerp(tint, Color.White, 0.55f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 2.dp)
                )

                Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(top = 2.dp, bottom = 10.dp)) {
                    status.apps.forEach { app ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable { tap(); onToggle(app.packageName, !app.on) }
                                .padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                app.label, color = Color.White, fontSize = 14.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                            )
                            CompactSwitch(app.on) { want -> onToggle(app.packageName, want) }
                        }
                    }
                }
            }
        }
    }
}

/** The e621 row: shows the signed-in user with a Log out button, or a Login
 *  button that swaps the row for the same inline username + API key + Login
 *  (+ white X to cancel) layout the AT Protocol "Add" row uses. */
@Composable
private fun E621AccountBubble(
    e621LoggedIn: Boolean, e621Username: String,
    onLoginE621: (String, String) -> Unit, onLogoutE621: () -> Unit,
    liquidGlass: Boolean, tint: Color, backdrop: GlassBackdrop?
) {
    var adding by remember { mutableStateOf(false) }
    var userField by remember { mutableStateOf("") }
    var keyField by remember { mutableStateOf("") }
    val canSubmit = userField.isNotBlank() && keyField.isNotBlank()

    fun cancel() { adding = false; userField = ""; keyField = "" }
    fun submit() {
        if (!canSubmit) return
        // Signing in just returns to this row (now showing the username);
        // it never opens the feed.
        onLoginE621(userField.trim(), keyField.trim())
        cancel()
    }

    // Back closes the open sign-in row instead of leaving the page.
    BackHandler(enabled = adding && !e621LoggedIn) { cancel() }

    SettingsBubble(liquidGlass, tint, backdrop) {
        if (adding && !e621LoggedIn) {
            BubbleRow {
                CompactField(userField, { userField = it }, "e621 username", Modifier.weight(1f))
                Spacer(Modifier.width(6.dp))
                CompactField(
                    keyField, { keyField = it }, "API key", Modifier.weight(1f),
                    password = true, imeAction = ImeAction.Done, onDone = { submit() }
                )
                Spacer(Modifier.width(6.dp))
                PillButton("Login", { submit() }, enabled = canSubmit)
                Spacer(Modifier.width(6.dp))
                CancelXButton({ cancel() })
            }
        } else {
            BubbleRow {
                RowLabel("e621", Modifier.weight(1f), sub = if (e621LoggedIn) "@$e621Username" else null)
                PillButton(
                    if (e621LoggedIn) "Log out" else "Login",
                    { if (e621LoggedIn) onLogoutE621() else adding = true },
                    color = if (e621LoggedIn) DangerRed else Color.White
                )
            }
        }
    }
}

// ── About page ──────────────────────────────────────────────────────────────

/** Business contact shown on the About page. */
internal const val STELLAR_CONTACT_EMAIL = "RechoRaccoonBusiness@proton.me"

/** Where bug reports and feature requests go (GitHub Issues takes both). */
internal const val STELLAR_ISSUES_URL = "https://github.com/RechoRaccoon/Stellar/issues"

/**
 * Settings → About: every credit on one compact screen (no scrolling), with
 * the business contact at the bottom. On a short screen the whole block
 * scales down to fit rather than scrolling.
 */
@Composable
internal fun AboutPageContent() {
    val recho = Color(0xFF00FF07)
    val rose = Color(0xFFE0245E)
    val dim = Color.White.copy(alpha = 0.6f)
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    val tap = rememberHapticTap()

    @Composable
    fun Header(text: String) {
        Text(
            text.uppercase(), color = Color.White.copy(alpha = 0.45f), fontSize = 10.sp,
            fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp,
            modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)
        )
    }
    /** "Name — what it's for" on one line. */
    @Composable
    fun Line(name: String, role: String, nameColor: Color = Color.White) {
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = nameColor, fontWeight = FontWeight.SemiBold)) { append(name) }
                if (role.isNotEmpty()) withStyle(SpanStyle(color = dim)) { append(" · $role") }
            },
            fontSize = 12.sp, lineHeight = 16.sp
        )
    }
    @Composable
    fun Small(text: String) {
        Text(text, color = dim, fontSize = 11.sp, lineHeight = 15.sp)
    }

    FitToHeight(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 14.dp)) {
        Column(Modifier.fillMaxWidth()) {
            Header("Created by")
            Text(
                "Recho Raccoon",
                color = recho, fontSize = 12.sp, lineHeight = 16.sp,
                fontWeight = FontWeight.SemiBold
            )

            Header("Special thanks")
            Line("Rose (SomeDudeGT)", "publishing builds on GitHub", rose)

            Header("Agents")
            Small("Claude Opus 5.5, Claude Sonnet, and Muse by Meta (RJ).")

            Header("AT Protocol")
            Line("Bluesky", "accounts, posts and feeds")
            Line("Leaflet / Standard.site", "long-form blogs")
            Line("Popfeed", "title reviews, backlog and covers")
            Line("Rocksky", "music listening history, yearly top stats, and the scrobbler's design")
            Line("Streamplace", "livestreams")

            Header("Other services")
            Line("Wikipedia & Wikidata", "synopses (CC BY-SA 4.0) and fallback covers")
            Line("e621", "content browsing")
            Line("DecAPI", "Twitch live status")
            Line("Hugging Face", "hosts the tagging model")

            Header("On-device AI")
            Line("Z3D-E621-Convnext", "tagging, by Zack3D, via ONNX Runtime")
            Line("MediaPipe", "VRM face, hand and pose tracking (Google)")
            Line("ML Kit", "translation and language ID (Google)")

            Header("Open source")
            Small("Jetpack Compose, Media3/ExoPlayer, CameraX, Filament, Coil, OkHttp, Retrofit, Gson, ZXing, and the Audiowide font by Astigmatic (SIL OFL).")

            Header("Bugs & feature requests")
            Small("Found a bug or have an idea? Open an issue on Stellar's GitHub:")
            Text(
                STELLAR_ISSUES_URL.removePrefix("https://"), color = recho, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 2.dp).clip(RoundedCornerShape(6.dp))
                    .clickable { tap(); runCatching { uriHandler.openUri(STELLAR_ISSUES_URL) } }
                    .padding(vertical = 2.dp)
            )

            Spacer(Modifier.height(12.dp))
            Small("All trademarks, logos and cover art belong to their respective owners. Stellar is independent and not affiliated with or endorsed by the services above.")
            Spacer(Modifier.height(10.dp))
            Small("For app inquiries or copyright and trademark concerns, please contact:")
            Text(
                STELLAR_CONTACT_EMAIL, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 2.dp).clip(RoundedCornerShape(6.dp))
                    .clickable { tap(); runCatching { uriHandler.openUri("mailto:$STELLAR_CONTACT_EMAIL") } }
                    .padding(vertical = 2.dp)
            )
        }
    }
}

/** Lays [content] out at its natural height and, if that's taller than the
 *  space available, scales it down (from the top) so it always fits on one
 *  screen instead of scrolling. */
@Composable
private fun FitToHeight(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    androidx.compose.ui.layout.Layout(content = content, modifier = modifier) { measurables, constraints ->
        val placeable = measurables.first().measure(
            constraints.copy(minWidth = 0, minHeight = 0, maxHeight = androidx.compose.ui.unit.Constraints.Infinity)
        )
        val maxH = if (constraints.hasBoundedHeight) constraints.maxHeight else placeable.height
        val scale = if (placeable.height > maxH && placeable.height > 0) maxH.toFloat() / placeable.height else 1f
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else placeable.width
        layout(width, maxH.coerceAtLeast(0)) {
            placeable.placeWithLayer(0, 0) {
                scaleX = scale
                scaleY = scale
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0f)
            }
        }
    }
}

// ── Customize Hub ───────────────────────────────────────────────────────────

/**
 * The Hub's Customize Hub (its floating button): one bubble per Hub row,
 * each with a grab handle on the left to drag it into a new place, an
 * on/off switch, and an X to remove it (tap once to arm, again to remove —
 * same as deleting a post). List and profile rows also get a
 * "Profiles"/"Posts" mode button. "Add" puts rows in: "Default" (any default
 * row or widget that isn't in the Hub — ones already there are greyed out)
 * and "Create Feed" (the Feed Builder: a feed of its own, as a row). Then
 * the Hub's own settings. With [split] (landscape) the rows sit on the left
 * and adding and the settings on the right, like Settings. Everything is
 * saved in [com.mediaviewer.util.HubLayout].
 */
/**
 * One row of a Hub or Home layout list (see [LayoutRowsList]): a default
 * row has [onRemove] (its X, two taps); a custom one has [onEdit] (its own
 * popup, which deletes it too) — never both. [trailing] goes before them
 * (a list row's Posts / Profiles). [stuck]: can't be removed.
 */
internal class LayoutRowItem(
    val id: String,
    val label: String,
    val onEdit: (() -> Unit)? = null,
    val onRemove: (() -> Unit)? = null,
    val trailing: (@Composable () -> Unit)? = null
)

/**
 * The Hub's and the Home tab's layout lists, the same everywhere: a short
 * bubble per row, held by its handle and dragged to move it. [liveIds]
 * reads the order as it is right now (a move just made counts straight
 * away); [onMove] moves one row from an index to another.
 */
@Composable
internal fun LayoutRowsList(
    items: List<LayoutRowItem>,
    liveIds: () -> List<String>,
    onMove: (Int, Int) -> Unit,
    liquidGlass: Boolean, tint: Color, backdrop: GlassBackdrop?,
    modifier: Modifier = Modifier
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val view = com.mediaviewer.ui.compat.rememberPlatformView()
    val gapPx = with(density) { 8.dp.toPx() }
    var draggingId by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val heights = remember { mutableStateMapOf<String, Int>() }
    fun buzz() { runCatching { view.performHapticFeedback(com.mediaviewer.ui.compat.HapticFeedbackConstants.CLOCK_TICK) } }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { item ->
            key(item.id) {
                val dragging = draggingId == item.id
                Box(
                    Modifier
                        .fillMaxWidth()
                        .zIndex(if (dragging) 1f else 0f)
                        .onSizeChanged { heights[item.id] = it.height }
                        .graphicsLayer {
                            translationY = if (dragging) dragOffset else 0f
                            val s = if (dragging) 1.02f else 1f
                            scaleX = s; scaleY = s
                        }
                ) {
                    val handle = Modifier.pointerInput(item.id) {
                        detectDragGestures(
                            onDragStart = { draggingId = item.id; dragOffset = 0f; buzz() },
                            onDragEnd = { draggingId = null; dragOffset = 0f },
                            onDragCancel = { draggingId = null; dragOffset = 0f },
                            onDrag = { change, amount ->
                                change.consume()
                                dragOffset += amount.y
                                val list = liveIds()
                                val idx = list.indexOf(item.id)
                                if (idx < 0) return@detectDragGestures
                                if (dragOffset > 0f && idx < list.lastIndex) {
                                    val step = (heights[list[idx + 1]] ?: 0) + gapPx
                                    if (step > gapPx && dragOffset > step / 2f) { onMove(idx, idx + 1); dragOffset -= step; buzz() }
                                } else if (dragOffset < 0f && idx > 0) {
                                    val step = (heights[list[idx - 1]] ?: 0) + gapPx
                                    if (step > gapPx && -dragOffset > step / 2f) { onMove(idx, idx - 1); dragOffset += step; buzz() }
                                }
                            }
                        )
                    }
                    LayoutRowBubble(item, handle, liquidGlass, tint, backdrop)
                }
            }
        }
    }
}

/** One row of [LayoutRowsList]: grab handle, name, then its edit or its X. */
@Composable
private fun LayoutRowBubble(item: LayoutRowItem, handle: Modifier, liquidGlass: Boolean, tint: Color, backdrop: GlassBackdrop?) {
    val editTap = rememberHapticTap()
    SettingsBubble(liquidGlass, tint, backdrop) {
        BubbleRow {
            Box(handle.size(width = 30.dp, height = 34.dp), contentAlignment = Alignment.CenterStart) {
                Icon(
                    androidx.compose.material.icons.Icons.Default.DragIndicator,
                    contentDescription = "Drag to reorder", tint = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.size(20.dp)
                )
            }
            Text(
                item.label, color = Color.White, fontSize = 14.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(end = 8.dp)
            )
            item.trailing?.let { it(); Spacer(Modifier.width(8.dp)) }
            if (item.onEdit != null) {
                // The same pen as editing your own profile, as a grey circle.
                Box(
                    Modifier.size(28.dp).clip(CircleShape).background(Color.White.copy(0.12f))
                        .clickable { editTap(); item.onEdit.invoke() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(androidx.compose.material.icons.Icons.Default.Edit, contentDescription = "Edit", tint = Color.White, modifier = Modifier.size(15.dp))
                }
            } else if (item.onRemove != null) {
                RemoveLayoutRowButton("row:" + item.id, item.onRemove)
            }
        }
    }
}

/** A layout row's X: the first tap asks "Really?", the second removes it
 *  (a tap anywhere else puts the X back). */
@Composable
private fun RemoveLayoutRowButton(key: String, onRemove: () -> Unit) {
    if (ReallyPrompt.isArmed(key)) {
        PillButton("Really?", { ReallyPrompt.tap(key, onRemove) }, modifier = Modifier.reallyTarget(key))
    } else {
        CancelXButton({ ReallyPrompt.arm(key) }, modifier = Modifier.reallyTarget(key))
    }
}

/** "Hub Layout", "Home Settings"…: a section's name centered between two
 *  lines in the profile color, like the Hub's own dividers. */
@Composable
internal fun LayoutSectionDivider(label: String, tint: Color, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(top = 14.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        HorizontalDivider(modifier = Modifier.weight(1f), color = tint.copy(alpha = 0.6f))
        Text(
            label, color = androidx.compose.ui.graphics.lerp(tint, Color.White, 0.35f), fontSize = 13.sp, lineHeight = 13.sp,
            fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 10.dp)
        )
        HorizontalDivider(modifier = Modifier.weight(1f), color = tint.copy(alpha = 0.6f))
    }
}

/** How row names look (Hub and Home Settings): Header Alignment, the
 *  divider look, and lines beside a big name (see RowHeaderStyle). */
@Composable
internal fun HeaderStyleSettings(
    style: RowHeaderStyle,
    onChange: (RowHeaderStyle) -> Unit,
    liquidGlass: Boolean, tint: Color, backdrop: GlassBackdrop?,
    extra: (@Composable ColumnScope.() -> Unit)? = null
) {
    CompositionLocalProvider(LocalSettingsAccent provides headerColorFor(tint)) {
        SettingsBubble(liquidGlass, tint, backdrop) {
            BubbleRow {
                RowLabel("Header Alignment", Modifier.weight(1f))
                ConnectedChoice(
                    options = listOf(
                        ChoiceOption(icon = androidx.compose.material.icons.Icons.AutoMirrored.Filled.FormatAlignLeft, description = "Left"),
                        ChoiceOption(icon = androidx.compose.material.icons.Icons.Default.FormatAlignCenter, description = "Center"),
                        ChoiceOption(icon = androidx.compose.material.icons.Icons.AutoMirrored.Filled.FormatAlignRight, description = "Right")
                    ),
                    selected = style.align,
                    onSelect = { onChange(style.copy(align = it)) }
                )
            }
            BubbleDivider()
            BubbleRow {
                // S: the small name between lines. L: a big name with lines.
                RowLabel("Header Size", Modifier.weight(1f))
                ConnectedChoice(
                    options = listOf(ChoiceOption("S", description = "Small"), ChoiceOption("L", description = "Large")),
                    selected = if (style.divider) 0 else 1,
                    onSelect = { onChange(style.copy(divider = it == 0, lines = true)) }
                )
            }
            extra?.invoke(this)
        }
    }
}

/** A layout page's button (Create, Add Default): clear like the rest of
 *  the page's glass — or, with Dev Tools' gradient buttons on, filled. */
@Composable
internal fun LayoutPillButton(label: String, liquidGlass: Boolean, tint: Color, backdrop: GlassBackdrop?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val tap = rememberHapticTap()
    val shape = RoundedCornerShape(20.dp)
    val m = modifier.height(40.dp).clip(shape).clickable { tap(); onClick() }
    val text: @Composable () -> Unit = {
        Text(label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.padding(horizontal = 20.dp))
    }
    when {
        com.mediaviewer.util.UiToggles.devGradientButtons -> Box(m.popupButtonFill(tint, shape), contentAlignment = Alignment.Center) { text() }
        liquidGlass -> LiquidGlassSurface(m, shape = shape, tint = tint, backdrop = backdrop, contentAlignment = Alignment.Center) { text() }
        else -> Box(m.background(Color.White.copy(alpha = 0.04f)).border(1.dp, tint.copy(alpha = 0.6f), shape), contentAlignment = Alignment.Center) { text() }
    }
}

/**
 * Customize Hub's page: Hub Layout (its rows, to drag, remove — or, for
 * your own rows, edit — then Add Default and Create) and Hub Settings
 * (how row names look, one layout per orientation). Everything takes
 * effect as it's changed.
 */
@Composable
internal fun CustomizeHubSection(
    extras: SettingsExtras, liquidGlass: Boolean, tint: Color, backdrop: GlassBackdrop?, split: Boolean = false
) {
    val hub = com.mediaviewer.util.HubLayout
    val rows = hub.rows
    val supporter = com.mediaviewer.util.Supporter.active
    // The Profiles row whose edit button was pressed.
    var editingProfilesRow by remember { mutableStateOf<com.mediaviewer.util.HubLayout.Row?>(null) }

    @Composable
    fun LayoutPart() {
        LayoutSectionDivider("Hub Layout", tint)
        LayoutRowsList(
            items = rows.map { row ->
                val custom = row.isProfiles || row.isFeed
                LayoutRowItem(
                    id = row.id, label = row.label,
                    onEdit = when {
                        row.isProfiles -> ({ editingProfilesRow = row })
                        row.isFeed -> ({
                            com.mediaviewer.util.LocalData.localFeed(com.mediaviewer.util.LocalData.LOCAL_FEED_PREFIX + row.feedId)?.let { feed ->
                                LocalOverlays.feedBuilderForHub = true; LocalOverlays.feedBuilder = feed
                            }
                        })
                        else -> null
                    },
                    // (Stellar Supporters stays for non-supporters.)
                    onRemove = if (custom || (row.id == com.mediaviewer.util.HubLayout.SUPPORTERS && !supporter)) null else ({ hub.remove(row.id) }),
                    trailing = if (!row.hasMembers) null else ({
                        PillButton(if (row.showPosts) "Posts" else "Profiles", { hub.setShowPosts(row.id, !row.showPosts) })
                    })
                )
            },
            liveIds = { hub.rows.map { it.id } },
            onMove = { from, to -> hub.move(from, to) },
            liquidGlass = liquidGlass, tint = tint, backdrop = backdrop
        )
        // ── Add Default / Create ──
        var defaultMenuOpen by remember { mutableStateOf(false) }
        Row(
            Modifier.fillMaxWidth().padding(top = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally)
        ) {
            Box {
                LayoutPillButton("Add Default", liquidGlass, tint, backdrop, { defaultMenuOpen = true })
                // Every default row, in the default order; the ones already
                // in the Hub are greyed out.
                DropdownMenu(expanded = defaultMenuOpen, onDismissRequest = { defaultMenuOpen = false }) {
                    hub.defaultRowIds.forEach { id ->
                        val added = rows.any { it.id == id }
                        DropdownMenuItem(
                            text = { Text(hub.BUILT_IN_LABELS[id] ?: id, color = if (added) DimGray else Color.Unspecified) },
                            onClick = { defaultMenuOpen = false; hub.addDefault(id) },
                            enabled = !added
                        )
                    }
                    // The widgets (supporters) are default rows too.
                    hub.WIDGET_LABELS.forEach { (id, label) ->
                        val added = rows.any { it.id == id }
                        DropdownMenuItem(
                            text = { Text(label, color = if (added) DimGray else Color.Unspecified, modifier = Modifier.supporterShine(!supporter)) },
                            onClick = { defaultMenuOpen = false; if (supporter) hub.addWidget(id) else com.mediaviewer.util.Supporter.openPage() },
                            enabled = !added
                        )
                    }
                }
            }
            LayoutPillButton("Create", liquidGlass, tint, backdrop, {
                LocalOverlays.feedBuilderForHub = true
                LocalOverlays.feedBuilder = com.mediaviewer.util.LocalFeed()
            })
        }
    }
    @Composable
    fun SettingsPart() {
        LayoutSectionDivider("Hub Settings", tint)
        val t = com.mediaviewer.util.UiToggles
        HeaderStyleSettings(
            style = RowHeaderStyle.hub,
            onChange = { s -> t.updateHubHeaderAlign(s.align); t.updateHubDividerHeaders(s.divider); t.updateHubHeaderLines(s.lines) },
            liquidGlass = liquidGlass, tint = tint, backdrop = backdrop
        ) {
            BubbleDivider()
            // One Hub layout for both ways up, or one each (see HubLayout):
            // with it on, this page edits the one the screen is in now.
            BubbleRow {
                Column(Modifier.weight(1f)) {
                    RowLabel("Custom Hub Layouts per Orientation")
                    if (hub.separateOrientations) {
                        Text(
                            if (hub.landscapeNow) "Editing the landscape layout" else "Editing the portrait layout",
                            color = DimGray, fontSize = 11.sp
                        )
                    }
                }
                CompactSwitch(hub.separateOrientations) { hub.useSeparateOrientations(it) }
            }
        }
    }
    editingProfilesRow?.let { row ->
        HubProfilesDialog(
            candidates = extras.hubProfileCandidates, searching = extras.hubProfileSearching, tint = tint,
            onSearch = extras.onSearchHubProfiles,
            onLoadMoreSuggestions = extras.onLoadMoreHubProfileSuggestions,
            onAdd = { name, members -> editingProfilesRow = null; extras.onEditHubProfiles(row.id, name, members) },
            onClose = { editingProfilesRow = null },
            initialName = row.name,
            initialSelected = row.profiles.map { com.mediaviewer.model.AuthorInfo(it.did, it.handle, it.displayName, it.avatarUrl) },
            editing = true,
            onDelete = { editingProfilesRow = null; hub.remove(row.id) }
        )
    }
    if (split) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(Modifier.weight(1.3f)) { LayoutPart() }
            Column(Modifier.weight(1f)) { SettingsPart() }
        }
    } else {
        Column(Modifier.fillMaxWidth()) { LayoutPart(); SettingsPart() }
    }
}

/** Customize Hub → Add → Other: a compact "List URL" popup. */
@Composable
private fun HubListUrlDialog(
    liquidGlass: Boolean, tint: Color,
    onAdd: (String, (String?) -> Unit) -> Unit,
    onDismiss: () -> Unit
) {
    var url by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            Modifier.fillMaxSize()
                .clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null, onClick = onDismiss
                ),
            contentAlignment = Alignment.Center
        ) {
            val shape = RoundedCornerShape(20.dp)
            @Composable
            fun Content() {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("List URL", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CompactField(
                            url, { url = it; error = null }, "https://bsky.app/profile/…/lists/…",
                            Modifier.weight(1f), imeAction = ImeAction.Done,
                            onDone = { if (url.isNotBlank() && !busy) { busy = true; onAdd(url.trim()) { err -> busy = false; error = err } } }
                        )
                        Spacer(Modifier.width(8.dp))
                        PillButton(
                            if (busy) "…" else "Add",
                            { busy = true; onAdd(url.trim()) { err -> busy = false; error = err } },
                            enabled = url.isNotBlank() && !busy
                        )
                    }
                    val err = error
                    if (err != null) Text(err, color = DangerRed, fontSize = 11.sp, lineHeight = 13.sp)
                }
            }
            val m = Modifier.fillMaxWidth(0.9f)
                .clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null
                ) {}
            if (liquidGlass) {
                LiquidGlassSurface(m, shape = shape, tint = tint) { Content() }
            } else {
                Box(m.clip(shape).background(OffBlack).border(1.dp, tint.copy(alpha = 0.5f), shape)) { Content() }
            }
        }
    }
}


/** Opens Android's "Open by default" page for [packageName] (the app's info
 *  page on older Android, or if that one isn't available). */
private fun openAppLinkSettings(context: com.mediaviewer.platform.PlatformContext, packageName: String) {
    if (!com.mediaviewer.ui.compat.openAppLinkSettings(context, packageName)) {
        com.mediaviewer.ui.compat.Toast.makeText(context, "Couldn't open that app's settings", com.mediaviewer.ui.compat.Toast.LENGTH_SHORT).show()
    }
}

/** Settings → Open Bluesky Links in Stellar → Set Up: a small centered
 *  popup with the two steps and a button for each app's settings. */
@Composable
private fun OpenLinksSetupDialog(liquidGlass: Boolean, tint: Color, onDismiss: () -> Unit) {
    val context = com.mediaviewer.ui.compat.LocalContext.current
    val tap = rememberHapticTap()
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = com.mediaviewer.ui.compat.edgeToEdgeDialogProperties()
    ) {
        // Blurs (and dims) everything behind the popup.
        com.mediaviewer.ui.compat.DialogBlurBehind(radius = 48, dimAmount = 0.45f)
        Box(
            Modifier.fillMaxSize().clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null, onClick = onDismiss
            ),
            contentAlignment = Alignment.Center
        ) {
            val shape = RoundedCornerShape(22.dp)
            @Composable
            fun Step(number: String, text: String) {
                Row(verticalAlignment = Alignment.Top) {
                    Box(
                        Modifier.size(20.dp).clip(CircleShape).background(headerColorFor(tint).copy(alpha = 0.85f)),
                        contentAlignment = Alignment.Center
                    ) { Text(number, color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                    Spacer(Modifier.width(10.dp))
                    Text(text, color = Color.White.copy(alpha = 0.9f), fontSize = 13.sp, lineHeight = 17.sp, modifier = Modifier.weight(1f))
                }
            }
            @Composable
            fun Content() {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Open Bluesky Links in Stellar", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
                    )
                    if (com.mediaviewer.platform.currentPlatform == com.mediaviewer.platform.PlatformKind.IOS) {
                        // iOS only lets the owner of bsky.app claim its
                        // links, so the way in is the share sheet: a
                        // two-step shortcut that hands the link to Stellar.
                        val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
                        val prefix = "stellar://open?url="
                        Step("1", "In the Shortcuts app, make a new shortcut. In its details (ⓘ), turn on \"Show in Share Sheet\".")
                        Step("2", "Add the action \"Open URLs\". As its URL, paste the text below, then add \"Shortcut Input\" right after it.")
                        Step("3", "Name it \"Open in Stellar\". From then on: Share a Bluesky profile or post, and pick it.")
                        Box(
                            Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(20.dp))
                                .background(Color.White.copy(alpha = 0.12f))
                                .border(1.dp, headerColorFor(tint).copy(alpha = 0.5f), RoundedCornerShape(20.dp))
                                .clickable {
                                    tap()
                                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(prefix))
                                    com.mediaviewer.ui.compat.showPlatformToast("Copied")
                                },
                            contentAlignment = Alignment.Center
                        ) { Text("Copy  $prefix", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1) }
                        return@Column
                    }
                    Step("1", "Tap Bluesky Settings and choose \"In your browser\".")
                    Step("2", "Tap Stellar Settings, then \"Add link\" and turn on bsky.app.")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("Bluesky Settings" to "xyz.blueskyweb.app", "Stellar Settings" to com.mediaviewer.ui.compat.appPackageName(context)).forEach { (label, pkg) ->
                            Box(
                                Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(20.dp))
                                    .background(Color.White.copy(alpha = 0.12f))
                                    .border(1.dp, headerColorFor(tint).copy(alpha = 0.5f), RoundedCornerShape(20.dp))
                                    .clickable { tap(); openAppLinkSettings(context, pkg) },
                                contentAlignment = Alignment.Center
                            ) { Text(label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1) }
                        }
                    }
                }
            }
            val m = Modifier.fillMaxWidth(0.9f).widthIn(max = 420.dp).clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null
            ) {}
            if (liquidGlass) {
                LiquidGlassSurface(m, shape = shape, tint = tint) { Content() }
            } else {
                Box(m.clip(shape).background(OffBlack).border(1.dp, tint.copy(alpha = 0.5f), shape)) { Content() }
            }
        }
    }
}

/** Data and Privacy → Data Sync Key: hidden until asked for, then shown on
 *  one line; Copy, Show and Regenerate beside it. (Syncing itself runs on
 *  its own, so there's no switch or Sync Now for it.) */
@Composable
private fun PrivateSyncBubble(liquidGlass: Boolean, tint: Color, backdrop: GlassBackdrop?) {
    val sync = com.mediaviewer.util.PrivateSync
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var reveal by remember { mutableStateOf(false) }
    var regenerating by remember { mutableStateOf(false) }
    var regenError by remember { mutableStateOf<String?>(null) }
    var newKeyShown by remember { mutableStateOf(false) }
    val ready = sync.state == com.mediaviewer.util.PrivateSync.State.READY
    val key = remember(sync.state, reveal, regenerating) { if (reveal) sync.savedSecretKey() else null }
    // Only shown while the key can't be used yet, so the row still says why.
    val status = when (sync.state) {
        com.mediaviewer.util.PrivateSync.State.READY -> if (regenerating) "Encrypting your data again…" else regenError
        com.mediaviewer.util.PrivateSync.State.OFFLINE -> "Can't reach your PDS right now; trying again soon"
        com.mediaviewer.util.PrivateSync.State.CHECKING -> "Checking…"
        com.mediaviewer.util.PrivateSync.State.NEEDS_KEY -> "Waiting for your sync key"
        com.mediaviewer.util.PrivateSync.State.NEEDS_SETUP -> "Not set up yet"
        com.mediaviewer.util.PrivateSync.State.OFF -> "Off"
    }
    SettingsBubble(liquidGlass, tint, backdrop) {
        BubbleRow {
            // (Three buttons leave this label a few dp short of one line,
            // so it never wraps: the words draw in full instead.)
            RowLabel("Data Sync Key", Modifier.weight(1f), sub = status, singleLine = true)
            if (ready) {
                PillButton("Copy", { sync.savedSecretKey()?.let { clipboard.setText(androidx.compose.ui.text.AnnotatedString(it)) } })
                Spacer(Modifier.width(6.dp))
                PillButton(if (reveal) "Hide" else "Show", { reveal = !reveal })
                Spacer(Modifier.width(6.dp))
                PillButton("Regenerate", {
                    LocalOverlays.confirm = ConfirmRequest(
                        "Regenerate Sync Key?",
                        "Stellar will encrypt your synced data again with a new key and delete the old copy. Your old key will stop working, and every other device signed in to this account will have to enter the new key before it can sync again.",
                        "Regenerate", tint
                    ) {
                        regenerating = true; regenError = null
                        val e = sync.regenerateKey()
                        regenerating = false
                        if (e == null) { reveal = true; newKeyShown = true } else regenError = e
                    }
                }, enabled = !regenerating && !sync.syncing)
            }
        }
        if (key != null) Box(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp)) { SecretKeyLine(key) }
    }
    if (newKeyShown) androidx.compose.ui.window.Dialog(
        onDismissRequest = {},
        properties = androidx.compose.ui.window.DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = false, usePlatformDefaultWidth = false)
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.82f)), contentAlignment = Alignment.Center) {
            Box(Modifier.widthIn(max = 460.dp).fillMaxWidth(0.9f).clip(RoundedCornerShape(22.dp)).background(OffBlack)) {
                NewSyncKeyStep(tint, onContinue = { newKeyShown = false })
            }
        }
    }
}
