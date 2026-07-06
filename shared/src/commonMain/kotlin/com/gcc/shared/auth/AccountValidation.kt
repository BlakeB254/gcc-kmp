package com.gcc.shared.auth

/**
 * Client-side validation for the account-settings flows. Pure functions so
 * they unit-test without Ktor; the server re-validates everything.
 * Returns null when valid, otherwise a user-facing error message.
 */
object AccountValidation {

    fun validateName(name: String): String? =
        if (name.trim().length in 2..100) null else "Name must be 2-100 characters"

    fun normalizeEmail(email: String): String =
        email.filterNot { it.isWhitespace() }.lowercase()

    private val emailRegex = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

    fun validateEmail(email: String): String? {
        val cleaned = normalizeEmail(email)
        return if (emailRegex.matches(cleaned) && cleaned.length <= 100) null
        else "Enter a valid email address"
    }

    fun validatePhone(phone: String): String? {
        val digits = phone.count { it.isDigit() }
        return if (digits in 7..15) null else "Enter a valid phone number"
    }

    fun validateNewPassword(password: String, confirm: String): String? = when {
        password.length < 8 -> "Password must be at least 8 characters"
        password != confirm -> "Passwords don't match"
        else -> null
    }

    /** Six digits — a live authenticator code. */
    fun isTotpCode(code: String): Boolean {
        val cleaned = code.filterNot { it.isWhitespace() }
        return cleaned.length == 6 && cleaned.all { it.isDigit() }
    }

    /** Eight alphanumerics once separators are stripped — a recovery code (XXXX-XXXX). */
    fun isRecoveryCode(code: String): Boolean =
        code.filter { it.isLetterOrDigit() }.length == 8

    /** The login/disable second-factor field accepts either form. */
    fun isSecondFactor(code: String): Boolean = isTotpCode(code) || isRecoveryCode(code)
}
