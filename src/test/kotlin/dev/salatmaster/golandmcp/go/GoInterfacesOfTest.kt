package dev.salatmaster.golandmcp.go

import com.intellij.openapi.application.runReadAction
import dev.salatmaster.golandmcp.GoMcpToolTestCase

class GoInterfacesOfTest : GoMcpToolTestCase() {

    private val facts: GoInterfaceFacts = GoInterfaceFactsImpl()

    fun `test lists every interface a value type satisfies`() {
        loadFixture("basic")
        val found = runReadAction { facts.interfacesOf(project, "Rect", limit = 50) }

        val names = found.map { it.interfaceName }.sorted()
        assertTrue("Rect satisfies Shape, was $names", names.contains("Shape"))
        assertTrue("Rect satisfies Namer, was $names", names.contains("Namer"))
        assertFalse("Rect has no Size method, was $names", names.contains("Sizer"))
    }

    fun `test reports pointer requirement per interface`() {
        loadFixture("basic")
        val found = runReadAction { facts.interfacesOf(project, "Circle", limit = 50) }

        val shape = found.single { it.interfaceName == "Shape" }
        assertTrue("Circle satisfies Shape only via *Circle", shape.requiresPointer)
    }

    fun `test resolves interfaces satisfied through embedding`() {
        loadFixture("basic")
        val found = runReadAction { facts.interfacesOf(project, "Pipe", limit = 50) }

        val names = found.map { it.interfaceName }.sorted()
        assertTrue("Pipe satisfies the embedding ReadWriter, was $names", names.contains("ReadWriter"))
        assertTrue("Pipe satisfies Reader, was $names", names.contains("Reader"))
        assertTrue("Pipe satisfies Writer, was $names", names.contains("Writer"))
    }

    fun `test partial implementation excludes the embedding interface`() {
        loadFixture("basic")
        val found = runReadAction { facts.interfacesOf(project, "HalfPipe", limit = 50) }

        val names = found.map { it.interfaceName }.sorted()
        assertTrue("HalfPipe satisfies Reader, was $names", names.contains("Reader"))
        assertFalse("HalfPipe cannot write, so not a ReadWriter, was $names", names.contains("ReadWriter"))
    }

    fun `test returns empty for an unknown type`() {
        loadFixture("basic")
        assertTrue(runReadAction { facts.interfacesOf(project, "Nonexistent", limit = 50) }.isEmpty())
    }
}
