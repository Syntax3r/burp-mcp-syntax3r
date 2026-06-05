package net.portswigger.mcp.varlayer

import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks how many times we've seen each (header, value) tuple in
 * Burp -> Claude traffic. When the count reaches the configured threshold,
 * the value becomes eligible for promotion to a session variable.
 *
 * Threading: ConcurrentHashMap.compute is atomic per key, so concurrent
 * tool calls won't race on the count.
 */
class PromotionTracker(private val threshold: Int) {

    data class Observation(
        val count: Int,
        val isPromoted: Boolean,
        val isFirstPromotion: Boolean,
    )

    private val counts = ConcurrentHashMap<String, Int>()

    private fun key(header: String, value: String) =
        "${header.lowercase()}\u0000$value"

    fun observe(header: String, value: String): Observation {
        var firstPromotion = false
        val newCount = counts.compute(key(header, value)) { _, prev ->
            val old = prev ?: 0
            val nc = old + 1
            if (old < threshold && nc >= threshold) firstPromotion = true
            nc
        }!!
        return Observation(
            count = newCount,
            isPromoted = newCount >= threshold,
            isFirstPromotion = firstPromotion
        )
    }

    fun clear() = counts.clear()
}
