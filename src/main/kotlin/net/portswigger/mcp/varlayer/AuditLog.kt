package net.portswigger.mcp.varlayer

import java.time.Instant
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

enum class AuditEvent {
    VAR_PROMOTED, VAR_UPDATED, VAR_REVEALED,
    BYPASS_ON_EDIT, JWT_EXPIRING, JWT_EXPIRED, SESSION_RESET
}

data class AuditEntry(
    val timestamp: Instant,
    val event: AuditEvent,
    val varName: String?,
    val details: String
)

/**
 * Bounded circular log, in-memory only — never persisted.
 * Capacity 500 × ~250 B per entry ≈ 125 KB ceiling. Negligible.
 */
class AuditLog(private val capacity: Int = 500) {
    private val buffer: ArrayDeque<AuditEntry> = ArrayDeque(capacity)
    private val lock = ReentrantReadWriteLock()

    fun record(event: AuditEvent, varName: String? = null, details: String = "") {
        lock.write {
            if (buffer.size >= capacity) buffer.removeFirst()
            buffer.addLast(AuditEntry(Instant.now(), event, varName, details))
        }
    }

    fun recent(n: Int = 50): List<AuditEntry> = lock.read {
        buffer.takeLast(n.coerceAtMost(buffer.size))
    }

    fun all(): List<AuditEntry> = lock.read { buffer.toList() }
    fun size(): Int = lock.read { buffer.size }
    fun clear() = lock.write { buffer.clear() }
}
