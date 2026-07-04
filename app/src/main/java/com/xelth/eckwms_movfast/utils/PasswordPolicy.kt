package com.xelth.eckwms_movfast.utils

/**
 * Client-side password-change validation. Mirrors the rules the WMS server
 * enforces (newPassword >= 8 chars, newPassword != oldPassword, oldPassword
 * present) so the user gets instant feedback before the network round-trip.
 * Pure function — no Android APIs — so it is unit-testable off-device.
 */
object PasswordPolicy {
    const val MIN_LENGTH = 8

    /** Returns an English error message, or null when the input is valid. */
    fun validate(oldPassword: String, newPassword: String, confirmPassword: String): String? {
        if (oldPassword.isBlank()) return "Enter your current password"
        if (newPassword.length < MIN_LENGTH) return "New password must be at least $MIN_LENGTH characters"
        if (newPassword == oldPassword) return "New password must be different from the current one"
        if (newPassword != confirmPassword) return "New password and confirmation do not match"
        return null
    }
}
