package dev.salatmaster.golandmcp

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Base class for tests that need a Go project in a light IDE fixture.
 */
abstract class GoMcpToolTestCase : BasePlatformTestCase() {

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
    protected fun <T> callTool(block: suspend () -> T): T = runBlocking { block() }
}
