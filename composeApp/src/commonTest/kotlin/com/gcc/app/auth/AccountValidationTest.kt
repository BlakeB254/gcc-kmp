package com.gcc.app.auth

import com.gcc.shared.auth.AccountValidation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Lives in composeApp commonTest — shared/'s own commonTest is broken on all
// targets (JdbcSqliteDriver leak); see repo memory/CLAUDE.md.
class AccountValidationTest {

    @Test
    fun nameRules() {
        assertNull(AccountValidation.validateName("Blake B"))
        assertNull(AccountValidation.validateName("  Al  ")) // trims to 2
        assertNotNull(AccountValidation.validateName("B"))
        assertNotNull(AccountValidation.validateName("x".repeat(101)))
    }

    @Test
    fun emailRules() {
        assertNull(AccountValidation.validateEmail("worker@example.com"))
        assertNull(AccountValidation.validateEmail(" Worker@Example.COM "))
        assertNotNull(AccountValidation.validateEmail("worker@example"))
        assertNotNull(AccountValidation.validateEmail("workerexample.com"))
        assertNotNull(AccountValidation.validateEmail("wo rker@example.com@x.co@")) // stays invalid after strip
        assertNotNull(AccountValidation.validateEmail("a@" + "b".repeat(100) + ".com"))
    }

    @Test
    fun emailNormalization() {
        assertEquals("worker@example.com", AccountValidation.normalizeEmail(" Worker@Example.COM "))
    }

    @Test
    fun phoneRules() {
        assertNull(AccountValidation.validatePhone("+1 (773) 555-0142"))
        assertNull(AccountValidation.validatePhone("7735550142"))
        assertNotNull(AccountValidation.validatePhone("555-014"))  // 6 digits
        assertNotNull(AccountValidation.validatePhone("1".repeat(16)))
        assertNotNull(AccountValidation.validatePhone("no digits here"))
    }

    @Test
    fun newPasswordRules() {
        assertNull(AccountValidation.validateNewPassword("longenough", "longenough"))
        assertNotNull(AccountValidation.validateNewPassword("short", "short"))
        assertNotNull(AccountValidation.validateNewPassword("longenough", "different"))
    }

    @Test
    fun totpCodeShape() {
        assertTrue(AccountValidation.isTotpCode("123456"))
        assertTrue(AccountValidation.isTotpCode("123 456"))
        assertFalse(AccountValidation.isTotpCode("12345"))
        assertFalse(AccountValidation.isTotpCode("12345a"))
    }

    @Test
    fun recoveryCodeShape() {
        assertTrue(AccountValidation.isRecoveryCode("AB3D-E2F9"))
        assertTrue(AccountValidation.isRecoveryCode("ab3d e2f9"))
        assertFalse(AccountValidation.isRecoveryCode("AB3D-E2F"))
        assertFalse(AccountValidation.isRecoveryCode("123456")) // six digits is TOTP, not recovery
    }

    @Test
    fun secondFactorAcceptsEitherForm() {
        assertTrue(AccountValidation.isSecondFactor("123456"))
        assertTrue(AccountValidation.isSecondFactor("AB3D-E2F9"))
        assertFalse(AccountValidation.isSecondFactor("nope"))
    }
}
