package com.voiceasset.android.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.voiceasset.core.model.AuthenticationMode
import com.voiceasset.core.model.CertificateFingerprint
import com.voiceasset.core.model.ServerProfile
import com.voiceasset.core.model.ServerProfileId
import com.voiceasset.core.model.TranscriptionPolicy
import com.voiceasset.core.model.UploadPolicy

@Entity(
    tableName = "server_profiles",
    indices = [Index(value = ["name"])],
)
data class ServerProfileEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val origin: String,
    @ColumnInfo(name = "authentication_mode")
    val authenticationMode: String,
    @ColumnInfo(name = "default_upload_policy")
    val defaultUploadPolicy: String,
    @ColumnInfo(name = "default_transcription_policy")
    val defaultTranscriptionPolicy: String,
    @ColumnInfo(name = "custom_ca_pem")
    val customCaPem: String?,
    @ColumnInfo(name = "certificate_fingerprint")
    val certificateFingerprint: String?,
    @ColumnInfo(name = "created_at_epoch_millis")
    val createdAtEpochMillis: Long,
    @ColumnInfo(name = "updated_at_epoch_millis")
    val updatedAtEpochMillis: Long,
)

internal fun ServerProfile.toEntity(): ServerProfileEntity =
    ServerProfileEntity(
        id = id.value,
        name = name,
        origin = origin.value,
        authenticationMode = authenticationMode.name,
        defaultUploadPolicy = defaultUploadPolicy.name,
        defaultTranscriptionPolicy = defaultTranscriptionPolicy.name,
        customCaPem = customCaPem,
        certificateFingerprint = certificateFingerprint?.value,
        createdAtEpochMillis = createdAtEpochMillis,
        updatedAtEpochMillis = updatedAtEpochMillis,
    )

internal fun ServerProfileEntity.toDomain(): ServerProfile =
    ServerProfile.create(
        id = ServerProfileId.parse(id),
        name = name,
        baseUrl = origin,
        authenticationMode = AuthenticationMode.valueOf(authenticationMode),
        defaultUploadPolicy = UploadPolicy.valueOf(defaultUploadPolicy),
        defaultTranscriptionPolicy = TranscriptionPolicy.valueOf(defaultTranscriptionPolicy),
        customCaPem = customCaPem,
        certificateFingerprint = certificateFingerprint?.let(CertificateFingerprint::parse),
        createdAtEpochMillis = createdAtEpochMillis,
        updatedAtEpochMillis = updatedAtEpochMillis,
    )
