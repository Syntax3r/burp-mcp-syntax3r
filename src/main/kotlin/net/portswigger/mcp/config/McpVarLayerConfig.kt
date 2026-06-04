package net.portswigger.mcp.config

import burp.api.montoya.persistence.PersistedObject

/**
 * Persistent configuration for the session variable substitution layer.
 *
 * Persistence policy (Hybrid):
 *   - Config (this file) persists across Burp restarts.
 *   - Captured session VALUES (JWT/cookies/UA) do NOT persist — they live only in the
 *     in-memory SessionStore and die on restart. Persisting credentials to disk would
 *     leak them via shared project files, and JWTs would be stale anyway.
 */
class McpVarLayerConfig(storage: PersistedObject) {

    /** Master toggle. Off by default — opt-in feature. */
    var enabled by storage.boolean(false)

    /** Apply substitution to specific tool surfaces. */
    var applyToHistory by storage.boolean(true)
    var applyToRepeater by storage.boolean(true)
    var applyToIntruder by storage.boolean(false)
    var applyToScanner by storage.boolean(false)

    /** Default mode: 0 = Opaque, 1 = Structured, 2 = Disabled. */
    var defaultMode by storage.int(1)

    /** Promote a header value to a variable after seeing it this many times. */
    var promotionThreshold by storage.int(3)

    /** Require user confirmation in Burp UI before revealing a variable's raw value. */
    var requireRevealApproval by storage.boolean(true)
}
