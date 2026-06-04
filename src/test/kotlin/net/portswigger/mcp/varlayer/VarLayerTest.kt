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
import kotlin.test.assertTrue

class VarLayerTest {

    private fun makeVarLayer(enabled: Boolean = true, threshold: Int = 1): VarLayer {
        val storage = mockk<PersistedObject>(relaxed = true) {
            every { getBoolean("enabled") } returns enabled
            every { getInteger("promotionThreshold") } returns threshold
        }
        val logging = mockk<Logging>(relaxed = true)
        return VarLayer(McpVarLayerConfig(storage), logging)
    }

    private fun compressOne(layer: VarLayer, raw: String): String {
        val out = layer.afterCall("get_proxy_http_history", listOf(TextContent(raw)))
        return (out.first() as TextContent).text.orEmpty()
    }

    @Test
    fun `Authorization is promoted to JWT placeholder`() {
        val layer = makeVarLayer(threshold = 1)
        val dump = "GET /me HTTP/1.1\r\n" +
                   "Host: api.target.com\r\n" +
                   "Authorization: Bearer eyJabc.xyz.signature\r\n" +
                   "Cookie: session=abc123;csrf=def456\r\n" +
                   "User-Agent: TestUA/1.0\r\n\r\n"
        val out = compressOne(layer, dump)

        assertTrue(out.contains("Authorization: {{JWT}}"), "JWT not substituted:\n$out")
        assertTrue(out.contains("Cookie: {{COOKIES}}"), "COOKIES not substituted")
        assertTrue(out.contains("User-Agent: {{UA}}"), "UA not substituted")
        assertTrue(out.contains("Host: api.target.com"), "Host MUST stay visible (locked)")
    }

    @Test
    fun `expand resolves {{JWT}} placeholder in tool args`() {
        val layer = makeVarLayer(threshold = 1)
        // Seed the variable. The whole post-colon value ("Bearer eyJseed...")
        // is captured under {{JWT}} in Opaque mode — scheme included.
        compressOne(layer, "Authorization: Bearer eyJseed.payload.sig\r\n")

        val args = buildJsonObject {
            put("request",
                "GET /admin HTTP/1.1\r\n" +
                "Host: api.target.com\r\n" +
                "Authorization: {{JWT}}\r\n\r\n")
            put("target_hostname", "api.target.com")
        }
        val expanded = layer.beforeCall("send_http1_request", args)
        val req = (expanded["request"] as JsonPrimitive).content

        assertTrue(req.contains("Authorization: Bearer eyJseed.payload.sig"),
                   "JWT not expanded into outgoing request:\n$req")
        assertTrue(req.contains("Host: api.target.com"))
        assertTrue(req.contains("GET /admin"))
    }

    @Test
    fun `threshold gating works — no promotion until N sightings`() {
        val layer = makeVarLayer(threshold = 3)
        val dump = "Authorization: Bearer eyJsame.value.here\r\n"

        // Pass 1 — count is 1, NO substitution
        var out = compressOne(layer, dump)
        assertTrue(out.contains("eyJsame.value.here"), "Should not substitute yet (count=1)")
        assertTrue(!out.contains("{{JWT}}"))

        // Pass 2 — count is 2, still no substitution
        out = compressOne(layer, dump)
        assertTrue(out.contains("eyJsame.value.here"), "Should not substitute yet (count=2)")
        assertTrue(!out.contains("{{JWT}}"))

        // Pass 3 — promotion fires
        out = compressOne(layer, dump)
        assertTrue(out.contains("{{JWT}}"), "Should promote at count=3:\n$out")
    }

    @Test
    fun `locked headers are never templated`() {
        val layer = makeVarLayer(threshold = 1)
        val text = "Host: api.target.com\r\n" +
                   "Origin: https://app.target.com\r\n" +
                   "Referer: https://app.target.com/x\r\n" +
                   "Content-Length: 42\r\n" +
                   "Transfer-Encoding: chunked\r\n" +
                   "X-Forwarded-For: 1.2.3.4\r\n" +
                   "X-Original-URL: /admin\r\n"
        val out = compressOne(layer, text)

        assertEquals(text, out, "Locked headers must pass through byte-for-byte:\n$out")
    }

    @Test
    fun `disabled config is no-op`() {
        val layer = makeVarLayer(enabled = false, threshold = 1)
        val text = "Authorization: Bearer eyJabc.xyz\r\n"
        val out = compressOne(layer, text)
        assertEquals(text, out)
    }

    @Test
    fun `non-templated tool calls are no-op`() {
        val layer = makeVarLayer(threshold = 1)
        val text = "Authorization: Bearer eyJabc.xyz\r\n"
        // url_encode is NOT in COMPRESS_TOOLS — should pass through
        val out = layer.afterCall("url_encode", listOf(TextContent(text)))
        assertEquals(text, (out.first() as TextContent).text)
    }

    @Test
    fun `unknown placeholder is left intact`() {
        val layer = makeVarLayer(threshold = 1)
        val args = buildJsonObject { put("request", "Authorization: Bearer {{NOSUCH}}\r\n") }
        val out = layer.beforeCall("send_http1_request", args)
        val req = (out["request"] as JsonPrimitive).content
        assertTrue(req.contains("{{NOSUCH}}"), "Unknown var should be left as-is for the user to notice")
    }

    @Test
    fun `JSON-encoded proxy history gets headers promoted`() {
        val layer = makeVarLayer(threshold = 1)
        // Simulate what get_proxy_http_history actually returns: JSON with \\r\\n separators
        val jsonDump = """{"request":"GET /api/me HTTP/1.1\r\nHost: api.target.com\r\nAuthorization: Bearer eyJtest.payload.sig\r\nCookie: session=abc123\r\nUser-Agent: TestUA/1.0\r\n\r\n","response":"HTTP/1.1 200 OK\r\n\r\n","statusCode":200}"""
        val out = compressOne(layer, jsonDump)

        assertTrue(out.contains("{{JWT}}"), "JWT not promoted in JSON content:\n$out")
        assertTrue(out.contains("{{COOKIES}}"), "COOKIES not promoted:\n$out")
        assertTrue(out.contains("{{UA}}"), "UA not promoted:\n$out")
        assertTrue(out.contains("Host: api.target.com"), "Host must stay raw (locked):\n$out")
        // JSON structure must be preserved
        assertTrue(out.startsWith("{\"request\":"), "JSON structure broken:\n$out")
        assertTrue(out.contains("\\r\\n"), "JSON-escaped newlines must be restored:\n$out")
    }

}
