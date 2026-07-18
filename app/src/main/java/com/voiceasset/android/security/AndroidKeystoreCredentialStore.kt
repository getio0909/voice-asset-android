package com.voiceasset.android.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.voiceasset.core.api.BearerCredential
import com.voiceasset.core.model.ServerProfileId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class AndroidKeystoreCredentialStore(
    private val dataStore: DataStore<Preferences>,
    private val keyAlias: String = DEFAULT_KEY_ALIAS,
) : ServerCredentialStore {
    override suspend fun write(
        profileId: ServerProfileId,
        credential: ByteArray,
    ) {
        require(credential.isNotEmpty()) { "credential must not be empty" }

        val plaintext = credential.copyOf()
        val encrypted =
            try {
                val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
                cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
                cipher.updateAAD(profileId.value.encodeToByteArray())
                EncryptedCredential(
                    ciphertext = cipher.doFinal(plaintext),
                    initializationVector = cipher.iv,
                )
            } catch (exception: Exception) {
                throw CredentialStoreException("unable to protect server credential", exception)
            } finally {
                plaintext.fill(0)
            }

        try {
            dataStore.edit { preferences ->
                preferences[ciphertextKey(profileId)] = encrypted.ciphertext.toBase64()
                preferences[initializationVectorKey(profileId)] = encrypted.initializationVector.toBase64()
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            throw CredentialStoreException("unable to persist protected server credential", exception)
        }
    }

    override suspend fun writeSession(
        profileId: ServerProfileId,
        session: StoredServerSession,
    ) {
        val encoded = ServerSessionCodec.encode(session)
        try {
            write(profileId, encoded)
        } finally {
            encoded.fill(0)
        }
    }

    override suspend fun read(profileId: ServerProfileId): ByteArray? {
        val plaintext = readProtected(profileId) ?: return null
        return try {
            if (ServerSessionCodec.isEncoded(plaintext)) {
                ServerSessionCodec
                    .decode(plaintext)
                    .accessCredential
                    .value
                    .encodeToByteArray()
            } else {
                plaintext.copyOf()
            }
        } finally {
            plaintext.fill(0)
        }
    }

    override suspend fun readSession(profileId: ServerProfileId): StoredServerSession? {
        val plaintext = readProtected(profileId) ?: return null
        return try {
            if (ServerSessionCodec.isEncoded(plaintext)) {
                ServerSessionCodec.decode(plaintext)
            } else {
                try {
                    StoredServerSession.legacy(BearerCredential(plaintext.toString(Charsets.UTF_8)))
                } catch (exception: IllegalArgumentException) {
                    throw StoredServerSessionInvalidException(exception)
                }
            }
        } finally {
            plaintext.fill(0)
        }
    }

    private suspend fun readProtected(profileId: ServerProfileId): ByteArray? {
        val preferences =
            try {
                dataStore.data.first()
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                throw CredentialStoreException("unable to load protected server credential", exception)
            }
        val ciphertext = preferences[ciphertextKey(profileId)]
        val initializationVector = preferences[initializationVectorKey(profileId)]
        if (ciphertext == null && initializationVector == null) {
            return null
        }
        if (ciphertext == null || initializationVector == null) {
            throw CredentialStoreException("stored server credential is incomplete")
        }

        return try {
            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(GCM_TAG_LENGTH_BITS, initializationVector.fromBase64()),
            )
            cipher.updateAAD(profileId.value.encodeToByteArray())
            cipher.doFinal(ciphertext.fromBase64())
        } catch (exception: Exception) {
            throw CredentialStoreException("unable to read protected server credential", exception)
        }
    }

    override suspend fun remove(profileId: ServerProfileId) {
        try {
            dataStore.edit { preferences ->
                preferences.remove(ciphertextKey(profileId))
                preferences.remove(initializationVectorKey(profileId))
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            throw CredentialStoreException("unable to remove protected server credential", exception)
        }
    }

    private fun getOrCreateKey(): SecretKey =
        synchronized(KEYSTORE_LOCK) {
            val keyStore =
                KeyStore.getInstance(ANDROID_KEYSTORE).apply {
                    load(null)
                }
            (keyStore.getKey(keyAlias, null) as? SecretKey) ?: generateKey()
        }

    private fun generateKey(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec
                .Builder(
                    keyAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(AES_KEY_SIZE_BITS)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private fun ciphertextKey(profileId: ServerProfileId): Preferences.Key<String> =
        stringPreferencesKey("server_${profileId.value}_ciphertext")

    private fun initializationVectorKey(profileId: ServerProfileId): Preferences.Key<String> =
        stringPreferencesKey("server_${profileId.value}_iv")

    private data class EncryptedCredential(
        val ciphertext: ByteArray,
        val initializationVector: ByteArray,
    )

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val DEFAULT_KEY_ALIAS = "voiceasset.server-credentials.v1"
        private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val AES_KEY_SIZE_BITS = 256
        private const val GCM_TAG_LENGTH_BITS = 128
        private val KEYSTORE_LOCK = Any()
    }
}

private fun ByteArray.toBase64(): String = Base64.encodeToString(this, Base64.NO_WRAP)

private fun String.fromBase64(): ByteArray = Base64.decode(this, Base64.NO_WRAP)
