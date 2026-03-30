package com.bccle.navigator.fragments.helpers

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("settings")
private val KEY_UNIT = stringPreferencesKey("unit_system")

object UserPrefs {
    fun unitFlow(ctx: Context): Flow<UnitSystem> =
        ctx.dataStore.data.map { prefs ->
            when (prefs[KEY_UNIT]) {
                "IMPERIAL" -> UnitSystem.IMPERIAL
                else -> UnitSystem.METRIC
            }
        }

    suspend fun setUnit(ctx: Context, unit: UnitSystem) {
        ctx.dataStore.edit { it[KEY_UNIT] = unit.name }
    }
}
