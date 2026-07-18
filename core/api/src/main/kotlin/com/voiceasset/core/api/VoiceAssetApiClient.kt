package com.voiceasset.core.api

import com.voiceasset.core.model.ServerProfile
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.security.cert.CertificateException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

interface VoiceAssetApi {
    fun getCapabilities(): ServerCapabilities

    fun listAdministrationJobs(
        cursor: String? = null,
        limit: Int = DEFAULT_ADMINISTRATION_LIST_LIMIT,
    ): AdministrationJobList

    fun retryAdministrationJob(jobId: String): AdministrationJob

    fun getAdministrationSystemStatus(): AdministrationSystemStatus

    fun listAsrProviderProfiles(): ProviderProfileList

    fun updateAsrProviderProfileState(
        profileId: String,
        expectedVersion: Long,
        state: ProviderProfileState,
    ): VersionedProviderProfile

    fun checkAsrProviderProfileHealth(profileId: String): ProviderHealth

    fun listLlmProviderProfiles(): ProviderProfileList

    fun updateLlmProviderProfileState(
        profileId: String,
        expectedVersion: Long,
        state: ProviderProfileState,
    ): VersionedProviderProfile

    fun checkLlmProviderProfileHealth(profileId: String): ProviderHealth

    fun login(
        email: String,
        password: String,
    ): LoginResult

    @Throws(VoiceAssetApiException::class)
    fun refreshSession(refreshCredential: RefreshCredential): LoginResult

    fun listDeviceSessions(): DeviceSessionList

    fun revokeDeviceSession(deviceSessionId: String)

    fun changePassword(
        currentPassword: String,
        newPassword: String,
    )

    fun createAsset(
        input: CreateAssetRequest,
        idempotencyKey: String,
    ): Asset

    fun getAsset(assetId: String): VersionedAsset

    fun listAssets(
        cursor: String? = null,
        limit: Int = DEFAULT_ASSET_LIST_LIMIT,
    ): AssetList

    fun updateAssetMetadata(
        assetId: String,
        expectedEntityTag: String,
        input: UpdateAssetMetadataRequest,
    ): VersionedAsset

    fun listSyncChanges(
        cursor: String? = null,
        limit: Int = DEFAULT_SYNC_LIMIT,
    ): SyncChangeList

    fun createUpload(
        input: CreateUploadRequest,
        idempotencyKey: String,
    ): UploadSession

    fun getUpload(uploadId: String): UploadSession

    fun putUploadPart(
        uploadId: String,
        partNumber: Int,
        bytes: ByteArray,
        partSha256: String,
    ): UploadPart

    fun completeUpload(uploadId: String): UploadSession

    fun createTranscription(
        assetId: String,
        idempotencyKey: String,
    ): TranscriptionJob

    fun getTranscriptionJob(jobId: String): TranscriptionJob

    fun listAssetTranscripts(assetId: String): TranscriptList

    fun getTranscriptRevision(revisionId: String): TranscriptRevision
}

class VoiceAssetApiException(
    val statusCode: Int,
    val code: String,
    val requestId: String?,
    message: String,
) : IOException(message)

class VoiceAssetProtocolException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

class VoiceAssetConnectionException(
    message: String,
    cause: IOException,
) : IOException(message, cause)

class VoiceAssetTlsException(
    message: String,
    cause: Throwable,
) : IOException(message, cause)

class VoiceAssetApiClient internal constructor(
    private val apiRoot: HttpUrl,
    private val httpClient: OkHttpClient,
    private val credential: BearerCredential?,
) : VoiceAssetApi {
    override fun getCapabilities(): ServerCapabilities = execute(path = "system/capabilities", decode = JsonCodec::decodeCapabilities)

    override fun listAdministrationJobs(
        cursor: String?,
        limit: Int,
    ): AdministrationJobList {
        validateListPageRequest(cursor, limit, "administration job list")
        val result =
            execute(
                path = "admin/jobs",
                query =
                    buildList {
                        add("limit" to limit.toString())
                        cursor?.let { add("cursor" to it) }
                    },
                decode = JsonCodec::decodeAdministrationJobList,
            )
        return validateAdministrationJobList(result, limit)
    }

    override fun retryAdministrationJob(jobId: String): AdministrationJob {
        val canonicalJobId = validateUuid(jobId, "administration job id")
        val result =
            execute(
                path = "admin/jobs/$canonicalJobId/retry",
                method = "POST",
                headers = mapOf("Origin" to apiRoot.origin()),
                decode = JsonCodec::decodeAdministrationJob,
            )
        val valid =
            validateAdministrationJob(result) &&
                result.id == canonicalJobId &&
                result.state == "queued" &&
                !result.retryable &&
                result.attempts < result.maxAttempts &&
                result.leaseExpiresAt == null &&
                result.lastErrorCode == null &&
                result.resultRevisionId == null
        if (!valid) {
            throw VoiceAssetProtocolException("Server returned an invalid administration job retry response.")
        }
        return result
    }

    override fun getAdministrationSystemStatus(): AdministrationSystemStatus =
        validateAdministrationSystemStatus(
            execute(
                path = "admin/system-status",
                decode = JsonCodec::decodeAdministrationSystemStatus,
            ),
        )

    override fun listAsrProviderProfiles(): ProviderProfileList =
        validateProviderProfileList(
            execute(path = "provider-profiles", decode = JsonCodec::decodeProviderProfileList),
            allowedProviderIds = ASR_PROVIDER_IDS,
        )

    override fun updateAsrProviderProfileState(
        profileId: String,
        expectedVersion: Long,
        state: ProviderProfileState,
    ): VersionedProviderProfile =
        updateProviderProfileState(
            pathPrefix = "provider-profiles",
            profileId = profileId,
            expectedVersion = expectedVersion,
            state = state,
            allowedProviderIds = ASR_PROVIDER_IDS,
        )

    override fun checkAsrProviderProfileHealth(profileId: String): ProviderHealth =
        checkProviderProfileHealth(
            pathPrefix = "provider-profiles",
            profileId = profileId,
            allowedErrorClasses = ASR_PROVIDER_HEALTH_ERROR_CLASSES,
        )

    override fun listLlmProviderProfiles(): ProviderProfileList =
        validateProviderProfileList(
            execute(path = "llm-profiles", decode = JsonCodec::decodeProviderProfileList),
            allowedProviderIds = LLM_PROVIDER_IDS,
        )

    override fun updateLlmProviderProfileState(
        profileId: String,
        expectedVersion: Long,
        state: ProviderProfileState,
    ): VersionedProviderProfile =
        updateProviderProfileState(
            pathPrefix = "llm-profiles",
            profileId = profileId,
            expectedVersion = expectedVersion,
            state = state,
            allowedProviderIds = LLM_PROVIDER_IDS,
        )

    override fun checkLlmProviderProfileHealth(profileId: String): ProviderHealth =
        checkProviderProfileHealth(
            pathPrefix = "llm-profiles",
            profileId = profileId,
            allowedErrorClasses = LLM_PROVIDER_HEALTH_ERROR_CLASSES,
        )

    override fun login(
        email: String,
        password: String,
    ): LoginResult = login(email, password, DEFAULT_DEVICE_NAME)

    fun login(
        email: String,
        password: String,
        deviceName: String,
    ): LoginResult {
        val normalizedEmail = email.trim()
        val normalizedDeviceName = deviceName.trim()
        require(normalizedEmail.length in 3..254 && '@' in normalizedEmail) { "email is invalid" }
        require(password.isNotEmpty() && password.length <= MAX_PASSWORD_LENGTH) {
            "password length is outside the contract limit"
        }
        require(
            normalizedDeviceName.codePointCount(0, normalizedDeviceName.length) in 1..MAX_DEVICE_NAME_LENGTH &&
                normalizedDeviceName.none(Char::isISOControl),
        ) { "device name is outside the contract limit" }
        return decodeSessionResponse(
            executeResponse(
                path = "auth/sessions",
                method = "POST",
                body =
                    JsonCodec.encodeLoginRequest(
                        LoginRequest(normalizedEmail, password, normalizedDeviceName),
                    ),
                authenticated = false,
            ),
        )
    }

    override fun refreshSession(refreshCredential: RefreshCredential): LoginResult =
        decodeSessionResponse(
            executeResponse(
                path = "auth/session/refresh",
                method = "POST",
                headers =
                    mapOf(
                        "Cookie" to "$REFRESH_COOKIE_NAME=${refreshCredential.value}",
                        "Origin" to apiRoot.origin(),
                    ),
                authenticated = false,
            ),
        )

    override fun listDeviceSessions(): DeviceSessionList =
        validateDeviceSessionList(
            execute(
                path = "auth/device-sessions",
                decode = JsonCodec::decodeDeviceSessionList,
            ),
        )

    override fun revokeDeviceSession(deviceSessionId: String) {
        executeNoContent(
            path = "auth/device-sessions/${validateUuid(deviceSessionId, "device session id")}",
            method = "DELETE",
            headers = mapOf("Origin" to apiRoot.origin()),
        )
    }

    fun claimPairing(
        payload: PairingPayload,
        deviceName: String,
    ): LoginResult {
        require(apiRoot.origin() == payload.origin) { "pairing payload origin does not match the API client" }
        val normalizedDeviceName = deviceName.trim()
        require(
            normalizedDeviceName.codePointCount(0, normalizedDeviceName.length) in 1..MAX_DEVICE_NAME_LENGTH &&
                normalizedDeviceName.none(Char::isISOControl),
        ) { "device name is outside the contract limit" }
        getCapabilities().requireDevicePairingCompatibility(payload)
        return decodeSessionResponse(
            executeResponse(
                path = "auth/pairing-sessions/${payload.pairingSessionId}/claim",
                method = "POST",
                body =
                    JsonCodec.encodePairingClaimRequest(
                        PairingClaimRequest(payload.secret.value, normalizedDeviceName),
                    ),
                headers = mapOf("Origin" to payload.origin),
                authenticated = false,
            ),
        )
    }

    override fun changePassword(
        currentPassword: String,
        newPassword: String,
    ) {
        requireNotNull(credential) { "an authenticated session credential is required" }
        validatePasswordChange(currentPassword, newPassword)
        executeNoContent(
            path = "auth/password",
            method = "PATCH",
            body =
                JsonCodec.encodeChangePasswordRequest(
                    ChangePasswordRequest(currentPassword, newPassword),
                ),
        )
    }

    private fun decodeSessionResponse(response: okhttp3.Response): LoginResult =
        response.use {
            val responseBody = it.body?.string().orEmpty()
            ensureSuccessful(it.code, responseBody, it.header("X-Request-ID"))
            val sessionBody = requireResponseBody(responseBody)
            val cookies =
                it.headers
                    .values("Set-Cookie")
                    .mapNotNull { value -> Cookie.parse(apiRoot, value) }
            val sessionCookie = requireSessionCookie(cookies, SESSION_COOKIE_NAME, API_COOKIE_PATH)
            val refreshCookie = requireSessionCookie(cookies, REFRESH_COOKIE_NAME, REFRESH_COOKIE_PATH)
            val sessionCredential =
                try {
                    BearerCredential(sessionCookie.value)
                } catch (exception: IllegalArgumentException) {
                    throw VoiceAssetProtocolException("Server returned an invalid VoiceAsset session credential.", exception)
                }
            val refreshCredential =
                try {
                    RefreshCredential(refreshCookie.value)
                } catch (exception: IllegalArgumentException) {
                    throw VoiceAssetProtocolException("Server returned an invalid VoiceAsset refresh credential.", exception)
                }
            LoginResult(
                session = JsonCodec.decodeWebSession(sessionBody),
                credential = sessionCredential,
                refreshCredential = refreshCredential,
            )
        }

    override fun createAsset(
        input: CreateAssetRequest,
        idempotencyKey: String,
    ): Asset {
        val normalized =
            input.copy(
                title = input.title.trim().also(::validateTitle),
                language = input.language.also(::validateLanguage),
            )
        return execute(
            path = "assets",
            method = "POST",
            body = JsonCodec.encodeCreateAssetRequest(normalized),
            headers = mapOf(IDEMPOTENCY_KEY to validateIdempotencyKey(idempotencyKey)),
            decode = JsonCodec::decodeAsset,
        )
    }

    override fun getAsset(assetId: String): VersionedAsset {
        val canonicalAssetId = validateUuid(assetId, "asset id")
        return executeVersionedAsset(
            path = "assets/$canonicalAssetId",
            expectedAssetId = canonicalAssetId,
        )
    }

    override fun listAssets(
        cursor: String?,
        limit: Int,
    ): AssetList {
        validateListPageRequest(cursor, limit, "asset list")
        val result =
            execute(
                path = "assets",
                query =
                    buildList {
                        add("limit" to limit.toString())
                        cursor?.let { add("cursor" to it) }
                    },
                decode = JsonCodec::decodeAssetList,
            )
        return validateAssetList(result, limit)
    }

    override fun updateAssetMetadata(
        assetId: String,
        expectedEntityTag: String,
        input: UpdateAssetMetadataRequest,
    ): VersionedAsset {
        val canonicalAssetId = validateUuid(assetId, "asset id")
        val expectedVersion = requireEntityTag(expectedEntityTag)
        val normalized =
            input.copy(
                title = input.title.trim().also(::validateTitle),
                language = input.language.also(::validateLanguage),
                collectionId = input.collectionId?.let { validateUuid(it, "collection id") },
            )
        val result =
            executeVersionedAsset(
                path = "assets/$canonicalAssetId/metadata",
                method = "PUT",
                body = JsonCodec.encodeUpdateAssetMetadataRequest(normalized),
                headers =
                    mapOf(
                        "If-Match" to expectedEntityTag,
                        "Origin" to apiRoot.origin(),
                    ),
                expectedAssetId = canonicalAssetId,
            )
        if (result.asset.version <= expectedVersion) {
            throw VoiceAssetProtocolException("Server did not advance the asset resource version.")
        }
        return result
    }

    override fun listSyncChanges(
        cursor: String?,
        limit: Int,
    ): SyncChangeList {
        validateListPageRequest(cursor, limit, "sync")
        val result =
            execute(
                path = "sync/changes",
                query =
                    buildList {
                        add("limit" to limit.toString())
                        cursor?.let { add("cursor" to it) }
                    },
                decode = JsonCodec::decodeSyncChangeList,
            )
        return validateSyncChanges(result, limit)
    }

    override fun createUpload(
        input: CreateUploadRequest,
        idempotencyKey: String,
    ): UploadSession {
        val normalized = validateUpload(input)
        return execute(
            path = "uploads",
            method = "POST",
            body = JsonCodec.encodeCreateUploadRequest(normalized),
            headers = mapOf(IDEMPOTENCY_KEY to validateIdempotencyKey(idempotencyKey)),
            decode = JsonCodec::decodeUploadSession,
        )
    }

    override fun getUpload(uploadId: String): UploadSession =
        execute(path = "uploads/${validateUuid(uploadId, "upload id")}", decode = JsonCodec::decodeUploadSession)

    override fun putUploadPart(
        uploadId: String,
        partNumber: Int,
        bytes: ByteArray,
        partSha256: String,
    ): UploadPart {
        require(partNumber in 1..10_000) { "part number is outside the contract limit" }
        require(bytes.isNotEmpty() && bytes.size <= MAX_PART_SIZE) { "upload part size is outside the contract limit" }
        require(SHA256.matches(partSha256)) { "part checksum must be lowercase SHA-256" }
        return execute(
            path = "uploads/${validateUuid(uploadId, "upload id")}/parts/$partNumber",
            method = "PUT",
            requestBody = bytes.toRequestBody(OCTET_STREAM),
            headers = mapOf(PART_SHA256 to partSha256),
            decode = JsonCodec::decodeUploadPart,
        )
    }

    override fun completeUpload(uploadId: String): UploadSession =
        execute(
            path = "uploads/${validateUuid(uploadId, "upload id")}/complete",
            method = "POST",
            decode = JsonCodec::decodeUploadSession,
        )

    override fun createTranscription(
        assetId: String,
        idempotencyKey: String,
    ): TranscriptionJob =
        execute(
            path = "assets/${validateUuid(assetId, "asset id")}/transcriptions",
            method = "POST",
            headers = mapOf(IDEMPOTENCY_KEY to validateIdempotencyKey(idempotencyKey)),
            decode = JsonCodec::decodeTranscriptionJob,
        )

    override fun getTranscriptionJob(jobId: String): TranscriptionJob =
        execute(
            path = "transcription-jobs/${validateUuid(jobId, "job id")}",
            decode = JsonCodec::decodeTranscriptionJob,
        )

    override fun listAssetTranscripts(assetId: String): TranscriptList =
        execute(
            path = "assets/${validateUuid(assetId, "asset id")}/transcripts",
            decode = JsonCodec::decodeTranscriptList,
        )

    override fun getTranscriptRevision(revisionId: String): TranscriptRevision =
        execute(
            path = "transcript-revisions/${validateUuid(revisionId, "revision id")}",
            decode = JsonCodec::decodeTranscriptRevision,
        )

    private fun <T> execute(
        path: String,
        method: String = "GET",
        body: String? = null,
        requestBody: okhttp3.RequestBody? = null,
        headers: Map<String, String> = emptyMap(),
        query: List<Pair<String, String>> = emptyList(),
        decode: (String) -> T,
    ): T =
        executeResponse(path, method, body, requestBody, headers, query).use { response ->
            val responseBody = response.body?.string().orEmpty()
            ensureSuccessful(response.code, responseBody, response.header("X-Request-ID"))
            decode(requireResponseBody(responseBody))
        }

    private fun executeVersionedAsset(
        path: String,
        expectedAssetId: String,
        method: String = "GET",
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): VersionedAsset =
        executeResponse(path = path, method = method, body = body, headers = headers).use { response ->
            val responseBody = response.body?.string().orEmpty()
            ensureSuccessful(response.code, responseBody, response.header("X-Request-ID"))
            val asset = JsonCodec.decodeAsset(requireResponseBody(responseBody))
            validateVersionedAsset(
                asset = asset,
                entityTag = response.header("ETag"),
                expectedAssetId = expectedAssetId,
            )
        }

    private fun updateProviderProfileState(
        pathPrefix: String,
        profileId: String,
        expectedVersion: Long,
        state: ProviderProfileState,
        allowedProviderIds: Set<String>,
    ): VersionedProviderProfile {
        val canonicalProfileId = validateUuid(profileId, "provider profile id")
        require(expectedVersion >= 1) { "provider profile version is invalid" }
        val expectedEntityTag = "\"$expectedVersion\""
        return executeResponse(
            path = "$pathPrefix/$canonicalProfileId",
            method = "PATCH",
            body =
                JsonCodec.encodeUpdateProviderProfileStateRequest(
                    UpdateProviderProfileStateRequest(state),
                ),
            headers =
                mapOf(
                    "If-Match" to expectedEntityTag,
                    "Origin" to apiRoot.origin(),
                ),
        ).use { response ->
            val responseBody = response.body?.string().orEmpty()
            ensureSuccessful(response.code, responseBody, response.header("X-Request-ID"))
            val profile = JsonCodec.decodeProviderProfile(requireResponseBody(responseBody))
            val result =
                validateVersionedProviderProfile(
                    profile = profile,
                    entityTag = response.header("ETag"),
                    expectedProfileId = canonicalProfileId,
                    allowedProviderIds = allowedProviderIds,
                )
            if (result.profile.version <= expectedVersion || result.profile.state != state) {
                throw VoiceAssetProtocolException("Server did not apply the provider profile state update.")
            }
            result
        }
    }

    private fun checkProviderProfileHealth(
        pathPrefix: String,
        profileId: String,
        allowedErrorClasses: Set<ProviderHealthErrorClass>,
    ): ProviderHealth {
        val canonicalProfileId = validateUuid(profileId, "provider profile id")
        val result =
            execute(
                path = "$pathPrefix/$canonicalProfileId/health",
                method = "POST",
                headers = mapOf("Origin" to apiRoot.origin()),
                decode = JsonCodec::decodeProviderHealth,
            )
        val valid =
            result.profileId == canonicalProfileId &&
                runCatching { Instant.parse(result.checkedAt) }.isSuccess &&
                (result.errorClass == null || result.errorClass in allowedErrorClasses) &&
                (result.status != ProviderHealthStatus.HEALTHY || result.errorClass == null)
        if (!valid) {
            throw VoiceAssetProtocolException("Server returned an invalid provider health response.")
        }
        return result
    }

    private fun executeNoContent(
        path: String,
        method: String,
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
    ) {
        executeResponse(path = path, method = method, body = body, headers = headers).use { response ->
            val responseBody = response.body?.string().orEmpty()
            ensureSuccessful(response.code, responseBody, response.header("X-Request-ID"))
            if (response.code != 204 || responseBody.isNotBlank()) {
                throw VoiceAssetProtocolException("VoiceAsset Server returned an invalid empty response.")
            }
        }
    }

    private fun executeResponse(
        path: String,
        method: String,
        body: String? = null,
        requestBody: okhttp3.RequestBody? = null,
        headers: Map<String, String> = emptyMap(),
        query: List<Pair<String, String>> = emptyList(),
        authenticated: Boolean = true,
    ): okhttp3.Response {
        require(!path.startsWith('/') && ".." !in path) { "API path must be relative" }
        val resolved = apiRoot.resolve(path) ?: throw IllegalArgumentException("API path is invalid")
        val url =
            resolved
                .newBuilder()
                .apply { query.forEach { (name, value) -> addQueryParameter(name, value) } }
                .build()
        val builder =
            Request
                .Builder()
                .url(url)
                .header("Accept", JSON_MEDIA_TYPE.toString())
                .header("X-Request-ID", UUID.randomUUID().toString())
        if (authenticated && credential != null) {
            builder.header("Authorization", "Bearer ${credential.value}")
        }
        headers.forEach(builder::header)
        val resolvedBody =
            requestBody
                ?: body?.toRequestBody(JSON_MEDIA_TYPE)
                ?: if (method in METHODS_REQUIRING_BODY) ByteArray(0).toRequestBody(null) else null
        builder.method(method, resolvedBody)
        return try {
            httpClient.newCall(builder.build()).execute()
        } catch (exception: SSLException) {
            throw VoiceAssetTlsException("VoiceAsset Server TLS verification failed.", exception)
        } catch (exception: IOException) {
            if (exception.hasTlsCause()) {
                throw VoiceAssetTlsException("VoiceAsset Server TLS verification failed.", exception)
            }
            throw VoiceAssetConnectionException("Could not reach the VoiceAsset Server.", exception)
        }
    }

    companion object {
        fun forProfile(
            profile: ServerProfile,
            credential: BearerCredential?,
        ): VoiceAssetApiClient =
            VoiceAssetApiClient(
                apiRoot = "${profile.origin.value}/api/v1/".toHttpUrl(),
                httpClient = ServerTls.buildClient(profile),
                credential = credential,
            )

        fun forPairing(payload: PairingPayload): VoiceAssetApiClient {
            val apiRoot = "${payload.origin}/api/v1/".toHttpUrl()
            require(apiRoot.isHttps) { "device pairing requires HTTPS" }
            return VoiceAssetApiClient(
                apiRoot = apiRoot,
                httpClient =
                    OkHttpClient
                        .Builder()
                        .followRedirects(false)
                        .followSslRedirects(false)
                        .callTimeout(30, TimeUnit.SECONDS)
                        .build(),
                credential = null,
            )
        }

        internal fun forTesting(
            apiRoot: HttpUrl,
            credential: BearerCredential?,
        ): VoiceAssetApiClient =
            VoiceAssetApiClient(
                apiRoot = apiRoot,
                httpClient =
                    OkHttpClient
                        .Builder()
                        .followRedirects(false)
                        .followSslRedirects(false)
                        .callTimeout(30, TimeUnit.SECONDS)
                        .build(),
                credential = credential,
            )
    }
}

private fun ensureSuccessful(
    statusCode: Int,
    responseBody: String,
    responseRequestId: String?,
) {
    if (statusCode in 200..299) {
        return
    }
    val error = JsonCodec.decodeError(responseBody)
    throw VoiceAssetApiException(
        statusCode = statusCode,
        code = error?.code ?: "http_$statusCode",
        requestId = error?.requestId ?: responseRequestId,
        message = error?.message ?: "VoiceAsset Server request failed.",
    )
}

private fun requireResponseBody(value: String): String {
    if (value.isBlank()) {
        throw VoiceAssetProtocolException("VoiceAsset Server returned an empty response.")
    }
    return value
}

private fun requireSessionCookie(
    cookies: List<Cookie>,
    name: String,
    path: String,
): Cookie {
    val cookie =
        cookies.singleOrNull { candidate -> candidate.name == name }
            ?: throw VoiceAssetProtocolException("Server did not return the required VoiceAsset session cookies.")
    if (!cookie.secure || !cookie.httpOnly || cookie.path != path) {
        throw VoiceAssetProtocolException("Server returned an unsafe VoiceAsset session cookie.")
    }
    return cookie
}

private fun HttpUrl.origin(): String =
    newBuilder()
        .encodedPath("/")
        .query(null)
        .fragment(null)
        .build()
        .toString()
        .removeSuffix("/")

private fun validateTitle(value: String) {
    require(value.isNotEmpty() && value.length <= 500 && value.none(Char::isISOControl)) {
        "asset title is invalid"
    }
}

private fun validateVersionedAsset(
    asset: Asset,
    entityTag: String?,
    expectedAssetId: String,
): VersionedAsset {
    val validatedEntityTag =
        entityTag ?: throw VoiceAssetProtocolException("Server returned an invalid versioned asset response.")
    val version = parseEntityTag(validatedEntityTag)
    val valid = validateAsset(asset) && asset.id == expectedAssetId && version == asset.version
    if (!valid) {
        throw VoiceAssetProtocolException("Server returned an invalid versioned asset response.")
    }
    return VersionedAsset(asset = asset, entityTag = validatedEntityTag)
}

private fun validateAdministrationJobList(
    result: AdministrationJobList,
    requestedLimit: Int,
): AdministrationJobList {
    val cursorIsValid =
        result.nextCursor?.let { cursor ->
            cursor.length in 1..MAX_CURSOR_LENGTH && cursor.none(Char::isISOControl)
        } ?: true
    val identifiers = HashSet<String>(result.items.size)
    val valid =
        result.items.size <= requestedLimit &&
            cursorIsValid &&
            result.items.all { job -> validateAdministrationJob(job) && identifiers.add(job.id) } &&
            result.items.zipWithNext().all { (first, second) ->
                val firstUpdatedAt = Instant.parse(first.updatedAt)
                val secondUpdatedAt = Instant.parse(second.updatedAt)
                firstUpdatedAt.isAfter(secondUpdatedAt) ||
                    (firstUpdatedAt == secondUpdatedAt && first.id > second.id)
            }
    if (!valid) {
        throw VoiceAssetProtocolException("Server returned an invalid administration job list page.")
    }
    return result
}

private fun validateDeviceSessionList(result: DeviceSessionList): DeviceSessionList {
    val identifiers = HashSet<String>(result.items.size)
    val valid =
        result.items.count(DeviceSession::current) == 1 &&
            result.items.all { session -> validateDeviceSession(session) && identifiers.add(session.id) } &&
            result.items.zipWithNext().all { (first, second) ->
                val firstSeen = Instant.parse(first.lastSeenAt)
                val secondSeen = Instant.parse(second.lastSeenAt)
                firstSeen.isAfter(secondSeen) || (firstSeen == secondSeen && first.id < second.id)
            }
    if (!valid) {
        throw VoiceAssetProtocolException("Server returned an invalid device session inventory.")
    }
    return result
}

private fun validateDeviceSession(session: DeviceSession): Boolean =
    runCatching {
        validateUuid(session.id, "device session id")
        val createdAt = Instant.parse(session.createdAt)
        val lastSeenAt = Instant.parse(session.lastSeenAt)
        val expiresAt = Instant.parse(session.expiresAt)
        val refreshExpiresAt = Instant.parse(session.refreshExpiresAt)
        require(!lastSeenAt.isBefore(createdAt))
        require(expiresAt.isAfter(createdAt))
        require(refreshExpiresAt.isAfter(expiresAt))
    }.isSuccess &&
        session.deviceName == session.deviceName.trim() &&
        session.deviceName.codePointCount(0, session.deviceName.length) in 1..100 &&
        session.deviceName.none(Char::isISOControl) &&
        session.revokedAt == null

private fun validateAdministrationJob(job: AdministrationJob): Boolean =
    runCatching {
        validateUuid(job.id, "administration job id")
        job.assetId?.let { validateUuid(it, "administration job asset id") }
        validateUuid(job.createdBy, "administration job creator id")
        job.resultRevisionId?.let { validateUuid(it, "administration job result revision id") }
        val availableAt = Instant.parse(job.availableAt)
        job.leaseExpiresAt?.let(Instant::parse)
        val createdAt = Instant.parse(job.createdAt)
        val updatedAt = Instant.parse(job.updatedAt)
        require(!updatedAt.isBefore(createdAt))
        require(job.state != "queued" || !availableAt.isBefore(createdAt))
    }.isSuccess &&
        JOB_KIND.matches(job.kind) &&
        job.state in ADMINISTRATION_JOB_STATES &&
        job.attempts >= 0 &&
        job.maxAttempts in 1..20 &&
        job.attempts <= job.maxAttempts &&
        (
            !job.retryable ||
                (
                    job.state == "failed" &&
                        job.assetId != null &&
                        job.resultRevisionId == null &&
                        job.leaseExpiresAt == null &&
                        job.maxAttempts < 20
                )
        ) &&
        (job.lastErrorCode == null || JOB_KIND.matches(job.lastErrorCode))

private fun validateAdministrationSystemStatus(result: AdministrationSystemStatus): AdministrationSystemStatus {
    val counts =
        listOf(
            result.activeUsers,
            result.assets.total,
            result.assets.active,
            result.assets.trashed,
            result.assets.purging,
            result.assets.failed,
            result.assets.audioDurationMillis,
            result.storage.objectCount,
            result.storage.bytes,
            result.transcripts.transcriptCount,
            result.transcripts.revisionCount,
            result.jobs.total,
            result.jobs.queued,
            result.jobs.running,
            result.jobs.retryWait,
            result.jobs.succeeded,
            result.jobs.failed,
            result.jobs.cancelled,
            result.providers.enabledAsr,
            result.providers.enabledLlm,
        )
    val jobStateTotal =
        listOf(
            result.jobs.queued,
            result.jobs.running,
            result.jobs.retryWait,
            result.jobs.succeeded,
            result.jobs.failed,
            result.jobs.cancelled,
        ).sum()
    val valid =
        runCatching { Instant.parse(result.generatedAt) }.isSuccess &&
            counts.all { it >= 0 } &&
            jobStateTotal == result.jobs.total
    if (!valid) {
        throw VoiceAssetProtocolException("Server returned an invalid administration system status.")
    }
    return result
}

private fun validateProviderProfileList(
    result: ProviderProfileList,
    allowedProviderIds: Set<String>,
): ProviderProfileList {
    val identifiers = HashSet<String>(result.items.size)
    val workspaceIdentifiers = HashSet<String>(1)
    val valid =
        result.items.size <= MAX_PROVIDER_PROFILES &&
            result.items.all { profile ->
                workspaceIdentifiers.add(profile.workspaceId)
                validateProviderProfile(profile, allowedProviderIds) && identifiers.add(profile.id)
            } &&
            workspaceIdentifiers.size <= 1
    if (!valid) {
        throw VoiceAssetProtocolException("Server returned an invalid provider profile list.")
    }
    return result
}

private fun validateVersionedProviderProfile(
    profile: ProviderProfile,
    entityTag: String?,
    expectedProfileId: String,
    allowedProviderIds: Set<String>,
): VersionedProviderProfile {
    val validatedEntityTag =
        entityTag ?: throw VoiceAssetProtocolException("Server returned an invalid versioned provider profile response.")
    val entityVersion = parseEntityTag(validatedEntityTag)
    if (
        !validateProviderProfile(profile, allowedProviderIds) ||
        profile.id != expectedProfileId ||
        entityVersion != profile.version
    ) {
        throw VoiceAssetProtocolException("Server returned an invalid versioned provider profile response.")
    }
    return VersionedProviderProfile(profile, validatedEntityTag)
}

private fun validateProviderProfile(
    profile: ProviderProfile,
    allowedProviderIds: Set<String>,
): Boolean =
    runCatching {
        validateUuid(profile.id, "provider profile id")
        validateUuid(profile.workspaceId, "provider profile workspace id")
        val createdAt = Instant.parse(profile.createdAt)
        val updatedAt = Instant.parse(profile.updatedAt)
        require(!updatedAt.isBefore(createdAt))
    }.isSuccess &&
        profile.providerId in allowedProviderIds &&
        profile.displayName == profile.displayName.trim() &&
        profile.displayName.length in 1..100 &&
        profile.displayName.none(Char::isISOControl) &&
        profile.priority in 1..1_000 &&
        profile.version >= 1

private fun validateAssetList(
    result: AssetList,
    requestedLimit: Int,
): AssetList {
    val cursorIsValid =
        result.nextCursor?.let { cursor ->
            cursor.length in 1..MAX_CURSOR_LENGTH && cursor.none(Char::isISOControl)
        } ?: true
    val identifiers = HashSet<String>(result.items.size)
    val workspaceIdentifiers = HashSet<String>(1)
    val valid =
        result.items.size <= requestedLimit &&
            cursorIsValid &&
            result.items.all { asset ->
                val assetIsValid = validateAsset(asset) && identifiers.add(asset.id)
                workspaceIdentifiers.add(asset.workspaceId)
                assetIsValid
            } &&
            workspaceIdentifiers.size <= 1 &&
            result.items.zipWithNext().all { (first, second) ->
                val firstCreatedAt = Instant.parse(first.createdAt)
                val secondCreatedAt = Instant.parse(second.createdAt)
                firstCreatedAt.isAfter(secondCreatedAt) ||
                    (firstCreatedAt == secondCreatedAt && first.id > second.id)
            }
    if (!valid) {
        throw VoiceAssetProtocolException("Server returned an invalid asset list page.")
    }
    return result
}

private fun validateAsset(asset: Asset): Boolean =
    runCatching {
        validateUuid(asset.id, "asset id")
        validateUuid(asset.workspaceId, "workspace id")
        asset.collectionId?.let { validateUuid(it, "collection id") }
        validateTitle(asset.title)
        validateLanguage(asset.language)
        val createdAt = Instant.parse(asset.createdAt)
        val updatedAt = Instant.parse(asset.updatedAt)
        require(!updatedAt.isBefore(createdAt))
    }.isSuccess &&
        asset.title == asset.title.trim() &&
        asset.status in SYNC_ASSET_STATUSES &&
        (asset.durationMillis == null || asset.durationMillis >= 0) &&
        asset.version >= 1

private fun requireEntityTag(value: String): Long = requireNotNull(parseEntityTag(value)) { "asset entity tag is invalid" }

private fun parseEntityTag(value: String): Long? {
    val match = ENTITY_TAG.matchEntire(value) ?: return null
    return match.groupValues[1].toLongOrNull()?.takeIf { it >= 1 }
}

private fun validateLanguage(value: String) {
    require(LANGUAGE.matches(value)) { "asset language is invalid" }
}

private fun validateUpload(input: CreateUploadRequest): CreateUploadRequest {
    val filename = input.filename.trim()
    require(filename.isNotEmpty() && filename.length <= 255) { "upload filename is invalid" }
    require(filename.none { it == '/' || it == '\\' || it.isISOControl() }) { "upload filename is invalid" }
    require(input.mimeType in SUPPORTED_MEDIA_TYPES) { "upload media type is not supported" }
    require(input.sizeBytes in 44..MAX_UPLOAD_SIZE) { "upload size is outside the contract limit" }
    require(SHA256.matches(input.sha256)) { "upload checksum must be lowercase SHA-256" }
    return input.copy(
        assetId = validateUuid(input.assetId, "asset id"),
        filename = filename,
    )
}

private fun validatePasswordChange(
    currentPassword: String,
    newPassword: String,
) {
    require(currentPassword.toByteArray(Charsets.UTF_8).size in 1..MAX_PASSWORD_LENGTH) {
        "current password length is outside the contract limit"
    }
    require(
        newPassword.codePointCount(0, newPassword.length) >= MIN_NEW_PASSWORD_LENGTH &&
            newPassword.toByteArray(Charsets.UTF_8).size <= MAX_PASSWORD_LENGTH,
    ) { "new password length is outside the contract limit" }
    require(newPassword != currentPassword) { "new password must differ from current password" }
}

private fun validateUuid(
    value: String,
    label: String,
): String {
    val parsed = runCatching { UUID.fromString(value) }.getOrNull()
    require(parsed != null && parsed.toString() == value.lowercase()) { "$label must be a canonical UUID" }
    return parsed.toString()
}

private fun validateIdempotencyKey(value: String): String {
    require(value.length in 1..200 && value.none(Char::isISOControl)) { "idempotency key is invalid" }
    return value
}

private fun validateListPageRequest(
    cursor: String?,
    limit: Int,
    label: String,
) {
    require(limit in 1..MAX_LIST_LIMIT) { "$label limit is outside the contract limit" }
    cursor?.let {
        require(it.length in 1..MAX_CURSOR_LENGTH && it.none(Char::isISOControl)) {
            "$label cursor is outside the contract limit"
        }
    }
}

private fun validateSyncChanges(
    result: SyncChangeList,
    requestedLimit: Int,
): SyncChangeList {
    if (result.items.size > requestedLimit ||
        result.nextCursor.length !in 1..MAX_CURSOR_LENGTH ||
        result.nextCursor.any(Char::isISOControl)
    ) {
        throw VoiceAssetProtocolException("Server returned an invalid incremental sync page.")
    }
    var previousSequence = 0L
    result.items.forEach { change ->
        val validID = runCatching { validateUuid(change.entityId, "sync entity id") }.isSuccess
        val validTime = runCatching { Instant.parse(change.changedAt) }.isSuccess
        val validOrder = change.sequence > previousSequence
        val validBase =
            validID && validTime && validOrder && change.entityType == "asset" && change.entityVersion >= 1
        val validOperation =
            when (change.operation) {
                "delete" -> change.asset == null
                "upsert" -> change.asset?.let { validateSyncSnapshot(it, change) } == true
                else -> false
            }
        if (!validBase || !validOperation) {
            throw VoiceAssetProtocolException("Server returned an invalid incremental sync change.")
        }
        previousSequence = change.sequence
    }
    return result
}

private fun validateSyncSnapshot(
    snapshot: SyncAssetSnapshot,
    change: SyncChange,
): Boolean =
    runCatching {
        validateUuid(snapshot.id, "sync asset id")
        snapshot.collectionId?.let { validateUuid(it, "sync collection id") }
        Instant.parse(snapshot.createdAt)
        Instant.parse(snapshot.updatedAt)
        snapshot.trashedAt?.let(Instant::parse)
    }.isSuccess &&
        snapshot.id == change.entityId &&
        snapshot.version == change.entityVersion &&
        snapshot.title.isNotBlank() &&
        snapshot.title.length <= 500 &&
        LANGUAGE.matches(snapshot.language) &&
        snapshot.status in SYNC_ASSET_STATUSES &&
        (snapshot.durationMillis == null || snapshot.durationMillis >= 0)

private fun Throwable.hasTlsCause(): Boolean {
    val pending = ArrayDeque<Throwable>()
    val inspected = mutableSetOf<Throwable>()
    pending.add(this)
    while (pending.isNotEmpty()) {
        val current = pending.removeFirst()
        if (!inspected.add(current)) {
            continue
        }
        if (current is SSLException || current is CertificateException) {
            return true
        }
        current.cause?.let(pending::add)
        current.suppressed.forEach(pending::add)
    }
    return false
}

private const val SESSION_COOKIE_NAME = "voiceasset_session"
private const val REFRESH_COOKIE_NAME = "voiceasset_refresh"
private const val API_COOKIE_PATH = "/api/v1"
private const val REFRESH_COOKIE_PATH = "/api/v1/auth"
private const val IDEMPOTENCY_KEY = "Idempotency-Key"
private const val PART_SHA256 = "X-Part-SHA256"
private const val MAX_PART_SIZE = 5_242_880
private const val MAX_UPLOAD_SIZE = 536_870_912L
private const val MAX_PASSWORD_LENGTH = 1_024
private const val MIN_NEW_PASSWORD_LENGTH = 12
private const val MAX_DEVICE_NAME_LENGTH = 100
private const val DEFAULT_DEVICE_NAME = "VoiceAsset Android"
const val DEFAULT_SYNC_LIMIT = 50
const val DEFAULT_ASSET_LIST_LIMIT = 50
const val DEFAULT_ADMINISTRATION_LIST_LIMIT = 50
private const val MAX_LIST_LIMIT = 100
private const val MAX_CURSOR_LENGTH = 1024
private const val MAX_PROVIDER_PROFILES = 1_000
private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
private val OCTET_STREAM = "application/octet-stream".toMediaType()
private val LANGUAGE = Regex("^(?:und|[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8})*)$")
private val SUPPORTED_MEDIA_TYPES = setOf("audio/wav", "audio/x-wav", "audio/mp4")
private val SYNC_ASSET_STATUSES = setOf("draft", "uploading", "processing", "ready", "failed", "trashed", "purging")
private val ADMINISTRATION_JOB_STATES = setOf("queued", "running", "retry_wait", "succeeded", "failed", "cancelled")
private val ASR_PROVIDER_IDS = setOf("mock_asr", "aliyun_asr", "tencent_asr")
private val LLM_PROVIDER_IDS = setOf("mock_llm", "openai_compatible_llm")
private val ASR_PROVIDER_HEALTH_ERROR_CLASSES =
    setOf(
        ProviderHealthErrorClass.INVALID_CONFIGURATION,
        ProviderHealthErrorClass.AUTHENTICATION,
        ProviderHealthErrorClass.AUTHORIZATION,
        ProviderHealthErrorClass.RATE_LIMITED,
        ProviderHealthErrorClass.INVALID_AUDIO,
        ProviderHealthErrorClass.UNSUPPORTED,
        ProviderHealthErrorClass.TRANSIENT,
        ProviderHealthErrorClass.REJECTED,
        ProviderHealthErrorClass.CANCELED,
    )
private val LLM_PROVIDER_HEALTH_ERROR_CLASSES =
    setOf(
        ProviderHealthErrorClass.INVALID_CONFIGURATION,
        ProviderHealthErrorClass.AUTHENTICATION,
        ProviderHealthErrorClass.AUTHORIZATION,
        ProviderHealthErrorClass.RATE_LIMITED,
        ProviderHealthErrorClass.TRANSIENT,
        ProviderHealthErrorClass.REJECTED,
        ProviderHealthErrorClass.UNSAFE_PROPOSAL,
        ProviderHealthErrorClass.CANCELED,
    )
private val JOB_KIND = Regex("^[a-z][a-z0-9_]{0,99}$")
private val ENTITY_TAG = Regex("^\"([1-9][0-9]{0,18})\"$")
private val METHODS_REQUIRING_BODY = setOf("POST", "PUT", "PATCH")
