package com.voiceasset.core.api

import com.voiceasset.core.model.ServerProfile
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.IOException
import java.security.cert.CertificateException
import java.util.UUID
import javax.net.ssl.SSLException

interface RealtimeConnectionListener {
    fun onOpen(connection: RealtimeConnection) = Unit

    fun onEvent(
        connection: RealtimeConnection,
        event: RealtimeServerEvent,
    ) = Unit

    fun onClosing(
        connection: RealtimeConnection,
        code: Int,
        reason: String,
    ) = Unit

    fun onClosed(
        connection: RealtimeConnection,
        code: Int,
        reason: String,
    ) = Unit

    fun onFailure(
        connection: RealtimeConnection,
        error: IOException,
    ) = Unit
}

class RealtimeConnection internal constructor() {
    private var socket: WebSocket? = null
    private var open = false
    private var terminal = false
    private var failureReported = false

    @Synchronized
    fun send(event: RealtimeClientEvent): Boolean {
        if (!open || terminal) {
            return false
        }
        return checkNotNull(socket).send(RealtimeProtocol.encodeClientEvent(event))
    }

    @Synchronized
    fun close(): Boolean {
        if (terminal) {
            return false
        }
        open = false
        return socket?.close(NORMAL_CLOSE_CODE, CLIENT_CLOSED_REASON) ?: false
    }

    @Synchronized
    fun cancel() {
        if (terminal) {
            return
        }
        open = false
        terminal = true
        socket?.cancel()
    }

    @Synchronized
    fun queueSize(): Long = socket?.queueSize() ?: 0

    @Synchronized
    fun isOpen(): Boolean = open && !terminal

    @Synchronized
    internal fun opened(webSocket: WebSocket): Boolean {
        if (terminal) {
            webSocket.cancel()
            return false
        }
        socket = webSocket
        open = true
        return true
    }

    @Synchronized
    internal fun attach(webSocket: WebSocket) {
        if (socket == null && !terminal) {
            socket = webSocket
        }
    }

    @Synchronized
    internal fun closing() {
        open = false
    }

    @Synchronized
    internal fun closed() {
        open = false
        terminal = true
    }

    @Synchronized
    internal fun reportFailure(): Boolean {
        open = false
        terminal = true
        if (failureReported) {
            return false
        }
        failureReported = true
        return true
    }

    private companion object {
        const val NORMAL_CLOSE_CODE = 1000
        const val CLIENT_CLOSED_REASON = "client_closed"
    }
}

class VoiceAssetRealtimeClient internal constructor(
    private val apiRoot: HttpUrl,
    private val httpClient: OkHttpClient,
    private val credential: BearerCredential,
) {
    fun connect(listener: RealtimeConnectionListener): RealtimeConnection {
        val endpoint =
            checkNotNull(apiRoot.resolve(REALTIME_PATH)) {
                "realtime API path is invalid"
            }
        val request =
            Request
                .Builder()
                .url(endpoint)
                .header("Authorization", "Bearer ${credential.value}")
                .header("X-Request-ID", UUID.randomUUID().toString())
                .build()
        val connection = RealtimeConnection()
        val webSocket = httpClient.newWebSocket(request, ProtocolWebSocketListener(connection, listener))
        connection.attach(webSocket)
        return connection
    }

    companion object {
        fun forProfile(
            profile: ServerProfile,
            credential: BearerCredential,
        ): VoiceAssetRealtimeClient =
            VoiceAssetRealtimeClient(
                apiRoot = "${profile.origin.value}/api/v1/".toHttpUrl(),
                httpClient = ServerTls.buildClient(profile),
                credential = credential,
            )

        internal fun forTesting(
            apiRoot: HttpUrl,
            httpClient: OkHttpClient,
            credential: BearerCredential,
        ): VoiceAssetRealtimeClient = VoiceAssetRealtimeClient(apiRoot, httpClient, credential)
    }
}

private class ProtocolWebSocketListener(
    private val connection: RealtimeConnection,
    private val listener: RealtimeConnectionListener,
) : WebSocketListener() {
    override fun onOpen(
        webSocket: WebSocket,
        response: Response,
    ) {
        if (connection.opened(webSocket)) {
            listener.onOpen(connection)
        }
    }

    override fun onMessage(
        webSocket: WebSocket,
        text: String,
    ) {
        val event =
            try {
                RealtimeProtocol.decodeServerEvent(text)
            } catch (_: VoiceAssetProtocolException) {
                failProtocol(webSocket, "invalid_server_event")
                return
            }
        listener.onEvent(connection, event)
    }

    override fun onMessage(
        webSocket: WebSocket,
        bytes: ByteString,
    ) {
        failProtocol(webSocket, "binary_event_not_supported")
    }

    override fun onClosing(
        webSocket: WebSocket,
        code: Int,
        reason: String,
    ) {
        connection.closing()
        listener.onClosing(connection, code, reason)
        webSocket.close(code, reason)
    }

    override fun onClosed(
        webSocket: WebSocket,
        code: Int,
        reason: String,
    ) {
        connection.closed()
        listener.onClosed(connection, code, reason)
    }

    override fun onFailure(
        webSocket: WebSocket,
        t: Throwable,
        response: Response?,
    ) {
        response?.close()
        if (connection.reportFailure()) {
            listener.onFailure(connection, realtimeConnectionError(t))
        }
    }

    private fun failProtocol(
        webSocket: WebSocket,
        reason: String,
    ) {
        if (connection.reportFailure()) {
            listener.onFailure(
                connection,
                VoiceAssetProtocolException("Server returned an invalid realtime event."),
            )
        }
        webSocket.close(PROTOCOL_ERROR_CLOSE_CODE, reason)
    }
}

private fun realtimeConnectionError(throwable: Throwable): IOException {
    if (throwable.hasRealtimeTlsCause()) {
        return VoiceAssetTlsException("VoiceAsset Server TLS verification failed.", throwable)
    }
    val cause = throwable as? IOException ?: IOException("Realtime WebSocket failed.", throwable)
    return VoiceAssetConnectionException("VoiceAsset realtime connection failed.", cause)
}

private fun Throwable.hasRealtimeTlsCause(): Boolean {
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

private const val REALTIME_PATH = "realtime/transcriptions"
private const val PROTOCOL_ERROR_CLOSE_CODE = 1002
