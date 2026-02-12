package com.xelth.eckwms_movfast.ui.viewmodels

import com.xelth.eckwms_movfast.utils.SettingsManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AppUser(
    val id: String,
    val name: String,
    val role: String
) {
    /**
     * Display label for the hexagonal half-button (max 3 lines).
     * Short names → big letters. Long names → abbreviated.
     */
    fun getDisplayLabel(): String {
        val display = name.ifEmpty { id.take(6) }
        val parts = display.split(" ")
        return when {
            parts.size == 1 && parts[0].length <= 5 -> parts[0]
            parts.size == 1 -> parts[0].take(6)
            parts.size >= 2 -> "${parts[0].take(1)}.\n${parts[1].take(7)}"
            else -> display.take(8)
        }
    }
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
        SettingsManager.saveCurrentUser(user.id, user.name)
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

    /** Color hex for the user button. */
    fun getButtonColor(): String = when {
        _currentUser.value == null -> "#F44336"  // Red: not logged in
        isActingAsSelf() -> "#4CAF50"            // Green: acting as self
        else -> "#FFEB3B"                        // Yellow: viewing another user
    }

    /** Label for the user button. */
    fun getButtonLabel(): String {
        val user = _viewingUser.value ?: _currentUser.value
        return user?.getDisplayLabel() ?: "LOG\nIN"
    }

    /** Restore persisted user on app start. */
    fun restoreFromSettings() {
        val id = SettingsManager.getCurrentUserId()
        val name = SettingsManager.getCurrentUserName()
        if (id.isNotEmpty() && name.isNotEmpty()) {
            val restored = AppUser(id, name, "")
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
