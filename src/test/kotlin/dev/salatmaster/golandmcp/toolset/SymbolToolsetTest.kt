package dev.salatmaster.golandmcp.toolset

import dev.salatmaster.golandmcp.GoMcpToolTestCase

/**
 * Note: these tests call `org.junit.Assert.assertThrows` by its full name on purpose.
 * `UsefulTestCase` — a superclass of [GoMcpToolTestCase] — declares its own `assertThrows`
 * returning `void`, and an inherited member wins over an imported top-level function, so a
 * plain import would silently resolve to the one that hands back nothing to assert on.
 */
class SymbolToolsetTest : GoMcpToolTestCase() {

    private val toolset = SymbolToolset()

    fun `test returns symbol details for a type`() {
        loadFixture("basic")
        val result = callTool { toolset.symbolInfo(project, "Rect") }

        assertEquals("TYPE", result.kind)
        assertEquals("Rect", result.name)
        assertTrue(result.exported)
        assertTrue(result.candidates.isEmpty())
    }

    fun `test reports a helpful error for an unknown symbol`() {
        loadFixture("basic")
        val error = org.junit.Assert.assertThrows(Exception::class.java) {
            callTool { toolset.symbolInfo(project, "Hexagon") }
        }
        assertTrue(
            "error should name the symbol, was: ${error.message}",
            error.message?.contains("Hexagon") == true,
        )
    }

    fun `test rejects a malformed reference with a usable message`() {
        loadFixture("basic")
        val error = org.junit.Assert.assertThrows(Exception::class.java) {
            callTool { toolset.symbolInfo(project, "net/http.") }
        }
        assertTrue(
            "error should explain the expected form, was: ${error.message}",
            error.message?.contains("net/http.Client") == true,
        )
    }
}
