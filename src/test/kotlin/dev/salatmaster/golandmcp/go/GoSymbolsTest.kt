package dev.salatmaster.golandmcp.go

import com.intellij.openapi.application.runReadAction
import dev.salatmaster.golandmcp.GoMcpToolTestCase
import dev.salatmaster.golandmcp.common.parseSymbolRef

class GoSymbolsTest : GoMcpToolTestCase() {

    private val symbols: GoSymbols = GoSymbolsImpl()

    fun `test finds a struct type by bare name`() {
        loadFixture("basic")
        val result = runReadAction { symbols.lookup(project, parseSymbolRef("Rect")) }

        val found = result as GoLookupResult.Found
        assertEquals(GoSymbolKind.TYPE, found.symbol.kind)
        assertEquals("Rect", found.symbol.name)
        assertTrue(found.symbol.exported)
        assertTrue(
            "location should point at the declaration line, was ${found.symbol.location}",
            found.symbol.location.endsWith("shapes.go:10"),
        )
    }

    fun `test finds an interface and reports its kind`() {
        loadFixture("basic")
        val result = runReadAction { symbols.lookup(project, parseSymbolRef("Shape")) }

        val found = result as GoLookupResult.Found
        assertEquals(GoSymbolKind.INTERFACE, found.symbol.kind)
    }

    fun `test finds a method by type and name`() {
        loadFixture("basic")
        val result = runReadAction { symbols.lookup(project, parseSymbolRef("Rect.Area")) }

        val found = result as GoLookupResult.Found
        assertEquals(GoSymbolKind.METHOD, found.symbol.kind)
        assertEquals("Area", found.symbol.name)
        assertTrue(
            "signature should show the return type, was ${found.symbol.signature}",
            found.symbol.signature.contains("float64"),
        )
    }

    fun `test reports not found for an unknown symbol`() {
        loadFixture("basic")
        val result = runReadAction { symbols.lookup(project, parseSymbolRef("Hexagon")) }

        assertEquals(GoLookupResult.NotFound, result)
    }

    fun `test carries the doc comment`() {
        loadFixture("basic")
        val result = runReadAction { symbols.lookup(project, parseSymbolRef("Shape")) }

        val found = result as GoLookupResult.Found
        assertTrue(
            "doc should carry the comment above the declaration, was ${found.symbol.doc}",
            found.symbol.doc?.contains("something with an area") == true,
        )
    }

    /**
     * `pkg.Symbol` is what an agent writes after reading an import. Two dotted segments are
     * ambiguous with `Type.Member`, so this resolves only on the retry.
     */
    fun `test resolves a package qualified reference with no slash`() {
        loadFixture("basic")
        val result = runReadAction { symbols.lookup(project, parseSymbolRef("basic.Rect")) }

        assertTrue("basic.Rect should resolve, got $result", result is GoLookupResult.Found)
        assertEquals("basic.Rect", (result as GoLookupResult.Found).symbol.qualifiedName)
    }

    fun `test a type and member reference still wins over the package reading`() {
        loadFixture("basic")
        val result = runReadAction { symbols.lookup(project, parseSymbolRef("Rect.Area")) }

        assertTrue(result is GoLookupResult.Found)
        assertEquals("Area", (result as GoLookupResult.Found).symbol.name)
    }
}
