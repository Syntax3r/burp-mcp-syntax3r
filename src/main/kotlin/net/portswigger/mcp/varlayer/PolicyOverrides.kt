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

    /** Find an override for a specific header name (case-insensitive + wildcard). */
    fun findOverride(config: McpVarLayerConfig, headerName: String): HeaderOverride? {
        val overrides = read(config)
        // Exact match first
        val exact = overrides.find { it.name.equals(headerName, ignoreCase = true) }
        if (exact != null) return exact
        // Wildcard: if override "Sec-Ch-Ua" should match "Sec-Ch-Ua-Mobile", but only
        // when the corresponding default rule is marked isWildcard=true.
        for (ovr in overrides) {
            if (headerName.startsWith(ovr.name, ignoreCase = true)) {
                val isWildcard = HeaderPolicy.DEFAULTS.any {
                    it.isWildcard && it.name.equals(ovr.name, ignoreCase = true)
                }
                if (isWildcard) return ovr
            }
        }
        return null
    }

    /** Set or update an override for a header. */
    fun setOverride(config: McpVarLayerConfig, name: String, enabled: Boolean, mode: String) {
        val existing = read(config).toMutableList()
        existing.removeAll { it.name.equals(name, ignoreCase = true) }
        existing.add(HeaderOverride(name, enabled, mode))
        write(config, existing)
    }
}
