package dev.salatmaster.golandmcp.toolset

import dev.salatmaster.golandmcp.GoMcpToolTestCase

class InterfacesOfToolTest : GoMcpToolTestCase() {

    private val toolset = InterfaceToolset()

    fun `test lists interfaces a type satisfies`() {
        loadFixture("basic")
        val result = callTool { toolset.interfacesOf(project, "Rect", limit = 50) }

        val names = result.interfaces.map { it.interfaceName }.sorted()
        assertTrue("Rect satisfies Shape, was $names", names.contains("Shape"))
        assertTrue("Rect satisfies Namer, was $names", names.contains("Namer"))
        assertFalse(result.truncated)
    }

    fun `test flags interfaces reachable only through a pointer`() {
        loadFixture("basic")
        val result = callTool { toolset.interfacesOf(project, "Circle", limit = 50) }

        assertTrue(result.interfaces.single { it.interfaceName == "Shape" }.requiresPointer)
    }

    fun `test fails clearly for a type that satisfies nothing`() {
        loadFixture("basic")
        val error = org.junit.Assert.assertThrows(Exception::class.java) {
            callTool { toolset.interfacesOf(project, "Nonexistent", limit = 50) }
        }
        assertTrue(
            "error should name the type, was: ${error.message}",
            error.message?.contains("Nonexistent") == true,
        )
    }
}
