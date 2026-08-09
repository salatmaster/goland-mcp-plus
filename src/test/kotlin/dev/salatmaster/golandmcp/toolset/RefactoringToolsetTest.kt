package dev.salatmaster.golandmcp.toolset

import dev.salatmaster.golandmcp.GoMcpToolTestCase

class RefactoringToolsetTest : GoMcpToolTestCase() {

    private val toolset = RefactoringToolset()

    fun `test refuses to delete a symbol that is still used`() {
        loadFixture("basic")
        val result = callTool { toolset.safeDelete(project, "Rect.Area", testUsagesBlock = true) }

        assertFalse("Rect.Area is called from Consumer", result.deleted)
        assertTrue("blocking usages should be listed", result.blockingUsages.isNotEmpty())
        assertTrue(
            "hint should say nothing was deleted, was: ${result.hint}",
            result.hint.contains("nothing was deleted"),
        )
    }

    fun `test lists the blocking usage with its location`() {
        loadFixture("basic")
        val result = callTool { toolset.safeDelete(project, "Rect.Area", testUsagesBlock = true) }

        assertTrue(
            "usage should point at a file and line, was ${result.blockingUsages.map { it.location }}",
            result.blockingUsages.any { it.location.contains(".go:") },
        )
    }

    fun `test test-only usages can be ignored`() {
        loadFixture("basic")
        val blocking = callTool { toolset.safeDelete(project, "Rect.Area", testUsagesBlock = true) }
        val ignoring = callTool { toolset.safeDelete(project, "Rect.Area", testUsagesBlock = false) }

        assertTrue(
            "counting test usages should find at least as many blockers",
            blocking.blockingUsages.size >= ignoring.blockingUsages.size,
        )
    }

    fun `test fails clearly for an unknown symbol`() {
        loadFixture("basic")
        val error = org.junit.Assert.assertThrows(Exception::class.java) {
            callTool { toolset.safeDelete(project, "Hexagon", testUsagesBlock = true) }
        }
        assertTrue(error.message?.contains("Hexagon") == true)
    }

    fun `test move reports a missing source file`() {
        loadFixture("basic")
        val result = callTool { toolset.moveFiles(project, listOf("nosuch.go"), ".") }

        assertFalse(result.succeeded)
        assertTrue("hint should name the file, was: ${result.hint}", result.hint.contains("nosuch.go"))
    }

    fun `test move reports a missing target directory`() {
        loadFixture("basic")
        val result = callTool { toolset.moveFiles(project, listOf("shapes.go"), "nosuchdir") }

        assertFalse(result.succeeded)
        assertTrue(
            "hint should name the directory, was: ${result.hint}",
            result.hint.contains("nosuchdir"),
        )
    }
}
