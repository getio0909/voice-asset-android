package com.voiceasset.android.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.voiceasset.core.model.AuthenticationMode
import com.voiceasset.core.model.CertificateFingerprint
import com.voiceasset.core.model.ServerProfile
import com.voiceasset.core.model.ServerProfileId
import com.voiceasset.core.model.TranscriptionPolicy
import com.voiceasset.core.model.UploadPolicy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ServerProfilePersistenceTest {
    private lateinit var database: VoiceAssetDatabase

    @Before
    fun createDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database =
            Room
                .inMemoryDatabaseBuilder(context, VoiceAssetDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun roundTripsProfilesWithoutCredentialColumns() =
        runBlocking {
            val repository = RoomServerProfileRepository(database.serverProfileDao())
            val profile = testProfile()

            repository.save(profile)

            assertEquals(profile, repository.find(profile.id))
            assertEquals(listOf(profile), repository.observeAll().first())
            assertFalse(serverProfileColumns().any(::looksSensitive))

            repository.delete(profile.id)
            assertNull(repository.find(profile.id))
        }

    private fun serverProfileColumns(): List<String> {
        val columns = mutableListOf<String>()
        database.openHelper.readableDatabase.query("PRAGMA table_info(`server_profiles`)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) {
                columns += cursor.getString(nameIndex)
            }
        }
        return columns
    }

    private fun looksSensitive(column: String): Boolean {
        val normalized = column.lowercase()
        return listOf("token", "secret", "credential", "password", "authorization").any(normalized::contains)
    }

    private fun testProfile(): ServerProfile =
        ServerProfile.create(
            id = ServerProfileId.parse("42ddfd1f-8f9e-4073-9455-b9ea404bd3ce"),
            name = "Remote test server",
            baseUrl = "https://API.GETIO.NET:10443/",
            authenticationMode = AuthenticationMode.API_TOKEN,
            defaultUploadPolicy = UploadPolicy.WIFI_ONLY,
            defaultTranscriptionPolicy = TranscriptionPolicy.AFTER_UPLOAD,
            customCaPem = null,
            certificateFingerprint = CertificateFingerprint.parse("ab".repeat(32)),
            createdAtEpochMillis = 1_000,
            updatedAtEpochMillis = 1_000,
        )
}
