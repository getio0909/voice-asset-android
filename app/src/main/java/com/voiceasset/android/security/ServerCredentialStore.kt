package com.voiceasset.android.security

import com.voiceasset.core.api.BearerCredential
import com.voiceasset.core.api.LoginResult
import com.voiceasset.core.api.RefreshCredential
import com.voiceasset.core.model.ServerProfileId
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.time.Duration
import java.time.Instant

interface ServerCredentialStore {
    suspend fun write(
        profileId: ServerProfileId,
        credential: ByteArray,
    )

    suspend fun read(profileId: ServerProfileId): ByteArray?

    suspend fun writeSession(
        profileId: ServerProfileId,
        session: StoredServerSession,
    ) {
        val access = session.accessCredential.value.encodeToByteArray()
        try {
            write(profileId, access)
        } finally {
            access.fill(0)
        }
    }

    suspend fun readSession(profileId: ServerProfileId): StoredServerSession? {
        val access = read(profileId) ?: return null
        return try {
            StoredServerSession.legacy(BearerCredential(access.toString(Charsets.UTF_8)))
        } catch (exception: IllegalArgumentException) {
            throw StoredServerSessionInvalidException(exception)
        } finally {
            access.fill(0)
        }
    }

    suspend fun remove(profileId: ServerProfileId)
}

data class StoredServerSession(
    val accessCredential: BearerCredential,
    val refreshCredential: RefreshCredential?,
    val accessExpiresAt: Instant?,
    val refreshExpiresAt: Instant?,
) {
    init {
        val refreshBacked = refreshCredential != null
        require(refreshBacked == (accessExpiresAt != null) && refreshBacked == (refreshExpiresAt != null)) {
            "refresh-backed session fields must be complete"
        }
        if (accessExpiresAt != null && refreshExpiresAt != null) {
            require(refreshExpiresAt.isAfter(accessExpiresAt)) {
                "refresh expiry must be after access expiry"
            }
        }
    }

    fun needsRefresh(
        now: Instant,
        refreshWindow: Duration,
    ): Boolean {
        require(!refreshWindow.isNegative) { "refresh window must not be negative" }
        val expiry = accessExpiresAt ?: return false
        return !now.plus(refreshWindow).isBefore(expiry)
    }

    fun refreshIsExpired(now: Instant): Boolean = refreshExpiresAt?.let { !now.isBefore(it) } ?: true

    override fun toString(): String = "StoredServerSession([REDACTED])"

    companion object {
        fun fromLogin(
            login: LoginResult,
            now: Instant = Instant.now(),
        ): StoredServerSession {
            val accessExpiry = parseExpiry(login.session.expiresAt)
            val refreshExpiry = parseExpiry(login.session.refreshExpiresAt)
            if (!accessExpiry.isAfter(now) || !refreshExpiry.isAfter(accessExpiry)) {
                throw CredentialStoreException("server session lifetime is invalid")
            }
            return StoredServerSession(
                accessCredential = login.credential,
                refreshCredential = login.refreshCredential,
                accessExpiresAt = accessExpiry,
                refreshExpiresAt = refreshExpiry,
            )
        }

        fun legacy(accessCredential: BearerCredential): StoredServerSession =
            StoredServerSession(
                accessCredential = accessCredential,
                refreshCredential = null,
                accessExpiresAt = null,
                refreshExpiresAt = null,
            )

        private fun parseExpiry(value: String): Instant =
            try {
                Instant.parse(value)
            } catch (exception: Exception) {
                throw CredentialStoreException("server session lifetime is invalid", exception)
            }
    }
}

internal object ServerSessionCodec {
    fun encode(session: StoredServerSession): ByteArray {
        val refresh = session.refreshCredential ?: throw CredentialStoreException("refresh credential is missing")
        val accessExpiry = session.accessExpiresAt ?: throw CredentialStoreException("access expiry is missing")
        val refreshExpiry = session.refreshExpiresAt ?: throw CredentialStoreException("refresh expiry is missing")
        return try {
            ByteArrayOutputStream().use { output ->
                DataOutputStream(output).use { data ->
                    data.write(MAGIC)
                    data.writeUTF(session.accessCredential.value)
                    data.writeUTF(refresh.value)
                    data.writeLong(accessExpiry.toEpochMilli())
                    data.writeLong(refreshExpiry.toEpochMilli())
                }
                output.toByteArray()
            }
        } catch (exception: Exception) {
            throw CredentialStoreException("unable to encode protected server session", exception)
        }
    }

    fun decode(encoded: ByteArray): StoredServerSession {
        try {
            DataInputStream(ByteArrayInputStream(encoded)).use { input ->
                val magic = ByteArray(MAGIC.size)
                input.readFully(magic)
                if (!magic.contentEquals(MAGIC)) {
                    throw StoredServerSessionInvalidException()
                }
                val access = BearerCredential(input.readUTF())
                val refresh = RefreshCredential(input.readUTF())
                val accessExpiry = Instant.ofEpochMilli(input.readLong())
                val refreshExpiry = Instant.ofEpochMilli(input.readLong())
                if (input.read() != -1) {
                    throw StoredServerSessionInvalidException()
                }
                return StoredServerSession(access, refresh, accessExpiry, refreshExpiry)
            }
        } catch (exception: StoredServerSessionInvalidException) {
            throw exception
        } catch (exception: Exception) {
            throw StoredServerSessionInvalidException(exception)
        }
    }

    fun isEncoded(value: ByteArray): Boolean {
        if (value.size < MAGIC.size) {
            return false
        }
        return value.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)
    }

    private val MAGIC = byteArrayOf(0x56, 0x41, 0x53, 0x01)
}

open class CredentialStoreException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

class StoredServerSessionInvalidException(
    cause: Throwable? = null,
) : CredentialStoreException("stored server session is invalid", cause)
