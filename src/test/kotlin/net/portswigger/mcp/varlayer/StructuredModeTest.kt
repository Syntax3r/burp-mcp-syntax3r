package net.portswigger.mcp.varlayer

import burp.api.montoya.logging.Logging
import burp.api.montoya.persistence.PersistedObject
import io.mockk.every
import io.mockk.mockk
import io.modelcontextprotocol.kotlin.sdk.TextContent
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.portswigger.mcp.config.McpVarLayerConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StructuredModeTest {

    // ---- JWT Summarizer unit tests ----

    @Test
    fun `JWT summarizer extracts alg, sub, role from a real-ish JWT`() {
        // Header: {"alg":"RS256","typ":"JWT","kid":"test-key-01"}
        // Payload: {"sub":"user_8f3a2b1c","role":"user","scope":"read:profile write:profile","exp":9999999999,"iss":"https://auth.example.com"}
        val jwt = "Bearer eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCIsImtpZCI6InRlc3Qta2V5LTAxIn0." +
                  "eyJzdWIiOiJ1c2VyXzhmM2EyYjFjIiwicm9sZSI6InVzZXIiLCJzY29wZSI6InJlYWQ6cHJvZmlsZSB3cml0ZTpwcm9maWxlIiwiZXhwIjo5OTk5OTk5OTk5LCJpc3MiOiJodHRwczovL2F1dGguZXhhbXBsZS5jb20ifQ." +
                  "fakesignature"
        val summary = JwtSummarizer.summarize(jwt)
        assertNotNull(summary, "Should parse successfully")
        assertTrue(summary.contains("alg=RS256"), "Should contain alg: $summary")
        assertTrue(summary.contains("kid=test-key-01"), "Should contain kid: $summary")
        assertTrue(summary.contains("sub=user_8f3a2b1c"), "Should contain sub: $summary")
        assertTrue(summary.contains("role=user"), "Should contain role: $summary")
        assertTrue(summary.contains("scope=read:profile write:profile"), "Should contain scope: $summary")
        assertTrue(summary.contains("iss=https://auth.example.com"), "Should contain iss: $summary")
    }

    @Test
    fun `JWT summarizer handles non-JWT auth values gracefully`() {
        // API key — should identify as api-key with prefix
        val apiKey = JwtSummarizer.summarize("Bearer sk-ant-oat01-abc123")
        assertNotNull(apiKey, "API key should produce a summary")
        assertTrue(apiKey!!.contains("type=api-key"), "Should identify as api-key: $apiKey")
        assertTrue(apiKey.contains("sk-ant"), "Should show prefix: $apiKey")

        // Basic auth — should decode and show username
        val basic = JwtSummarizer.summarize("Basic dXNlcjpwYXNz")
        assertNotNull(basic, "Basic auth should produce a summary")
        assertTrue(basic!!.contains("type=basic"), "Should identify as basic: $basic")
        assertTrue(basic.contains("user=user"), "Should decode username: $basic")

        // Generic bearer — should show type and length
        val generic = JwtSummarizer.summarize("Bearer not-a-jwt")
        assertNotNull(generic, "Generic bearer should produce a summary")
        assertTrue(generic!!.contains("type=bearer"), "Should identify as bearer: $generic")

        // Empty — still null (no auth to summarize)
        assertNull(JwtSummarizer.summarize(""))
    }

    @Test
    fun `JWT summarizer handles missing Bearer prefix`() {
        val token = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ0ZXN0In0.sig"
        val summary = JwtSummarizer.summarize(token)
        assertNotNull(summary)
        assertTrue(summary.contains("alg=HS256"))
        assertTrue(summary.contains("sub=test"))
    }

    // ---- Cookie Summarizer unit tests ----

    @Test
    fun `Cookie summarizer classifies auth, tracking, prefs correctly`() {
        val cookies = "session_id=abc123def456; csrf_token=XYZ789; _ga=GA1.2.123; " +
                      "_gid=GA1.2.456; theme=dark; lang=en; consent_v2=given"
        val summary = CookieSummarizer.summarize(cookies)

        assertTrue(summary.contains("auth:"), "Should have auth section: $summary")
        assertTrue(summary.contains("session_id"), "session_id is auth: $summary")
        assertTrue(summary.contains("csrf_token"), "csrf_token is auth: $summary")
        assertTrue(summary.contains("tracking:"), "Should have tracking section: $summary")
        assertTrue(summary.contains("_ga"), "_ga is tracking: $summary")
        assertTrue(summary.contains("_gid"), "_gid is tracking: $summary")
        assertTrue(summary.contains("prefs:"), "Should have prefs section: $summary")
        assertTrue(summary.contains("theme=dark"), "theme value shown inline: $summary")
        assertTrue(summary.contains("lang=en"), "lang value shown inline: $summary")
    }

    @Test
    fun `Cookie summarizer shows byte size for long auth cookie values`() {
        val cookies = "session_id=a]".replace("]", "b".repeat(64))
        val summary = CookieSummarizer.summarize(cookies)
        assertTrue(summary.contains("B)"), "Long auth values should show byte size: $summary")
    }

    // ---- Integration: structured mode in VarLayer ----

    private fun makeVarLayer(mode: Int = 1, threshold: Int = 1): VarLayer {
        val storage = mockk<PersistedObject>(relaxed = true) {
            every { getBoolean("enabled") } returns true
            every { getInteger("promotionThreshold") } returns threshold
            every { getInteger("defaultMode") } returns mode
        }
        val logging = mockk<Logging>(relaxed = true)
        return VarLayer(McpVarLayerConfig(storage), logging)
    }

    @Test
    fun `structured mode produces JWT annotation in proxy history output`() {
        val layer = makeVarLayer(mode = 1, threshold = 1)
        val jwt = "Bearer eyJhbGciOiJSUzI1NiIsImtpZCI6InRlc3QifQ." +
                  "eyJzdWIiOiJ1c2VyXzEyMyIsInJvbGUiOiJhZG1pbiJ9.sig"
        val dump = """{"request":"GET / HTTP/1.1\r\nAuthorization: $jwt\r\n\r\n"}"""
        val out = (layer.afterCall("get_proxy_http_history", listOf(TextContent(dump)))
            .first() as TextContent).text.orEmpty()

        assertTrue(out.contains("{{JWT|"), "Structured JWT annotation expected:\n$out")
        assertTrue(out.contains("alg=RS256"), "alg visible:\n$out")
        assertTrue(out.contains("sub=user_123"), "sub visible:\n$out")
        assertTrue(out.contains("role=admin"), "role visible:\n$out")
        assertTrue(!out.contains("eyJhbGci"), "Raw token should be replaced:\n$out")
    }

    @Test
    fun `opaque mode produces plain JWT placeholder`() {
        val layer = makeVarLayer(mode = 0, threshold = 1)  // 0 = OPAQUE
        val jwt = "Bearer eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJ0ZXN0In0.sig"
        val dump = """{"request":"GET / HTTP/1.1\r\nAuthorization: $jwt\r\n\r\n"}"""
        val out = (layer.afterCall("get_proxy_http_history", listOf(TextContent(dump)))
            .first() as TextContent).text.orEmpty()

        assertTrue(out.contains("{{JWT}}"), "Opaque mode — no annotation:\n$out")
        assertTrue(!out.contains("{{JWT|"), "Should NOT have structured annotation:\n$out")
    }

    @Test
    fun `structured Cookie annotation classifies correctly`() {
        val layer = makeVarLayer(mode = 1, threshold = 1)
        val cookies = "session_id=abc123456789; _ga=GA1.2.111; theme=dark"
        val dump = """{"request":"GET / HTTP/1.1\r\nCookie: $cookies\r\n\r\n"}"""
        val out = (layer.afterCall("get_proxy_http_history", listOf(TextContent(dump)))
            .first() as TextContent).text.orEmpty()

        assertTrue(out.contains("{{COOKIES|"), "Structured cookie annotation expected:\n$out")
        assertTrue(out.contains("session_id"), "auth cookie visible:\n$out")
        assertTrue(out.contains("_ga"), "tracking cookie visible:\n$out")
        assertTrue(out.contains("theme=dark"), "pref visible:\n$out")
    }

    @Test
    fun `expand handles {{JWT|annotation}} — strips annotation, resolves value`() {
        val layer = makeVarLayer(mode = 1, threshold = 1)
        // Seed the variable
        val jwt = "Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ0ZXN0In0.sig"
        val dump = """{"request":"GET / HTTP/1.1\r\nAuthorization: $jwt\r\n\r\n"}"""
        layer.afterCall("get_proxy_http_history", listOf(TextContent(dump)))

        // Claude writes a request with {{JWT|alg=HS256 sub=test}} — annotation must be stripped
        val args = buildJsonObject {
            put("request", "GET /admin HTTP/1.1\r\nAuthorization: {{JWT|alg=HS256 sub=test}}\r\n\r\n")
        }
        val expanded = layer.beforeCall("send_http1_request", args)
        val req = (expanded["request"] as JsonPrimitive).content

        assertTrue(req.contains(jwt), "JWT should be expanded to full value:\n$req")
        assertTrue(!req.contains("{{JWT"), "Placeholder should be resolved:\n$req")
    }
}
