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
    var showingNewKey by remember { mutableStateOf(false) }
    val needed = signedIn && (state == PrivateSync.State.NEEDS_SETUP || state == PrivateSync.State.NEEDS_KEY)
    if (!needed && !showingNewKey) return
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
                    showingNewKey -> NewKeyStep(
                        title = "Your New Secret Key",
                        body = "Your Private Sync was started over with this device's data. Keep this key somewhere safe; your other devices will ask for it.",
                        tint = tint, onSaved = { showingNewKey = false }
                    )
                    state == PrivateSync.State.NEEDS_SETUP -> SetupStep(tint)
                    else -> UnlockStep(tint, onStartedOver = { showingNewKey = true })
                }
            }
        }
    }
}

@Composable
private fun SetupStep(tint: Color) {
    val scope = rememberCoroutineScope()
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    NewKeyStep(
        title = "Private Sync",
        body = "Your drafts, notes, calendar, bookmark folders, archived posts, Hub layout, Blogs and Reviews lists and settings are saved to your own PDS, encrypted, so they follow you to every device you sign in on.\n\nOnly this Secret Key can open them; not even Stellar can. Keep it somewhere safe (a password manager is ideal). You can see it again any time in Settings → Data and Privacy.",
        tint = tint, working = working, error = error,
        onSaved = {
            working = true; error = null
            scope.launch {
                error = PrivateSync.createVault()
                working = false
            }
        }
    )
}

@Composable
private fun NewKeyStep(
    title: String, body: String, tint: Color,
    working: Boolean = false, error: String? = null, onSaved: () -> Unit
) {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val tap = rememberHapticTap()
    var copied by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(10.dp))
        Text(body, color = DimGray, fontSize = 13.sp, lineHeight = 18.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        SecretKeyBox(PrivateSync.newSecretKey)
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = {
                tap()
                clipboard.setText(androidx.compose.ui.text.AnnotatedString(PrivateSync.newSecretKey))
                copied = true
            },
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
            modifier = Modifier.fillMaxWidth().height(44.dp)
        ) { Text(if (copied) "Copied" else "Copy Secret Key") }
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = { tap(); onSaved() },
            enabled = !working,
            colors = ButtonDefaults.buttonColors(containerColor = tint),
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) {
            if (working) CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
            else Text("I've Saved It", color = Color.White, fontWeight = FontWeight.SemiBold)
        }
        if (error != null) {
            Spacer(Modifier.height(8.dp))
            Text(error, color = Color(0xFFFF8A80), fontSize = 12.sp, textAlign = TextAlign.Center)
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
            Text("Unlock Private Sync", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Spacer(Modifier.height(10.dp))
            Text(
                if (PrivateSync.keyChangedElsewhere) "Your Secret Key was changed on another device. Enter the new one to keep syncing."
                else "This account already has Private Sync. Enter the Secret Key you saved when you set it up, and your drafts, notes, bookmark folders, archive and settings will come over.",
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
                message = "A new Secret Key is made and this device's data becomes your Private Sync. What only your other devices had saved there can't be opened anymore; they'll ask for the new key.",
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
