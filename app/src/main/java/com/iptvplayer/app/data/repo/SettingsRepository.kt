package com.iptvplayer.app.data.repo

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.iptvplayer.app.data.model.ChannelSort
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** App settings persisted in DataStore. */
data class AppSettings(
    val theme: String, // SYSTEM | DARK | LIGHT
    val showLogos: Boolean,
    val channelFontScale: Int,
    val programmeFontScale: Int,
    val preferredAudioLang: String,
    val preferredSubsLang: String,
    val defaultSort: ChannelSort,
    val parentalEnabled: Boolean,
    val lastPlaylistId: Long,
    val autoRefreshHours: Int, // 0 = off
    val autoUpdateApp: Boolean,
    val lastUpdateCheck: Long,
    val updateRepoOverride: String, // "" = use BuildConfig.UPDATE_REPO_SLUG
)

class SettingsRepository(private val context: Context) {

    private object Keys {
        val THEME = stringPreferencesKey("theme")
        val SHOW_LOGOS = booleanPreferencesKey("show_logos")
        val CHANNEL_FONT_SCALE = intPreferencesKey("channel_font_scale")
        val PROGRAMME_FONT_SCALE = intPreferencesKey("programme_font_scale")
        val PREFERRED_AUDIO_LANG = stringPreferencesKey("preferred_audio_lang")
        val PREFERRED_SUBS_LANG = stringPreferencesKey("preferred_subs_lang")
        val DEFAULT_SORT = stringPreferencesKey("default_sort")
        val PARENTAL_ENABLED = booleanPreferencesKey("parental_enabled")
        val PIN_HASH = stringPreferencesKey("pin_hash") // "salt:hash" hex
        val LAST_PLAYLIST = longPreferencesKey("last_playlist")
        val AUTO_REFRESH_HOURS = intPreferencesKey("auto_refresh_hours")
        val AUTO_UPDATE_APP = booleanPreferencesKey("auto_update_app")
        val LAST_UPDATE_CHECK = longPreferencesKey("last_update_check")
        val UPDATE_REPO_OVERRIDE = stringPreferencesKey("update_repo_override")
    }

    companion object {
        /** Default values, also used as a placeholder before the first emission. */
        val DEFAULTS = AppSettings(
        theme = "SYSTEM",
        showLogos = true,
        channelFontScale = 100,
        programmeFontScale = 100,
        preferredAudioLang = "",
        preferredSubsLang = "",
        defaultSort = ChannelSort.ORDER,
        parentalEnabled = false,
        lastPlaylistId = 0L,
        autoRefreshHours = 0,
            autoUpdateApp = true,
            lastUpdateCheck = 0L,
            updateRepoOverride = "",
        )
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            theme = p[Keys.THEME] ?: DEFAULTS.theme,
            showLogos = p[Keys.SHOW_LOGOS] ?: DEFAULTS.showLogos,
            channelFontScale = p[Keys.CHANNEL_FONT_SCALE] ?: DEFAULTS.channelFontScale,
            programmeFontScale = p[Keys.PROGRAMME_FONT_SCALE] ?: DEFAULTS.programmeFontScale,
            preferredAudioLang = p[Keys.PREFERRED_AUDIO_LANG] ?: DEFAULTS.preferredAudioLang,
            preferredSubsLang = p[Keys.PREFERRED_SUBS_LANG] ?: DEFAULTS.preferredSubsLang,
            defaultSort = runCatching {
                ChannelSort.valueOf(p[Keys.DEFAULT_SORT] ?: DEFAULTS.defaultSort.name)
            }.getOrDefault(DEFAULTS.defaultSort),
            parentalEnabled = p[Keys.PARENTAL_ENABLED] ?: DEFAULTS.parentalEnabled,
            lastPlaylistId = p[Keys.LAST_PLAYLIST] ?: DEFAULTS.lastPlaylistId,
            autoRefreshHours = p[Keys.AUTO_REFRESH_HOURS] ?: DEFAULTS.autoRefreshHours,
            autoUpdateApp = p[Keys.AUTO_UPDATE_APP] ?: DEFAULTS.autoUpdateApp,
            lastUpdateCheck = p[Keys.LAST_UPDATE_CHECK] ?: DEFAULTS.lastUpdateCheck,
            updateRepoOverride = p[Keys.UPDATE_REPO_OVERRIDE] ?: DEFAULTS.updateRepoOverride,
        )
    }

    suspend fun setTheme(value: String) = context.dataStore.edit { it[Keys.THEME] = value }
    suspend fun setShowLogos(value: Boolean) = context.dataStore.edit { it[Keys.SHOW_LOGOS] = value }
    suspend fun setChannelFontScale(value: Int) = context.dataStore.edit { it[Keys.CHANNEL_FONT_SCALE] = value.coerceIn(80, 180) }
    suspend fun setProgrammeFontScale(value: Int) = context.dataStore.edit { it[Keys.PROGRAMME_FONT_SCALE] = value.coerceIn(80, 180) }
    suspend fun setPreferredAudioLang(value: String) =
        context.dataStore.edit { it[Keys.PREFERRED_AUDIO_LANG] = value }
    suspend fun setPreferredSubsLang(value: String) =
        context.dataStore.edit { it[Keys.PREFERRED_SUBS_LANG] = value }
    suspend fun setDefaultSort(value: ChannelSort) =
        context.dataStore.edit { it[Keys.DEFAULT_SORT] = value.name }
    suspend fun setParentalEnabled(value: Boolean) =
        context.dataStore.edit { it[Keys.PARENTAL_ENABLED] = value }
    suspend fun setLastPlaylist(value: Long) =
        context.dataStore.edit { it[Keys.LAST_PLAYLIST] = value }
    suspend fun setAutoRefreshHours(value: Int) =
        context.dataStore.edit { it[Keys.AUTO_REFRESH_HOURS] = value }
    suspend fun setAutoUpdateApp(value: Boolean) =
        context.dataStore.edit { it[Keys.AUTO_UPDATE_APP] = value }
    suspend fun setLastUpdateCheck(value: Long) =
        context.dataStore.edit { it[Keys.LAST_UPDATE_CHECK] = value }
    suspend fun setUpdateRepoOverride(value: String) =
        context.dataStore.edit { it[Keys.UPDATE_REPO_OVERRIDE] = value.trim() }

    // ------------------------------------------------------------- parental PIN

    private fun hashPin(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, 24_000, 128)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
        return factory.generateSecret(spec).encoded
    }

    /** Stores (or clears, when pin is null) the parental control PIN. */
    suspend fun setPin(pin: String?) = context.dataStore.edit { p ->
        if (pin.isNullOrEmpty()) {
            p.remove(Keys.PIN_HASH)
        } else {
            val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val hash = hashPin(pin, salt)
            p[Keys.PIN_HASH] = salt.toHex() + ":" + hash.toHex()
        }
    }

    suspend fun hasPin(): Boolean =
        context.dataStore.data.map { it[Keys.PIN_HASH] != null }.first()

    /** Verifies a PIN. Always false when no PIN is configured. */
    suspend fun checkPin(pin: String): Boolean {
        val stored = context.dataStore.data.map { it[Keys.PIN_HASH] }
        // dataStore.data is a cold flow; take the first emitted value
        val value = stored.first() ?: return false
        val parts = value.split(":")
        if (parts.size != 2) return false
        val salt = parts[0].hexToBytes()
        val expected = parts[1].hexToBytes()
        val actual = hashPin(pin, salt)
        return expected.contentEquals(actual)
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun String.hexToBytes(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
