package net.portswigger.mcp.varlayer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Base64

/**
 * Decodes a JWT (without verifying the signature) and produces a
 * structured summary string suitable for the {{JWT|...}} placeholder.
 *
 * No external dependencies — uses java.util.Base64 + kotlinx.serialization.json.
 * We intentionally don't verify signatures: the goal is to expose claim
 * structure to the model for attack reasoning, not to validate tokens.
 */
object JwtSummarizer {

    private val timeFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
        .withZone(ZoneId.of("UTC"))

    /**
     * @param bearerValue the full Authorization header value, e.g. "Bearer eyJhbGci..."
     * @return structured summary like "alg=RS256 kid=abc sub=user_8f3a role=user exp=2024-11-10T17:00:00Z"
     *         or null if the value is not a parseable JWT.
     */
    fun summarize(bearerValue: String): String? {
        val token = bearerValue
            .removePrefix("Bearer ").removePrefix("bearer ")
            .removePrefix("BEARER ").trim()
        val parts = token.split(".")
        if (parts.size < 2) return null  // not a JWT (at least header.payload required)

        return try {
            val header = decodeSegment(parts[0])
            val payload = decodeSegment(parts[1])

            buildString {
                // Header fields
                header.str("alg")?.let { append("alg=$it") }
                header.str("typ")?.let { append(" typ=$it") }
                header.str("kid")?.let { append(" kid=${it.ellipsis(24)}") }
                header.str("jku")?.let { append(" jku=${it.ellipsis(40)}") }
                header.str("x5u")?.let { append(" x5u=${it.ellipsis(40)}") }

                // Payload claims (attack-relevant ones)
                payload.str("sub")?.let { append(" sub=${it.ellipsis(24)}") }
                payload.str("role")?.let { append(" role=$it") }
                payload.str("roles")?.let { append(" roles=$it") }
                payload.str("scope")?.let { append(" scope=${it.ellipsis(50)}") }
                payload.str("aud")?.let { append(" aud=${it.ellipsis(40)}") }
                payload.str("iss")?.let { append(" iss=${it.ellipsis(40)}") }

                // Expiry — with human-readable time + minutes remaining
                payload.num("exp")?.let { exp ->
                    val expInstant = Instant.ofEpochSecond(exp)
                    val remaining = java.time.Duration.between(Instant.now(), expInstant)
                    val mins = remaining.toMinutes()
                    append(" exp=${timeFmt.format(expInstant)}")
                    if (mins > 0) append("(${mins}min)")
                    else append("(EXPIRED)")
                }

                payload.num("iat")?.let { iat ->
                    append(" iat=${timeFmt.format(Instant.ofEpochSecond(iat))}")
                }
            }.trim()
        } catch (_: Exception) {
            null  // unparseable — fall back to opaque mode for this value
        }
    }

    // ---- helpers ----

    private fun decodeSegment(encoded: String): JsonObject {
        // JWT uses base64url (no padding). Add padding for the Java decoder.
        val padded = encoded + "=".repeat((4 - encoded.length % 4) % 4)
        val bytes = Base64.getUrlDecoder().decode(padded)
        return Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
    }

    private fun JsonObject.str(key: String): String? =
        this[key]?.jsonPrimitive?.takeIf { it.isString }?.content

    private fun JsonObject.num(key: String): Long? =
        this[key]?.jsonPrimitive?.longOrNull

    private fun String.ellipsis(max: Int): String =
        if (length <= max) this else take(max - 1) + "…"
}
