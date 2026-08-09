package dev.salatmaster.golandmcp.toolset

import dev.salatmaster.golandmcp.GoMcpToolTestCase

class DocAndSourceToolTest : GoMcpToolTestCase() {

    private val toolset = SymbolToolset()

    fun `test doc returns the comment and signature`() {
        loadFixture("basic")
        val result = callTool { toolset.doc(project, "Shape") }

        assertTrue("doc should be carried, was '${result.doc}'", result.doc.contains("area"))
        assertTrue(
            "signature should describe the interface, was '${result.signature}'",
            result.signature.contains("interface"),
        )
    }

    fun `test source returns the whole declaration`() {
        loadFixture("basic")
        val result = callTool { toolset.sourceOf(project, "Rect") }

        assertTrue(
            "source should include the struct body, was: ${result.source}",
            result.source.contains("W float64") && result.source.contains("H float64"),
        )
        assertFalse("fixture code is part of the project", result.external)
    }

    fun `test source of a method includes its body`() {
        loadFixture("basic")
        val result = callTool { toolset.sourceOf(project, "Rect.Area") }

        assertTrue(
            "source should include the body, was: ${result.source}",
            result.source.contains("r.W * r.H"),
        )
    }

    fun `test source fails clearly for an unknown symbol`() {
        loadFixture("basic")
        val error = org.junit.Assert.assertThrows(Exception::class.java) {
            callTool { toolset.sourceOf(project, "Hexagon") }
        }
        assertTrue(error.message?.contains("Hexagon") == true)
    }
}
