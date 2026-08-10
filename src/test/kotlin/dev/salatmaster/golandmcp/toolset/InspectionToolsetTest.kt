package dev.salatmaster.golandmcp.toolset

import dev.salatmaster.golandmcp.GoMcpToolTestCase

/**
 * The IDE knows both what is wrong and how to repair it. Reporting only the first half is
 * what leaves an agent inventing the second, which is where it writes plausible, wrong Go.
 *
 * Fix names are never hard-coded here: they belong to the Go plugin and would turn a
 * GoLand upgrade into a red suite for no reason. The tests find a fix the IDE offers and
 * then hold it to its word.
 */
class InspectionToolsetTest : GoMcpToolTestCase() {

    private val toolset = InspectionToolset()

    /**
     * A light fixture starts with an empty inspection profile — nothing is enabled unless the
     * test says so, which is why the tools found nothing here at first. A real IDE runs the
     * developer's profile, so this enables one Go inspection to prove the machinery, and the
     * breadth is checked by hand against a real project.
     */
    private fun loadProblemFixture() {
        loadFixture("problems")
        myFixture.enableInspections(
            com.goide.inspections.GoUnsortedImportInspection(),
            com.goide.inspections.GoRedundantImportAliasInspection(),
        )
    }

    fun `test reports problems with the fixes the IDE knows`() {
        loadProblemFixture()
        val result = callTool { toolset.quickFixes(project, "messy.go", includeWeak = true) }

        assertTrue(
            "the fixture has unsorted imports and a redundant alias, got ${result.problems}",
            result.problems.isNotEmpty(),
        )
        assertTrue(
            "every problem should say which inspection reported it, got ${result.problems}",
            result.problems.all { it.inspection.isNotEmpty() },
        )
        assertTrue(
            "at least one problem should carry a fix, got ${result.problems}",
            result.problems.any { it.fixes.isNotEmpty() },
        )
    }

    fun `test a fix that cannot run from a tool call says so instead of failing later`() {
        loadProblemFixture()
        val result = callTool { toolset.quickFixes(project, "messy.go", includeWeak = true) }

        for (problem in result.problems) {
            for (fix in problem.fixes) {
                assertEquals(
                    "a fix is either applicable or carries a reason, got '${fix.name}'",
                    fix.applicable,
                    fix.whyNot.isEmpty(),
                )
            }
        }
    }

    fun `test applying a fix changes the file and returns the diff`() {
        loadProblemFixture()
        val before = callTool { toolset.quickFixes(project, "messy.go", includeWeak = true) }
        val target = before.problems.firstOrNull { p -> p.fixes.any { it.applicable } }
        assertNotNull(
            "the fixture must offer an applicable fix, or this test proves nothing; got " +
                "${before.problems}",
            target,
        )
        requireNotNull(target)

        val fix = target.fixes.first { it.applicable }
        val result = callTool { toolset.applyQuickFix(project, "messy.go", target.line, fix.name) }

        assertTrue("'${fix.name}' should apply, hint was: ${result.hint}", result.applied)
        assertTrue("an applied fix must show what it changed", result.diff.isNotEmpty())
    }

    fun `test an unknown fix name fails with what is on offer`() {
        loadProblemFixture()
        val error = org.junit.Assert.assertThrows(Exception::class.java) {
            callTool { toolset.applyQuickFix(project, "messy.go", 5, "Reticulate splines") }
        }

        assertTrue(
            "the error should name the line, was: ${error.message}",
            error.message?.contains("line 5") == true,
        )
    }

    fun `test a non-Go file is refused`() {
        loadProblemFixture()
        val error = org.junit.Assert.assertThrows(Exception::class.java) {
            callTool { toolset.quickFixes(project, "nosuch.txt", includeWeak = false) }
        }

        assertTrue(
            "the error should say what it accepts, was: ${error.message}",
            error.message?.contains(".go") == true,
        )
    }

    fun `test a non positive line is refused`() {
        loadProblemFixture()
        val error = org.junit.Assert.assertThrows(Exception::class.java) {
            callTool { toolset.applyQuickFix(project, "messy.go", 0, "anything") }
        }

        assertTrue(error.message?.contains("1-based") == true)
    }
}
