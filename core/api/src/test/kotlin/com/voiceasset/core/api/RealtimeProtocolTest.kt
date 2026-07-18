package com.voiceasset.core.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimeProtocolTest {
    @Test
    fun `client events encode the exact versioned protocol`() {
        val start =
            RealtimeStartEvent(
                protocolVersion = REALTIME_PROTOCOL_VERSION,
                clientSessionId = CLIENT_SESSION_ID,
                idempotencyKey = "recording-$CLIENT_SESSION_ID",
                encoding = REALTIME_PCM_ENCODING,
                sampleRateHz = 16_000,
                channels = 1,
                frameDurationMillis = 20,
                language = "zh-CN",
            )

        val encoded = RealtimeProtocol.encodeClientEvent(start)
        val payload = Json.parseToJsonElement(encoded).jsonObject

        assertEquals("start", payload.getValue("type").jsonPrimitive.content)
        assertEquals("1", payload.getValue("protocol_version").jsonPrimitive.content)
        assertEquals(CLIENT_SESSION_ID, payload.getValue("client_session_id").jsonPrimitive.content)
        assertEquals("pcm_s16le", payload.getValue("encoding").jsonPrimitive.content)
        assertFalse(payload.containsKey("provider_profile_id"))
        assertFalse(payload.containsKey("hotword_set_id"))

        val pcm = ByteArray(640) { index -> (index % 127).toByte() }
        val audio = RealtimeAudioEvent.fromPcm(SESSION_ID, 7, 140, pcm)
        assertArrayEquals(pcm, audio.pcmBytes())
        val audioPayload = Json.parseToJsonElement(RealtimeProtocol.encodeClientEvent(audio)).jsonObject
        assertEquals("audio", audioPayload.getValue("type").jsonPrimitive.content)
        assertEquals("7", audioPayload.getValue("sequence").jsonPrimitive.content)
    }

    @Test
    fun `server events decode into strict typed models`() {
        val events =
            listOf(
                (
                    """{"type":"ready","protocol_version":"1","session_id":"$SESSION_ID","next_sequence":0,""" +
                        """"max_frame_bytes":640,"heartbeat_interval_ms":15000,"expires_at":"2026-07-17T18:00:00Z"}"""
                ) to
                    RealtimeReadyEvent::class,
                """{"type":"ack","session_id":"$SESSION_ID","acknowledged_sequence":0,"received_bytes":640}""" to
                    RealtimeAckEvent::class,
                """{"type":"heartbeat_ack","session_id":"$SESSION_ID","server_at":"2026-07-17T16:00:00Z"}""" to
                    RealtimeHeartbeatAckEvent::class,
                """{"type":"partial_transcript","session_id":"$SESSION_ID","revision":1,"text":"欢迎使用","final_through_ms":500}""" to
                    RealtimePartialTranscriptEvent::class,
                (
                    """{"type":"final_transcript","session_id":"$SESSION_ID","text":"欢迎使用语音资产。",""" +
                        """"language":"zh-CN","provider_id":"mock_asr"}"""
                ) to
                    RealtimeFinalTranscriptEvent::class,
                """{"type":"error","code":"invalid_event","message":"Invalid realtime event.","retriable":true}""" to
                    RealtimeErrorEvent::class,
                """{"type":"closed","session_id":"$SESSION_ID","reason":"completed"}""" to
                    RealtimeClosedEvent::class,
            )

        events.forEach { (payload, expectedType) ->
            assertEquals(expectedType, RealtimeProtocol.decodeServerEvent(payload)::class)
        }
        val preSessionError =
            RealtimeProtocol.decodeServerEvent(
                """{"type":"error","code":"invalid_event","message":"Invalid realtime event.","retriable":true}""",
            ) as RealtimeErrorEvent
        assertNull(preSessionError.sessionId)
    }

    @Test
    fun `server decoder rejects ambiguous unknown and oversized input`() {
        val valid =
            """{"type":"ready","protocol_version":"1","session_id":"$SESSION_ID","next_sequence":0,"max_frame_bytes":640,"heartbeat_interval_ms":15000,"expires_at":"2026-07-17T18:00:00Z"}"""
        val unsafe =
            listOf(
                valid.replace("\"next_sequence\":0", "\"next_sequence\":0,\"secret\":\"no\""),
                valid.replace("\"type\":\"ready\"", "\"type\":\"ready\",\"type\":\"closed\""),
                valid.replace("\"type\":\"ready\"", "\"type\":\"ready\",\"\\u0074ype\":\"closed\""),
                valid.replace("\"protocol_version\":\"1\"", "\"protocol_version\":\"2\""),
                valid.replace("\"max_frame_bytes\":640", "\"max_frame_bytes\":65537"),
                valid + "{}",
                " ".repeat(MAX_REALTIME_SERVER_MESSAGE_BYTES + 1),
            )

        unsafe.forEach { payload ->
            val error =
                assertThrows(VoiceAssetProtocolException::class.java) {
                    RealtimeProtocol.decodeServerEvent(payload)
                }
            assertEquals("Server returned an invalid realtime event.", error.message)
            assertFalse(error.toString().contains("secret"))
        }
    }

    @Test
    fun `client constructors reject unsafe frames and identifiers`() {
        assertThrows(IllegalArgumentException::class.java) {
            RealtimeAudioEvent.fromPcm(SESSION_ID, 0, 0, ByteArray(3))
        }
        assertThrows(IllegalArgumentException::class.java) {
            RealtimeResumeEvent(
                REALTIME_PROTOCOL_VERSION,
                "10000000-0000-4000-8000-00000000000A",
                -1,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            RealtimeStartEvent(
                protocolVersion = REALTIME_PROTOCOL_VERSION,
                clientSessionId = CLIENT_SESSION_ID,
                idempotencyKey = "unsafe\nkey",
                encoding = REALTIME_PCM_ENCODING,
                sampleRateHz = 16_000,
                channels = 1,
                frameDurationMillis = 20,
                language = "en-US",
            )
        }
    }

    @Test
    fun `server message limit accommodates the transcript contract`() {
        val transcript = "x".repeat(MAX_REALTIME_TRANSCRIPT_BYTES)
        val decoded =
            RealtimeProtocol.decodeServerEvent(
                """{"type":"final_transcript","session_id":"$SESSION_ID","text":"$transcript","language":"en-US","provider_id":"mock_asr"}""",
            )
        assertTrue(decoded is RealtimeFinalTranscriptEvent)
        assertEquals(MAX_REALTIME_TRANSCRIPT_BYTES, (decoded as RealtimeFinalTranscriptEvent).text.length)
    }

    private companion object {
        const val SESSION_ID = "10000000-0000-4000-8000-000000000001"
        const val CLIENT_SESSION_ID = "10000000-0000-4000-8000-000000000002"
    }
}
