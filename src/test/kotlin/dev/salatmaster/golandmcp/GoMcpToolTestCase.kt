package dev.salatmaster.golandmcp

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File

/**
 * Base class for tests that need a Go project in a light IDE fixture.
 */
abstract class GoMcpToolTestCase : BasePlatformTestCase() {

    /**
     * Runs each test off the event dispatch thread.
     *
     * Tools that write to a document switch to the EDT. With the default (test on the EDT)
     * the blocking bridge in [callTool] would hold that same thread, and the switch could
     * never complete — the run deadlocks rather than failing.
     */
    override fun runInDispatchThread(): Boolean = false

    override fun getTestDataPath(): String = File("src/test/testData").absolutePath

    /** Copies a fixture directory into the in-memory project. */
    protected fun loadFixture(dir: String) {
        myFixture.copyDirectoryToProject(dir, "")
    }

    /**
     * Runs a suspending toolset function.
     *
     * Tests call the `internal` overloads that take a [com.intellij.openapi.project.Project]
     * explicitly, not the `@McpTool` methods. Putting a project into the coroutine context
     * would mean constructing `McpCallInfo`, whose constructor demands
     * `McpServerService.McpSessionOptions` — an internal class of the MCP server plugin.
     * Depending on it would couple this suite to another plugin's implementation details.
     */
    protected fun <T> callTool(block: suspend () -> T): T =
        runBlocking { withTimeout(TOOL_TIMEOUT_MS) { block() } }

    private companion object {
        /** A hung tool should fail the test in seconds, not stall the build for minutes. */
        const val TOOL_TIMEOUT_MS = 60_000L
    }
}
