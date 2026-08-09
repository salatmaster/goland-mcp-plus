package dev.salatmaster.golandmcp.metrics

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException

/** How one tool has been used since the IDE started. */
data class GoToolUsage(
    val tool: String,
    val calls: Long,
    val failures: Long,
    val cancellations: Long,
    val totalMillis: Long,
    /** Epoch millis of the last call, or 0 when never called. */
    val lastCallMillis: Long,
) {
    val averageMillis: Long get() = if (calls == 0L) 0 else totalMillis / calls
}

/**
 * Counts tool calls, in memory, for the current IDE session.
 *
 * This exists to answer the one question every MCP user asks first — is the agent actually
 * calling these tools, and which of them fail? Nothing is written to disk and nothing leaves
 * the machine; restarting the IDE clears it.
 */
@Service(Service.Level.APP)
class GoMcpToolMetrics {

    private class Counters {
        val calls = AtomicLong()
        val failures = AtomicLong()
        val cancellations = AtomicLong()
        val totalNanos = AtomicLong()
        val lastCallMillis = AtomicLong()
    }

    private val counters = ConcurrentHashMap<String, Counters>()

    fun record(tool: String, elapsedNanos: Long, outcome: GoToolOutcome) {
        val entry = counters.computeIfAbsent(tool) { Counters() }
        entry.calls.incrementAndGet()
        entry.totalNanos.addAndGet(elapsedNanos)
        entry.lastCallMillis.set(System.currentTimeMillis())
        when (outcome) {
            GoToolOutcome.SUCCEEDED -> Unit
            GoToolOutcome.FAILED -> entry.failures.incrementAndGet()
            GoToolOutcome.CANCELLED -> entry.cancellations.incrementAndGet()
        }
    }

    /** Busiest first, so the table reads as "what is this agent doing". */
    fun snapshot(): List<GoToolUsage> =
        counters.entries
            .map { (tool, c) ->
                GoToolUsage(
                    tool = tool,
                    calls = c.calls.get(),
                    failures = c.failures.get(),
                    cancellations = c.cancellations.get(),
                    totalMillis = c.totalNanos.get() / 1_000_000,
                    lastCallMillis = c.lastCallMillis.get(),
                )
            }
            .sortedWith(compareByDescending<GoToolUsage> { it.calls }.thenBy { it.tool })

    fun reset() = counters.clear()

    companion object {
        fun getInstance(): GoMcpToolMetrics = service()
    }
}

enum class GoToolOutcome { SUCCEEDED, FAILED, CANCELLED }

private class GoMcpToolMetricsMarker

private val LOG = logger<GoMcpToolMetricsMarker>()

/**
 * Runs a tool body and records how it went.
 *
 * Cancellation is counted apart from failure: a client that disconnects mid-call is not a
 * broken tool, and conflating the two would make the table lie about reliability. The
 * exception itself is always rethrown untouched.
 *
 * Bookkeeping must never be the reason a tool call fails, so recording is guarded.
 */
suspend fun <T> tracked(tool: String, block: suspend () -> T): T {
    val started = System.nanoTime()
    try {
        val result = block()
        record(tool, started, GoToolOutcome.SUCCEEDED)
        return result
    } catch (e: CancellationException) {
        record(tool, started, GoToolOutcome.CANCELLED)
        throw e
    } catch (e: Throwable) {
        record(tool, started, GoToolOutcome.FAILED)
        throw e
    }
}

private fun record(tool: String, startedNanos: Long, outcome: GoToolOutcome) {
    try {
        GoMcpToolMetrics.getInstance().record(tool, System.nanoTime() - startedNanos, outcome)
    } catch (e: Exception) {
        LOG.debug("Could not record usage of $tool", e)
    }
}
