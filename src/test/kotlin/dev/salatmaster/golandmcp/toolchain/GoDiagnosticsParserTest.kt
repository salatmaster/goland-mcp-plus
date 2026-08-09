package dev.salatmaster.golandmcp.toolchain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GoDiagnosticsParserTest {

    @Test
    fun `parses file line column and message`() {
        val parsed = GoDiagnosticsParser.parse("./internal/store.go:42:9: undefined: Foo")

        val diagnostic = parsed.diagnostics.single()
        assertEquals("internal/store.go", diagnostic.file)
        assertEquals(42, diagnostic.line)
        assertEquals(9, diagnostic.column)
        assertEquals("undefined: Foo", diagnostic.message)
    }

    @Test
    fun `parses diagnostics without a column`() {
        val parsed = GoDiagnosticsParser.parse("main.go:7: declared and not used: x")

        val diagnostic = parsed.diagnostics.single()
        assertEquals(7, diagnostic.line)
        assertEquals(0, diagnostic.column)
    }

    @Test
    fun `keeps unpositioned lines as notes`() {
        val parsed = GoDiagnosticsParser.parse(
            "# example.com/basic\nmain.go:3:1: syntax error\nsome linker complaint",
        )

        assertEquals(1, parsed.diagnostics.size)
        assertTrue(parsed.notes.contains("# example.com/basic"))
        assertTrue(parsed.notes.contains("some linker complaint"))
    }

    @Test
    fun `ignores blank lines`() {
        val parsed = GoDiagnosticsParser.parse("\n\n   \n")
        assertTrue(parsed.diagnostics.isEmpty())
        assertTrue(parsed.notes.isEmpty())
    }
}
