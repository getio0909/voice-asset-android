package com.voiceasset.android.security

import com.voiceasset.core.api.BearerCredential
import com.voiceasset.core.api.VoiceAssetApi
import com.voiceasset.core.api.VoiceAssetApiException
import com.voiceasset.core.model.ServerProfile
import com.voiceasset.core.model.ServerProfileId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant

class RefreshingServerSessionProvider(
    private val credentials: ServerCredentialStore,
    private val apiFactory: (ServerProfile, BearerCredential?) -> VoiceAssetApi,
    private val now: () -> Instant = Instant::now,
    private val refreshWindow: Duration = DEFAULT_REFRESH_WINDOW,
) {
    private val refreshMutex = Mutex()

    init {
        require(!refreshWindow.isNegative) { "refresh window must not be negative" }
    }

    suspend fun createApi(profile: ServerProfile): VoiceAssetApi =
        refreshMutex.withLock {
            val stored =
                try {
                    credentials.readSession(profile.id)
                } catch (exception: StoredServerSessionInvalidException) {
                    credentials.remove(profile.id)
                    throw ServerSessionAuthenticationRequiredException(exception)
                } ?: throw ServerSessionAuthenticationRequiredException()
            if (!stored.needsRefresh(now(), refreshWindow)) {
                return@withLock apiFactory(profile, stored.accessCredential)
            }
            val refresh = stored.refreshCredential
            if (refresh == null || stored.refreshIsExpired(now())) {
                credentials.remove(profile.id)
                throw ServerSessionAuthenticationRequiredException()
            }

            val rotated =
                try {
                    apiFactory(profile, stored.accessCredential).refreshSession(refresh)
                } catch (exception: VoiceAssetApiException) {
                    if (exception.statusCode == 401) {
                        credentials.remove(profile.id)
                        throw ServerSessionAuthenticationRequiredException(exception)
                    }
                    throw exception
                }
            try {
                val replacement = StoredServerSession.fromLogin(rotated, now())
                credentials.writeSession(profile.id, replacement)
                apiFactory(profile, replacement.accessCredential)
            } catch (exception: CancellationException) {
                removeAfterFailedRotation(profile.id)
                throw exception
            } catch (exception: Exception) {
                removeAfterFailedRotation(profile.id)
                throw exception
            }
        }

    suspend fun remove(profileId: ServerProfileId) {
        refreshMutex.withLock { credentials.remove(profileId) }
    }

    private suspend fun removeAfterFailedRotation(profileId: ServerProfileId) {
        withContext(NonCancellable) {
            runCatching { credentials.remove(profileId) }
        }
    }

    private companion object {
        val DEFAULT_REFRESH_WINDOW: Duration = Duration.ofMinutes(5)
    }
}

class ServerSessionAuthenticationRequiredException(
    cause: Throwable? = null,
) : IllegalStateException("server authentication is required", cause)
