package net.portswigger.mcp.varlayer

import burp.api.montoya.logging.Logging
import io.modelcontextprotocol.kotlin.sdk.PromptMessageContent
import io.modelcontextprotocol.kotlin.sdk.TextContent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.portswigger.mcp.config.McpVarLayerConfig
import net.portswigger.mcp.tools.ToolInterceptor
import java.util.concurrent.ConcurrentHashMap

/**
 * {{VAR_NAME}} or {{VAR_NAME|annotation...}} pattern.
 * Group 1 captures the variable name only (e.g. "JWT" from "{{JWT|alg=RS256 sub=...}}").
 * The annotation after | is informational (for Claude) and stripped during expansion.
 */
private val VAR_PLACEHOLDER = Regex("""\{\{([A-Z][A-Z0-9_]*)(?:\|[^}]*)?\}\}""")

/** Tools whose arguments may contain {{VAR}} placeholders to EXPAND. */
private val EXPAND_TOOLS = setOf(
    "send_http1_request",
    "send_http2_request",
    "create_repeater_tab",
    "create_repeater_tab_http2",
    "send_to_intruder",
)

/** Tools whose output contains HTTP traffic to COMPRESS. */
private val COMPRESS_TOOLS = setOf(
    // Proxy / WebSocket history (always HTTP content)
    "get_proxy_http_history",
    "get_proxy_http_history_regex",
    "get_proxy_websocket_history",
    "get_proxy_websocket_history_regex",
    // Active editor — what Claude reads when analysing Repeater/Proxy/Intruder tabs
    "get_active_editor_contents",
    // Send tools also return responses containing HTTP headers
    "send_http1_request",
    "send_http2_request",
    // Organiser items often contain stored requests
    "get_organizer_items",
    "get_organizer_items_regex",
)

/**
 * Session variable substitution layer — Phase 1B (Opaque mode).
 *
 *   afterCall (Burp -> Claude):
 *     Scan tool output text for header values matching HeaderPolicy.DEFAULTS.
 *     Count occurrences. Once a value crosses the promotion threshold, register
 *     it as a session variable and replace it with {{VAR}}. Future occurrences
 *     of the same value substitute immediately.
 *
 *   beforeCall (Claude -> Burp):
 *     Recursively walk JSON arguments. In string values, replace {{VAR}}
 *     placeholders with their captured raw values.
 *
 *   Locked headers (Host, Origin, Content-Length, X-Forwarded-*, etc.) are
 *   NEVER templated — they carry attack-surface information.
 *
 *   Host scoping: GLOBAL in Phase 1B. Phase 1D introduces (host, auth-hash)
 *   keying so multi-host engagements don't collide on variable names.
 */
class VarLayer(
    private val config: McpVarLayerConfig,
    private val logging: Logging
) : ToolInterceptor {

    val sessionStore: SessionStore = SessionStore()
    val auditLog: AuditLog = AuditLog(capacity = 500)
    private val promotionTracker = PromotionTracker(threshold = config.promotionThreshold)

    /** varName -> captured VarValue. */
    private val variables = ConcurrentHashMap<String, VarValue>()
    /** Raw value -> varName. Lets us substitute on re-encounters without re-counting. */
    private val valueToVar = ConcurrentHashMap<String, String>()

    // ============================================================
    // beforeCall — expand {{VAR}} in tool arguments
    // ============================================================
    override fun beforeCall(toolName: String, args: JsonObject): JsonObject {
        if (!config.enabled) return args
        if (toolName !in EXPAND_TOOLS) return args
        return expandJson(args) as JsonObject
    }

    private fun expandJson(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.mapValues { (_, v) -> expandJson(v) })
        is JsonArray -> JsonArray(element.map { expandJson(it) })
        is JsonPrimitive ->
            if (element.isString) JsonPrimitive(expandString(element.content)) else element
        is JsonNull -> element
    }

    private fun expandString(s: String): String {
        if (!s.contains("{{")) return s
        return VAR_PLACEHOLDER.replace(s) { m ->
            val varName = m.groupValues[1]
            val captured = variables[varName]
            if (captured != null) {
                logging.logToOutput(
                    "MCP VarLayer: expanded {{$varName}} (${captured.rawValue.length}B)"
                )
                captured.rawValue
            } else {
                logging.logToOutput("MCP VarLayer: WARN unknown variable {{$varName}} — leaving as-is")
                m.value
            }
        }
    }

    // ============================================================
    // afterCall — compress repeated header values to {{VAR}}
    // ============================================================
    override fun afterCall(
        toolName: String,
        content: List<PromptMessageContent>
    ): List<PromptMessageContent> {
        if (!config.enabled) return content
        val eligible = toolName in COMPRESS_TOOLS
        logging.logToOutput("MCP VarLayer: afterCall(\"$toolName\")  eligible=$eligible")
        if (!eligible) return content
        return content.map { item ->
            if (item is TextContent) TextContent(compressText(item.text ?: "")) else item
        }
    }

    private fun compressText(text: String): String {
        // Proxy history output is JSON-encoded (Json.encodeToString): headers are
        // separated by literal \r\n (4-char sequence), not actual CR+LF bytes.
        // Normalize to real newlines for header scanning, then restore format.
        val jsonEscaped = "\\r\\n" in text
        val normalized = if (jsonEscaped) text.replace("\\r\\n", "\r\n") else text

        val rewritten = HeaderUtils.rewriteHeaders(normalized) { headerName, value ->
            // Never touch attack-critical headers
            if (HeaderPolicy.isLocked(headerName)) return@rewriteHeaders null

            val rule = findRule(headerName) ?: return@rewriteHeaders null
            if (rule.mode == HeaderMode.DISABLED) return@rewriteHeaders null

            // Already known? Re-check policy before substituting — user may have
            // disabled this header since the variable was promoted.
            valueToVar[value]?.let { varName ->
                if (findRule(headerName) == null) return@rewriteHeaders null  // disabled by user
                val v = variables[varName]
                val tag = if (v?.structuredSummary != null) "$varName|${v.structuredSummary}" else varName
                return@rewriteHeaders "{{$tag}}"
            }

            // Otherwise track and possibly promote.
            val obs = promotionTracker.observe(headerName, value)
            if (obs.isFirstPromotion) {
                val varName = rule.variableName
                // Generate structured summary if mode is STRUCTURED (defaultMode == 1)
                // Generate structured summary based on mode
                val summary = if (config.defaultMode == 1) {
                    when (varName) {
                        "JWT" -> JwtSummarizer.summarize(value)
                        "COOKIES" -> CookieSummarizer.summarize(value)
                        // For other headers (UA, LANG, ENC): show a brief description
                        // so the Structured Summary column is always populated
                        else -> if (value.length <= 60) value else "${value.take(57)}..."
                    }
                } else null

                variables[varName] = VarValue(
                    name = varName,
                    rawValue = value,
                    structuredSummary = summary,
                    seenCount = obs.count
                )
                valueToVar[value] = varName

                val displayTag = if (summary != null) "$varName|$summary" else varName
                auditLog.record(
                    AuditEvent.VAR_PROMOTED,
                    varName,
                    "$headerName (${value.length}B) after ${obs.count} sightings"
                )
                logging.logToOutput(
                    "MCP VarLayer: promoted {{$displayTag}} <- $headerName (${value.length}B, seen ${obs.count}x)"
                )
                return@rewriteHeaders "{{$displayTag}}"
            }

            null  // not yet at threshold, leave value visible
        }

        return if (jsonEscaped) rewritten.replace("\r\n", "\\r\\n") else rewritten
    }

    private fun findRule(headerName: String): HeaderRule? {
        // Check user overrides first (persisted in config)
        val override = PolicyOverrides.findOverride(config, headerName)
        if (override != null) {
            if (!override.enabled) return null  // user disabled this header
            val mode = try { HeaderMode.valueOf(override.mode) } catch (_: Exception) { HeaderMode.OPAQUE }
            // Find the default rule to get the variable name
            val defaultRule = HeaderPolicy.DEFAULTS.find { rule ->
                if (rule.isWildcard) headerName.startsWith(rule.name, ignoreCase = true)
                else headerName.equals(rule.name, ignoreCase = true)
            }
            return defaultRule?.copy(mode = mode) ?: HeaderRule(headerName, mode, headerName.uppercase())
        }
        // Fall back to static defaults
        return HeaderPolicy.DEFAULTS.find { rule ->
            if (rule.isWildcard) headerName.startsWith(rule.name, ignoreCase = true)
            else headerName.equals(rule.name, ignoreCase = true)
        }
    }

    // ============================================================
    // Read-only accessors for UI panels
    // ============================================================

    /** Snapshot of all currently-captured variables. Safe to call from EDT. */
    fun capturedVariables(): List<VarValue> = variables.values.toList()

    /** Wipe captured state. Policy config and audit log are preserved. */
    fun clearCapturedVariables() {
        variables.clear()
        valueToVar.clear()
        promotionTracker.clear()
        auditLog.record(AuditEvent.SESSION_RESET, null, "manual clear from UI")
    }

}
