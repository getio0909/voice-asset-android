package com.voiceasset.android.security

import com.voiceasset.core.model.ServerProfile
import com.voiceasset.core.model.ServerProfileId
import kotlinx.coroutines.CancellationException

/**
 * Selects only server profiles that can resume authenticated work at process
 * startup. Missing or unreadable credentials must never make local recording
 * depend on the network.
 */
internal class StartupSyncPolicy(
    private val credentials: ServerCredentialStore,
) {
    suspend fun authenticatedProfileIds(profiles: List<ServerProfile>): Set<ServerProfileId> =
        profiles
            .filter { profile -> hasReadableSession(profile.id) }
            .map { profile -> profile.id }
            .toSet()

    /**
     * Returns whether a profile has a locally readable session and can safely
     * schedule authenticated work without making recording depend on login.
     */
    suspend fun hasReadableSession(profileId: ServerProfileId): Boolean =
        try {
            credentials.readSession(profileId) != null
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            false
        }
}
