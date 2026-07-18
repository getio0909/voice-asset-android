package com.voiceasset.core.api

import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class VoiceAssetRealtimeClientTest {
    private lateinit var server: MockWebServer
    private lateinit var httpClient: OkHttpClient

    @Before
    fun startServer() {
        server = MockWebServer()
        server.start()
        httpClient = OkHttpClient.Builder().build()
    }

    @After
    fun stopServer() {
        httpClient.dispatcher.executorService.shutdownNow()
        httpClient.connectionPool.evictAll()
        server.shutdown()
    }

    @Test
    fun `authenticated websocket exchanges typed realtime events`() {
        val clientMessage = AtomicReference<String>()
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onMessage(
                        webSocket: WebSocket,
                        text: String,
                    ) {
                        clientMessage.set(text)
                        webSocket.send(readyJson())
                        webSocket.send(
                            """{"type":"ack","session_id":"$SESSION_ID","acknowledged_sequence":-1,"received_bytes":0}""",
                        )
                        webSocket.close(1000, "completed")
                    }
                },
            ),
        )
        val opened = AtomicBoolean()
        val closed = CountDownLatch(1)
        val failure = AtomicReference<IOException>()
        val events = CopyOnWriteArrayList<RealtimeServerEvent>()

        val connection =
            realtimeClient().connect(
                object : RealtimeConnectionListener {
                    override fun onOpen(connection: RealtimeConnection) {
                        opened.set(connection.send(startEvent()))
                    }

                    override fun onEvent(
                        connection: RealtimeConnection,
                        event: RealtimeServerEvent,
                    ) {
                        events.add(event)
                    }

                    override fun onClosed(
                        connection: RealtimeConnection,
                        code: Int,
                        reason: String,
                    ) {
                        closed.countDown()
                    }

                    override fun onFailure(
                        connection: RealtimeConnection,
                        error: IOException,
                    ) {
                        failure.set(error)
                        closed.countDown()
                    }
                },
            )

        assertTrue(closed.await(5, TimeUnit.SECONDS))
        assertTrue(opened.get())
        assertFalse(connection.isOpen())
        assertNull(failure.get())
        assertEquals(listOf(RealtimeReadyEvent::class, RealtimeAckEvent::class), events.map { it::class })
        assertTrue(clientMessage.get().contains("\"type\":\"start\""))

        val request = server.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(request)
        assertEquals("/api/v1/realtime/transcriptions", request?.path)
        assertEquals("Bearer va_realtime_token_with_sufficient_entropy", request?.getHeader("Authorization"))
        assertNotNull(UUID.fromString(request?.getHeader("X-Request-ID")))
    }

    @Test
    fun `invalid server event is closed with a sanitized protocol failure`() {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(
                        webSocket: WebSocket,
                        response: Response,
                    ) {
                        webSocket.send(
                            """{"type":"ready","type":"error","secret":"must-not-escape"}""",
                        )
                    }

                    override fun onClosing(
                        webSocket: WebSocket,
                        code: Int,
                        reason: String,
                    ) {
                        webSocket.close(code, reason)
                    }
                },
            ),
        )
        val failed = CountDownLatch(1)
        val failure = AtomicReference<IOException>()

        realtimeClient().connect(
            object : RealtimeConnectionListener {
                override fun onFailure(
                    connection: RealtimeConnection,
                    error: IOException,
                ) {
                    failure.set(error)
                    failed.countDown()
                }
            },
        )

        assertTrue(failed.await(5, TimeUnit.SECONDS))
        assertTrue(failure.get() is VoiceAssetProtocolException)
        assertEquals("Server returned an invalid realtime event.", failure.get().message)
        assertFalse(failure.get().toString().contains("must-not-escape"))
    }

    private fun realtimeClient(): VoiceAssetRealtimeClient =
        VoiceAssetRealtimeClient.forTesting(
            apiRoot = server.url("/api/v1/"),
            httpClient = httpClient,
            credential = BearerCredential("va_realtime_token_with_sufficient_entropy"),
        )

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
    }
}
