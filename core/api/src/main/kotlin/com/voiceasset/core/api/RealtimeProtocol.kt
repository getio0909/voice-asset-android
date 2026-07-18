package com.voiceasset.core.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.Base64
import java.util.UUID

const val REALTIME_PROTOCOL_VERSION = "1"
const val REALTIME_PCM_ENCODING = "pcm_s16le"
const val MAX_REALTIME_FRAME_BYTES = 64 * 1024
const val MAX_REALTIME_CLIENT_MESSAGE_BYTES = 96 * 1024
const val MAX_REALTIME_SERVER_MESSAGE_BYTES = 320 * 1024
const val MAX_REALTIME_TRANSCRIPT_BYTES = 256 * 1024

@Serializable
sealed interface RealtimeClientEvent

@Serializable
@SerialName("start")
data class RealtimeStartEvent(
    @SerialName("protocol_version")
    val protocolVersion: String,
    @SerialName("client_session_id")
    val clientSessionId: String,
    @SerialName("idempotency_key")
    val idempotencyKey: String,
    val encoding: String,
    @SerialName("sample_rate_hz")
    val sampleRateHz: Int,
    val channels: Int,
    @SerialName("frame_duration_ms")
    val frameDurationMillis: Int,
    val language: String,
    @SerialName("provider_profile_id")
    val providerProfileId: String? = null,
    @SerialName("hotword_set_id")
    val hotwordSetId: String? = null,
) : RealtimeClientEvent {
    init {
        require(protocolVersion == REALTIME_PROTOCOL_VERSION) { "unsupported realtime protocol version" }
        requireCanonicalUuid(clientSessionId, "client session id")
        requireValidIdempotencyKey(idempotencyKey)
        require(encoding == REALTIME_PCM_ENCODING) { "unsupported realtime audio encoding" }
        require(sampleRateHz in SUPPORTED_REALTIME_SAMPLE_RATES) { "unsupported realtime sample rate" }
        require(channels == 1) { "realtime audio must be mono" }
        require(frameDurationMillis in 20..100) { "realtime frame duration is outside the contract limit" }
        require(LANGUAGE_TAG.matches(language)) { "realtime language is invalid" }
        providerProfileId?.let { requireCanonicalUuid(it, "provider profile id") }
        hotwordSetId?.let { requireCanonicalUuid(it, "hotword set id") }
        require(sampleRateHz * channels * 2 * frameDurationMillis / 1000 <= MAX_REALTIME_FRAME_BYTES) {
            "realtime frame size exceeds the contract limit"
        }
    }
}

@Serializable
@SerialName("resume")
data class RealtimeResumeEvent(
    @SerialName("protocol_version")
    val protocolVersion: String,
    @SerialName("session_id")
    val sessionId: String,
    @SerialName("last_acknowledged_sequence")
    val lastAcknowledgedSequence: Long,
) : RealtimeClientEvent {
    init {
        require(protocolVersion == REALTIME_PROTOCOL_VERSION) { "unsupported realtime protocol version" }
        requireCanonicalUuid(sessionId, "session id")
        require(lastAcknowledgedSequence >= -1) { "acknowledged sequence is invalid" }
    }
}

@Serializable
@SerialName("audio")
data class RealtimeAudioEvent(
    @SerialName("session_id")
    val sessionId: String,
    val sequence: Long,
    @SerialName("captured_at_ms")
    val capturedAtMillis: Long,
    @SerialName("pcm_base64")
    val pcmBase64: String,
) : RealtimeClientEvent {
    init {
        requireCanonicalUuid(sessionId, "session id")
        require(sequence >= 0) { "audio sequence is invalid" }
        require(capturedAtMillis >= 0) { "audio capture time is invalid" }
        decodeRealtimePcm(pcmBase64)
    }

    fun pcmBytes(): ByteArray = decodeRealtimePcm(pcmBase64)

    companion object {
        fun fromPcm(
            sessionId: String,
            sequence: Long,
            capturedAtMillis: Long,
            pcm: ByteArray,
        ): RealtimeAudioEvent {
            require(pcm.isNotEmpty() && pcm.size <= MAX_REALTIME_FRAME_BYTES && pcm.size % 2 == 0) {
                "PCM frame size is invalid"
            }
            return RealtimeAudioEvent(
                sessionId = sessionId,
                sequence = sequence,
                capturedAtMillis = capturedAtMillis,
                pcmBase64 = Base64.getEncoder().encodeToString(pcm),
            )
        }
    }
}

@Serializable
@SerialName("finish")
data class RealtimeFinishEvent(
    @SerialName("session_id")
    val sessionId: String,
    @SerialName("final_sequence")
    val finalSequence: Long,
    @SerialName("captured_duration_ms")
    val capturedDurationMillis: Long,
    @SerialName("client_archive_sha256")
    val clientArchiveSha256: String,
) : RealtimeClientEvent {
    init {
        requireCanonicalUuid(sessionId, "session id")
        require(finalSequence >= -1) { "final sequence is invalid" }
        require(capturedDurationMillis >= 0) { "captured duration is invalid" }
        require(SHA256_HEX.matches(clientArchiveSha256)) { "archive checksum must be lowercase SHA-256" }
    }
}

@Serializable
@SerialName("heartbeat")
data class RealtimeHeartbeatEvent(
    @SerialName("session_id")
    val sessionId: String,
    @SerialName("sent_at_ms")
    val sentAtMillis: Long,
) : RealtimeClientEvent {
    init {
        requireCanonicalUuid(sessionId, "session id")
        require(sentAtMillis >= 0) { "heartbeat time is invalid" }
    }
}

@Serializable
sealed interface RealtimeServerEvent

@Serializable
@SerialName("ready")
data class RealtimeReadyEvent(
    @SerialName("protocol_version")
    val protocolVersion: String,
    @SerialName("session_id")
    val sessionId: String,
    @SerialName("next_sequence")
    val nextSequence: Long,
    @SerialName("max_frame_bytes")
    val maxFrameBytes: Int,
    @SerialName("heartbeat_interval_ms")
    val heartbeatIntervalMillis: Long,
    @SerialName("expires_at")
    val expiresAt: String,
) : RealtimeServerEvent {
    init {
        require(protocolVersion == REALTIME_PROTOCOL_VERSION) { "unsupported realtime protocol version" }
        requireCanonicalUuid(sessionId, "session id")
        require(nextSequence >= 0) { "next sequence is invalid" }
        require(maxFrameBytes in 1..MAX_REALTIME_FRAME_BYTES) { "server frame limit is invalid" }
        require(heartbeatIntervalMillis >= 1000) { "server heartbeat interval is invalid" }
        requireInstant(expiresAt, "session expiry")
    }
}

@Serializable
@SerialName("ack")
data class RealtimeAckEvent(
    @SerialName("session_id")
    val sessionId: String,
    @SerialName("acknowledged_sequence")
    val acknowledgedSequence: Long,
    @SerialName("received_bytes")
    val receivedBytes: Long,
) : RealtimeServerEvent {
    init {
        requireCanonicalUuid(sessionId, "session id")
        require(acknowledgedSequence >= -1) { "acknowledged sequence is invalid" }
        require(receivedBytes >= 0) { "received byte count is invalid" }
    }
}

@Serializable
@SerialName("heartbeat_ack")
data class RealtimeHeartbeatAckEvent(
    @SerialName("session_id")
    val sessionId: String,
    @SerialName("server_at")
    val serverAt: String,
) : RealtimeServerEvent {
    init {
        requireCanonicalUuid(sessionId, "session id")
        requireInstant(serverAt, "heartbeat time")
    }
}

@Serializable
@SerialName("partial_transcript")
data class RealtimePartialTranscriptEvent(
    @SerialName("session_id")
    val sessionId: String,
    val revision: Long,
    val text: String,
    @SerialName("final_through_ms")
    val finalThroughMillis: Long,
) : RealtimeServerEvent {
    init {
        requireCanonicalUuid(sessionId, "session id")
        require(revision >= 1) { "partial revision is invalid" }
        require(text.utf8Size() <= MAX_REALTIME_TRANSCRIPT_BYTES) { "partial transcript is too large" }
        require(finalThroughMillis >= 0) { "partial transcript time is invalid" }
    }
}

@Serializable
@SerialName("final_transcript")
data class RealtimeFinalTranscriptEvent(
    @SerialName("session_id")
    val sessionId: String,
    val text: String,
    val language: String,
    @SerialName("provider_id")
    val providerId: String,
) : RealtimeServerEvent {
    init {
        requireCanonicalUuid(sessionId, "session id")
        require(text.isNotEmpty() && text.utf8Size() <= MAX_REALTIME_TRANSCRIPT_BYTES) {
            "final transcript is invalid"
        }
        require(LANGUAGE_TAG.matches(language)) { "transcript language is invalid" }
        require(PROVIDER_ID.matches(providerId)) { "provider id is invalid" }
    }
}

@Serializable
@SerialName("error")
data class RealtimeErrorEvent(
    @SerialName("session_id")
    val sessionId: String? = null,
    val code: String,
    val message: String,
    val retriable: Boolean,
    @SerialName("expected_sequence")
    val expectedSequence: Long? = null,
) : RealtimeServerEvent {
    init {
        sessionId?.let { requireCanonicalUuid(it, "session id") }
        require(ERROR_CODE.matches(code)) { "realtime error code is invalid" }
        require(message.utf8Size() in 1..256) { "realtime error message is invalid" }
        require(expectedSequence == null || expectedSequence >= 0) { "expected sequence is invalid" }
    }
}

@Serializable
@SerialName("closed")
data class RealtimeClosedEvent(
    @SerialName("session_id")
    val sessionId: String,
    val reason: String,
) : RealtimeServerEvent {
    init {
        requireCanonicalUuid(sessionId, "session id")
        require(reason in CLOSED_REASONS) { "realtime close reason is invalid" }
    }
}

object RealtimeProtocol {
    private val format =
        Json {
            ignoreUnknownKeys = false
            isLenient = false
            coerceInputValues = false
            encodeDefaults = false
            explicitNulls = false
            classDiscriminator = "type"
        }

    fun encodeClientEvent(event: RealtimeClientEvent): String {
        val encoded = format.encodeToString<RealtimeClientEvent>(event)
        require(encoded.utf8Size() <= MAX_REALTIME_CLIENT_MESSAGE_BYTES) {
            "realtime event exceeds the client message limit"
        }
        return encoded
    }

    fun decodeServerEvent(value: String): RealtimeServerEvent {
        if (value.isEmpty() || value.utf8Size() > MAX_REALTIME_SERVER_MESSAGE_BYTES) {
            throw VoiceAssetProtocolException("Server returned an invalid realtime event.")
        }
        try {
            StrictJsonScanner(value).validate()
            return format.decodeFromString<RealtimeServerEvent>(value)
        } catch (exception: SerializationException) {
            throw VoiceAssetProtocolException("Server returned an invalid realtime event.", exception)
        } catch (exception: IllegalArgumentException) {
            throw VoiceAssetProtocolException("Server returned an invalid realtime event.", exception)
        }
    }
}

private fun decodeRealtimePcm(value: String): ByteArray {
    require(value.length in 4..MAX_BASE64_FRAME_LENGTH) { "PCM frame encoding is invalid" }
    val decoded =
        try {
            Base64.getDecoder().decode(value)
        } catch (exception: IllegalArgumentException) {
            throw IllegalArgumentException("PCM frame encoding is invalid", exception)
        }
    require(decoded.isNotEmpty() && decoded.size <= MAX_REALTIME_FRAME_BYTES && decoded.size % 2 == 0) {
        "PCM frame size is invalid"
    }
    return decoded
}

private fun requireCanonicalUuid(
    value: String,
    label: String,
) {
    val parsed = runCatching { UUID.fromString(value) }.getOrNull()
    require(parsed != null && parsed.toString() == value) { "$label must be a canonical lowercase UUID" }
}

private fun requireValidIdempotencyKey(value: String) {
    require(value.length in 1..200 && value.trim() == value && value.none(Char::isISOControl)) {
        "realtime idempotency key is invalid"
    }
}

private fun requireInstant(
    value: String,
    label: String,
) {
    try {
        Instant.parse(value)
    } catch (exception: DateTimeParseException) {
        throw IllegalArgumentException("$label is invalid", exception)
    }
}

private fun String.utf8Size(): Int = toByteArray(Charsets.UTF_8).size

private class StrictJsonScanner(
    private val source: String,
) {
    private var index = 0

    fun validate() {
        skipWhitespace()
        readValue()
        skipWhitespace()
        require(index == source.length) { "trailing JSON value" }
    }

    private fun readValue() {
        require(index < source.length) { "missing JSON value" }
        when (source[index]) {
            '{' -> readObject()
            '[' -> readArray()
            '"' -> readString()
            't' -> readLiteral("true")
            'f' -> readLiteral("false")
            'n' -> readLiteral("null")
            '-', in '0'..'9' -> readNumber()
            else -> throw IllegalArgumentException("invalid JSON value")
        }
    }

    private fun readObject() {
        index++
        skipWhitespace()
        if (consume('}')) {
            return
        }
        val keys = mutableSetOf<String>()
        while (true) {
            require(index < source.length && source[index] == '"') { "invalid JSON object key" }
            val key = readString()
            require(keys.add(key)) { "duplicate JSON object key" }
            skipWhitespace()
            require(consume(':')) { "missing JSON object separator" }
            skipWhitespace()
            readValue()
            skipWhitespace()
            if (consume('}')) {
                return
            }
            require(consume(',')) { "invalid JSON object" }
            skipWhitespace()
        }
    }

    private fun readArray() {
        index++
        skipWhitespace()
        if (consume(']')) {
            return
        }
        while (true) {
            readValue()
            skipWhitespace()
            if (consume(']')) {
                return
            }
            require(consume(',')) { "invalid JSON array" }
            skipWhitespace()
        }
    }

    private fun readString(): String {
        require(consume('"')) { "invalid JSON string" }
        val result = StringBuilder()
        while (index < source.length) {
            val character = source[index++]
            when {
                character == '"' -> return result.toString()
                character == '\\' -> result.append(readEscape())
                character.code < 0x20 -> throw IllegalArgumentException("invalid JSON string character")
                else -> result.append(character)
            }
        }
        throw IllegalArgumentException("unterminated JSON string")
    }

    private fun readEscape(): Char {
        require(index < source.length) { "unterminated JSON escape" }
        return when (val escaped = source[index++]) {
            '"', '\\', '/' -> escaped
            'b' -> '\b'
            'f' -> '\u000c'
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            'u' -> {
                require(index + 4 <= source.length) { "invalid JSON unicode escape" }
                val digits = source.substring(index, index + 4)
                require(digits.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
                    "invalid JSON unicode escape"
                }
                index += 4
                digits.toInt(16).toChar()
            }
            else -> throw IllegalArgumentException("invalid JSON escape")
        }
    }

    private fun readLiteral(literal: String) {
        require(source.startsWith(literal, index)) { "invalid JSON literal" }
        index += literal.length
    }

    private fun readNumber() {
        consume('-')
        require(index < source.length) { "invalid JSON number" }
        if (consume('0')) {
            require(index >= source.length || source[index] !in '0'..'9') { "invalid JSON number" }
        } else {
            require(source[index] in '1'..'9') { "invalid JSON number" }
            while (index < source.length && source[index] in '0'..'9') {
                index++
            }
        }
        if (consume('.')) {
            require(index < source.length && source[index] in '0'..'9') { "invalid JSON number" }
            while (index < source.length && source[index] in '0'..'9') {
                index++
            }
        }
        if (index < source.length && source[index].lowercaseChar() == 'e') {
            index++
            consume('+') || consume('-')
            require(index < source.length && source[index] in '0'..'9') { "invalid JSON number" }
            while (index < source.length && source[index] in '0'..'9') {
                index++
            }
        }
    }

    private fun skipWhitespace() {
        while (index < source.length && source[index] in JSON_WHITESPACE) {
            index++
        }
    }

    private fun consume(character: Char): Boolean {
        if (index < source.length && source[index] == character) {
            index++
            return true
        }
        return false
    }
}

private const val MAX_BASE64_FRAME_LENGTH = 87_384
private val SUPPORTED_REALTIME_SAMPLE_RATES = setOf(8000, 16000, 24000, 48000)
private val LANGUAGE_TAG = Regex("^[A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,8})*$")
private val SHA256_HEX = Regex("^[0-9a-f]{64}$")
private val PROVIDER_ID = Regex("^[a-z0-9][a-z0-9_-]{0,63}$")
private val ERROR_CODE = Regex("^[a-z][a-z0-9_]{0,63}$")
private val CLOSED_REASONS = setOf("completed", "failed", "expired", "client_closed")
private val JSON_WHITESPACE = setOf(' ', '\t', '\r', '\n')
