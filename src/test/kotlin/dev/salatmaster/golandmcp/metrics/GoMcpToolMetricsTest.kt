package dev.salatmaster.golandmcp.metrics

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

class GoMcpToolMetricsTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        GoMcpToolMetrics.getInstance().reset()
    }

    override fun tearDown() {
        try {
            GoMcpToolMetrics.getInstance().reset()
        } finally {
            super.tearDown()
        }
    }

    private fun usageOf(tool: String) =
        GoMcpToolMetrics.getInstance().snapshot().single { it.tool == tool }

    fun `test a successful call is counted without a failure`() {
        runBlocking { tracked("go_probe_ok") { 42 } }

        val usage = usageOf("go_probe_ok")
        assertEquals(1L, usage.calls)
        assertEquals(0L, usage.failures)
        assertEquals(0L, usage.cancellations)
        assertTrue("the last call should be stamped", usage.lastCallMillis > 0)
    }

    // try/catch rather than assertThrows: a no-argument lambda that returns Unit inside a
    // method named 'test...' compiles to a synthetic method JUnit 3 then collects as a test.
    fun `test a failing call is counted and the exception still escapes`() {
        var caught: Throwable? = null
        try {
            runBlocking { tracked<Unit>("go_probe_fail") { error("boom") } }
        } catch (e: IllegalStateException) {
            caught = e
        }
        assertEquals("boom", caught?.message)

        val usage = usageOf("go_probe_fail")
        assertEquals(1L, usage.calls)
        assertEquals(1L, usage.failures)
    }

    /** A client that disconnects mid-call is not a broken tool; the table must not say it is. */
    fun `test cancellation is counted apart from failure`() {
        var caught: Throwable? = null
        try {
            runBlocking { tracked<Unit>("go_probe_cancel") { throw CancellationException("gone") } }
        } catch (e: CancellationException) {
            caught = e
        }
        assertNotNull("cancellation must still escape the tracker", caught)

        val usage = usageOf("go_probe_cancel")
        assertEquals(1L, usage.calls)
        assertEquals(0L, usage.failures)
        assertEquals(1L, usage.cancellations)
    }

    fun `test calls accumulate per tool`() {
        runBlocking {
            repeat(3) { tracked("go_probe_many") { it } }
            tracked("go_probe_other") { Unit }
        }

        assertEquals(3L, usageOf("go_probe_many").calls)
        assertEquals(1L, usageOf("go_probe_other").calls)
    }

    fun `test reset clears everything`() {
        runBlocking { tracked("go_probe_reset") { Unit } }
        GoMcpToolMetrics.getInstance().reset()

        assertTrue(GoMcpToolMetrics.getInstance().snapshot().isEmpty())
    }

    fun `test the catalog lists every contributed tool`() {
        val names = GoMcpToolCatalog.toolNames

        assertEquals("every tool name should be unique", names.size, names.toSet().size)
        assertTrue("expected the full tool set, got ${names.size}", names.size >= 24)
        assertTrue(
            "every contributed tool should be named go_*, got $names",
            names.all { it.startsWith("go_") },
        )
        assertTrue(names.contains("go_change_signature"))
        assertTrue(names.contains("go_interface_check"))
    }
}
