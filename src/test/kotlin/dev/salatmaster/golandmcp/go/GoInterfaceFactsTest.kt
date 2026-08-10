package dev.salatmaster.golandmcp.go

import com.intellij.openapi.application.runReadAction
import dev.salatmaster.golandmcp.GoMcpToolTestCase
import dev.salatmaster.golandmcp.common.parseSymbolRef

class GoInterfaceFactsTest : GoMcpToolTestCase() {

    private val facts: GoInterfaceFacts = GoInterfaceFactsImpl()

    fun `test value receiver type satisfies the interface`() {
        loadFixture("basic")
        val result = (runReadAction { facts.check(project, parseSymbolRef("Rect"), parseSymbolRef("Shape")) } as GoCheckOutcome.Checked).satisfaction

        assertTrue("Rect should satisfy Shape", result.satisfied)
        assertEquals("Rect", result.checkedAs)
        assertTrue(result.missing.isEmpty())
        assertTrue(result.mismatched.isEmpty())
    }

    fun `test pointer receiver type is reported as satisfied only via pointer`() {
        loadFixture("basic")
        val result = (runReadAction { facts.check(project, parseSymbolRef("Circle"), parseSymbolRef("Shape")) } as GoCheckOutcome.Checked).satisfaction

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
        val result = (runReadAction { facts.check(project, parseSymbolRef("Triangle"), parseSymbolRef("Shape")) } as GoCheckOutcome.Checked).satisfaction

        assertFalse(result.satisfied)
        assertEquals(listOf("Name"), result.missing)
        assertTrue(result.pointerOnly.isEmpty())
    }

    fun `test returns null when the interface does not exist`() {
        loadFixture("basic")
        assertEquals(
            GoCheckOutcome.InterfaceNotFound,
            runReadAction { facts.check(project, parseSymbolRef("Rect"), parseSymbolRef("Nonexistent")) },
        )
    }

    fun `test finds implementors of an interface`() {
        loadFixture("basic")
        val found = runReadAction { facts.implementors(project, parseSymbolRef("Shape"), limit = 50) }!!.items

        assertEquals(
            listOf("Boxed", "BoxedCircle", "Circle", "PointerBoxed", "Rect"),
            found.map { it.typeName }.sorted(),
        )
        assertTrue(
            "Circle satisfies Shape only through *Circle",
            found.single { it.typeName == "Circle" }.requiresPointer,
        )
        assertFalse(found.single { it.typeName == "Rect" }.requiresPointer)
    }

    /**
     * Go promotes an embedded type's methods, so a struct that embeds Rect and declares
     * nothing satisfies Shape. Walking only declared methods called every such type
     * incomplete — a false negative from the one tool whose job is being right about this.
     */
    fun `test promoted methods count toward satisfaction`() {
        loadFixture("basic")
        val result = (runReadAction {
            facts.check(project, parseSymbolRef("Boxed"), parseSymbolRef("Shape"))
        } as GoCheckOutcome.Checked).satisfaction

        assertTrue("Boxed embeds Rect and gets its methods; missing was ${result.missing}", result.missing.isEmpty())
        assertTrue("Boxed should satisfy Shape", result.satisfied)
    }

    /**
     * A type that gains its whole method set by embedding declares nothing, so it appears in
     * no method index. Searching only for declarations left it out — and the result still
     * said the list was complete, which is the one failure mode this tool must not have.
     */
    fun `test finds an implementor that declares nothing and only embeds`() {
        loadFixture("basic")
        val search = runReadAction { facts.implementors(project, parseSymbolRef("Shape"), limit = 50) }!!

        assertTrue(
            "Boxed embeds Rect and implements Shape, got ${search.items.map { it.typeName }}",
            search.items.any { it.typeName == "Boxed" },
        )
        assertTrue("the fixture is far below the candidate cap", search.complete)
    }

    /**
     * How the field is embedded decides the method set. `struct { Circle }` promotes
     * Circle's pointer-receiver methods into *BoxedCircle alone, while `struct { *Circle }`
     * puts them into the value's method set too. Reporting both the same way would send a
     * caller to code that does not compile.
     */
    fun `test embedding by value keeps pointer-receiver methods off the value`() {
        loadFixture("basic")
        val found = runReadAction { facts.implementors(project, parseSymbolRef("Shape"), limit = 50) }!!.items

        assertTrue(
            "BoxedCircle embeds Circle by value, so only *BoxedCircle satisfies Shape",
            found.single { it.typeName == "BoxedCircle" }.requiresPointer,
        )
        assertFalse(
            "PointerBoxed embeds *Circle, so the value satisfies Shape as well",
            found.single { it.typeName == "PointerBoxed" }.requiresPointer,
        )
    }
}
