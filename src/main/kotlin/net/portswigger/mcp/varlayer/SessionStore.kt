package net.portswigger.mcp.varlayer

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** Identifies a logical session — typically host + auth-context hash. */
data class SessionKey(val host: String, val authHash: String)

/** A captured session variable: name, raw value, structured summary, expiry. */
data class VarValue(
    val name: String,
    val rawValue: String,
    val structuredSummary: String? = null,
    val host: String = "unknown",
    val expiresAt: Instant? = null,
    val capturedAt: Instant = Instant.now(),
    val seenCount: Int = 1
)

/** Per-session variable bag. */
class SessionData(val key: SessionKey) {
    private val variables = ConcurrentHashMap<String, VarValue>()

    fun put(value: VarValue) { variables[value.name] = value }
    fun get(name: String): VarValue? = variables[name]
    fun all(): List<VarValue> = variables.values.toList()
    fun names(): Set<String> = variables.keys.toSet()
    fun remove(name: String) { variables.remove(name) }
    fun clear() { variables.clear() }
    fun size(): Int = variables.size
}

/**
 * Thread-safe, in-memory session store.
 * NEVER persisted to disk: stored credentials are stale after restart and would
 * leak via shared Burp project files.
 */
class SessionStore {
    private val sessions = ConcurrentHashMap<SessionKey, SessionData>()

    fun getOrCreate(key: SessionKey): SessionData =
        sessions.getOrPut(key) { SessionData(key) }

    fun get(key: SessionKey): SessionData? = sessions[key]
    fun remove(key: SessionKey) { sessions.remove(key) }
    fun all(): List<SessionData> = sessions.values.toList()
    fun clear() { sessions.clear() }
    fun sessionCount(): Int = sessions.size
}
