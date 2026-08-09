package dev.salatmaster.golandmcp.go

import com.intellij.openapi.application.runReadAction
import dev.salatmaster.golandmcp.GoMcpToolTestCase

class GoInterfaceFactsTest : GoMcpToolTestCase() {

    private val facts: GoInterfaceFacts = GoInterfaceFactsImpl()

    fun `test value receiver type satisfies the interface`() {
        loadFixture("basic")
        val result = runReadAction { facts.check(project, "Rect", "Shape") }!!

        assertTrue("Rect should satisfy Shape", result.satisfied)
        assertEquals("Rect", result.checkedAs)
        assertTrue(result.missing.isEmpty())
        assertTrue(result.mismatched.isEmpty())
    }

    fun `test pointer receiver type is reported as satisfied only via pointer`() {
        loadFixture("basic")
        val result = runReadAction { facts.check(project, "Circle", "Shape") }!!

        assertFalse("Circle by value should not satisfy Shape", result.satisfied)
        assertEquals("*Circle", result.checkedAs)
        assertEquals(
            "both methods are declared on the pointer receiver",
            listOf("Area", "Name"),
            result.pointerOnly.sorted(),
        )
        assertTrue(result.missing.isEmpty())
    }

    fun `test missing method is listed`() {
        loadFixture("basic")
        val result = runReadAction { facts.check(project, "Triangle", "Shape") }!!

        assertFalse(result.satisfied)
        assertEquals(listOf("Name"), result.missing)
        assertTrue(result.pointerOnly.isEmpty())
    }

    fun `test returns null when the interface does not exist`() {
        loadFixture("basic")
        assertNull(runReadAction { facts.check(project, "Rect", "Nonexistent") })
    }

    fun `test finds implementors of an interface`() {
        loadFixture("basic")
        val found = runReadAction { facts.implementors(project, "Shape", limit = 50) }

        assertEquals(listOf("Circle", "Rect"), found.map { it.typeName }.sorted())
        assertTrue(
            "Circle satisfies Shape only through *Circle",
            found.single { it.typeName == "Circle" }.requiresPointer,
        )
        assertFalse(found.single { it.typeName == "Rect" }.requiresPointer)
    }
}
