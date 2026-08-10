package dev.salatmaster.golandmcp.toolset

import dev.salatmaster.golandmcp.GoMcpToolTestCase

/**
 * A method called through an interface does not reference the concrete declaration, so it
 * cannot appear in the result. Said nothing, `callCount: 0, truncated: false` reads as
 * "nothing calls this" — and go_safe_delete is the next tool in that chain.
 */
class UsagesToolsetDispatchTest : GoMcpToolTestCase() {

    private val toolset = UsagesToolset()

    fun `test a method result warns that interface calls are not counted`() {
        loadFixture("basic")
        val result = callTool { toolset.findUsages(project, "Rect.Area", includeTests = true, limit = 50) }

        assertTrue(
            "the hint should say what the search cannot see, was: ${result.hint}",
            result.hint.contains("through an interface"),
        )
        assertTrue(
            "and name the interfaces the receiver satisfies, was: ${result.hint}",
            result.hint.contains("Shape"),
        )
    }

    fun `test a plain function result carries no such warning`() {
        loadFixture("basic")
        val result = callTool { toolset.findUsages(project, "Consumer", includeTests = true, limit = 50) }

        assertFalse(
            "a function is not dispatched through an interface, was: ${result.hint}",
            result.hint.contains("through an interface"),
        )
    }
}
