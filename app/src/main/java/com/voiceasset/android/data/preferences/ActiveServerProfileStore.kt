package com.voiceasset.android.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.voiceasset.core.model.ServerProfileId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

interface ActiveProfileStore {
    fun observe(): Flow<ServerProfileId?>

    suspend fun set(profileId: ServerProfileId)

    suspend fun clear()
}

class ActiveServerProfileStore(
    private val dataStore: DataStore<Preferences>,
) : ActiveProfileStore {
    override fun observe(): Flow<ServerProfileId?> =
        dataStore.data.map { preferences ->
            preferences[ACTIVE_PROFILE_ID]?.let(ServerProfileId::parse)
        }

    override suspend fun set(profileId: ServerProfileId) {
        dataStore.edit { preferences ->
            preferences[ACTIVE_PROFILE_ID] = profileId.value
        }
    }

    override suspend fun clear() {
        dataStore.edit { preferences ->
            preferences.remove(ACTIVE_PROFILE_ID)
        }
    }

    private companion object {
        val ACTIVE_PROFILE_ID = stringPreferencesKey("active_server_profile_id")
    }
}
