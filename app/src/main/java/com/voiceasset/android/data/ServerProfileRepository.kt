package com.voiceasset.android.data

import com.voiceasset.core.model.ServerProfile
import com.voiceasset.core.model.ServerProfileId
import kotlinx.coroutines.flow.Flow

interface ServerProfileRepository {
    fun observeAll(): Flow<List<ServerProfile>>

    suspend fun find(id: ServerProfileId): ServerProfile?

    suspend fun save(profile: ServerProfile)

    suspend fun delete(id: ServerProfileId)
}
