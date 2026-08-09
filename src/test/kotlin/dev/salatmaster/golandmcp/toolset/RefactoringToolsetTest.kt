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

    fun `test inlines a function into its call sites`() {
        loadFixture("basic")
        val batch = BatchToolset()

        val result = callTool { toolset.inline(project, "double", removeDeclaration = true) }

        assertTrue("inlining should succeed, hint was: ${result.hint}", result.inlined)

        val content = callTool { batch.readFiles(project, listOf("inline.go")) }.files.single().content
        assertFalse("calls should be gone, file was:\n$content", content.contains("double(2)"))
        assertFalse("declaration should be removed, file was:\n$content", content.contains("func double("))
    }

    fun `test inline keeps the declaration when asked`() {
        loadFixture("basic")
        val batch = BatchToolset()

        val result = callTool { toolset.inline(project, "double", removeDeclaration = false) }

        assertTrue("inlining should succeed, hint was: ${result.hint}", result.inlined)
        val content = callTool { batch.readFiles(project, listOf("inline.go")) }.files.single().content
        assertTrue("declaration should remain, file was:\n$content", content.contains("func double("))
    }

    fun `test inline rejects a non function symbol`() {
        loadFixture("basic")
        val error = org.junit.Assert.assertThrows(Exception::class.java) {
            callTool { toolset.inline(project, "Rect", removeDeclaration = false) }
        }
        assertTrue(
            "error should explain what was wrong, was: ${error.message}",
            error.message!!.contains("not a function"),
        )
    }
}
