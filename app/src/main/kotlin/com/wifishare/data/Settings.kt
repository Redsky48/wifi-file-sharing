package com.wifishare.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

data class AppSettings(
    val folderUri: String? = null,
    val folderDisplay: String = "",
    val port: Int = 8080,
    val allowUploads: Boolean = true,
    val allowDelete: Boolean = false,
    val autoStart: Boolean = false,
    val passwordEnabled: Boolean = false,
    val password: String = "",
)

class SettingsRepository(private val context: Context) {

    private object Keys {
        val FOLDER_URI = stringPreferencesKey("folder_uri")
        val FOLDER_DISPLAY = stringPreferencesKey("folder_display")
        val PORT = intPreferencesKey("port")
        val ALLOW_UPLOADS = booleanPreferencesKey("allow_uploads")
        val ALLOW_DELETE = booleanPreferencesKey("allow_delete")
        val AUTO_START = booleanPreferencesKey("auto_start")
        val PASSWORD_ENABLED = booleanPreferencesKey("password_enabled")
        val PASSWORD = stringPreferencesKey("password")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { it.toSettings() }

    suspend fun setFolder(uri: String, display: String) = context.dataStore.edit {
        it[Keys.FOLDER_URI] = uri
        it[Keys.FOLDER_DISPLAY] = display
    }

    suspend fun setPort(port: Int) = context.dataStore.edit { it[Keys.PORT] = port }

    suspend fun setAllowUploads(value: Boolean) =
        context.dataStore.edit { it[Keys.ALLOW_UPLOADS] = value }

    suspend fun setAllowDelete(value: Boolean) =
        context.dataStore.edit { it[Keys.ALLOW_DELETE] = value }

    suspend fun setAutoStart(value: Boolean) =
        context.dataStore.edit { it[Keys.AUTO_START] = value }

    suspend fun setPasswordEnabled(value: Boolean) =
        context.dataStore.edit { it[Keys.PASSWORD_ENABLED] = value }

    suspend fun setPassword(value: String) =
        context.dataStore.edit { it[Keys.PASSWORD] = value }

    private fun Preferences.toSettings() = AppSettings(
        folderUri = this[Keys.FOLDER_URI],
        folderDisplay = this[Keys.FOLDER_DISPLAY] ?: "",
        port = this[Keys.PORT] ?: 8080,
        allowUploads = this[Keys.ALLOW_UPLOADS] ?: true,
        allowDelete = this[Keys.ALLOW_DELETE] ?: false,
        autoStart = this[Keys.AUTO_START] ?: false,
        passwordEnabled = this[Keys.PASSWORD_ENABLED] ?: false,
        password = this[Keys.PASSWORD] ?: "",
    )
}
