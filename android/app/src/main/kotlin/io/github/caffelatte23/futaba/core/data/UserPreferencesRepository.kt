@file:Suppress("RedundantSuspendModifier") // detekt false positive: DataStore.edit is a suspend call

package io.github.caffelatte23.futaba.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

enum class ThemeMode { System, Light, Dark }

@Singleton
class UserPreferencesRepository
    @Inject
    constructor(
        private val dataStore: DataStore<Preferences>,
    ) {
        val draft: Flow<String> = dataStore.data.map { it[DRAFT] ?: "" }
        val openKeyboardOnLaunch: Flow<Boolean> = dataStore.data.map { it[OPEN_KEYBOARD] ?: true }
        val theme: Flow<ThemeMode> =
            dataStore.data.map { prefs ->
                ThemeMode.entries.firstOrNull { it.name == prefs[THEME] } ?: ThemeMode.System
            }

        suspend fun setDraft(text: String) {
            dataStore.edit { it[DRAFT] = text }
        }

        suspend fun setOpenKeyboardOnLaunch(value: Boolean) {
            dataStore.edit { it[OPEN_KEYBOARD] = value }
        }

        suspend fun setTheme(mode: ThemeMode) {
            dataStore.edit { it[THEME] = mode.name }
        }

        private companion object {
            val DRAFT = stringPreferencesKey("draft")
            val OPEN_KEYBOARD = booleanPreferencesKey("open_keyboard_on_launch")
            val THEME = stringPreferencesKey("theme")
        }
    }
