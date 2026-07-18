package com.voiceasset.core.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class ReliableRealtimeSessionTest {
    private lateinit var server: MockWebServer
    private lateinit var httpClient: OkHttpClient
    private var activeSession: ReliableRealtimeSession? = null

    @Before
    fun startServer() {
        server = MockWebServer()
        server.start()
        httpClient = OkHttpClient.Builder().build()
    }

    @After
    fun stopServer() {
        activeSession?.takeUnless { it.state() in TERMINAL_STATES }?.cancel()
        httpClient.dispatcher.executorService.shutdownNow()
        httpClient.connectionPool.evictAll()
        server.shutdown()
    }

    @Test
    fun `buffered audio completes with acknowledgements and final transcript`() {
        val clientEvents = CopyOnWriteArrayList<String>()
        server.enqueue(MockResponse().withWebSocketUpgrade(completingServer(clientEvents)))
        val completed = CountDownLatch(1)
        val finalTranscript = AtomicReference<RealtimeFinalTranscriptEvent>()
        val session = reliableSession(completed, finalTranscript)
        activeSession = session
        session.appendPcm(0, ByteArray(640) { 1 })
        session.appendPcm(20, ByteArray(640) { 2 })
        session.finish(40, "a".repeat(64))

        session.connect()

        assertTrue(completed.await(5, TimeUnit.SECONDS))
        assertEquals(ReliableRealtimeState.COMPLETED, session.state())
        assertEquals("Welcome to VoiceAsset.", finalTranscript.get().text)
        assertEquals(listOf("start", "audio", "audio", "finish"), clientEvents)
        val snapshot = session.snapshot()
        assertEquals(2L, snapshot.nextSequence)
        assertEquals(1L, snapshot.lastAcknowledgedSequence)
        assertTrue(snapshot.closed)
    }

    @Test
    fun `idempotent start error adopts session and reconnects with resume`() {
        val clientEvents = CopyOnWriteArrayList<String>()
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onMessage(
                        webSocket: WebSocket,
                        text: String,
                    ) {
                        clientEvents.add(eventType(text))
                        webSocket.send(
                            """{"type":"error","session_id":"$SESSION_ID","code":"session_exists",""" +
                                """"message":"Session already exists; reconnect with resume.","retriable":true}""",
                        )
                    }
                },
            ),
        )
        server.enqueue(MockResponse().withWebSocketUpgrade(completingServer(clientEvents)))
        val completed = CountDownLatch(1)
        val session = reliableSession(completed, AtomicReference())
        activeSession = session
        session.appendPcm(0, ByteArray(640))
        session.finish(20, "b".repeat(64))

        session.connect()

        assertTrue(completed.await(5, TimeUnit.SECONDS))
        assertEquals(ReliableRealtimeState.COMPLETED, session.state())
        assertEquals(listOf("start", "resume", "audio", "finish"), clientEvents)
        assertEquals(SESSION_ID, session.snapshot().sessionId)
    }

    private fun reliableSession(
        completed: CountDownLatch,
        finalTranscript: AtomicReference<RealtimeFinalTranscriptEvent>,
    ): ReliableRealtimeSession =
        ReliableRealtimeSession(
            client =
                VoiceAssetRealtimeClient.forTesting(
                    apiRoot = server.url("/api/v1/"),
                    httpClient = httpClient,
                    credential = BearerCredential("va_reliable_realtime_token_with_entropy"),
                ),
            startEvent = startEvent(),
            listener =
                object : ReliableRealtimeListener {
                    override fun onFinalTranscript(event: RealtimeFinalTranscriptEvent) {
                        finalTranscript.set(event)
                    }

                    override fun onStateChanged(state: ReliableRealtimeState) {
                        if (state == ReliableRealtimeState.COMPLETED) {
                            completed.countDown()
                        }
                    }
                },
            maxPendingBytes = 4096,
            reconnectPolicy =
                RealtimeReconnectPolicy(
                    initialDelayMillis = 1,
                    maximumDelayMillis = 5,
                    reconnectWindowMillis = 1000,
                ),
        )

    private fun completingServer(clientEvents: MutableList<String>): WebSocketListener =
        object : WebSocketListener() {
            private var receivedBytes = 0L

            override fun onMessage(
                webSocket: WebSocket,
                text: String,
            ) {
                val payload = Json.parseToJsonElement(text).jsonObject
                val type = payload.getValue("type").jsonPrimitive.content
                clientEvents.add(type)
                when (type) {
                    "start", "resume" -> webSocket.send(readyJson())
                    "audio" -> {
                        val sequence =
                            payload
                                .getValue("sequence")
                                .jsonPrimitive
                                .content
                                .toLong()
                        receivedBytes += 640
                        webSocket.send(
                            """{"type":"ack","session_id":"$SESSION_ID","acknowledged_sequence":$sequence,""" +
                                """"received_bytes":$receivedBytes}""",
                        )
                    }
                    "finish" -> {
                        webSocket.send(
                            """{"type":"final_transcript","session_id":"$SESSION_ID",""" +
                                """"text":"Welcome to VoiceAsset.","language":"en-US","provider_id":"mock_asr"}""",
                        )
                        webSocket.send(
                            """{"type":"closed","session_id":"$SESSION_ID","reason":"completed"}""",
                        )
                        webSocket.close(1000, "completed")
                    }
                }
            }
        }

    private fun eventType(payload: String): String = eventObject(payload).getValue("type").jsonPrimitive.content

    private fun eventObject(payload: String) = Json.parseToJsonElement(payload).jsonObject

    private fun startEvent(): RealtimeStartEvent =
        RealtimeStartEvent(
            protocolVersion = REALTIME_PROTOCOL_VERSION,
            clientSessionId = CLIENT_SESSION_ID,
            idempotencyKey = "recording-$CLIENT_SESSION_ID",
            encoding = REALTIME_PCM_ENCODING,
            sampleRateHz = 16_000,
            channels = 1,
            frameDurationMillis = 20,
            language = "en-US",
        )

    private fun readyJson(): String =
        """{"type":"ready","protocol_version":"1","session_id":"$SESSION_ID","next_sequence":0,""" +
            """"max_frame_bytes":640,"heartbeat_interval_ms":15000,"expires_at":"2026-07-17T18:00:00Z"}"""

    private companion object {
        const val SESSION_ID = "10000000-0000-4000-8000-000000000001"
        const val CLIENT_SESSION_ID = "10000000-0000-4000-8000-000000000002"
        val TERMINAL_STATES =
            setOf(
                ReliableRealtimeState.COMPLETED,
                ReliableRealtimeState.FAILED,
                ReliableRealtimeState.CANCELLED,
            )
    }
}
