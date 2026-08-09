package dev.salatmaster.golandmcp.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SymbolRefTest {

    @Test
    fun `parses import path with type and method`() {
        val ref = parseSymbolRef("net/http.Client.Do")
        assertEquals(SymbolRef.Qualified("net/http", "Client", "Do"), ref)
    }

    @Test
    fun `parses import path with a single symbol`() {
        val ref = parseSymbolRef("net/http.Client")
        assertEquals(SymbolRef.Qualified("net/http", null, "Client"), ref)
    }

    @Test
    fun `parses relative package path`() {
        val ref = parseSymbolRef("./internal/store.Store")
        assertEquals(SymbolRef.Qualified("./internal/store", null, "Store"), ref)
    }

    @Test
    fun `parses bare type and method`() {
        val ref = parseSymbolRef("Handler.ServeHTTP")
        assertEquals(SymbolRef.Bare("Handler", "ServeHTTP"), ref)
    }

    @Test
    fun `parses bare symbol`() {
        val ref = parseSymbolRef("ServeHTTP")
        assertEquals(SymbolRef.Bare(null, "ServeHTTP"), ref)
    }

    @Test
    fun `parses domain qualified import path`() {
        val ref = parseSymbolRef("github.com/gin-gonic/gin.Context.JSON")
        assertEquals(SymbolRef.Qualified("github.com/gin-gonic/gin", "Context", "JSON"), ref)
    }

    @Test
    fun `rejects blank input`() {
        assertThrows(SymbolRefParseException::class.java) { parseSymbolRef("  ") }
    }

    @Test
    fun `rejects trailing dot`() {
        assertThrows(SymbolRefParseException::class.java) { parseSymbolRef("net/http.") }
    }

    @Test
    fun `rejects more than two segments after the package path`() {
        assertThrows(SymbolRefParseException::class.java) { parseSymbolRef("net/http.Client.Do.Extra") }
    }

    @Test
    fun `parses a single-segment package path`() {
        assertEquals(SymbolRef.Qualified("store", "User", "Save"), parseSymbolRef("store.User.Save"))
    }

    @Test
    fun `strips quoting an agent adds around the reference`() {
        assertEquals(SymbolRef.Bare(null, "ServeHTTP"), parseSymbolRef("`ServeHTTP`"))
        assertEquals(SymbolRef.Bare(null, "ServeHTTP"), parseSymbolRef("\"ServeHTTP\""))
    }

    @Test
    fun `accepts a pasted function declaration`() {
        assertEquals(SymbolRef.Bare(null, "Double"), parseSymbolRef("func Double(x int) int"))
    }

    @Test
    fun `accepts a pasted method declaration with its receiver`() {
        assertEquals(SymbolRef.Bare("Circle", "Area"), parseSymbolRef("func (c *Circle) Area() float64"))
    }

    @Test
    fun `accepts the pointer receiver form used in Go documentation`() {
        assertEquals(SymbolRef.Bare("Circle", "Area"), parseSymbolRef("(*Circle).Area"))
        assertEquals(SymbolRef.Bare("Circle", "Area"), parseSymbolRef("*Circle.Area"))
    }

    @Test
    fun `accepts a pasted type declaration`() {
        assertEquals(SymbolRef.Bare(null, "User"), parseSymbolRef("type User struct"))
        assertEquals(SymbolRef.Bare(null, "Shape"), parseSymbolRef("type Shape interface"))
    }

    @Test
    fun `accepts a call written with empty parentheses`() {
        assertEquals(SymbolRef.Bare("Rect", "Area"), parseSymbolRef("Rect.Area()"))
    }

    @Test
    fun `keeps a qualified reference intact while normalizing`() {
        assertEquals(
            SymbolRef.Qualified("net/http", "Client", "Do"),
            parseSymbolRef("  `net/http.Client.Do`  "),
        )
    }
}
