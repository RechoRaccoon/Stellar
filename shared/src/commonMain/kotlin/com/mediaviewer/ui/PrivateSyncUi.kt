package com.mediaviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mediaviewer.i18n.Text
import com.mediaviewer.ui.theme.DimGray
import com.mediaviewer.ui.theme.OffBlack
import com.mediaviewer.ui.theme.RoundedCornerShape
import com.mediaviewer.util.PrivateSync
import com.mediaviewer.util.rememberHapticTap
import kotlinx.coroutines.launch

/**
 * Private Sync's sign-in step (required): a new account gets its Secret
 * Key to save; a device joining an account that already has Private Sync
 * asks for it. Shown over everything until it's done; the account's data
 * stays usable on this device the whole time.
 */
@Composable
fun PrivateSyncGate(signedIn: Boolean, tint: Color) {
    val state = PrivateSync.state
    // Shown once syncing has started (or started over): the key to take to
    // the other devices.
    var keyReady by remember { mutableStateOf(false) }
    val needed = signedIn && (state == PrivateSync.State.NEEDS_SETUP || state == PrivateSync.State.NEEDS_KEY)
    if (!needed && !keyReady) return
    androidx.compose.ui.window.Dialog(
        onDismissRequest = {},
        properties = androidx.compose.ui.window.DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = false, usePlatformDefaultWidth = false)
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.82f)).imePadding(), contentAlignment = Alignment.Center) {
            Box(
                Modifier.widthIn(max = 460.dp).fillMaxWidth(0.9f).clip(RoundedCornerShape(22.dp)).background(OffBlack)
                    .verticalScroll(rememberScrollState())
            ) {
                when {
                    keyReady -> KeyReadyStep(tint, onContinue = { keyReady = false })
                    state == PrivateSync.State.NEEDS_SETUP -> SetupStep(tint, onStarted = { keyReady = true })
                    else -> UnlockStep(tint, onStartedOver = { keyReady = true })
                }
            }
        }
    }
}

@Composable
private fun StepTitle(text: String) =
    Text(text, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)

@Composable
private fun StepBody(text: String) =
    Text(text, color = DimGray, fontSize = 13.sp, lineHeight = 18.sp, textAlign = TextAlign.Center)

/** No sync yet: what it is, and Copy Key / Start Syncing. */
@Composable
private fun SetupStep(tint: Color, onStarted: () -> Unit) {
    val scope = rememberCoroutineScope()
    val tap = rememberHapticTap()
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var working by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        StepTitle("Private Data Sync")
        Spacer(Modifier.height(10.dp))
        StepBody("Stellar encrypts your private data (drafts, notes, bookmark folders, archive and Hub layout) and keeps it in sync across your devices through your PDS. Only your sync key can unlock it. Never show it to anyone.")
        Spacer(Modifier.height(10.dp))
        StepBody("If this is the device you mostly use Stellar on, tap Start Syncing. If your Stellar data is on another device, tap Start Syncing there first, then reload Stellar here and enter the key it gives you.")
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = {
                    tap()
                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(PrivateSync.newSecretKey))
                    copied = true
                },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                modifier = Modifier.weight(1f).height(48.dp)
            ) { Text(if (copied) "Copied" else "Copy Key", maxLines = 1) }
            Button(
                onClick = {
                    tap(); working = true; error = null
                    scope.launch {
                        val e = PrivateSync.createVault()
                        working = false
                        if (e == null) onStarted() else error = e
                    }
                },
                enabled = !working,
                colors = ButtonDefaults.buttonColors(containerColor = tint),
                modifier = Modifier.weight(1f).height(48.dp)
            ) {
                if (working) CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                else Text("Start Syncing", color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
        }
        if (error != null) {
            Spacer(Modifier.height(8.dp))
            Text(error!!, color = Color(0xFFFF8A80), fontSize = 12.sp, textAlign = TextAlign.Center)
        }
    }
}

/** Syncing has started: enter this key on the other devices (once each). */
@Composable
private fun KeyReadyStep(tint: Color, onContinue: () -> Unit) {
    val tap = rememberHapticTap()
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val key = remember { PrivateSync.savedSecretKey() ?: PrivateSync.newSecretKey }
    var copied by remember { mutableStateOf(false) }
    var revealed by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        StepTitle("You're Syncing")
        Spacer(Modifier.height(10.dp))
        StepBody("To sync another device, open Stellar there and enter this key. You only need to do it once per device. Don't show it to anyone.")
        Spacer(Modifier.height(18.dp))
        // Tap to show the key right in the button; tap again to hide it.
        OutlinedButton(
            onClick = { tap(); revealed = !revealed },
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
        ) {
            if (revealed) Text(key, fontFamily = FontFamily.Monospace, fontSize = 14.sp, letterSpacing = 0.5.sp, textAlign = TextAlign.Center)
            else Text("Reveal Key")
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = { tap(); clipboard.setText(androidx.compose.ui.text.AnnotatedString(key)); copied = true },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                modifier = Modifier.weight(1f).height(48.dp)
            ) { Text(if (copied) "Copied" else "Copy Key", maxLines = 1) }
            Button(
                onClick = { tap(); onContinue() },
                colors = ButtonDefaults.buttonColors(containerColor = tint),
                modifier = Modifier.weight(1f).height(48.dp)
            ) { Text("Continue", color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1) }
        }
    }
}

/** After Regenerate Sync Key: the new key, to enter on the other devices. */
@Composable
internal fun NewSyncKeyStep(tint: Color, onContinue: () -> Unit) {
    val tap = rememberHapticTap()
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val key = remember { PrivateSync.savedSecretKey() ?: "" }
    var copied by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        StepTitle("New Sync Key")
        Spacer(Modifier.height(10.dp))
        StepBody("Your data is encrypted with this new key and the old copy is gone. Your other devices will ask for it the next time they sync. Don't show it to anyone.")
        Spacer(Modifier.height(14.dp))
        SecretKeyBox(key)
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = { tap(); clipboard.setText(androidx.compose.ui.text.AnnotatedString(key)); copied = true },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                modifier = Modifier.weight(1f).height(48.dp)
            ) { Text(if (copied) "Copied" else "Copy Key", maxLines = 1) }
            Button(
                onClick = { tap(); onContinue() },
                colors = ButtonDefaults.buttonColors(containerColor = tint),
                modifier = Modifier.weight(1f).height(48.dp)
            ) { Text("Done", color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1) }
        }
    }
}

/** The key in large, evenly spaced characters, two groups of four per line. */
@Composable
internal fun SecretKeyBox(key: String) {
    val groups = key.split('-')
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color.White.copy(0.07f)).padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        groups.chunked(2).forEach { line ->
            Text(
                line.joinToString("  "), color = Color.White, fontSize = 18.sp, letterSpacing = 1.5.sp,
                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold
            )
        }
    }
}

/** The key as it's typed, dashes and all, on a single line: the text
 *  shrinks to fit the width instead of wrapping. */
@Composable
internal fun SecretKeyLine(key: String) {
    BoxWithConstraints(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color.White.copy(0.07f)).padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        // A monospace character is about 0.6 of its size wide.
        val fit = (maxWidth.value / (key.length.coerceAtLeast(1) * 0.62f)).coerceIn(8f, 16f)
        Text(
            key, color = Color.White, fontSize = fit.sp, maxLines = 1, softWrap = false,
            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun UnlockStep(tint: Color, onStartedOver: () -> Unit) {
    val scope = rememberCoroutineScope()
    val tap = rememberHapticTap()
    var typed by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmStartOver by remember { mutableStateOf(false) }
    Box {
        Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            StepTitle("Private Data Sync")
            Spacer(Modifier.height(10.dp))
            Text(
                if (PrivateSync.keyChangedElsewhere) "Your sync key was changed on another device. Enter the new key to keep syncing."
                else "Your Stellar data is already syncing from another device. Enter the key from that device to bring it here. You can find it in Settings → Data and Privacy on that device.",
                color = DimGray, fontSize = 13.sp, lineHeight = 18.sp, textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = typed, onValueChange = { typed = it.uppercase().take(48); error = null },
                placeholder = { Text("XXXX-XXXX-XXXX-…", color = DimGray, fontFamily = FontFamily.Monospace) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                colors = fieldColors(),
                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 15.sp, color = Color.White),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    tap(); working = true; error = null
                    scope.launch { error = PrivateSync.unlock(typed); working = false }
                },
                enabled = !working && typed.count { it.isLetterOrDigit() } >= 32,
                colors = ButtonDefaults.buttonColors(containerColor = tint),
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) {
                if (working) CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                else Text("Unlock", color = Color.White, fontWeight = FontWeight.SemiBold)
            }
            if (error != null) {
                Spacer(Modifier.height(8.dp))
                Text(error!!, color = Color(0xFFFF8A80), fontSize = 12.sp, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(14.dp))
            Text(
                "Lost it? Start Over", color = DimGray, fontSize = 13.sp,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { tap(); confirmStartOver = true }.padding(8.dp)
            )
        }
        if (confirmStartOver) androidx.compose.ui.window.Dialog(
            onDismissRequest = { confirmStartOver = false },
            properties = com.mediaviewer.ui.compat.edgeToEdgeDialogProperties()
        ) { Box(Modifier.fillMaxSize()) {
            ConfirmPopup(
                title = "Start Over?",
                message = "This device's data becomes your synced data, with a new key. Anything only your other devices had synced can't be opened anymore, and they'll ask for the new key.",
                confirmLabel = "Start Over",
                liquidGlass = false, tint = tint, backdrop = null,
                onConfirm = {
                    confirmStartOver = false; working = true; error = null
                    scope.launch {
                        val e = PrivateSync.startOver()
                        working = false
                        if (e == null) onStartedOver() else error = e
                    }
                },
                onDismiss = { confirmStartOver = false }
            )
        } }
    }
}
