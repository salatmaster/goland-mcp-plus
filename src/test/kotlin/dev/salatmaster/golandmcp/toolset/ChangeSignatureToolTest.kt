package dev.salatmaster.golandmcp.toolset

import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.psi.PsiManager
import dev.salatmaster.golandmcp.GoMcpToolTestCase

/**
 * Covers `go_change_signature`, which rewrites call sites as well as the declaration —
 * so every assertion checks the whole file, not just the signature line.
 */
class ChangeSignatureToolTest : GoMcpToolTestCase() {

    private val toolset = RefactoringToolset()

    private fun textOf(path: String): String =
        runReadActionBlocking {
            PsiManager.getInstance(project).findFile(myFixture.findFileInTempDir(path))!!.text
        }

    private fun entry(
        fromIndex: Int,
        name: String,
        type: String,
        variadic: Boolean = false,
        defaultValue: String = "",
    ) = GoSignatureEntry(fromIndex, name, type, variadic, defaultValue)

    /** `func Double(x int) int` as the request that leaves it exactly as it is. */
    private val doubleAsIs = listOf(entry(0, "x", "int"))
    private val intResult = listOf(entry(0, "", "int"))

    private fun changeDouble(
        newName: String = "",
        parameters: List<GoSignatureEntry> = doubleAsIs,
        results: List<GoSignatureEntry> = intResult,
        reference: String = "Double",
        updateImplementations: Boolean = false,
    ) = callTool {
        toolset.changeSignature(project, reference, newName, parameters, results, updateImplementations)
    }

    fun `test renames the function and every call site`() {
        loadFixture("signature")
        val result = changeDouble(newName = "Twice")

        assertTrue(result.applied)
        assertEquals("func Double(x int) int", result.before)
        assertEquals("func Twice(x int) int", result.after)

        val source = textOf("api.go")
        assertTrue("declaration should be renamed, was:\n$source", source.contains("func Twice(x int) int"))
        assertTrue("call site should be renamed, was:\n$source", source.contains("n := Twice(21)"))
    }

    fun `test appends a parameter and passes its default at call sites`() {
        loadFixture("signature")
        val result = changeDouble(
            parameters = doubleAsIs + entry(-1, "factor", "int", defaultValue = "1"),
        )

        assertTrue(result.applied)
        assertEquals("func Double(x int, factor int) int", result.after)

        val source = textOf("api.go")
        assertTrue(
            "the existing call must gain the default argument, was:\n$source",
            source.contains("n := Double(21, 1)"),
        )
    }

    fun `test reordering parameters moves the arguments with them`() {
        loadFixture("signature")
        val result = callTool {
            toolset.changeSignature(
                project,
                "Pair",
                "",
                listOf(entry(1, "b", "string"), entry(0, "a", "int")),
                listOf(entry(0, "", "string")),
                false,
            )
        }

        assertTrue(result.applied)
        assertEquals("func Pair(b string, a int) string", result.after)

        val source = textOf("api.go")
        assertTrue(
            "arguments must follow their parameter, was:\n$source",
            source.contains("""return Pair("answer", n)"""),
        )
    }

    fun `test dropping a parameter drops its argument`() {
        loadFixture("signature")
        val result = callTool {
            toolset.changeSignature(
                project,
                "Pair",
                "",
                listOf(entry(1, "b", "string")),
                listOf(entry(0, "", "string")),
                false,
            )
        }

        assertTrue(result.applied)
        assertEquals("func Pair(b string) string", result.after)

        val source = textOf("api.go")
        assertTrue(
            "the dropped argument must go too, was:\n$source",
            source.contains("""return Pair("answer")"""),
        )
    }

    fun `test adding an error result rewrites returns and call sites`() {
        loadFixture("signature")
        val result = changeDouble(
            results = intResult + entry(-1, "", "error", defaultValue = "nil"),
        )

        assertTrue(result.applied)
        assertEquals("func Double(x int) (int, error)", result.after)

        val source = textOf("api.go")
        assertTrue(
            "the return statement must supply the new result, was:\n$source",
            source.contains("return x * 2, nil"),
        )
        assertTrue(
            "the call site must accept the extra value, was:\n$source",
            source.contains("n, _ := Double(21)"),
        )
    }

    fun `test retypes a parameter without touching its argument`() {
        loadFixture("signature")
        val result = changeDouble(parameters = listOf(entry(0, "x", "int64")))

        assertTrue(result.applied)
        assertEquals("func Double(x int64) int", result.after)
        assertTrue(textOf("api.go").contains("n := Double(21)"))
    }

    fun `test renaming a parameter renames it in the body too`() {
        loadFixture("signature")
        val result = changeDouble(parameters = listOf(entry(0, "value", "int")))

        assertTrue(result.applied)
        val source = textOf("api.go")
        assertTrue(
            "the body must use the new parameter name, was:\n$source",
            source.contains("func Double(value int) int { return value * 2 }"),
        )
    }

    fun `test a new variadic parameter needs no default`() {
        loadFixture("signature")
        val result = changeDouble(
            parameters = doubleAsIs + entry(-1, "tags", "string", variadic = true),
        )

        assertTrue(result.applied)
        assertEquals("func Double(x int, tags ...string) int", result.after)
        assertTrue(
            "a variadic parameter may be omitted, so the call stays as it was",
            textOf("api.go").contains("n := Double(21)"),
        )
    }

    fun `test changing an interface method updates the interface and its callers`() {
        loadFixture("signature")
        val result = callTool {
            toolset.changeSignature(
                project,
                "Repo.Get",
                "",
                listOf(entry(0, "id", "int"), entry(-1, "verbose", "bool", defaultValue = "false")),
                listOf(entry(0, "", "string")),
                true,
            )
        }

        assertTrue(result.applied)

        val source = textOf("store.go")
        assertTrue(
            "the interface method must be rewritten, was:\n$source",
            source.contains("Get(id int, verbose bool) string"),
        )
        assertTrue(
            "calls through the interface must be rewritten, was:\n$source",
            source.contains("return r.Get(1, false)"),
        )
    }

    fun `test a request for the current signature changes nothing`() {
        loadFixture("signature")
        val result = changeDouble()

        assertFalse(result.applied)
        assertEquals(result.before, result.after)
        assertTrue(
            "hint should say why nothing happened, was: ${result.hint}",
            result.hint.contains("already has"),
        )
        assertTrue(textOf("api.go").contains("func Double(x int) int"))
    }

    fun `test rejects a fromIndex the signature does not have`() {
        loadFixture("signature")
        val error = org.junit.Assert.assertThrows(Exception::class.java) {
            changeDouble(parameters = doubleAsIs + entry(3, "extra", "int"))
        }

        val message = error.message.orEmpty()
        assertTrue("should name the offending index, was: $message", message.contains("3"))
        assertTrue(
            "should show the current signature so it can be corrected, was: $message",
            message.contains("func Double(x int) int"),
        )
        assertTrue(textOf("api.go").contains("func Double(x int) int"))
    }

    fun `test rejects mapping one parameter onto two`() {
        loadFixture("signature")
        val error = org.junit.Assert.assertThrows(Exception::class.java) {
            changeDouble(parameters = listOf(entry(0, "x", "int"), entry(0, "y", "int")))
        }

        assertTrue(
            "should explain the duplicate mapping, was: ${error.message}",
            error.message.orEmpty().contains("twice"),
        )
    }

    /**
     * The regression that made an earlier attempt at this tool unshippable: the Go parser
     * accepts malformed type text by silently reading only the part it understands.
     */
    fun `test rejects malformed type text instead of quietly truncating it`() {
        loadFixture("signature")
        val error = org.junit.Assert.assertThrows(Exception::class.java) {
            changeDouble(parameters = listOf(entry(0, "x", "x ((((")))
        }

        val message = error.message.orEmpty()
        assertTrue("should quote the input, was: $message", message.contains("x (((("))
        assertTrue(
            "should report what the parser actually read, was: $message",
            message.contains("read it as"),
        )
        assertTrue(
            "nothing may be modified, was:\n" + textOf("api.go"),
            textOf("api.go").contains("func Double(x int) int"),
        )
    }

    fun `test rejects a variadic spelled in the type`() {
        loadFixture("signature")
        val error = org.junit.Assert.assertThrows(Exception::class.java) {
            changeDouble(parameters = doubleAsIs + entry(-1, "tags", "...string"))
        }

        assertTrue(
            "should point at the variadic flag, was: ${error.message}",
            error.message.orEmpty().contains("variadic"),
        )
    }

    fun `test rejects a new parameter whose call-site value cannot be determined`() {
        loadFixture("signature")
        val error = org.junit.Assert.assertThrows(Exception::class.java) {
            changeDouble(parameters = doubleAsIs + entry(-1, "factor", "int"))
        }

        assertTrue(
            "should ask for a defaultValue, was: ${error.message}",
            error.message.orEmpty().contains("defaultValue"),
        )
        assertTrue(
            "nothing may be modified when the request is refused",
            textOf("api.go").contains("n := Double(21)"),
        )
    }

    fun `test rejects a half-named parameter list`() {
        loadFixture("signature")
        val error = org.junit.Assert.assertThrows(Exception::class.java) {
            changeDouble(
                parameters = listOf(entry(0, "x", "int"), entry(-1, "", "string", defaultValue = "\"\"")),
            )
        }

        assertTrue(
            "should explain Go's all-or-nothing naming, was: ${error.message}",
            error.message.orEmpty().contains("all named or all unnamed"),
        )
    }

    fun `test rejects a Go keyword as a name`() {
        loadFixture("signature")
        val error = org.junit.Assert.assertThrows(Exception::class.java) {
            changeDouble(parameters = listOf(entry(0, "range", "int")))
        }

        assertTrue(
            "should reject the identifier, was: ${error.message}",
            error.message.orEmpty().contains("not a valid Go identifier"),
        )
    }

    fun `test rejects a variadic result`() {
        loadFixture("signature")
        val error = org.junit.Assert.assertThrows(Exception::class.java) {
            changeDouble(results = listOf(entry(0, "", "int", variadic = true)))
        }

        assertTrue(
            "should say results cannot be variadic, was: ${error.message}",
            error.message.orEmpty().contains("cannot be variadic"),
        )
    }

    fun `test rejects a duplicate parameter name`() {
        loadFixture("signature")
        val error = org.junit.Assert.assertThrows(Exception::class.java) {
            changeDouble(
                parameters = listOf(entry(0, "x", "int"), entry(-1, "x", "int", defaultValue = "0")),
            )
        }

        assertTrue(
            "should name the duplicate, was: ${error.message}",
            error.message.orEmpty().contains("declared twice"),
        )
    }

    fun `test rejects a symbol that has no signature`() {
        loadFixture("signature")
        val error = org.junit.Assert.assertThrows(Exception::class.java) {
            changeDouble(reference = "Memory", parameters = emptyList(), results = emptyList())
        }

        assertTrue(
            "should explain why the symbol does not qualify, was: ${error.message}",
            error.message.orEmpty().contains("no signature to change"),
        )
    }

    fun `test fails clearly for an unknown symbol`() {
        loadFixture("signature")
        val error = org.junit.Assert.assertThrows(Exception::class.java) {
            changeDouble(reference = "Nonexistent", parameters = emptyList(), results = emptyList())
        }

        assertTrue(error.message.orEmpty().contains("Nonexistent"))
    }
}
