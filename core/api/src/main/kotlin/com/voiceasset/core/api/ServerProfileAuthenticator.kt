package com.voiceasset.core.api

import com.voiceasset.core.model.ServerProfile

fun interface ServerProfileAuthenticator {
    fun authenticate(
        profile: ServerProfile,
        email: String,
        password: String,
    ): LoginResult
}

class ApiServerProfileAuthenticator(
    private val apiFactory: (ServerProfile, BearerCredential?) -> VoiceAssetApi,
) : ServerProfileAuthenticator {
    override fun authenticate(
        profile: ServerProfile,
        email: String,
        password: String,
    ): LoginResult {
        val api = apiFactory(profile, null)
        api
            .getCapabilities()
            .requireAndroidSyncCompatibility()
        return api.login(email, password)
    }
}
