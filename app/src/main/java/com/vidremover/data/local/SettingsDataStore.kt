package com.vidremover.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.vidremover.domain.usecase.DetectionMode
import com.vidremover.presentation.viewmodel.AppTheme
import com.vidremover.presentation.viewmodel.UserSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * DataStore for persisting user settings.
 * Provides type-safe access to app preferences with coroutines support.
 *
 * @property context The application context for accessing DataStore
 */
@Singleton
class SettingsDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        val DETECTION_MODE = stringPreferencesKey("detection_mode")
        val PHASH_THRESHOLD = floatPreferencesKey("phash_threshold")
        val THEME = stringPreferencesKey("theme")
    }

    /**
     * Flow of user settings that emits whenever settings change.
     */
    val settingsFlow: Flow<UserSettings> = context.dataStore.data
        .map { preferences ->
            UserSettings(
                detectionMode = preferences[DETECTION_MODE]?.let { modeString ->
                    try {
                        DetectionMode.valueOf(modeString)
                    } catch (e: IllegalArgumentException) {
                        DetectionMode.BOTH
                    }
                } ?: DetectionMode.BOTH,
                pHashThreshold = preferences[PHASH_THRESHOLD] ?: 0.9f,
                theme = preferences[THEME]?.let { themeString ->
                    try {
                        AppTheme.valueOf(themeString)
                    } catch (e: IllegalArgumentException) {
                        AppTheme.SYSTEM
                    }
                } ?: AppTheme.SYSTEM
            )
        }

    /**
     * Saves the detection mode preference.
     *
     * @param mode The detection mode to save
     */
    suspend fun saveDetectionMode(mode: DetectionMode) {
        context.dataStore.edit { preferences ->
            preferences[DETECTION_MODE] = mode.name
        }
    }

    /**
     * Saves the pHash threshold preference.
     *
     * @param threshold The similarity threshold to save (0.0 - 1.0)
     */
    suspend fun savePHashThreshold(threshold: Float) {
        context.dataStore.edit { preferences ->
            preferences[PHASH_THRESHOLD] = threshold.coerceIn(0.0f, 1.0f)
        }
    }

    /**
     * Saves the theme preference.
     *
     * @param theme The theme to save
     */
    suspend fun saveTheme(theme: AppTheme) {
        context.dataStore.edit { preferences ->
            preferences[THEME] = theme.name
        }
    }

    /**
     * Resets all settings to their default values.
     */
    suspend fun resetToDefaults() {
        context.dataStore.edit { preferences ->
            preferences[DETECTION_MODE] = DetectionMode.BOTH.name
            preferences[PHASH_THRESHOLD] = 0.9f
            preferences[THEME] = AppTheme.SYSTEM.name
        }
    }

    /**
     * Gets the current detection mode.
     *
     * @return The saved detection mode or default value
     */
    suspend fun getDetectionMode(): DetectionMode {
        return context.dataStore.data.map { preferences ->
            preferences[DETECTION_MODE]?.let { modeString ->
                try {
                    DetectionMode.valueOf(modeString)
                } catch (e: IllegalArgumentException) {
                    DetectionMode.BOTH
                }
            } ?: DetectionMode.BOTH
        }.first()
    }

    /**
     * Gets the current pHash threshold.
     *
     * @return The saved threshold or default value (0.9)
     */
    suspend fun getPHashThreshold(): Float {
        return context.dataStore.data.map { preferences ->
            preferences[PHASH_THRESHOLD] ?: 0.9f
        }.first()
    }

    /**
     * Gets the current theme.
     *
     * @return The saved theme or default value
     */
    suspend fun getTheme(): AppTheme {
        return context.dataStore.data.map { preferences ->
            preferences[THEME]?.let { themeString ->
                try {
                    AppTheme.valueOf(themeString)
                } catch (e: IllegalArgumentException) {
                    AppTheme.SYSTEM
                }
            } ?: AppTheme.SYSTEM
        }.first()
    }
}
