package com.mediaviewer.ui

import com.mediaviewer.resources.stellar_logo_vector

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalUriHandler
import org.jetbrains.compose.resources.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

import com.mediaviewer.util.rememberHapticTap
import androidx.compose.material.icons.filled.Cake
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.MarkEmailRead
import kotlinx.coroutines.launch

/** The login page's accent: Stellar pink (nobody's profile colors to use yet). */
val LoginPink = Color(0xFFFF4FA1)

/** Where the sign-in page is: signing in, or one of Create Account's steps. */
private enum class LoginStep { SIGN_IN, CREATE, VERIFY, EMAIL }

/**
 * The sign-in page: black space with stars, the big Stellar logo near the
 * top, compact rounded fields and a pink button. Signs in to any AT
 * Protocol account (any handle, app password or the account's own), and
 * "Create Account" makes a new Bluesky account without leaving Stellar:
 * its details, Bluesky's own human check, then the emailed code.
 * [drawBackground] false when the caller already draws the starry
 * background behind it (the Hub). [onClose] non-null shows a back button
 * (Dev Tools' preview).
 */
@Composable
fun LoginScreen(
    isLoading: Boolean,
    onLogin: (String, String) -> Unit,
    modifier: Modifier = Modifier,
    drawBackground: Boolean = true,
    onClose: (() -> Unit)? = null
) {
    var handle by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    val passwordFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val tap = rememberHapticTap()
    val flow = com.mediaviewer.util.LoginFlow
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(LoginStep.SIGN_IN) }

    // ── Create Account ──
    var newEmail by remember { mutableStateOf("") }
    var newName by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var birthday by remember { mutableStateOf("") }
    var emailCode by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    val fullHandle = newName + NEW_HANDLE_SUFFIX
    val birthIso = remember(birthday) { birthdayToIso(birthday) }
    val canCreate = newEmail.contains('@') && newEmail.contains('.') && isValidNewName(newName) &&
        newPassword.length >= 8 && birthIso != null && !working

    val canSubmit = handle.isNotBlank() && password.isNotBlank() && !isLoading && (!flow.needsCode || flow.code.isNotBlank())
    fun submit() {
        if (!canSubmit) return
        focusManager.clearFocus()
        onLogin(handle.trim().removePrefix("@"), password)
    }

    /** The account itself, once the details (and the human check) are in. */
    fun createAccount(verificationCode: String?) {
        val create = flow.createAccount ?: return
        working = true; problem = null
        scope.launch {
            val error = create(newEmail.trim(), fullHandle, newPassword, verificationCode, birthIso.orEmpty())
            working = false
            if (error == null) {
                newPassword = ""
                emailCode = ""; notice = null
                step = LoginStep.EMAIL
            } else {
                problem = error
                step = LoginStep.CREATE
            }
        }
    }
    fun continueCreate() {
        if (!canCreate) return
        focusManager.clearFocus()
        if (!isOldEnough(birthIso)) { problem = "You need to be at least 13 to make an account"; return }
        working = true; problem = null
        scope.launch {
            val free = flow.handleAvailable?.invoke(fullHandle)
            if (free == false) { working = false; problem = "That username is taken"; return@launch }
            val needsCheck = flow.needsVerification?.invoke() ?: true
            working = false
            if (needsCheck) step = LoginStep.VERIFY else createAccount(null)
        }
    }
    fun confirmEmail() {
        val confirm = flow.confirmEmail ?: return
        if (emailCode.isBlank() || working) return
        focusManager.clearFocus()
        working = true; problem = null; notice = null
        scope.launch {
            val error = confirm(newEmail.trim(), emailCode.trim())
            if (error == null) {
                // Confirmed. The page stays busy while the app opens on the
                // new account, so the button can't be pressed a second time
                // (which used to answer "Start again" although all was well).
                notice = "Signing you in…"
                flow.finish?.invoke()
            } else {
                working = false
                problem = error
            }
        }
    }

    Box(modifier.fillMaxSize()) {
        if (drawBackground) SpaceSky(Color.Black, Modifier.matchParentSize())
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val logoWidth = (maxWidth * 0.8f).coerceAtMost(380.dp)
            val pageHeight = maxHeight
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .imePadding()
                    .padding(horizontal = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(if (drawBackground) rememberTopCutoutClearance() + 56.dp else 40.dp))
                // (The human check gets the whole page: no logo above it.)
                if (step != LoginStep.VERIFY) {
                    // The Stellar + "Created by Recho Raccoon" lockup the Stellar
                    // loading animation uses, with the same soft glow.
                    Box(contentAlignment = Alignment.Center) {
                        Box(
                            Modifier.graphicsLayer { scaleX = 1.04f; scaleY = 1.12f; alpha = 0.55f }
                                .blur(14.dp, edgeTreatment = BlurredEdgeTreatment.Unbounded)
                                .padding(40.dp)
                        ) {
                            Image(painterResource(com.mediaviewer.resources.Res.drawable.stellar_logo_vector), contentDescription = null, modifier = Modifier.width(logoWidth))
                        }
                        Image(painterResource(com.mediaviewer.resources.Res.drawable.stellar_logo_vector), contentDescription = "Stellar", modifier = Modifier.width(logoWidth))
                    }
                    Spacer(Modifier.height(36.dp))
                }
                // iOS: the system's secure-entry keyboard (KeyboardType.Password)
                // misbehaves inside Compose on iOS (dropped/cleared characters,
                // no Paste), so iOS uses a plain ASCII keyboard with autocorrect
                // off — the dots still come from PasswordVisualTransformation —
                // plus a Paste button. Android is unchanged.
                val isIos = com.mediaviewer.platform.currentPlatform == com.mediaviewer.platform.PlatformKind.IOS
                @Suppress("DEPRECATION")
                val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
                @Composable
                fun PasswordField(value: String, onValue: (String) -> Unit, placeholder: String, imeAction: ImeAction, onIme: () -> Unit, fieldModifier: Modifier = Modifier) {
                    LoginField(
                        value = value, onValueChange = onValue,
                        placeholder = placeholder, icon = Icons.Default.Key,
                        keyboardType = if (isIos) KeyboardType.Ascii else KeyboardType.Password, imeAction = imeAction,
                        onImeAction = onIme,
                        password = !showPassword,
                        noAutoCorrect = isIos,
                        modifier = fieldModifier,
                        trailing = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (isIos) {
                                    Icon(
                                        Icons.Default.ContentPaste,
                                        contentDescription = "Paste password",
                                        tint = Color.White.copy(alpha = 0.6f),
                                        modifier = Modifier.size(34.dp).clip(CircleShape).clickable {
                                            val pasted = runCatching { clipboard.getText()?.text }.getOrNull()
                                            if (!pasted.isNullOrEmpty()) { tap(); onValue(pasted.trim()) }
                                        }.padding(8.dp)
                                    )
                                }
                                Icon(
                                    if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (showPassword) "Hide password" else "Show password",
                                    tint = Color.White.copy(alpha = 0.6f),
                                    modifier = Modifier.size(34.dp).clip(CircleShape).clickable { showPassword = !showPassword }.padding(8.dp)
                                )
                            }
                        }
                    )
                }
                when (step) {
                    LoginStep.SIGN_IN -> {
                        LoginField(
                            value = handle, onValueChange = { handle = it.trim() },
                            placeholder = "Handle", icon = Icons.Default.AlternateEmail,
                            keyboardType = KeyboardType.Email, imeAction = ImeAction.Next,
                            onImeAction = { runCatching { passwordFocus.requestFocus() } }
                        )
                        Spacer(Modifier.height(10.dp))
                        PasswordField(
                            password, { password = it }, "Password",
                            if (flow.needsCode) ImeAction.Next else ImeAction.Done, { submit() },
                            Modifier.focusRequester(passwordFocus)
                        )
                        if (flow.needsCode) {
                            Spacer(Modifier.height(10.dp))
                            LoginField(
                                value = flow.code, onValueChange = { flow.code = it.trim().take(16) },
                                placeholder = "Email code", icon = Icons.Default.MarkEmailRead,
                                keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done,
                                onImeAction = { submit() }, noAutoCorrect = true
                            )
                        }
                        Spacer(Modifier.height(16.dp))
                        LoginButton("Sign In", enabled = canSubmit, busy = isLoading) { tap(); submit() }
                        Spacer(Modifier.height(10.dp))
                        LoginButton("Create Account", enabled = !isLoading, busy = false, outlined = true) {
                            tap(); focusManager.clearFocus(); problem = null; step = LoginStep.CREATE
                        }
                    }
                    LoginStep.CREATE -> {
                        LoginField(
                            value = newEmail, onValueChange = { newEmail = it.trim() },
                            placeholder = "Email", icon = Icons.Default.Email,
                            keyboardType = KeyboardType.Email, imeAction = ImeAction.Next, onImeAction = {}
                        )
                        Spacer(Modifier.height(10.dp))
                        LoginField(
                            value = newName,
                            onValueChange = { typed -> newName = typed.lowercase().filter { it in 'a'..'z' || it in '0'..'9' || it == '-' }.take(18) },
                            placeholder = "Username", icon = Icons.Default.AlternateEmail,
                            keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next, onImeAction = {},
                            noAutoCorrect = true,
                            trailing = {
                                Text(
                                    NEW_HANDLE_SUFFIX, color = Color.White.copy(alpha = 0.55f), fontSize = 14.sp, maxLines = 1,
                                    modifier = Modifier.padding(end = 10.dp)
                                )
                            }
                        )
                        Spacer(Modifier.height(10.dp))
                        PasswordField(newPassword, { newPassword = it }, "Password", ImeAction.Next, {})
                        Spacer(Modifier.height(10.dp))
                        LoginField(
                            value = birthday, onValueChange = { birthday = formatBirthday(it) },
                            placeholder = "Birthday (MM/DD/YYYY)", icon = Icons.Default.Cake,
                            keyboardType = KeyboardType.Number, imeAction = ImeAction.Done,
                            onImeAction = { continueCreate() }
                        )
                        Spacer(Modifier.height(16.dp))
                        LoginButton("Continue", enabled = canCreate, busy = working) { tap(); continueCreate() }
                        Spacer(Modifier.height(10.dp))
                        LoginButton("Back", enabled = !working, busy = false, outlined = true) { tap(); problem = null; step = LoginStep.SIGN_IN }
                    }
                    LoginStep.VERIFY -> {
                        // Bluesky's own human check, shown right here. Its
                        // page hands back a one-time code when it's passed,
                        // and the account is made with it.
                        val gateState = remember { com.mediaviewer.platform.randomUuidString().replace("-", "").take(15) }
                        val browser = remember {
                            BrowserState(
                                "https://bsky.social/gate/signup?handle=" + com.mediaviewer.platform.urlEncode(fullHandle) +
                                    "&state=" + gateState + "&colorScheme=dark"
                            // (The check has pieces to drag: the page around
                            // it mustn't take those drags over as scrolling.)
                            ).also { it.holdsTouches = true }
                        }
                        DisposableEffect(Unit) { onDispose { browser.dispose() } }
                        val opened = remember { com.mediaviewer.platform.currentTimeMillis() }
                        var done by remember { mutableStateOf(false) }
                        // Every address the page reaches is checked as it
                        // happens: the check finishes by going to Bluesky's
                        // site with the code, which must be caught before
                        // that site opens here.
                        var gateCode by remember { mutableStateOf<String?>(null) }
                        DisposableEffect(browser) {
                            browser.onUrl = { address -> if (gateCode == null) gateCodeFrom(address, gateState)?.let { gateCode = it } }
                            onDispose { browser.onUrl = null }
                        }
                        LaunchedEffect(gateCode) {
                            val code = gateCode ?: return@LaunchedEffect
                            if (done) return@LaunchedEffect
                            done = true
                            // (Bluesky's app gives the check a few seconds too.)
                            val wait = 3500L - (com.mediaviewer.platform.currentTimeMillis() - opened)
                            if (wait > 0) kotlinx.coroutines.delay(wait)
                            createAccount(code)
                        }
                        // Tall enough for the whole check, instructions included.
                        val checkHeight = (pageHeight - rememberTopCutoutClearance() - 170.dp).coerceAtLeast(480.dp)
                        Box(
                            Modifier.fillMaxWidth().height(checkHeight).clip(RoundedCornerShape(24.dp))
                                .background(Color.Black).border(1.dp, LoginPink.copy(alpha = 0.6f), RoundedCornerShape(24.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            if (done || working || gateCode != null) CircularProgressIndicator(Modifier.size(24.dp), color = Color.White, strokeWidth = 2.dp)
                            else PlatformBrowserView(browser, Modifier.fillMaxSize())
                        }
                        Spacer(Modifier.height(12.dp))
                        LoginButton("Back", enabled = !working && !done, busy = false, outlined = true) { tap(); step = LoginStep.CREATE }
                    }
                    LoginStep.EMAIL -> {
                        Text(
                            newEmail.trim(), color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp, fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center, maxLines = 1, modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(14.dp))
                        LoginField(
                            value = emailCode, onValueChange = { emailCode = it.trim().take(16) },
                            placeholder = "Email code", icon = Icons.Default.MarkEmailRead,
                            keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done,
                            onImeAction = { confirmEmail() }, noAutoCorrect = true
                        )
                        Spacer(Modifier.height(16.dp))
                        LoginButton("Verify", enabled = emailCode.isNotBlank() && !working, busy = working) { tap(); confirmEmail() }
                        Spacer(Modifier.height(10.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(Modifier.weight(1f)) {
                                LoginButton("Resend", enabled = !working, busy = false, outlined = true) {
                                    tap()
                                    val resend = flow.resendEmail
                                    if (resend != null) scope.launch {
                                        problem = null
                                        val error = resend()
                                        if (error == null) notice = "Sent" else problem = error
                                    }
                                }
                            }
                            Box(Modifier.weight(1f)) {
                                LoginButton("Skip", enabled = !working, busy = false, outlined = true) {
                                    tap(); working = true; problem = null; notice = "Signing you in…"; flow.finish?.invoke()
                                }
                            }
                        }
                    }
                }
                val message = problem ?: notice
                if (message != null) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        message, color = if (problem != null) Color(0xFFFF8A80) else Color.White.copy(alpha = 0.7f),
                        fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
                    )
                }
                Spacer(Modifier.height(40.dp))
            }
        }
        if (onClose != null) {
            val notchY = rememberNotchCenterY()
            Box(
                Modifier.align(Alignment.TopStart).padding(start = 20.dp)
                    .offset(y = (notchY - 20.dp).coerceAtLeast(4.dp))
                    .size(40.dp).clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.1f))
                    .border(1.dp, LoginPink.copy(alpha = 0.5f), CircleShape)
                    .clickable { tap(); onClose() },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }
    }
}

private const val NEW_HANDLE_SUFFIX = ".bsky.social"

/** Bluesky's rules for the name in front of ".bsky.social". */
private fun isValidNewName(name: String): Boolean =
    name.length in 3..18 && !name.startsWith("-") && !name.endsWith("-") && name.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }

/** Digits typed into the birthday field, shown as MM/DD/YYYY. */
private fun formatBirthday(typed: String): String {
    val d = typed.filter { it.isDigit() }.take(8)
    return buildString {
        d.forEachIndexed { i, c ->
            if (i == 2 || i == 4) append('/')
            append(c)
        }
    }
}

/** "MM/DD/YYYY" → "YYYY-MM-DDT00:00:00.000Z", or null if it isn't a real date. */
private fun birthdayToIso(text: String): String? {
    val d = text.filter { it.isDigit() }
    if (d.length != 8) return null
    val month = d.substring(0, 2).toInt()
    val day = d.substring(2, 4).toInt()
    val year = d.substring(4, 8).toInt()
    if (month !in 1..12 || year !in 1900..2100) return null
    if (day !in 1..com.mediaviewer.util.CalendarMath.daysInMonth(year, month)) return null
    return d.substring(4, 8) + "-" + d.substring(0, 2) + "-" + d.substring(2, 4) + "T00:00:00.000Z"
}

/** Bluesky accounts are for people 13 and over. */
private fun isOldEnough(iso: String?): Boolean {
    val key = iso?.take(10)?.filter { it.isDigit() }?.toIntOrNull() ?: return false
    val today = com.mediaviewer.util.CalendarMath.todayKey()
    return key <= today && (today - key) / 10000 >= 13
}

/** The one-time code in the address Bluesky's human check finishes on
 *  (https://bsky.app/?state=…&code=…), if [url] is that address and
 *  carries the state this page started with. */
private fun gateCodeFrom(url: String, state: String): String? {
    val rest = url.substringAfter("://", "")
    val host = rest.substringBefore('/').substringBefore('?').lowercase()
    if (!url.startsWith("https://") || host !in GATE_FINISH_HOSTS) return null
    val path = rest.substringAfter('/', "").substringBefore('?').substringBefore('#')
    if (path.startsWith("gate/")) return null
    val query = url.substringAfter('?', "").substringBefore('#')
    if (query.isEmpty()) return null
    val params = query.split('&').mapNotNull { part ->
        val i = part.indexOf('=')
        if (i <= 0) null else part.substring(0, i) to runCatching { com.mediaviewer.platform.urlDecode(part.substring(i + 1)) }.getOrDefault(part.substring(i + 1))
    }.toMap()
    if (params["state"] != state) return null
    return params["code"]?.takeIf { it.isNotBlank() }
}

private val GATE_FINISH_HOSTS = setOf("bsky.app", "bsky.social", "www.bsky.app")

/** The page's pill button: filled pink, or outlined for the second choice. */
@Composable
private fun LoginButton(label: String, enabled: Boolean, busy: Boolean, outlined: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(shape)
            .then(
                if (outlined) Modifier.background(Color.White.copy(alpha = 0.05f))
                    .border(1.dp, LoginPink.copy(alpha = if (enabled) 0.75f else 0.3f), shape)
                else Modifier.background(
                    if (enabled) Brush.horizontalGradient(listOf(LoginPink, lerp(LoginPink, Color.White, 0.18f)))
                    else Brush.horizontalGradient(listOf(LoginPink.copy(alpha = 0.35f), LoginPink.copy(alpha = 0.35f)))
                )
            )
            .clickable(enabled = enabled && !busy, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
        else Text(
            label, color = Color.White.copy(alpha = if (enabled) 1f else 0.6f), fontSize = 15.sp,
            fontWeight = if (outlined) FontWeight.SemiBold else FontWeight.Bold
        )
    }
}

/** A compact rounded login input: icon, text, optional trailing control;
 *  the rim turns pink while it's focused. */
@Composable
private fun LoginField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    icon: ImageVector,
    keyboardType: KeyboardType,
    imeAction: ImeAction,
    onImeAction: () -> Unit,
    modifier: Modifier = Modifier,
    password: Boolean = false,
    noAutoCorrect: Boolean = false,
    trailing: (@Composable () -> Unit)? = null
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(24.dp)
    BasicTextField(
        value = value, onValueChange = onValueChange, singleLine = true,
        interactionSource = interaction,
        textStyle = LocalTextStyle.current.copy(color = Color.White, fontSize = 14.sp),
        cursorBrush = SolidColor(LoginPink),
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = if (noAutoCorrect) KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = keyboardType, imeAction = imeAction
        ) else KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            keyboardType = keyboardType, imeAction = imeAction
        ),
        keyboardActions = KeyboardActions(onNext = { onImeAction() }, onDone = { onImeAction() }),
        modifier = modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Row(
                Modifier.fillMaxWidth().height(48.dp).clip(shape)
                    .background(Color.White.copy(alpha = 0.06f))
                    .border(1.dp, if (focused) LoginPink.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.16f), shape)
                    .padding(start = 16.dp, end = if (trailing != null) 6.dp else 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(icon, contentDescription = null, tint = if (focused) LoginPink else Color.White.copy(alpha = 0.5f), modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Text(placeholder, color = Color.White.copy(alpha = 0.4f), fontSize = 14.sp, maxLines = 1)
                    inner()
                }
                if (trailing != null) trailing()
            }
        }
    )
}
