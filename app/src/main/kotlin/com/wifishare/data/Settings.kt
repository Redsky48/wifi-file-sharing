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
    val quickConnectVisible: Boolean = true,
    val useBiometric: Boolean = false,
    val adaptiveBitrate: Boolean = true,
    val notificationsEnabled: Boolean = true,
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
        val QUICK_CONNECT = booleanPreferencesKey("quick_connect")
        val USE_BIOMETRIC = booleanPreferencesKey("use_biometric")
        val ADAPTIVE_BITRATE = booleanPreferencesKey("adaptive_bitrate")
        val NOTIFICATIONS = booleanPreferencesKey("notifications")
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

    suspend fun setQuickConnectVisible(value: Boolean) =
        context.dataStore.edit { it[Keys.QUICK_CONNECT] = value }

    suspend fun setUseBiometric(value: Boolean) =
        context.dataStore.edit { it[Keys.USE_BIOMETRIC] = value }

    suspend fun setAdaptiveBitrate(value: Boolean) =
        context.dataStore.edit { it[Keys.ADAPTIVE_BITRATE] = value }

    suspend fun setNotificationsEnabled(value: Boolean) =
        context.dataStore.edit { it[Keys.NOTIFICATIONS] = value }

    private fun Preferences.toSettings() = AppSettings(
        folderUri = this[Keys.FOLDER_URI],
        folderDisplay = this[Keys.FOLDER_DISPLAY] ?: "",
        port = this[Keys.PORT] ?: 8080,
        allowUploads = this[Keys.ALLOW_UPLOADS] ?: true,
        allowDelete = this[Keys.ALLOW_DELETE] ?: false,
        autoStart = this[Keys.AUTO_START] ?: false,
        passwordEnabled = this[Keys.PASSWORD_ENABLED] ?: false,
        password = this[Keys.PASSWORD] ?: "",
        quickConnectVisible = this[Keys.QUICK_CONNECT] ?: true,
        useBiometric = this[Keys.USE_BIOMETRIC] ?: false,
        adaptiveBitrate = this[Keys.ADAPTIVE_BITRATE] ?: true,
        notificationsEnabled = this[Keys.NOTIFICATIONS] ?: true,
    )
}
