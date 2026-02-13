package com.xelth.eckwms_movfast.ui.viewmodels

import com.xelth.eckwms_movfast.utils.SettingsManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AppUser(
    val id: String,
    val username: String,  // nickname (short, for button display)
    val name: String,      // full name (for dialogs)
    val role: String
) {
    /** Display label for the hex button — uses username (nickname), not full name. */
    fun getDisplayLabel(): String {
        val display = username.ifEmpty { name.ifEmpty { id.take(6) } }
        return if (display.length <= 6) display else display.take(6)
    }

    /** Display name for dialogs — full name if available, otherwise username. */
    fun getDialogName(): String = if (name.isNotEmpty()) "$name ($username)" else username
}

/**
 * Singleton managing multi-user state on shared PDA device.
 *
 * - currentUser: who is logged in (set via long press + PIN)
 * - viewingUser: whose data we are looking at (set via short press)
 * - availableUsers: fetched from server
 *
 * Colors:
 *   Green  = acting as self (currentUser == viewingUser)
 *   Yellow = viewing another user's data
 *   Red    = not logged in
 */
object UserManager {

    private val _currentUser = MutableStateFlow<AppUser?>(null)
    val currentUser: StateFlow<AppUser?> = _currentUser.asStateFlow()

    private val _viewingUser = MutableStateFlow<AppUser?>(null)
    val viewingUser: StateFlow<AppUser?> = _viewingUser.asStateFlow()

    private val _availableUsers = MutableStateFlow<List<AppUser>>(emptyList())
    val availableUsers: StateFlow<List<AppUser>> = _availableUsers.asStateFlow()

    fun setAvailableUsers(users: List<AppUser>) {
        _availableUsers.value = users
    }

    /** Long press: login as user (Anmeldung). Resets viewingUser to self. */
    fun login(user: AppUser) {
        _currentUser.value = user
        _viewingUser.value = user
        // Persist to survive app restart
        SettingsManager.saveCurrentUser(user.id, user.username)
    }

    /** Short press: switch to view another user's data. */
    fun switchView(user: AppUser) {
        _viewingUser.value = user
    }

    /** Reset view back to self. */
    fun resetViewToSelf() {
        _viewingUser.value = _currentUser.value
    }

    fun isLoggedIn(): Boolean = _currentUser.value != null

    fun isActingAsSelf(): Boolean =
        _currentUser.value != null && _currentUser.value?.id == _viewingUser.value?.id

    /** Background color for the user button (dark tones matching network indicator). */
    fun getButtonColor(): String = when {
        _currentUser.value == null -> "#8B0000"  // Dark red: not logged in
        isActingAsSelf() -> "#1B5E20"            // Dark green: acting as self
        else -> "#5D4037"                        // Dark brown: viewing another user
    }

    /** Text color for the user button (matching network indicator style). */
    fun getButtonTextColor(): String = when {
        _currentUser.value == null -> "#FFFFFF"  // White on dark red
        isActingAsSelf() -> "#4CAF50"            // Light green on dark green
        else -> "#FFEB3B"                        // Yellow on dark brown
    }

    /** Label for the user button. */
    fun getButtonLabel(): String {
        val user = _viewingUser.value ?: _currentUser.value
        return user?.getDisplayLabel() ?: "LOG\nIN"
    }

    /** Restore persisted user on app start. */
    fun restoreFromSettings() {
        val id = SettingsManager.getCurrentUserId()
        val username = SettingsManager.getCurrentUserName() // stored as username
        if (id.isNotEmpty() && username.isNotEmpty()) {
            val restored = AppUser(id, username, "", "")
            _currentUser.value = restored
            _viewingUser.value = restored
        }
    }

    /** Logout current user. */
    fun logout() {
        _currentUser.value = null
        _viewingUser.value = null
        SettingsManager.saveCurrentUser("", "")
    }
}
