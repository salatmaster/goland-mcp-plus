package dev.salatmaster.golandmcp.toolset

import dev.salatmaster.golandmcp.GoMcpToolTestCase

class BatchToolsetTest : GoMcpToolTestCase() {

    private val toolset = BatchToolset()

    fun `test reads several files at once`() {
        loadFixture("basic")
        val result = callTool { toolset.readFiles(project, listOf("shapes.go", "store.go")) }

        assertEquals(2, result.files.size)
        assertEquals(0, result.failed)
        assertTrue(result.files.first { it.path == "shapes.go" }.content.contains("type Rect"))
    }

    fun `test reports unreadable files individually`() {
        loadFixture("basic")
        val result = callTool { toolset.readFiles(project, listOf("shapes.go", "missing.go")) }

        assertEquals(1, result.failed)
        assertTrue(result.files.single { it.path == "missing.go" }.error.contains("not found"))
        assertTrue("the readable file still came back", result.files.single { it.path == "shapes.go" }.error.isEmpty())
    }

    fun `test replaces a line range and returns a diff`() {
        loadFixture("basic")
        val result = callTool {
            toolset.replaceLines(
                project,
                listOf(GoLineReplacement("shapes.go", 1, 1, "package basic // edited")),
            )
        }

        val edit = result.results.single()
        assertTrue("edit should apply, error was: ${edit.error}", edit.applied)
        assertTrue("diff should show the change, was: ${edit.diff}", edit.diff.contains("+package basic // edited"))
    }

    fun `test applies multiple ranges in one file bottom up`() {
        loadFixture("basic")
        val result = callTool {
            toolset.replaceLines(
                project,
                listOf(
                    GoLineReplacement("shapes.go", 1, 1, "package basic // first"),
                    GoLineReplacement("shapes.go", 3, 3, "// second"),
                ),
            )
        }

        val edit = result.results.single()
        assertTrue("edit should apply, error was: ${edit.error}", edit.applied)
        val content = callTool { toolset.readFiles(project, listOf("shapes.go")) }
            .files.single().content
        assertTrue(content.contains("package basic // first"))
        assertTrue(content.contains("// second"))
    }

    fun `test rejects an out of range line`() {
        loadFixture("basic")
        val result = callTool {
            toolset.replaceLines(project, listOf(GoLineReplacement("shapes.go", 9999, 9999, "x")))
        }

        val edit = result.results.single()
        assertFalse(edit.applied)
        assertTrue("error should explain the range, was: ${edit.error}", edit.error.contains("Invalid range"))
    }

    fun `test replaces unique text`() {
        loadFixture("basic")
        val result = callTool {
            toolset.batchReplaceText(
                project, "shapes.go",
                listOf(GoTextReplacement("""return "rect"""", """return "rectangle"""", false)),
            )
        }

        assertTrue("edit should apply, error was: ${result.error}", result.applied)
        assertTrue(result.diff.contains("rectangle"))
    }

    fun `test refuses an ambiguous match instead of guessing`() {
        loadFixture("basic")
        val result = callTool {
            toolset.batchReplaceText(
                project, "shapes.go",
                listOf(GoTextReplacement("float64", "float32", false)),
            )
        }

        assertFalse(result.applied)
        assertTrue(
            "error should say how many matched, was: ${result.error}",
            result.error.contains("occurs") && result.error.contains("replaceAll"),
        )
    }

    fun `test reports missing text rather than silently doing nothing`() {
        loadFixture("basic")
        val result = callTool {
            toolset.batchReplaceText(
                project, "shapes.go",
                listOf(GoTextReplacement("nosuchtext", "x", false)),
            )
        }

        assertFalse(result.applied)
        assertTrue(result.error.contains("not found"))
    }
}
