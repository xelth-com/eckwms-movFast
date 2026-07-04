package com.xelth.eckwms_movfast.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure-function tests for [PasswordPolicy.validate]. Mirrors the WMS server's
 * change-password rules (newPassword >= 8, != oldPassword, oldPassword present,
 * new == confirm). No Android APIs, so it runs as a plain JVM test.
 */
class PasswordPolicyTest {

    @Test
    fun `valid input returns null`() {
        assertNull(PasswordPolicy.validate("oldpass1", "newpass1", "newpass1"))
    }

    @Test
    fun `blank current password is rejected`() {
        assertNotNull(PasswordPolicy.validate("", "newpass1", "newpass1"))
        assertNotNull(PasswordPolicy.validate("   ", "newpass1", "newpass1"))
    }

    @Test
    fun `new password shorter than 8 is rejected`() {
        assertNotNull(PasswordPolicy.validate("oldpass1", "short7c", "short7c"))
    }

    @Test
    fun `exactly 8 characters is accepted`() {
        assertNull(PasswordPolicy.validate("oldpass1", "12345678", "12345678"))
    }

    @Test
    fun `new equal to old is rejected`() {
        assertNotNull(PasswordPolicy.validate("samepass", "samepass", "samepass"))
    }

    @Test
    fun `mismatched confirmation is rejected`() {
        assertNotNull(PasswordPolicy.validate("oldpass1", "newpass1", "newpass2"))
    }

    @Test
    fun `length check runs before mismatch check`() {
        // A too-short new password surfaces the length error regardless of confirm.
        assertEquals(
            "New password must be at least 8 characters",
            PasswordPolicy.validate("oldpass1", "short", "different")
        )
    }
}
