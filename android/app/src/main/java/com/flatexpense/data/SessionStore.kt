package com.flatexpense.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.flatexpense.BuildConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "flat_expense_session")

data class Session(
    val token: String? = null,
    val userId: Long = 0,
    val userName: String = "",
    val groupId: Long = 0,
    val groupName: String = "",
    val isAdmin: Boolean = false,
    val apiBase: String = BuildConfig.DEFAULT_API_BASE
) {
    val isSignedIn: Boolean get() = !token.isNullOrBlank() && groupId > 0
}

/**
 * Holds the JWT and the selected group. The token is the only credential kept
 * on the device — passwords are never stored, in line with section 16.
 */
class SessionStore(private val context: Context) {

    private object Keys {
        val TOKEN = stringPreferencesKey("token")
        val USER_ID = longPreferencesKey("user_id")
        val USER_NAME = stringPreferencesKey("user_name")
        val GROUP_ID = longPreferencesKey("group_id")
        val GROUP_NAME = stringPreferencesKey("group_name")
        val IS_ADMIN = booleanPreferencesKey("is_admin")
        val API_BASE = stringPreferencesKey("api_base")
    }

    val session: Flow<Session> = context.dataStore.data.map { it.toSession() }

    private fun Preferences.toSession() = Session(
        token = this[Keys.TOKEN],
        userId = this[Keys.USER_ID] ?: 0,
        userName = this[Keys.USER_NAME] ?: "",
        groupId = this[Keys.GROUP_ID] ?: 0,
        groupName = this[Keys.GROUP_NAME] ?: "",
        isAdmin = this[Keys.IS_ADMIN] ?: false,
        apiBase = this[Keys.API_BASE] ?: BuildConfig.DEFAULT_API_BASE
    )

    suspend fun current(): Session = session.first()

    suspend fun save(
        token: String,
        userId: Long,
        userName: String,
        groupId: Long,
        groupName: String,
        isAdmin: Boolean
    ) {
        context.dataStore.edit {
            it[Keys.TOKEN] = token
            it[Keys.USER_ID] = userId
            it[Keys.USER_NAME] = userName
            it[Keys.GROUP_ID] = groupId
            it[Keys.GROUP_NAME] = groupName
            it[Keys.IS_ADMIN] = isAdmin
        }
    }

    /**
     * Admin-ness is a property of the group, not a fixed fact about the user —
     * it moves when the Admin transfers the role. Transferring it away revokes
     * your own rights, so the flag is re-read from /auth/me rather than left at
     * whatever it was at sign-in.
     */
    suspend fun setIsAdmin(value: Boolean) {
        context.dataStore.edit { it[Keys.IS_ADMIN] = value }
    }

    suspend fun setApiBase(value: String) {
        val normalised = value.trim().let { if (it.endsWith("/")) it else "$it/" }
        context.dataStore.edit { it[Keys.API_BASE] = normalised }
    }

    /** Clears the token but keeps the API base, which is device configuration. */
    suspend fun signOut() {
        context.dataStore.edit {
            it.remove(Keys.TOKEN)
            it.remove(Keys.USER_ID)
            it.remove(Keys.USER_NAME)
            it.remove(Keys.GROUP_ID)
            it.remove(Keys.GROUP_NAME)
            it.remove(Keys.IS_ADMIN)
        }
    }
}
