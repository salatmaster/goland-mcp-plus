package dev.salatmaster.golandmcp.go

import com.intellij.openapi.application.runReadAction
import dev.salatmaster.golandmcp.GoMcpToolTestCase
import dev.salatmaster.golandmcp.common.parseSymbolRef

class GoUsagesTest : GoMcpToolTestCase() {

    private val usages: GoUsages = GoUsagesImpl()

    private fun find(reference: String, includeTests: Boolean = true, limit: Int = 50) =
        runReadAction { usages.find(project, parseSymbolRef(reference), includeTests, limit) }

    fun `test finds usages of a method`() {
        loadFixture("basic")
        val result = find("Rect.Area")!!

        assertTrue("should find several call sites, got ${result.usages.size}", result.usages.size >= 3)
        assertTrue(
            "calls should be classified, kinds were ${result.usages.map { it.kind }}",
            result.usages.any { it.kind == GoUsageKind.CALL },
        )
    }

    fun `test excludes test files on request`() {
        loadFixture("basic")
        val withTests = find("Rect.Area", includeTests = true)!!
        val withoutTests = find("Rect.Area", includeTests = false)!!

        assertTrue(withTests.usages.any { it.inTestFile })
        assertFalse(withoutTests.usages.any { it.inTestFile })
        assertTrue(withoutTests.usages.size < withTests.usages.size)
    }

    fun `test carries the source line of each usage`() {
        loadFixture("basic")
        val result = find("Rect.Area", includeTests = false)!!

        assertTrue(
            "snippet should show the calling line, got ${result.usages.map { it.snippet }}",
            result.usages.any { it.snippet.contains("r.Area()") },
        )
    }

    fun `test reports truncation when the limit is hit`() {
        loadFixture("basic")
        val result = find("Rect.Area", limit = 1)!!

        assertEquals(1, result.usages.size)
        assertTrue(result.truncated)
    }

    fun `test returns null for an unknown symbol`() {
        loadFixture("basic")
        assertNull(find("Hexagon"))
    }
}
