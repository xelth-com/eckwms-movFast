package com.xelth.eckwms_movfast.ui.viewmodels

import com.xelth.eckwms_movfast.utils.SettingsManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AppUser(
    val id: String,
    val username: String,  // nickname (short, for button display)
    val name: String,      // full name (for dialogs)
    val role: String,
    // True for a bulk-seeded staff account that must set its own password before
    // using the app (mirrors the server's login/user "mustChangePassword" flag).
    val mustChangePassword: Boolean = false
) {
    /** Display label for the hex button — uses username (nickname), not full name. */
    fun getDisplayLabel(): String {
        return username.ifEmpty { name.ifEmpty { id.take(8) } }
    }

    /** Display name for dialogs — full name if available, otherwise username. */
    fun getDialogName(): String = if (name.isNotEmpty()) "$name ($username)" else username
}

/** Result of a PIN login attempt (mirrors the server verify-pin response). */
data class PinAuthResult(
    val ok: Boolean,
    val mustChangePassword: Boolean = false
)

/** Result of a self-service password change. `error` is a display-ready message. */
data class ChangePasswordResult(
    val success: Boolean,
    val error: String? = null
)

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
        // Cache the roster so it survives app restarts AND off-LAN sessions (the
        // master is usually on a different subnet / unreachable — without this
        // the picker is empty until the phone is back on the workshop LAN).
        if (users.isNotEmpty()) {
            try {
                val arr = org.json.JSONArray()
                users.forEach { u ->
                    arr.put(org.json.JSONObject().apply {
                        put("id", u.id); put("username", u.username); put("name", u.name)
                        put("role", u.role); put("mustChangePassword", u.mustChangePassword)
                    })
                }
                SettingsManager.saveUsersRoster(arr.toString())
            } catch (e: Exception) {
                android.util.Log.w("UserManager", "roster cache failed: ${e.message}")
            }
        }
    }

    /** Load the cached roster into memory (call on start before the live fetch,
     *  so users are pickable immediately and even fully offline). */
    fun restoreRosterFromCache() {
        val json = SettingsManager.getUsersRoster()
        if (json.isBlank()) return
        try {
            val arr = org.json.JSONArray(json)
            val list = ArrayList<AppUser>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(AppUser(
                    id = o.getString("id"),
                    username = o.optString("username", ""),
                    name = o.optString("name", ""),
                    role = o.optString("role", "user"),
                    mustChangePassword = o.optBoolean("mustChangePassword", false)
                ))
            }
            if (list.isNotEmpty() && _availableUsers.value.isEmpty()) {
                _availableUsers.value = list
            }
        } catch (e: Exception) {
            android.util.Log.w("UserManager", "roster restore failed: ${e.message}")
        }
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

    /** Clear the forced-password-change flag on the current (and viewing) user
     *  after a successful self-service change, so the app can proceed. */
    fun clearMustChangePassword() {
        val u = _currentUser.value ?: return
        if (!u.mustChangePassword) return
        val cleared = u.copy(mustChangePassword = false)
        _currentUser.value = cleared
        if (_viewingUser.value?.id == u.id) _viewingUser.value = cleared
    }

    /** Logout current user. */
    fun logout() {
        _currentUser.value = null
        _viewingUser.value = null
        SettingsManager.saveCurrentUser("", "")
    }
}
