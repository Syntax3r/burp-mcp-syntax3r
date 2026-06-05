package net.portswigger.mcp.varlayer

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.portswigger.mcp.config.McpVarLayerConfig

@Serializable
data class HeaderOverride(
    val name: String,
    val enabled: Boolean = true,
    val mode: String = "OPAQUE"  // "OPAQUE", "STRUCTURED", "DISABLED"
)

/**
 * Reads/writes per-header overrides from McpVarLayerConfig.headerPolicyJson.
 * Thread-safe: PersistedObject is synchronized internally.
 */
object PolicyOverrides {

    private val json = Json { ignoreUnknownKeys = true }

    fun read(config: McpVarLayerConfig): List<HeaderOverride> {
        return try {
            json.decodeFromString<List<HeaderOverride>>(config.headerPolicyJson)
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun write(config: McpVarLayerConfig, overrides: List<HeaderOverride>) {
        config.headerPolicyJson = json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(HeaderOverride.serializer()),
            overrides
        )
    }

    /** Find an override for a specific header name (case-insensitive). */
    fun findOverride(config: McpVarLayerConfig, headerName: String): HeaderOverride? {
        return read(config).find { it.name.equals(headerName, ignoreCase = true) }
    }

    /** Set or update an override for a header. */
    fun setOverride(config: McpVarLayerConfig, name: String, enabled: Boolean, mode: String) {
        val existing = read(config).toMutableList()
        existing.removeAll { it.name.equals(name, ignoreCase = true) }
        existing.add(HeaderOverride(name, enabled, mode))
        write(config, existing)
    }
}
