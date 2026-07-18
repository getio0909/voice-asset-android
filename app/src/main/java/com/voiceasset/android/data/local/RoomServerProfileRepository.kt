package com.voiceasset.android.data.local

import com.voiceasset.android.data.ServerProfileRepository
import com.voiceasset.core.model.ServerProfile
import com.voiceasset.core.model.ServerProfileId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomServerProfileRepository(
    private val dao: ServerProfileDao,
) : ServerProfileRepository {
    override fun observeAll(): Flow<List<ServerProfile>> =
        dao.observeAll().map { profiles ->
            profiles.map(ServerProfileEntity::toDomain)
        }

    override suspend fun find(id: ServerProfileId): ServerProfile? = dao.find(id.value)?.toDomain()

    override suspend fun save(profile: ServerProfile) {
        dao.upsert(profile.toEntity())
    }

    override suspend fun delete(id: ServerProfileId) {
        dao.delete(id.value)
    }
}
