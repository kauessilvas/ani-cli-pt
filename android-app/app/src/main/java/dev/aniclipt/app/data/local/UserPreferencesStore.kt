package dev.aniclipt.app.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.userPreferencesDataStore: DataStore<Preferences> by preferencesDataStore(name = "user_preferences")

data class UserPreferences(
    val lightTheme: Boolean = false,
    val hideAdult: Boolean = false,
    val defaultDubbed: Boolean = false,
    val videoAspectRatio: String = "fit",
    val quality: String = "best",
    val subtitleSize: String = "medium",
    val subtitleColor: String = "white",
    val subtitleFont: String = "sans",
    val subtitleBackground: String = "none",
    val subtitleOutlineColor: String = "black",
    val subtitleOutlineThickness: String = "thick",
    val subtitlePosition: String = "bottom",
    val openSubtitlesApiKey: String = "",
    val openSubtitlesEnabled: Boolean = false,
    val episodeNotifications: Boolean = false,
    val automaticReleaseUpdates: Boolean = false,
)

/** Simple user settings only. Structured history, favorites and metadata belong to Room. */
class UserPreferencesStore(context: Context) {
    private val appContext = context.applicationContext
    private val store = appContext.userPreferencesDataStore

    val preferences: Flow<UserPreferences> = store.data.catch { error ->
        if (error is IOException) emit(emptyPreferences()) else throw error
    }.map { values -> values.toUserPreferences() }

    suspend fun current(): UserPreferences = preferences.first()

    /** Idempotent import. Do not delete legacy prefs until every old reader and worker is migrated. */
    suspend fun migrateLegacyIfNeeded() {
        val legacy = appContext.getSharedPreferences("ani-cli-pt", Context.MODE_PRIVATE)
        store.edit { values ->
            if (values[Keys.migrated] == true) return@edit
            Keys.booleans.forEach { key -> if (legacy.contains(key.name)) values[key] = legacy.getBoolean(key.name, false) }
            Keys.strings.forEach { key -> if (legacy.contains(key.name)) values[key] = legacy.getString(key.name, "").orEmpty() }
            values[Keys.migrated] = true
        }
    }

    suspend fun setLightTheme(value: Boolean) = set(Keys.lightTheme, value)
    suspend fun setHideAdult(value: Boolean) = set(Keys.hideAdult, value)
    suspend fun setDefaultDubbed(value: Boolean) = set(Keys.defaultDubbed, value)
    suspend fun setVideoAspectRatio(value: String) = set(Keys.videoAspectRatio, value)
    suspend fun setQuality(value: String) = set(Keys.quality, value)
    suspend fun setSubtitleSize(value: String) = set(Keys.subtitleSize, value)
    suspend fun setSubtitleColor(value: String) = set(Keys.subtitleColor, value)
    suspend fun setSubtitleFont(value: String) = set(Keys.subtitleFont, value)
    suspend fun setSubtitleBackground(value: String) = set(Keys.subtitleBackground, value)
    suspend fun setSubtitleOutlineColor(value: String) = set(Keys.subtitleOutlineColor, value)
    suspend fun setSubtitleOutlineThickness(value: String) = set(Keys.subtitleOutlineThickness, value)
    suspend fun setSubtitlePosition(value: String) = set(Keys.subtitlePosition, value)
    suspend fun setOpenSubtitlesApiKey(value: String) = set(Keys.openSubtitlesApiKey, value)
    suspend fun setOpenSubtitlesEnabled(value: Boolean) = set(Keys.openSubtitlesEnabled, value)
    suspend fun setEpisodeNotifications(value: Boolean) = set(Keys.episodeNotifications, value)
    suspend fun setAutomaticReleaseUpdates(value: Boolean) = set(Keys.automaticReleaseUpdates, value)

    private suspend fun set(key: Preferences.Key<Boolean>, value: Boolean) { store.edit { it[key] = value } }
    private suspend fun set(key: Preferences.Key<String>, value: String) { store.edit { it[key] = value } }

    private object Keys {
        val migrated = booleanPreferencesKey("legacy_preferences_imported_v1")
        val lightTheme = booleanPreferencesKey("light_theme")
        val hideAdult = booleanPreferencesKey("hide_adult")
        val defaultDubbed = booleanPreferencesKey("default_dubbed")
        val videoAspectRatio = stringPreferencesKey("video_aspect_ratio")
        val quality = stringPreferencesKey("quality")
        val subtitleSize = stringPreferencesKey("subtitle_size")
        val subtitleColor = stringPreferencesKey("subtitle_color")
        val subtitleFont = stringPreferencesKey("subtitle_font")
        val subtitleBackground = stringPreferencesKey("subtitle_background")
        val subtitleOutlineColor = stringPreferencesKey("subtitle_outline_color")
        val subtitleOutlineThickness = stringPreferencesKey("subtitle_outline_thickness")
        val subtitlePosition = stringPreferencesKey("subtitle_position")
        val openSubtitlesApiKey = stringPreferencesKey("opensubtitles_api_key")
        val openSubtitlesEnabled = booleanPreferencesKey("opensubtitles_enabled")
        val episodeNotifications = booleanPreferencesKey("episode_notifications")
        val automaticReleaseUpdates = booleanPreferencesKey("automatic_release_updates")

        val booleans = listOf(lightTheme, hideAdult, defaultDubbed, openSubtitlesEnabled, episodeNotifications, automaticReleaseUpdates)
        val strings = listOf(videoAspectRatio, quality, subtitleSize, subtitleColor, subtitleFont,
            subtitleBackground, subtitleOutlineColor, subtitleOutlineThickness, subtitlePosition, openSubtitlesApiKey)
    }

    private fun Preferences.toUserPreferences() = UserPreferences(
        lightTheme = this[Keys.lightTheme] ?: false,
        hideAdult = this[Keys.hideAdult] ?: false,
        defaultDubbed = this[Keys.defaultDubbed] ?: false,
        videoAspectRatio = this[Keys.videoAspectRatio] ?: "fit",
        quality = this[Keys.quality] ?: "best",
        subtitleSize = this[Keys.subtitleSize] ?: "medium",
        subtitleColor = this[Keys.subtitleColor] ?: "white",
        subtitleFont = this[Keys.subtitleFont] ?: "sans",
        subtitleBackground = this[Keys.subtitleBackground] ?: "none",
        subtitleOutlineColor = this[Keys.subtitleOutlineColor] ?: "black",
        subtitleOutlineThickness = this[Keys.subtitleOutlineThickness] ?: "thick",
        subtitlePosition = this[Keys.subtitlePosition] ?: "bottom",
        openSubtitlesApiKey = this[Keys.openSubtitlesApiKey] ?: "",
        openSubtitlesEnabled = this[Keys.openSubtitlesEnabled] ?: false,
        episodeNotifications = this[Keys.episodeNotifications] ?: false,
        automaticReleaseUpdates = this[Keys.automaticReleaseUpdates] ?: false,
    )
}
