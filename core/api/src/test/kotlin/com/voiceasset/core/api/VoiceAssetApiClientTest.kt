package com.voiceasset.core.api

import com.voiceasset.core.model.AuthenticationMode
import com.voiceasset.core.model.ServerProfile
import com.voiceasset.core.model.ServerProfileId
import com.voiceasset.core.model.TranscriptionPolicy
import com.voiceasset.core.model.UploadPolicy
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

class VoiceAssetApiClientTest {
    private lateinit var server: MockWebServer

    @Before
    fun startServer() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun stopServer() {
        server.shutdown()
    }

    @Test
    fun `capability request uses bearer authentication and parses the contract`() {
        server.enqueue(
            jsonResponse(
                """
                {
                  "server_version":"0.1.0-dev",
                  "api_version":"v1",
                  "contract_version":"0.22.0",
                  "features":["capability_negotiation","m4a_uploads","resumable_uploads"]
                }
                """.trimIndent(),
            ),
        )
        val client = client(credential = "va_test_token_with_sufficient_entropy")

        val capabilities = client.getCapabilities()

        assertEquals("0.22.0", capabilities.contractVersion)
        assertEquals(listOf("capability_negotiation", "m4a_uploads", "resumable_uploads"), capabilities.features)
        val request = server.takeRequest()
        assertEquals("/api/v1/system/capabilities", request.path)
        assertEquals("Bearer va_test_token_with_sufficient_entropy", request.getHeader("Authorization"))
        assertFalse(request.getHeader("X-Request-ID").isNullOrBlank())
    }

    @Test
    fun `administration reads encode pagination and validate bounded operational data`() {
        val newerJobId = "31000000-0000-4000-8000-000000000003"
        val olderJobId = "30000000-0000-4000-8000-000000000003"
        server.enqueue(
            jsonResponse(
                """
                {
                  "items":[
                    ${administrationJobJson(newerJobId, updatedAt = "2026-07-16T08:01:00Z")},
                    ${administrationJobJson(olderJobId)}
                  ],
                  "next_cursor":"opaque jobs page"
                }
                """.trimIndent(),
            ),
        )
        server.enqueue(jsonResponse(administrationSystemStatusJson()))
        val client = client(credential = "va_test_token_with_sufficient_entropy")

        val jobs = client.listAdministrationJobs(cursor = "previous jobs", limit = 2)
        val status = client.getAdministrationSystemStatus()

        assertEquals(listOf(newerJobId, olderJobId), jobs.items.map(AdministrationJob::id))
        assertEquals("opaque jobs page", jobs.nextCursor)
        assertEquals(7L, status.jobs.total)
        assertEquals(2L, status.providers.enabledAsr)
        assertEquals("/api/v1/admin/jobs?limit=2&cursor=previous%20jobs", server.takeRequest().path)
        assertEquals("/api/v1/admin/system-status", server.takeRequest().path)
    }

    @Test
    fun `administration reads reject duplicate jobs and inconsistent status totals`() {
        val jobId = "30000000-0000-4000-8000-000000000003"
        server.enqueue(
            jsonResponse(
                """{"items":[${administrationJobJson(jobId)},${administrationJobJson(jobId)}]}""",
            ),
        )
        server.enqueue(jsonResponse(administrationSystemStatusJson().replace("\"total\":7", "\"total\":8")))
        val client = client(credential = "va_test_token_with_sufficient_entropy")

        assertThrows(VoiceAssetProtocolException::class.java) {
            client.listAdministrationJobs(limit = 2)
        }
        assertThrows(VoiceAssetProtocolException::class.java) {
            client.getAdministrationSystemStatus()
        }
        assertThrows(IllegalArgumentException::class.java) {
            client.listAdministrationJobs(cursor = "", limit = 2)
        }
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `administration retry posts the exact job and validates a single added attempt`() {
        val jobId = "30000000-0000-4000-8000-000000000003"
        server.enqueue(
            jsonResponse(
                administrationJobJson(
                    id = jobId,
                    state = "queued",
                    attempts = 3,
                    maxAttempts = 4,
                    retryable = false,
                    resultRevisionId = null,
                ),
            ),
        )
        val client = client(credential = "va_test_token_with_sufficient_entropy")

        val retried = client.retryAdministrationJob(jobId)

        assertEquals(jobId, retried.id)
        assertEquals("queued", retried.state)
        assertEquals(3, retried.attempts)
        assertEquals(4, retried.maxAttempts)
        assertFalse(retried.retryable)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/v1/admin/jobs/$jobId/retry", request.path)
        assertEquals(server.url("/").toString().removeSuffix("/"), request.getHeader("Origin"))
        assertEquals(0L, request.bodySize)
    }

    @Test
    fun `administration retry rejects a different job and a still failed response`() {
        val jobId = "30000000-0000-4000-8000-000000000003"
        server.enqueue(
            jsonResponse(
                administrationJobJson(
                    id = "30000000-0000-4000-8000-000000000004",
                    state = "queued",
                    attempts = 3,
                    maxAttempts = 4,
                    resultRevisionId = null,
                ),
            ),
        )
        server.enqueue(
            jsonResponse(
                administrationJobJson(
                    id = jobId,
                    state = "failed",
                    attempts = 3,
                    maxAttempts = 3,
                    retryable = true,
                    resultRevisionId = null,
                ),
            ),
        )
        val client = client(credential = "va_test_token_with_sufficient_entropy")

        assertThrows(VoiceAssetProtocolException::class.java) {
            client.retryAdministrationJob(jobId)
        }
        assertThrows(VoiceAssetProtocolException::class.java) {
            client.retryAdministrationJob(jobId)
        }
        assertThrows(IllegalArgumentException::class.java) {
            client.retryAdministrationJob("not-a-job")
        }
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `provider administration lists and toggles ASR and LLM profiles with exact versions`() {
        val asrProfileId = "32000000-0000-4000-8000-000000000003"
        val llmProfileId = "33000000-0000-4000-8000-000000000003"
        server.enqueue(jsonResponse("""{"items":[${providerProfileJson(asrProfileId, "mock_asr")}]}"""))
        server.enqueue(jsonResponse("""{"items":[${providerProfileJson(llmProfileId, "mock_llm")}]}"""))
        server.enqueue(
            jsonResponse(
                providerProfileJson(asrProfileId, "mock_asr", state = "enabled", version = 2),
                headers = mapOf("ETag" to "\"2\""),
            ),
        )
        server.enqueue(
            jsonResponse(
                providerProfileJson(llmProfileId, "mock_llm", state = "enabled", version = 2),
                headers = mapOf("ETag" to "\"2\""),
            ),
        )
        val client = client(credential = "va_test_token_with_sufficient_entropy")

        assertEquals(
            "mock_asr",
            client
                .listAsrProviderProfiles()
                .items
                .single()
                .providerId,
        )
        assertEquals(
            "mock_llm",
            client
                .listLlmProviderProfiles()
                .items
                .single()
                .providerId,
        )
        val asr = client.updateAsrProviderProfileState(asrProfileId, 1, ProviderProfileState.ENABLED)
        val llm = client.updateLlmProviderProfileState(llmProfileId, 1, ProviderProfileState.ENABLED)

        assertEquals(2L, asr.profile.version)
        assertEquals(ProviderProfileState.ENABLED, llm.profile.state)
        assertEquals("/api/v1/provider-profiles", server.takeRequest().path)
        assertEquals("/api/v1/llm-profiles", server.takeRequest().path)
        val asrRequest = server.takeRequest()
        assertEquals("PATCH", asrRequest.method)
        assertEquals("/api/v1/provider-profiles/$asrProfileId", asrRequest.path)
        assertEquals("\"1\"", asrRequest.getHeader("If-Match"))
        assertEquals(server.url("/").toString().removeSuffix("/"), asrRequest.getHeader("Origin"))
        assertEquals("{\"state\":\"enabled\"}", asrRequest.body.readUtf8())
        val llmRequest = server.takeRequest()
        assertEquals("/api/v1/llm-profiles/$llmProfileId", llmRequest.path)
        assertEquals("{\"state\":\"enabled\"}", llmRequest.body.readUtf8())
    }

    @Test
    fun `provider administration fails closed on family drift and invalid version responses`() {
        val profileId = "32000000-0000-4000-8000-000000000003"
        server.enqueue(jsonResponse("""{"items":[${providerProfileJson(profileId, "mock_llm")}]}"""))
        server.enqueue(
            jsonResponse(
                providerProfileJson(profileId, "mock_asr", state = "enabled", version = 1),
                headers = mapOf("ETag" to "\"1\""),
            ),
        )
        val client = client(credential = "va_test_token_with_sufficient_entropy")

        assertThrows(VoiceAssetProtocolException::class.java) { client.listAsrProviderProfiles() }
        assertThrows(VoiceAssetProtocolException::class.java) {
            client.updateAsrProviderProfileState(profileId, 1, ProviderProfileState.ENABLED)
        }
        assertThrows(IllegalArgumentException::class.java) {
            client.updateAsrProviderProfileState("not-a-uuid", 1, ProviderProfileState.ENABLED)
        }
        assertThrows(IllegalArgumentException::class.java) {
            client.updateAsrProviderProfileState(profileId, 0, ProviderProfileState.ENABLED)
        }
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `provider health checks are explicit typed posts for ASR and LLM profiles`() {
        val asrProfileId = "32000000-0000-4000-8000-000000000003"
        val llmProfileId = "33000000-0000-4000-8000-000000000003"
        server.enqueue(jsonResponse(providerHealthJson(asrProfileId, status = "healthy")))
        server.enqueue(
            jsonResponse(
                providerHealthJson(
                    llmProfileId,
                    status = "unhealthy",
                    errorClass = "authentication",
                ),
            ),
        )
        val client = client(credential = "va_test_token_with_sufficient_entropy")

        val asr = client.checkAsrProviderProfileHealth(asrProfileId)
        val llm = client.checkLlmProviderProfileHealth(llmProfileId)

        assertEquals(ProviderHealthStatus.HEALTHY, asr.status)
        assertNull(asr.errorClass)
        assertEquals(ProviderHealthStatus.UNHEALTHY, llm.status)
        assertEquals(ProviderHealthErrorClass.AUTHENTICATION, llm.errorClass)
        val asrRequest = server.takeRequest()
        assertEquals("POST", asrRequest.method)
        assertEquals("/api/v1/provider-profiles/$asrProfileId/health", asrRequest.path)
        assertEquals(server.url("/").toString().removeSuffix("/"), asrRequest.getHeader("Origin"))
        assertEquals(0L, asrRequest.bodySize)
        val llmRequest = server.takeRequest()
        assertEquals("POST", llmRequest.method)
        assertEquals("/api/v1/llm-profiles/$llmProfileId/health", llmRequest.path)
    }

    @Test
    fun `provider health checks fail closed on identity semantics and family drift`() {
        val profileId = "32000000-0000-4000-8000-000000000003"
        val otherProfileId = "33000000-0000-4000-8000-000000000003"
        server.enqueue(jsonResponse(providerHealthJson(otherProfileId, status = "healthy")))
        server.enqueue(
            jsonResponse(
                providerHealthJson(profileId, status = "unhealthy", errorClass = "invalid_audio"),
            ),
        )
        server.enqueue(
            jsonResponse(
                providerHealthJson(profileId, status = "healthy", errorClass = "transient"),
            ),
        )
        val client = client(credential = "va_test_token_with_sufficient_entropy")

        assertThrows(VoiceAssetProtocolException::class.java) {
            client.checkAsrProviderProfileHealth(profileId)
        }
        assertThrows(VoiceAssetProtocolException::class.java) {
            client.checkLlmProviderProfileHealth(profileId)
        }
        assertThrows(VoiceAssetProtocolException::class.java) {
            client.checkAsrProviderProfileHealth(profileId)
        }
        assertThrows(IllegalArgumentException::class.java) {
            client.checkAsrProviderProfileHealth("not-a-uuid")
        }
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `login extracts the opaque session cookie without returning the password`() {
        server.enqueue(
            jsonResponse(
                body =
                    """
                    {
                      "expires_at":"2026-07-17T08:00:00Z",
                      "refresh_expires_at":"2026-08-16T08:00:00Z",
                      "user":{
                        "id":"10000000-0000-4000-8000-000000000001",
                        "workspace_id":"20000000-0000-4000-8000-000000000002",
                        "role":"owner",
                        "email":"owner@example.com",
                        "scopes":["assets:read","assets:write"]
                      }
                    }
                    """.trimIndent(),
                headers =
                    mapOf(
                        "Set-Cookie" to
                            "voiceasset_session=va_device_token_with_sufficient_entropy; " +
                            "Path=/api/v1; HttpOnly; Secure; SameSite=Strict",
                    ),
                additionalHeaders =
                    listOf(
                        "Set-Cookie" to
                            "voiceasset_refresh=va_rft_${"r".repeat(43)}; " +
                            "Path=/api/v1/auth; HttpOnly; Secure; SameSite=Strict",
                    ),
                status = 201,
            ),
        )
        val client = client()

        val login = client.login(" owner@example.com ", "never-store-this", " Pixel 9 Pro ")

        assertEquals("va_device_token_with_sufficient_entropy", login.credential.value)
        assertEquals("va_rft_${"r".repeat(43)}", login.refreshCredential.value)
        assertEquals("2026-08-16T08:00:00Z", login.session.refreshExpiresAt)
        assertEquals("owner@example.com", login.session.user.email)
        val request = server.takeRequest()
        assertEquals("/api/v1/auth/sessions", request.path)
        assertNull(request.getHeader("Authorization"))
        assertEquals(
            "{\"email\":\"owner@example.com\",\"password\":\"never-store-this\",\"device_name\":\"Pixel 9 Pro\"}",
            request.body.readUtf8(),
        )
        assertFalse(login.toString().contains("never-store-this"))
    }

    @Test
    fun `pairing preflights capability then claims with exact origin and no reusable authorization`() {
        server.enqueue(
            jsonResponse(
                """{"server_version":"0.1.0-dev","api_version":"v1","contract_version":"0.22.0","features":["device_pairing"]}""",
            ),
        )
        server.enqueue(
            jsonResponse(
                body =
                    """
                    {
                      "expires_at":"2026-07-18T16:00:00Z",
                      "refresh_expires_at":"2026-08-17T16:00:00Z",
                      "user":{
                        "id":"10000000-0000-4000-8000-000000000001",
                        "workspace_id":"20000000-0000-4000-8000-000000000002",
                        "role":"owner",
                        "email":"owner@example.com",
                        "scopes":["assets:read","assets:write"]
                      }
                    }
                    """.trimIndent(),
                headers =
                    mapOf(
                        "Set-Cookie" to
                            "voiceasset_session=va_paired_token_with_sufficient_entropy; " +
                            "Path=/api/v1; HttpOnly; Secure; SameSite=Strict",
                    ),
                additionalHeaders =
                    listOf(
                        "Set-Cookie" to
                            "voiceasset_refresh=va_rft_${"r".repeat(43)}; " +
                            "Path=/api/v1/auth; HttpOnly; Secure; SameSite=Strict",
                    ),
                status = 201,
            ),
        )
        val secret = PairingSecret("va_pair_${"A".repeat(43)}")
        val payload =
            PairingPayload(
                origin = server.url("/").toString().removeSuffix("/"),
                pairingSessionId = "40000000-0000-4000-8000-000000000001",
                secret = secret,
                expiresAt = Instant.parse("2026-07-18T04:05:00Z"),
                contractVersion = "0.22.0",
            )

        val paired = client().claimPairing(payload, " Pixel 9 Pro ")

        assertEquals("va_paired_token_with_sufficient_entropy", paired.credential.value)
        val capabilitiesRequest = server.takeRequest()
        assertEquals("/api/v1/system/capabilities", capabilitiesRequest.path)
        assertNull(capabilitiesRequest.getHeader("Authorization"))
        val claimRequest = server.takeRequest()
        assertEquals("POST", claimRequest.method)
        assertEquals("/api/v1/auth/pairing-sessions/${payload.pairingSessionId}/claim", claimRequest.path)
        assertEquals(payload.origin, claimRequest.getHeader("Origin"))
        assertNull(claimRequest.getHeader("Authorization"))
        assertEquals(
            "{\"secret\":\"${secret.value}\",\"device_name\":\"Pixel 9 Pro\"}",
            claimRequest.body.readUtf8(),
        )
    }

    @Test
    fun `pairing fails closed before claim when capability is absent`() {
        server.enqueue(
            jsonResponse(
                """{"server_version":"0.1.0-dev","api_version":"v1","contract_version":"0.22.0","features":[]}""",
            ),
        )
        val payload =
            PairingPayload(
                origin = server.url("/").toString().removeSuffix("/"),
                pairingSessionId = "40000000-0000-4000-8000-000000000001",
                secret = PairingSecret("va_pair_${"A".repeat(43)}"),
                expiresAt = Instant.parse("2026-07-18T04:05:00Z"),
                contractVersion = "0.22.0",
            )

        assertThrows(VoiceAssetProtocolException::class.java) {
            client().claimPairing(payload, "Android")
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `password change uses the authenticated contract path and redacts credentials`() {
        server.enqueue(MockResponse().setResponseCode(204))
        val client = client(credential = "va_test_token_with_sufficient_entropy")

        client.changePassword("current-password", "new-password-456")

        val request = server.takeRequest()
        assertEquals("PATCH", request.method)
        assertEquals("/api/v1/auth/password", request.path)
        assertEquals("Bearer va_test_token_with_sufficient_entropy", request.getHeader("Authorization"))
        assertEquals(
            "{\"current_password\":\"current-password\",\"new_password\":\"new-password-456\"}",
            request.body.readUtf8(),
        )
        val debugValue = ChangePasswordRequest("secret-current", "secret-future").toString()
        assertFalse(debugValue.contains("secret-current"))
        assertFalse(debugValue.contains("secret-future"))
    }

    @Test
    fun `device sessions are strictly decoded and an exact session can be revoked`() {
        val currentId = "41000000-0000-4000-8000-000000000001"
        val otherId = "41000000-0000-4000-8000-000000000002"
        server.enqueue(
            jsonResponse(
                """
                {
                  "items":[
                    {
                      "id":"$currentId",
                      "device_name":"VoiceAsset Android",
                      "current":true,
                      "created_at":"2026-07-18T05:00:00Z",
                      "last_seen_at":"2026-07-18T05:30:00Z",
                      "expires_at":"2026-07-18T17:30:00Z",
                      "refresh_expires_at":"2026-08-17T05:30:00Z"
                    },
                    {
                      "id":"$otherId",
                      "device_name":"Firefox",
                      "current":false,
                      "created_at":"2026-07-17T05:00:00Z",
                      "last_seen_at":"2026-07-17T06:00:00Z",
                      "expires_at":"2026-07-17T18:00:00Z",
                      "refresh_expires_at":"2026-08-16T06:00:00Z",
                      "revoked_at":null
                    }
                  ]
                }
                """.trimIndent(),
            ),
        )
        server.enqueue(MockResponse().setResponseCode(204))
        val client = client(credential = "va_device_token_with_sufficient_entropy")

        val sessions = client.listDeviceSessions()
        client.revokeDeviceSession(otherId)

        assertEquals(listOf(currentId, otherId), sessions.items.map(DeviceSession::id))
        assertEquals("VoiceAsset Android", sessions.items.single(DeviceSession::current).deviceName)
        val listRequest = server.takeRequest()
        assertEquals("GET", listRequest.method)
        assertEquals("/api/v1/auth/device-sessions", listRequest.path)
        assertEquals("Bearer va_device_token_with_sufficient_entropy", listRequest.getHeader("Authorization"))
        val revokeRequest = server.takeRequest()
        assertEquals("DELETE", revokeRequest.method)
        assertEquals("/api/v1/auth/device-sessions/$otherId", revokeRequest.path)
        assertEquals(server.url("/").toString().removeSuffix("/"), revokeRequest.getHeader("Origin"))
    }

    @Test
    fun `device session inventory rejects duplicates missing current and revoked rows`() {
        val id = "42000000-0000-4000-8000-000000000001"
        val valid =
            """
            {
              "id":"$id",
              "device_name":"Android",
              "current":false,
              "created_at":"2026-07-18T05:00:00Z",
              "last_seen_at":"2026-07-18T05:30:00Z",
              "expires_at":"2026-07-18T17:30:00Z",
              "refresh_expires_at":"2026-08-17T05:30:00Z"
            }
            """.trimIndent()
        val current = valid.replace("\"current\":false", "\"current\":true")
        val revoked = current.replace("\n}", ",\n  \"revoked_at\":\"2026-07-18T06:00:00Z\"\n}")
        val invalidBodies =
            listOf(
                """{"items":[$valid]}""",
                """{"items":[$current,$current]}""",
                """{"items":[$revoked]}""",
            )
        invalidBodies.forEach { body -> server.enqueue(jsonResponse(body)) }
        val client = client(credential = "va_device_token_with_sufficient_entropy")

        invalidBodies.forEach {
            assertThrows(VoiceAssetProtocolException::class.java) { client.listDeviceSessions() }
        }
    }

    @Test
    fun `password change rejects invalid input before network access`() {
        val client = client(credential = "va_test_token_with_sufficient_entropy")

        assertThrows(IllegalArgumentException::class.java) {
            client.changePassword("same-password", "same-password")
        }
        assertThrows(IllegalArgumentException::class.java) {
            client.changePassword("current-password", "short")
        }
        assertThrows(IllegalArgumentException::class.java) {
            client.changePassword("current-password", "界".repeat(342))
        }
        assertNull(server.takeRequest(100, java.util.concurrent.TimeUnit.MILLISECONDS))
    }

    @Test
    fun `login rejects unsafe or malformed session cookies`() {
        val cookieHeaders =
            listOf(
                "voiceasset_session=va_device_token_with_sufficient_entropy; Path=/api/v1; HttpOnly",
                "voiceasset_session=va_device_token_with_sufficient_entropy; Path=/; HttpOnly; Secure",
                "voiceasset_session=invalid; Path=/api/v1; HttpOnly; Secure",
            )
        cookieHeaders.forEach { cookieHeader ->
            server.enqueue(
                jsonResponse(
                    body =
                        """
                        {
                          "expires_at":"2026-07-17T08:00:00Z",
                          "refresh_expires_at":"2026-08-16T08:00:00Z",
                          "user":{
                            "id":"10000000-0000-4000-8000-000000000001",
                            "workspace_id":"20000000-0000-4000-8000-000000000002",
                            "role":"owner",
                            "email":"owner@example.com",
                            "scopes":["assets:read","assets:write"]
                          }
                        }
                        """.trimIndent(),
                    headers = mapOf("Set-Cookie" to cookieHeader),
                    additionalHeaders =
                        listOf(
                            "Set-Cookie" to
                                "voiceasset_refresh=va_rft_${"r".repeat(43)}; " +
                                "Path=/api/v1/auth; HttpOnly; Secure; SameSite=Strict",
                        ),
                    status = 201,
                ),
            )

            assertThrows(VoiceAssetProtocolException::class.java) {
                client().login("owner@example.com", "never-store-this")
            }
        }
    }

    @Test
    fun `refresh rotates both credentials with an exact origin and no authorization header`() {
        server.enqueue(
            jsonResponse(
                body =
                    """
                    {
                      "expires_at":"2026-07-17T20:00:00Z",
                      "refresh_expires_at":"2026-08-16T08:00:00Z",
                      "user":{
                        "id":"10000000-0000-4000-8000-000000000001",
                        "workspace_id":"20000000-0000-4000-8000-000000000002",
                        "role":"owner",
                        "email":"owner@example.com",
                        "scopes":["assets:read","assets:write"]
                      }
                    }
                    """.trimIndent(),
                headers =
                    mapOf(
                        "Set-Cookie" to
                            "voiceasset_session=va_rotated_token_with_sufficient_entropy; " +
                            "Path=/api/v1; HttpOnly; Secure; SameSite=Strict",
                    ),
                additionalHeaders =
                    listOf(
                        "Set-Cookie" to
                            "voiceasset_refresh=va_rft_${"n".repeat(43)}; " +
                            "Path=/api/v1/auth; HttpOnly; Secure; SameSite=Strict",
                    ),
            ),
        )
        val oldRefresh = RefreshCredential("va_rft_${"o".repeat(43)}")

        val refreshed = client(credential = "va_expired_token_with_sufficient_entropy").refreshSession(oldRefresh)

        assertEquals("va_rotated_token_with_sufficient_entropy", refreshed.credential.value)
        assertEquals("va_rft_${"n".repeat(43)}", refreshed.refreshCredential.value)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/v1/auth/session/refresh", request.path)
        assertEquals("voiceasset_refresh=${oldRefresh.value}", request.getHeader("Cookie"))
        assertEquals(server.url("/").toString().removeSuffix("/"), request.getHeader("Origin"))
        assertNull(request.getHeader("Authorization"))
    }

    @Test
    fun `profile authentication verifies capabilities before returning the credential`() {
        server.enqueue(
            jsonResponse(
                """
                {
                  "server_version":"0.1.0-dev",
                  "api_version":"v1",
                  "contract_version":"0.13.0",
                  "features":[
                    "capability_negotiation",
                    "m4a_uploads",
                    "refresh_sessions",
                    "resumable_uploads",
                    "structured_errors",
                    "transcription_jobs"
                  ]
                }
                """.trimIndent(),
            ),
        )
        server.enqueue(
            jsonResponse(
                body =
                    """
                    {
                      "expires_at":"2026-07-17T08:00:00Z",
                      "refresh_expires_at":"2026-08-16T08:00:00Z",
                      "user":{
                        "id":"10000000-0000-4000-8000-000000000001",
                        "workspace_id":"20000000-0000-4000-8000-000000000002",
                        "role":"owner",
                        "email":"owner@example.com",
                        "scopes":["assets:read","assets:write"]
                      }
                    }
                    """.trimIndent(),
                headers =
                    mapOf(
                        "Set-Cookie" to
                            "voiceasset_session=va_device_token_with_sufficient_entropy; " +
                            "Path=/api/v1; HttpOnly; Secure; SameSite=Strict",
                    ),
                additionalHeaders =
                    listOf(
                        "Set-Cookie" to
                            "voiceasset_refresh=va_rft_${"r".repeat(43)}; " +
                            "Path=/api/v1/auth; HttpOnly; Secure; SameSite=Strict",
                    ),
                status = 201,
            ),
        )
        val authenticator =
            ApiServerProfileAuthenticator { _, credential -> client(credential?.value) }

        val login = authenticator.authenticate(profile(), "owner@example.com", "never-store-this")

        assertEquals("va_device_token_with_sufficient_entropy", login.credential.value)
        assertEquals("va_rft_${"r".repeat(43)}", login.refreshCredential.value)
        val capabilitiesRequest = server.takeRequest()
        assertEquals("/api/v1/system/capabilities", capabilitiesRequest.path)
        assertNull(capabilitiesRequest.getHeader("Authorization"))
        assertEquals("/api/v1/auth/sessions", server.takeRequest().path)
    }

    @Test
    fun `profile authentication rejects incompatible server before login`() {
        server.enqueue(
            jsonResponse(
                """
                {
                  "server_version":"0.1.0-dev",
                  "api_version":"v1",
                  "contract_version":"0.12.0",
                  "features":[]
                }
                """.trimIndent(),
            ),
        )
        val authenticator =
            ApiServerProfileAuthenticator { _, credential -> client(credential?.value) }

        assertThrows(VoiceAssetProtocolException::class.java) {
            authenticator.authenticate(profile(), "owner@example.com", "never-store-this")
        }

        assertEquals("/api/v1/system/capabilities", server.takeRequest().path)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `asset and upload requests preserve idempotency and exact part bytes`() {
        val assetId = "30000000-0000-4000-8000-000000000003"
        val uploadId = "40000000-0000-4000-8000-000000000004"
        server.enqueue(jsonResponse(assetJson(assetId), status = 201))
        server.enqueue(jsonResponse(uploadJson(uploadId, assetId, parts = "null"), status = 201))
        server.enqueue(
            jsonResponse(
                """{"number":1,"size_bytes":4,"sha256":"${"a".repeat(64)}","created_at":"2026-07-16T08:00:00Z"}""",
                status = 201,
            ),
        )
        val client = client(credential = "va_test_token_with_sufficient_entropy")

        client.createAsset(CreateAssetRequest(" Field note ", "en-US"), "asset-key")
        client.createUpload(
            CreateUploadRequest(
                assetId = assetId,
                filename = "field-note.m4a",
                mimeType = "audio/mp4",
                sizeBytes = 44,
                sha256 = "b".repeat(64),
            ),
            "upload-key",
        )
        val bytes = byteArrayOf(0, 1, 2, 3)
        client.putUploadPart(uploadId, 1, bytes, "a".repeat(64))

        val assetRequest = server.takeRequest()
        assertEquals("asset-key", assetRequest.getHeader("Idempotency-Key"))
        assertEquals("{\"title\":\"Field note\",\"language\":\"en-US\"}", assetRequest.body.readUtf8())
        val uploadRequest = server.takeRequest()
        assertEquals("upload-key", uploadRequest.getHeader("Idempotency-Key"))
        val uploadBody = uploadRequest.body.readUtf8()
        assertTrue(uploadBody.contains("\"size_bytes\":44"))
        assertTrue(uploadBody.contains("\"mime_type\":\"audio/mp4\""))
        val partRequest = server.takeRequest()
        assertEquals("application/octet-stream", partRequest.getHeader("Content-Type"))
        assertEquals("a".repeat(64), partRequest.getHeader("X-Part-SHA256"))
        assertArrayEquals(bytes, partRequest.body.readByteArray())
    }

    @Test
    fun `asset metadata update reuses the fetched strong ETag and validates the new version`() {
        val assetId = "30000000-0000-4000-8000-000000000003"
        val collectionId = "50000000-0000-4000-8000-000000000005"
        server.enqueue(
            jsonResponse(
                assetJson(id = assetId, title = "Original title", version = 4),
                headers = mapOf("ETag" to "\"4\""),
            ),
        )
        server.enqueue(
            jsonResponse(
                assetJson(
                    id = assetId,
                    title = "Renamed field note",
                    language = "zh-CN",
                    collectionId = collectionId,
                    version = 5,
                    updatedAt = "2026-07-16T08:01:00Z",
                ),
                headers = mapOf("ETag" to "\"5\""),
            ),
        )
        val client = client(credential = "va_test_token_with_sufficient_entropy")

        val current = client.getAsset(assetId)
        val updated =
            client.updateAssetMetadata(
                assetId = assetId,
                expectedEntityTag = current.entityTag,
                input =
                    UpdateAssetMetadataRequest(
                        title = " Renamed field note ",
                        language = "zh-CN",
                        collectionId = collectionId,
                    ),
            )

        assertEquals(4L, current.asset.version)
        assertEquals("\"5\"", updated.entityTag)
        assertEquals("Renamed field note", updated.asset.title)
        assertEquals(collectionId, updated.asset.collectionId)
        val getRequest = server.takeRequest()
        assertEquals("GET", getRequest.method)
        assertEquals("/api/v1/assets/$assetId", getRequest.path)
        val updateRequest = server.takeRequest()
        assertEquals("PUT", updateRequest.method)
        assertEquals("/api/v1/assets/$assetId/metadata", updateRequest.path)
        assertEquals("\"4\"", updateRequest.getHeader("If-Match"))
        assertEquals(server.url("/").toString().removeSuffix("/"), updateRequest.getHeader("Origin"))
        assertEquals(
            "{\"title\":\"Renamed field note\",\"language\":\"zh-CN\",\"collection_id\":\"$collectionId\"}",
            updateRequest.body.readUtf8(),
        )
    }

    @Test
    fun `asset catalog encodes pagination and validates a stable workspace page`() {
        val firstAssetId = "30000000-0000-4000-8000-000000000004"
        val secondAssetId = "30000000-0000-4000-8000-000000000003"
        server.enqueue(
            jsonResponse(
                """
                {
                  "items":[
                    ${assetJson(firstAssetId, title = "Newest")},
                    ${assetJson(secondAssetId, title = "Older")}
                  ],
                  "next_cursor":"opaque page/2"
                }
                """.trimIndent(),
            ),
        )
        val client = client(credential = "va_test_token_with_sufficient_entropy")

        val result = client.listAssets(cursor = "previous page", limit = 2)

        assertEquals(listOf(firstAssetId, secondAssetId), result.items.map(Asset::id))
        assertEquals("opaque page/2", result.nextCursor)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/v1/assets?limit=2&cursor=previous%20page", request.path)
    }

    @Test
    fun `asset catalog rejects duplicate cross-workspace and malformed pages`() {
        val assetId = "30000000-0000-4000-8000-000000000003"
        val otherWorkspace =
            assetJson("30000000-0000-4000-8000-000000000002")
                .replace("20000000-0000-4000-8000-000000000002", "20000000-0000-4000-8000-000000000003")
        server.enqueue(jsonResponse("""{"items":[${assetJson(assetId)},${assetJson(assetId)}]}"""))
        server.enqueue(jsonResponse("""{"items":[${assetJson(assetId)},$otherWorkspace]}"""))
        server.enqueue(jsonResponse("""{"items":[],"next_cursor":""}"""))
        val client = client(credential = "va_test_token_with_sufficient_entropy")

        repeat(3) {
            assertThrows(VoiceAssetProtocolException::class.java) { client.listAssets(limit = 2) }
        }
        assertThrows(IllegalArgumentException::class.java) { client.listAssets(cursor = "", limit = 2) }
        assertThrows(IllegalArgumentException::class.java) { client.listAssets(limit = 101) }
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `versioned asset reads fail closed on missing or inconsistent ETags`() {
        val assetId = "30000000-0000-4000-8000-000000000003"
        server.enqueue(jsonResponse(assetJson(assetId)))
        server.enqueue(
            jsonResponse(
                assetJson(assetId),
                headers = mapOf("ETag" to "\"2\""),
            ),
        )
        val client = client(credential = "va_test_token_with_sufficient_entropy")

        assertThrows(VoiceAssetProtocolException::class.java) { client.getAsset(assetId) }
        assertThrows(VoiceAssetProtocolException::class.java) { client.getAsset(assetId) }
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `metadata conflicts surface once and invalid preconditions never reach the network`() {
        val assetId = "30000000-0000-4000-8000-000000000003"
        val client = client(credential = "va_test_token_with_sufficient_entropy")
        val input = UpdateAssetMetadataRequest("Field note", "en-US", null)

        assertThrows(IllegalArgumentException::class.java) {
            client.updateAssetMetadata(assetId, "W/\"4\"", input)
        }
        assertThrows(IllegalArgumentException::class.java) {
            client.updateAssetMetadata(assetId, "\"4\"", input.copy(title = " "))
        }
        assertThrows(IllegalArgumentException::class.java) {
            client.updateAssetMetadata(assetId, "\"4\"", input.copy(collectionId = "not-a-uuid"))
        }
        assertEquals(0, server.requestCount)

        server.enqueue(
            jsonResponse(
                """{"error":{"code":"conflict","message":"asset version changed","request_id":"request-9"}}""",
                status = 409,
            ),
        )
        val exception =
            assertThrows(VoiceAssetApiException::class.java) {
                client.updateAssetMetadata(assetId, "\"4\"", input)
            }
        assertEquals(409, exception.statusCode)
        assertEquals("conflict", exception.code)
        assertEquals(1, server.requestCount)
        val request = server.takeRequest()
        assertEquals("\"4\"", request.getHeader("If-Match"))
        assertEquals(
            "{\"title\":\"Field note\",\"language\":\"en-US\",\"collection_id\":null}",
            request.body.readUtf8(),
        )
    }

    @Test
    fun `incremental sync encodes the cursor and validates deletion and snapshot changes`() {
        val assetId = "30000000-0000-4000-8000-000000000003"
        server.enqueue(
            jsonResponse(
                """
                {
                  "items":[
                    {
                      "sequence":4,
                      "entity_type":"asset",
                      "entity_id":"$assetId",
                      "operation":"upsert",
                      "entity_version":2,
                      "changed_at":"2026-07-17T20:00:00Z",
                      "asset":{
                        "id":"$assetId",
                        "collection_id":null,
                        "title":"Field note",
                        "language":"en-US",
                        "status":"ready",
                        "duration_ms":1200,
                        "version":2,
                        "created_at":"2026-07-16T08:00:00Z",
                        "updated_at":"2026-07-17T20:00:00Z",
                        "trashed_at":null
                      }
                    },
                    {
                      "sequence":7,
                      "entity_type":"asset",
                      "entity_id":"$assetId",
                      "operation":"delete",
                      "entity_version":2,
                      "changed_at":"2026-07-17T20:01:00Z"
                    }
                  ],
                  "next_cursor":"next-page",
                  "has_more":false
                }
                """.trimIndent(),
            ),
        )
        val client = client(credential = "va_test_token_with_sufficient_entropy")

        val page = client.listSyncChanges(cursor = "opaque+/=", limit = 2)

        assertEquals(2, page.items.size)
        assertEquals(
            "Field note",
            page.items
                .first()
                .asset
                ?.title,
        )
        assertNull(page.items.last().asset)
        assertEquals("/api/v1/sync/changes?limit=2&cursor=opaque%2B%2F%3D", server.takeRequest().path)
    }

    @Test
    fun `structured errors expose safe contract fields without echoing bodies`() {
        server.enqueue(
            jsonResponse(
                """
                {"error":{"code":"unauthorized","message":"Authentication is required.","request_id":"request-7"}}
                """.trimIndent(),
                status = 401,
            ),
        )
        val client = client(credential = "va_test_token_with_sufficient_entropy")

        val exception = assertThrows(VoiceAssetApiException::class.java) { client.getCapabilities() }

        assertEquals(401, exception.statusCode)
        assertEquals("unauthorized", exception.code)
        assertEquals("request-7", exception.requestId)
        assertEquals("Authentication is required.", exception.message)
        assertFalse(exception.toString().contains("va_test_token"))
    }

    @Test
    fun `strict response parsing rejects undeclared fields`() {
        server.enqueue(
            jsonResponse(
                """
                {
                  "server_version":"0.1.0-dev",
                  "api_version":"v1",
                  "contract_version":"0.22.0",
                  "features":[],
                  "surprise":true
                }
                """.trimIndent(),
            ),
        )

        assertThrows(VoiceAssetProtocolException::class.java) { client().getCapabilities() }
    }

    @Test
    fun `upload recovery skips only server-recorded part numbers`() {
        val parts =
            """
            [
              {"number":1,"size_bytes":5242880,"sha256":"${"a".repeat(64)}","created_at":"2026-07-16T08:00:00Z"},
              {"number":3,"size_bytes":7,"sha256":"${"b".repeat(64)}","created_at":"2026-07-16T08:01:00Z"}
            ]
            """.trimIndent()
        val session =
            JsonCodec.decodeUploadSession(
                uploadJson("40000000-0000-4000-8000-000000000004", "30000000-0000-4000-8000-000000000003", parts),
            )

        val missing = UploadRecoveryPlan.from(session).missingPartNumbers

        assertEquals(listOf(2), missing)
    }

    @Test
    fun `transcription request uses the contract path and deterministic retry key`() {
        val assetId = "30000000-0000-4000-8000-000000000003"
        server.enqueue(
            jsonResponse(
                """
                {
                  "id":"50000000-0000-4000-8000-000000000005",
                  "workspace_id":"20000000-0000-4000-8000-000000000002",
                  "asset_id":"$assetId",
                  "created_by":"10000000-0000-4000-8000-000000000001",
                  "kind":"mock_transcribe",
                  "state":"queued",
                  "payload":{"asset_id":"$assetId"},
                  "attempts":0,
                  "max_attempts":3,
                  "available_at":"2026-07-16T08:00:00Z",
                  "created_at":"2026-07-16T08:00:00Z",
                  "updated_at":"2026-07-16T08:00:00Z"
                }
                """.trimIndent(),
                status = 201,
            ),
        )
        val client = client(credential = "va_test_token_with_sufficient_entropy")

        val job = client.createTranscription(assetId, "android-transcription-key")

        assertEquals("queued", job.state)
        val request = server.takeRequest()
        assertEquals("/api/v1/assets/$assetId/transcriptions", request.path)
        assertEquals("android-transcription-key", request.getHeader("Idempotency-Key"))
    }

    @Test
    fun `completed transcription reads the immutable revision with exact timeline`() {
        val assetId = "30000000-0000-4000-8000-000000000003"
        val jobId = "50000000-0000-4000-8000-000000000005"
        val transcriptId = "60000000-0000-4000-8000-000000000006"
        val revisionId = "70000000-0000-4000-8000-000000000007"
        val segmentId = "80000000-0000-4000-8000-000000000008"
        server.enqueue(jsonResponse(succeededJobJson(jobId, assetId, revisionId)))
        server.enqueue(jsonResponse(transcriptListJson(transcriptId, assetId, revisionId)))
        server.enqueue(jsonResponse(revisionJson(revisionId, transcriptId, assetId, jobId, segmentId)))
        val client = client(credential = "va_test_token_with_sufficient_entropy")

        val job = client.getTranscriptionJob(jobId)
        val transcripts = client.listAssetTranscripts(assetId)
        val revision = client.getTranscriptRevision(revisionId)

        assertEquals("succeeded", job.state)
        assertEquals(revisionId, job.resultRevisionId)
        assertEquals(revisionId, transcripts.items.single().latestRevisionId)
        assertEquals("Offline field note", revision.text)
        assertEquals(jobId, revision.sourceJobId)
        assertEquals(segmentId, revision.segments.single().id)
        assertEquals(0L, revision.segments.single().startMillis)
        assertEquals(1_250L, revision.segments.single().endMillis)
        val providerToken =
            revision.segments
                .single()
                .words
                .single()["provider_token"]
                ?.toString()
                ?.trim('"')
        assertEquals("preserved", providerToken)
        assertEquals(
            listOf(
                "/api/v1/transcription-jobs/$jobId",
                "/api/v1/assets/$assetId/transcripts",
                "/api/v1/transcript-revisions/$revisionId",
            ),
            List(3) { server.takeRequest().path },
        )
    }

    @Test
    fun `Android sync compatibility fails closed on missing capabilities`() {
        val compatible =
            ServerCapabilities(
                serverVersion = "0.1.0-dev",
                apiVersion = "v1",
                contractVersion = SUPPORTED_CONTRACT_VERSION,
                features =
                    listOf(
                        "capability_negotiation",
                        "m4a_uploads",
                        "refresh_sessions",
                        "resumable_uploads",
                        "structured_errors",
                        "transcription_jobs",
                    ),
            )
        compatible.requireAndroidSyncCompatibility()
        compatible.copy(contractVersion = "0.13.0").requireAndroidSyncCompatibility()
        compatible.copy(contractVersion = "0.14.0").requireAndroidSyncCompatibility()
        compatible.copy(contractVersion = "0.15.0").requireAndroidSyncCompatibility()
        compatible.copy(contractVersion = "0.16.0").requireAndroidSyncCompatibility()
        compatible.copy(contractVersion = "0.17.0").requireAndroidSyncCompatibility()
        compatible.copy(contractVersion = "0.18.0").requireAndroidSyncCompatibility()
        compatible.copy(contractVersion = "0.19.0").requireAndroidSyncCompatibility()

        assertThrows(VoiceAssetProtocolException::class.java) {
            compatible
                .copy(features = compatible.features - "m4a_uploads")
                .requireAndroidSyncCompatibility()
        }
        assertThrows(VoiceAssetProtocolException::class.java) {
            compatible.copy(contractVersion = "0.12.0").requireAndroidSyncCompatibility()
        }
    }

    private fun client(credential: String? = null): VoiceAssetApiClient =
        VoiceAssetApiClient.forTesting(
            apiRoot = server.url("/api/v1/"),
            credential = credential?.let(::BearerCredential),
        )

    private fun profile(): ServerProfile =
        ServerProfile.create(
            id = ServerProfileId.parse("5f6f7209-87e1-40e8-ad9b-23df239b6230"),
            name = "Test server",
            baseUrl = "https://example.test",
            authenticationMode = AuthenticationMode.LOCAL_SESSION,
            defaultUploadPolicy = UploadPolicy.WIFI_ONLY,
            defaultTranscriptionPolicy = TranscriptionPolicy.AFTER_UPLOAD,
            customCaPem = null,
            certificateFingerprint = null,
            createdAtEpochMillis = 1,
            updatedAtEpochMillis = 1,
        )
}

private fun jsonResponse(
    body: String,
    status: Int = 200,
    headers: Map<String, String> = emptyMap(),
    additionalHeaders: List<Pair<String, String>> = emptyList(),
): MockResponse {
    val response =
        MockResponse()
            .setResponseCode(status)
            .setHeader("Content-Type", "application/json")
            .setBody(body)
    headers.forEach(response::setHeader)
    additionalHeaders.forEach { (name, value) -> response.addHeader(name, value) }
    return response
}

private fun assetJson(
    id: String,
    title: String = "Field note",
    language: String = "en-US",
    collectionId: String? = null,
    version: Long = 1,
    updatedAt: String = "2026-07-16T08:00:00Z",
): String =
    """
    {
      "id":"$id",
      "workspace_id":"20000000-0000-4000-8000-000000000002",
      "collection_id":${collectionId?.let { "\"$it\"" } ?: "null"},
      "title":"$title",
      "language":"$language",
      "status":"draft",
      "duration_ms":null,
      "version":$version,
      "created_at":"2026-07-16T08:00:00Z",
      "updated_at":"$updatedAt"
    }
    """.trimIndent()

private fun administrationJobJson(
    id: String,
    updatedAt: String = "2026-07-16T08:00:00Z",
    state: String = "succeeded",
    attempts: Int = 1,
    maxAttempts: Int = 3,
    retryable: Boolean = false,
    resultRevisionId: String? = "40000000-0000-4000-8000-000000000004",
): String =
    """
    {
      "id":"$id",
      "asset_id":"30000000-0000-4000-8000-000000000009",
      "created_by":"10000000-0000-4000-8000-000000000001",
      "kind":"mock_transcribe",
      "state":"$state",
      "attempts":$attempts,
      "max_attempts":$maxAttempts,
      "retryable":$retryable,
      "available_at":"2026-07-16T08:00:00Z",
      ${resultRevisionId?.let { "\"result_revision_id\":\"$it\"," } ?: ""}
      "created_at":"2026-07-16T08:00:00Z",
      "updated_at":"$updatedAt"
    }
    """.trimIndent()

private fun administrationSystemStatusJson(): String =
    """
    {
      "generated_at":"2026-07-16T08:02:00Z",
      "active_users":2,
      "assets":{"total":4,"active":3,"trashed":1,"purging":0,"failed":0,"audio_duration_ms":65000},
      "storage":{"object_count":3,"bytes":2048},
      "transcripts":{"transcript_count":2,"revision_count":3},
      "jobs":{"total":7,"queued":1,"running":1,"retry_wait":1,"succeeded":2,"failed":1,"cancelled":1},
      "providers":{"enabled_asr":2,"enabled_llm":1}
    }
    """.trimIndent()

private fun providerProfileJson(
    id: String,
    providerId: String,
    state: String = "disabled",
    version: Long = 1,
): String =
    """
    {
      "id":"$id",
      "workspace_id":"20000000-0000-4000-8000-000000000002",
      "provider_id":"$providerId",
      "display_name":"Mock provider",
      "config":{"model":"mock-v1"},
      "state":"$state",
      "priority":100,
      "version":$version,
      "secret_configured":true,
      "created_at":"2026-07-16T08:00:00Z",
      "updated_at":"2026-07-16T08:01:00Z"
    }
    """.trimIndent()

private fun providerHealthJson(
    profileId: String,
    status: String,
    errorClass: String? = null,
): String =
    """
    {
      "profile_id":"$profileId",
      "status":"$status",
      ${errorClass?.let { "\"error_class\":\"$it\"," } ?: ""}
      "checked_at":"2026-07-16T08:03:00Z"
    }
    """.trimIndent()

private fun uploadJson(
    id: String,
    assetId: String,
    parts: String,
): String =
    """
    {
      "id":"$id",
      "asset_id":"$assetId",
      "workspace_id":"20000000-0000-4000-8000-000000000002",
      "filename":"field-note.wav",
      "mime_type":"audio/wav",
      "expected_size":10485767,
      "expected_sha256":"${"c".repeat(64)}",
      "part_size":5242880,
      "state":"active",
      "expires_at":"2026-07-17T08:00:00Z",
      "created_at":"2026-07-16T08:00:00Z",
      "updated_at":"2026-07-16T08:00:00Z",
      "completed_at":null,
      "error_code":null,
      "parts":$parts
    }
    """.trimIndent()

private fun succeededJobJson(
    jobId: String,
    assetId: String,
    revisionId: String,
): String =
    """
    {
      "id":"$jobId",
      "workspace_id":"20000000-0000-4000-8000-000000000002",
      "asset_id":"$assetId",
      "created_by":"10000000-0000-4000-8000-000000000001",
      "kind":"mock_transcribe",
      "state":"succeeded",
      "payload":{"asset_id":"$assetId"},
      "attempts":1,
      "max_attempts":3,
      "available_at":"2026-07-16T08:00:00Z",
      "result_revision_id":"$revisionId",
      "created_at":"2026-07-16T08:00:00Z",
      "updated_at":"2026-07-16T08:00:01Z"
    }
    """.trimIndent()

private fun transcriptListJson(
    transcriptId: String,
    assetId: String,
    revisionId: String,
): String =
    """
    {
      "items":[{
        "id":"$transcriptId",
        "asset_id":"$assetId",
        "language":"en-US",
        "latest_revision_id":"$revisionId",
        "latest_kind":"normalized",
        "latest_text":"Offline field note",
        "created_at":"2026-07-16T08:00:00Z",
        "revision_created_at":"2026-07-16T08:00:01Z"
      }]
    }
    """.trimIndent()

private fun revisionJson(
    revisionId: String,
    transcriptId: String,
    assetId: String,
    jobId: String,
    segmentId: String,
): String =
    """
    {
      "id":"$revisionId",
      "transcript_id":"$transcriptId",
      "asset_id":"$assetId",
      "kind":"normalized",
      "language":"en-US",
      "text":"Offline field note",
      "provider_snapshot":{"provider_id":"mock_asr","raw_schema":"voiceasset.normalized.v1","version":"1"},
      "hotword_snapshot":{},
      "glossary_snapshot":{},
      "diff":{},
      "validation_result":{},
      "provider_raw_object_id":"90000000-0000-4000-8000-000000000009",
      "source_job_id":"$jobId",
      "created_by_type":"system",
      "review_status":"pending",
      "created_at":"2026-07-16T08:00:01Z",
      "segments":[{
        "id":"$segmentId",
        "ordinal":0,
        "start_ms":0,
        "end_ms":1250,
        "speaker":null,
        "text":"Offline field note",
        "confidence":0.98,
        "words":[{
          "start_ms":0,
          "end_ms":1250,
          "text":"Offline field note",
          "confidence":0.98,
          "provider_token":"preserved"
        }]
      }]
    }
    """.trimIndent()
