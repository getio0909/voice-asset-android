package com.voiceasset.android.security

import com.voiceasset.android.data.ServerProfileRepository
import com.voiceasset.core.api.ServerProfileAuthenticator
import com.voiceasset.core.model.ServerProfileId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

interface ServerProfileReconnector {
    suspend fun reconnect(
        serverProfileId: ServerProfileId,
        email: String,
        password: String,
    )
}

class AuthenticatedServerProfileReconnector(
    private val profiles: ServerProfileRepository,
    private val credentials: ServerCredentialStore,
    private val authenticator: ServerProfileAuthenticator,
) : ServerProfileReconnector {
    override suspend fun reconnect(
        serverProfileId: ServerProfileId,
        email: String,
        password: String,
    ) {
        val profile = profiles.find(serverProfileId) ?: throw ServerProfileReconnectProfileUnavailableException()
        var authenticated = false
        var credentialWritten = false
        try {
            val login = authenticator.authenticate(profile, email.trim(), password)
            authenticated = true
            val session = StoredServerSession.fromLogin(login)
            credentials.writeSession(serverProfileId, session)
            credentialWritten = true
        } catch (exception: CancellationException) {
            if (authenticated && !credentialWritten) {
                removePartialCredential(serverProfileId)
            }
            throw exception
        } catch (exception: Exception) {
            if (authenticated && !credentialWritten) {
                removePartialCredential(serverProfileId)
            }
            throw exception
        }
    }

    private suspend fun removePartialCredential(serverProfileId: ServerProfileId) {
        withContext(NonCancellable) {
            runCatching { credentials.remove(serverProfileId) }
        }
    }
}

class ServerProfileReconnectProfileUnavailableException : IllegalStateException("server profile is unavailable")
