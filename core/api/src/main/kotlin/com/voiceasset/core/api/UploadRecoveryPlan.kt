package com.voiceasset.core.api

@ConsistentCopyVisibility
data class UploadRecoveryPlan private constructor(
    val totalParts: Int,
    val missingPartNumbers: List<Int>,
) {
    companion object {
        fun from(session: UploadSession): UploadRecoveryPlan = build(session, localPartSha256 = null)

        fun from(
            session: UploadSession,
            localPartSha256: (offset: Long, size: Int) -> String,
        ): UploadRecoveryPlan = build(session, localPartSha256)

        private fun build(
            session: UploadSession,
            localPartSha256: ((offset: Long, size: Int) -> String)?,
        ): UploadRecoveryPlan {
            require(session.expectedSize > 0) { "upload expected size must be positive" }
            require(session.partSize > 0) { "upload part size must be positive" }
            val totalPartsLong = ((session.expectedSize - 1) / session.partSize) + 1
            require(totalPartsLong in 1..10_000) { "upload part count is outside the contract limit" }
            val totalParts = totalPartsLong.toInt()

            val recorded = mutableSetOf<Int>()
            session.parts.orEmpty().forEach { part ->
                require(part.number in 1..totalParts) { "server returned an out-of-range upload part" }
                require(recorded.add(part.number)) { "server returned a duplicate upload part" }
                val offset = (part.number - 1L) * session.partSize
                val expectedSize =
                    if (part.number == totalParts) {
                        session.expectedSize - offset
                    } else {
                        session.partSize.toLong()
                    }
                require(part.sizeBytes == expectedSize) { "server returned an invalid upload part size" }
                require(SHA256.matches(part.sha256)) { "server returned an invalid upload part checksum" }
                localPartSha256?.let { checksum ->
                    val localChecksum = checksum(offset, expectedSize.toInt())
                    require(SHA256.matches(localChecksum)) { "local upload part checksum is invalid" }
                    require(part.sha256 == localChecksum) { "server upload part does not match local media" }
                }
            }

            return UploadRecoveryPlan(
                totalParts = totalParts,
                missingPartNumbers = (1..totalParts).filterNot(recorded::contains),
            )
        }
    }
}

internal val SHA256 = Regex("^[0-9a-f]{64}$")
