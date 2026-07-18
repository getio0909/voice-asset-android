package com.voiceasset.core.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.MessageDigest

class UploadRecoveryPlanTest {
    @Test
    fun `verified recovery skips only server parts that match local bytes`() {
        val bytes = byteArrayOf(1, 2, 3, 4, 5, 6, 7)
        val observedRanges = mutableListOf<Pair<Long, Int>>()
        val session = session(bytes, recordedPartNumbers = listOf(1))

        val plan =
            UploadRecoveryPlan.from(session) { offset, size ->
                observedRanges += offset to size
                bytes.copyOfRange(offset.toInt(), offset.toInt() + size).sha256()
            }

        assertEquals(listOf(0L to 4), observedRanges)
        assertEquals(2, plan.totalParts)
        assertEquals(listOf(2), plan.missingPartNumbers)
    }

    @Test
    fun `verified recovery rejects a server part that differs from local bytes`() {
        val localBytes = byteArrayOf(1, 2, 3, 4, 5, 6, 7)
        val differentBytes = byteArrayOf(9, 9, 9, 9, 5, 6, 7)
        val session = session(differentBytes, recordedPartNumbers = listOf(1))

        assertThrows(IllegalArgumentException::class.java) {
            UploadRecoveryPlan.from(session) { offset, size ->
                localBytes.copyOfRange(offset.toInt(), offset.toInt() + size).sha256()
            }
        }
    }

    @Test
    fun `recovery rejects duplicate and malformed server checkpoints`() {
        val bytes = byteArrayOf(1, 2, 3, 4, 5, 6, 7)
        val duplicate = session(bytes, recordedPartNumbers = listOf(1, 1))
        val wrongSize =
            session(bytes, recordedPartNumbers = listOf(1)).copy(
                parts = listOf(requireNotNull(session(bytes, listOf(1)).parts).single().copy(sizeBytes = 3)),
            )

        assertThrows(IllegalArgumentException::class.java) { UploadRecoveryPlan.from(duplicate) }
        assertThrows(IllegalArgumentException::class.java) { UploadRecoveryPlan.from(wrongSize) }
    }

    private fun session(
        bytes: ByteArray,
        recordedPartNumbers: List<Int>,
    ): UploadSession =
        UploadSession(
            id = "40000000-0000-4000-8000-000000000004",
            assetId = "30000000-0000-4000-8000-000000000003",
            workspaceId = "20000000-0000-4000-8000-000000000002",
            filename = "field-note.m4a",
            mimeType = "audio/mp4",
            expectedSize = bytes.size.toLong(),
            expectedSha256 = bytes.sha256(),
            partSize = 4,
            state = "active",
            expiresAt = NOW,
            createdAt = NOW,
            updatedAt = NOW,
            completedAt = null,
            errorCode = null,
            parts =
                recordedPartNumbers.map { number ->
                    val offset = (number - 1) * 4
                    val end = minOf(offset + 4, bytes.size)
                    val partBytes = bytes.copyOfRange(offset, end)
                    UploadPart(
                        number = number,
                        sizeBytes = partBytes.size.toLong(),
                        sha256 = partBytes.sha256(),
                        createdAt = NOW,
                    )
                },
        )

    private fun ByteArray.sha256(): String =
        MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }

    private companion object {
        const val NOW = "2026-07-16T08:00:00Z"
    }
}
