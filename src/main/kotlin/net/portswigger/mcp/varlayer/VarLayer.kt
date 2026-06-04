package net.portswigger.mcp.varlayer

import burp.api.montoya.logging.Logging
import io.modelcontextprotocol.kotlin.sdk.PromptMessageContent
import kotlinx.serialization.json.JsonObject
import net.portswigger.mcp.config.McpVarLayerConfig
import net.portswigger.mcp.tools.ToolInterceptor

/**
 * Session variable substitution layer.
 *
 *   beforeCall : Claude -> Burp direction. Expand {{VAR}} placeholders in tool arguments.
 *   afterCall  : Burp -> Claude direction. Compress repeated header values into placeholders.
 *
 * Phase 1A (current): hooks are wired up; substitution is a no-op. The infrastructure
 * is proven by compiling cleanly with VarLayerHook.interceptor set. Phase 1B adds the
 * actual rewriting logic.
 */
class VarLayer(
    private val config: McpVarLayerConfig,
    private val logging: Logging
) : ToolInterceptor {

    val sessionStore: SessionStore = SessionStore()
    val auditLog: AuditLog = AuditLog(capacity = 500)

    override fun beforeCall(toolName: String, args: JsonObject): JsonObject {
        if (!config.enabled) return args
        // TODO Phase 1B: parse {{VAR}} placeholders from args, expand from sessionStore.
        return args
    }

    override fun afterCall(
        toolName: String,
        content: List<PromptMessageContent>
    ): List<PromptMessageContent> {
        if (!config.enabled) return content
        // TODO Phase 1B: scan content for repeated header values, promote and substitute.
        return content
    }
}
