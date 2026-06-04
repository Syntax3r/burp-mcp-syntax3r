package net.portswigger.mcp.varlayer

enum class HeaderMode { OPAQUE, STRUCTURED, DISABLED }

data class HeaderRule(
    val name: String,
    val mode: HeaderMode,
    val variableName: String,
    val isWildcard: Boolean = false
)

/**
 * Static templating policy. The LOCKED set is non-overridable: these headers
 * carry attack-surface information (host injection, request smuggling,
 * access-control bypass) and MUST stay visible to the model in raw form.
 */
object HeaderPolicy {

    /** Never template these, even if the user tries. */
    val LOCKED: Set<String> = setOf(
        "Host", "Origin", "Referer",
        "Content-Length", "Transfer-Encoding",
        "X-Forwarded-For", "X-Forwarded-Host", "X-Forwarded-Proto",
        "X-Real-IP", "X-Original-URL", "X-Rewrite-URL"
    )

    /** Default templating rules. User can adjust mode but not unlock LOCKED. */
    val DEFAULTS: List<HeaderRule> = listOf(
        HeaderRule("Authorization",   HeaderMode.STRUCTURED, "JWT"),
        HeaderRule("Cookie",          HeaderMode.STRUCTURED, "COOKIES"),
        HeaderRule("User-Agent",      HeaderMode.OPAQUE,     "UA"),
        HeaderRule("Sec-Ch-Ua",       HeaderMode.OPAQUE,     "UA_CH", isWildcard = true),
        HeaderRule("Accept-Encoding", HeaderMode.OPAQUE,     "ENC"),
        HeaderRule("Accept-Language", HeaderMode.OPAQUE,     "LANG"),
    )

    fun isLocked(headerName: String): Boolean =
        LOCKED.any { it.equals(headerName, ignoreCase = true) }
}
