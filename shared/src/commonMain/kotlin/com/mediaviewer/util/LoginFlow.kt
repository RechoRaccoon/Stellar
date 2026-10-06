package com.mediaviewer.util

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * What the sign-in page needs beyond "handle + password": the emailed code
 * for accounts with two-factor sign-in, and the Create Account steps. The
 * ViewModel fills in the actions; the page only shows them. Passwords are
 * never kept here (or anywhere) — they go straight to the account's server.
 */
object LoginFlow {
    /** Bluesky has emailed a sign-in code: the page shows a field for it. */
    var needsCode by mutableStateOf(false)
    /** What's typed in that field. */
    var code by mutableStateOf("")

    // ── Create Account ──
    /** True = Bluesky wants its human check first. */
    var needsVerification: (suspend () -> Boolean)? = null
    /** True = free, false = taken, null = couldn't tell. */
    var handleAvailable: (suspend (handle: String) -> Boolean?)? = null
    /** Makes the account; returns an error, or null once it exists (the
     *  confirmation email is sent as part of this). */
    var createAccount: (suspend (email: String, handle: String, password: String, verificationCode: String?, birthDate: String) -> String?)? = null
    var resendEmail: (suspend () -> String?)? = null
    var confirmEmail: (suspend (email: String, code: String) -> String?)? = null
    /** Opens the app on the account just made. */
    var finish: (() -> Unit)? = null

    fun reset() { needsCode = false; code = "" }
}
