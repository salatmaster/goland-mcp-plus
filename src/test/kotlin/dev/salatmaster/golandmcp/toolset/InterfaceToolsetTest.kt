package dev.salatmaster.golandmcp.toolset

import dev.salatmaster.golandmcp.GoMcpToolTestCase

/**
 * `org.junit.Assert.assertThrows` is called by its full name on purpose: `UsefulTestCase`
 * declares its own `assertThrows` returning `void`, and an inherited member wins over an
 * imported top-level function.
 */
class InterfaceToolsetTest : GoMcpToolTestCase() {

    private val toolset = InterfaceToolset()

    fun `test lists implementations with pointer requirement`() {
        loadFixture("basic")
        val result = callTool { toolset.implementations(project, "Shape", limit = 50) }

        assertEquals(listOf("Circle", "Rect"), result.implementations.map { it.typeName }.sorted())
        assertTrue(result.implementations.single { it.typeName == "Circle" }.requiresPointer)
        assertFalse(result.implementations.single { it.typeName == "Rect" }.requiresPointer)
        assertFalse(result.truncated)
        assertEquals(0, result.omitted)
    }

    fun `test truncates and reports how many were omitted`() {
        loadFixture("basic")
        val result = callTool { toolset.implementations(project, "Shape", limit = 1) }

        assertEquals(1, result.implementations.size)
        assertTrue(result.truncated)
        assertEquals(1, result.omitted)
    }

    fun `test rejects a non positive limit`() {
        loadFixture("basic")
        val error = org.junit.Assert.assertThrows(Exception::class.java) {
            callTool { toolset.implementations(project, "Shape", limit = 0) }
        }
        assertTrue(
            "error should mention the limit, was: ${error.message}",
            error.message?.contains("limit") == true,
        )
    }

    fun `test check reports satisfaction for a value receiver type`() {
        loadFixture("basic")
        val result = callTool { toolset.interfaceCheck(project, "Rect", "Shape") }

        assertTrue(result.satisfied)
        assertEquals("Rect", result.checkedAs)
        assertTrue(result.hint.isEmpty())
    }

    fun `test check explains the pointer receiver trap`() {
        loadFixture("basic")
        val result = callTool { toolset.interfaceCheck(project, "Circle", "Shape") }

        assertFalse(result.satisfied)
        assertEquals("*Circle", result.checkedAs)
        assertEquals(listOf("Area", "Name"), result.pointerReceiverOnly.sorted())
        assertTrue(
            "hint should tell the agent to use a pointer, was: ${result.hint}",
            result.hint.contains("*Circle"),
        )
    }

    fun `test check lists missing methods`() {
        loadFixture("basic")
        val result = callTool { toolset.interfaceCheck(project, "Triangle", "Shape") }

        assertFalse(result.satisfied)
        assertEquals(listOf("Name"), result.missingMethods)
        assertTrue(
            "hint should name the missing method, was: ${result.hint}",
            result.hint.contains("Name"),
        )
    }

    fun `test check fails clearly when the interface is unknown`() {
        loadFixture("basic")
        val error = org.junit.Assert.assertThrows(Exception::class.java) {
            callTool { toolset.interfaceCheck(project, "Rect", "Nonexistent") }
        }
        assertTrue(
            "error should name the missing interface, was: ${error.message}",
            error.message?.contains("Nonexistent") == true,
        )
    }
}
