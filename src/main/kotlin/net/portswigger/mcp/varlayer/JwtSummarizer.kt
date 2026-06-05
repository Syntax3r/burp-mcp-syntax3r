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
    /**
     * Summarizes any Authorization header value — not just JWTs.
     * - JWT (Bearer eyJ...): full claims summary (alg, sub, role, exp, etc.)
     * - API key (sk-*, key-*, etc.): type + prefix + length
     * - Basic auth: decoded username
     * - Other Bearer: type + length
     *
     * Always returns a non-null summary so the Structured Summary column
     * is never empty when structured mode is selected.
     */
    fun summarize(bearerValue: String): String? {
        val trimmed = bearerValue.trim()
        if (trimmed.isEmpty()) return null

        // Handle Basic auth
        if (trimmed.startsWith("Basic ", ignoreCase = true)) {
            return summarizeBasic(trimmed.substringAfter(" ").trim())
        }

        val token = trimmed
            .removePrefix("Bearer ").removePrefix("bearer ")
            .removePrefix("BEARER ").trim()

        // Try JWT first (has 2+ dot-separated base64 segments)
        val parts = token.split(".")
        if (parts.size >= 2) {
            val jwtSummary = tryParseJwt(parts)
            if (jwtSummary != null) return jwtSummary
        }

        // Not a JWT — classify the auth type
        return summarizeNonJwt(token, trimmed)
    }

    private fun summarizeBasic(encoded: String): String {
        return try {
            val decoded = java.util.Base64.getDecoder().decode(encoded).toString(Charsets.UTF_8)
            val user = decoded.substringBefore(":")
            "type=basic user=${user.ellipsis(30)}"
        } catch (_: Exception) {
            "type=basic (${encoded.length}B)"
        }
    }

    private fun summarizeNonJwt(token: String, original: String): String {
        return when {
            token.startsWith("sk-") -> "type=api-key prefix=${token.take(18)}… (${token.length}B)"
            token.startsWith("key-") || token.startsWith("pk_") || token.startsWith("rk_") ->
                "type=api-key prefix=${token.take(18)}… (${token.length}B)"
            token.startsWith("ghp_") || token.startsWith("gho_") || token.startsWith("ghs_") ->
                "type=github-token prefix=${token.take(10)}… (${token.length}B)"
            token.startsWith("xox") -> "type=slack-token prefix=${token.take(12)}… (${token.length}B)"
            token.length > 40 -> "type=bearer non-JWT (${token.length}B) prefix=${token.take(16)}…"
            else -> "type=bearer (${token.length}B)"
        }
    }

    private fun tryParseJwt(parts: List<String>): String? {
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
            null  // not a valid JWT despite having dots
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
