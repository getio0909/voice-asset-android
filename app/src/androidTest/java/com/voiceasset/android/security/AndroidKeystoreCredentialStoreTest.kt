package com.voiceasset.android.security

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.voiceasset.core.api.BearerCredential
import com.voiceasset.core.api.RefreshCredential
import com.voiceasset.core.model.ServerProfileId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import java.time.Instant
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AndroidKeystoreCredentialStoreTest {
    private lateinit var context: Context
    private lateinit var dataStoreName: String
    private lateinit var keyAlias: String
    private lateinit var scope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>

    @Before
    fun createStore() {
        context = ApplicationProvider.getApplicationContext()
        val suffix = UUID.randomUUID().toString()
        dataStoreName = "credential-$suffix"
        keyAlias = "voiceasset.test.$suffix"
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        dataStore =
            PreferenceDataStoreFactory.create(scope = scope) {
                context.preferencesDataStoreFile(dataStoreName)
            }
    }

    @After
    fun deleteStore() {
        scope.cancel()
        KeyStore.getInstance("AndroidKeyStore").apply {
            load(null)
            deleteEntry(keyAlias)
        }
        context.preferencesDataStoreFile(dataStoreName).delete()
    }

    @Test
    fun encryptsRoundTripsAndRemovesCredential() =
        runBlocking {
            val profileId = ServerProfileId.parse("b6b25e6c-3268-4b39-9245-a39bc7c81f0b")
            val credential = byteArrayOf(11, 29, 47, 83, 101, 7, 61, 13)
            val store = AndroidKeystoreCredentialStore(dataStore, keyAlias)

            store.write(profileId, credential)

            assertArrayEquals(credential, requireNotNull(store.read(profileId)))
            val persistedValues =
                dataStore.data
                    .first()
                    .asMap()
                    .values
                    .map(Any::toString)
            assertFalse(persistedValues.any { value -> value.contains(credential.decodeToString()) })

            store.remove(profileId)
            assertNull(store.read(profileId))
        }

    @Test
    fun encryptsAndRoundTripsRotatableSessionWithoutPersistingPlaintext() =
        runBlocking {
            val profileId = ServerProfileId.parse("c6b25e6c-3268-4b39-9245-a39bc7c81f0b")
            val access = "va_android_access_with_sufficient_entropy"
            val refresh = "va_rft_${"r".repeat(43)}"
            val session =
                StoredServerSession(
                    accessCredential = BearerCredential(access),
                    refreshCredential = RefreshCredential(refresh),
                    accessExpiresAt = Instant.parse("2099-07-19T00:00:00Z"),
                    refreshExpiresAt = Instant.parse("2099-08-18T00:00:00Z"),
                )
            val store = AndroidKeystoreCredentialStore(dataStore, keyAlias)

            store.writeSession(profileId, session)

            val restored = requireNotNull(store.readSession(profileId))
            assertEquals(access, restored.accessCredential.value)
            assertEquals(refresh, restored.refreshCredential?.value)
            assertArrayEquals(access.encodeToByteArray(), requireNotNull(store.read(profileId)))
            val persistedValues =
                dataStore.data
                    .first()
                    .asMap()
                    .values
                    .map(Any::toString)
            assertFalse(persistedValues.any { value -> access in value || refresh in value })
        }
}
