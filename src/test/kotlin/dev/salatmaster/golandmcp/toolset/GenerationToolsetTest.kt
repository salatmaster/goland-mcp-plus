package dev.salatmaster.golandmcp.toolset

import dev.salatmaster.golandmcp.GoMcpToolTestCase

class GenerationToolsetTest : GoMcpToolTestCase() {

    private val toolset = GenerationToolset()
    private val batch = BatchToolset()

    fun `test generates only the missing methods`() {
        loadFixture("basic")
        val result = callTool {
            toolset.implementInterface(project, "Triangle", "Shape", pointerReceiver = false, apply = false)
        }

        assertTrue("Name is missing and should be generated, was: ${result.code}", result.code.contains("func (t Triangle) Name() string"))
        assertFalse("Area already exists and should not be regenerated", result.code.contains("Area()"))
        assertFalse(result.applied)
    }

    fun `test reports nothing to do when the type already satisfies`() {
        loadFixture("basic")
        val result = callTool {
            toolset.implementInterface(project, "Rect", "Shape", pointerReceiver = false, apply = false)
        }

        assertTrue(result.code.isEmpty())
        assertTrue("hint should say why, was: ${result.hint}", result.hint.contains("already satisfies"))
    }

    fun `test appends stubs to the declaring file when asked`() {
        loadFixture("basic")
        val result = callTool {
            toolset.implementInterface(project, "Triangle", "Shape", pointerReceiver = false, apply = true)
        }

        assertTrue("should apply, hint was: ${result.hint}", result.applied)
        assertTrue("diff should show the addition, was: ${result.diff}", result.diff.contains("Name() string"))

        val content = callTool { batch.readFiles(project, listOf("shapes.go")) }.files.single().content
        assertTrue("file should now contain the stub", content.contains("func (t Triangle) Name() string"))
    }

    fun `test converts json into struct declarations`() {
        loadFixture("basic")
        val result = callTool {
            toolset.typeFromJson(project, """{"user_id": 1, "url": "x"}""", "Payload", "")
        }

        assertTrue(result.code, result.code.contains("type Payload struct"))
        assertTrue("initialisms should be respected, was: ${result.code}", result.code.contains("UserID"))
        assertTrue(result.code.contains("URL"))
        assertFalse(result.applied)
    }

    fun `test rejects an invalid json sample with a usable message`() {
        loadFixture("basic")
        val error = org.junit.Assert.assertThrows(Exception::class.java) {
            callTool { toolset.typeFromJson(project, "{broken", "X", "") }
        }
        assertTrue(error.message!!, error.message!!.contains("not valid JSON"))
    }

    fun `test generates a table driven test skeleton`() {
        loadFixture("basic")
        val result = callTool { toolset.generateTest(project, "Area", "Rect", "") }

        assertTrue(result.code, result.code.contains("func TestArea(t *testing.T)"))
        assertTrue(result.code.contains("tests := []struct"))
    }

    /**
     * Generating a table test almost always means a `_test.go` nobody has created yet, so
     * refusing to create it made the tool useless in its main case.
     */
    fun `test creates the target file when it does not exist`() {
        loadFixture("basic")
        val result = callTool { toolset.generateTest(project, "Area", "Rect", "shapes_test.go") }

        assertTrue("should have written the file, hint was: ${result.hint}", result.applied)

        val content = callTool { batch.readFiles(project, listOf("shapes_test.go")) }
            .files.single().content
        assertTrue("should join the package it sits in, was:\n$content", content.contains("package basic"))
        assertTrue("a new test file needs testing, was:\n$content", content.contains("\"testing\""))
        assertTrue("the generated test should be there, was:\n$content", content.contains("func TestArea("))
    }

    fun `test creates the target file in a new directory`() {
        loadFixture("basic")
        val result = callTool {
            toolset.typeFromJson(project, """{"id": 1}""", "Row", "internal/store/row.go")
        }

        assertTrue("should have written the file, hint was: ${result.hint}", result.applied)
        val content = callTool { batch.readFiles(project, listOf("internal/store/row.go")) }
            .files.single().content
        assertTrue(
            "package should come from the directory name, was:\n$content",
            content.contains("package store"),
        )
        assertTrue(content.contains("type Row struct"))
    }

    fun `test refuses to generate Go into a file that is not Go`() {
        loadFixture("basic")
        val result = callTool { toolset.generateTest(project, "Area", "Rect", "notes.txt") }

        assertFalse(result.applied)
        assertTrue("code should still come back, was empty", result.code.isNotEmpty())
        assertTrue(
            "hint should say why, was: ${result.hint}",
            result.hint.contains(".go file"),
        )
    }

    fun `test adds a missing import`() {
        loadFixture("basic")
        val result = callTool { toolset.fixImports(project, "shapes.go", listOf("fmt"), optimize = false) }

        assertTrue("should change the file, hint was: ${result.hint}", result.applied)
        val content = callTool { batch.readFiles(project, listOf("shapes.go")) }.files.single().content
        assertTrue("import should be present, file was:\n$content", content.contains("\"fmt\""))
    }

    fun `test adding an existing import changes nothing`() {
        loadFixture("basic")
        callTool { toolset.fixImports(project, "shapes.go", listOf("fmt"), optimize = false) }
        val second = callTool { toolset.fixImports(project, "shapes.go", listOf("fmt"), optimize = false) }

        assertFalse("second add should be a no-op", second.applied)
        assertTrue(second.hint.contains("already correct"))
    }

    fun `test reports a missing file rather than failing silently`() {
        loadFixture("basic")
        val result = callTool { toolset.fixImports(project, "nosuch.go", listOf("fmt"), optimize = false) }

        assertFalse(result.applied)
        assertTrue(result.hint.contains("not found"))
    }

    fun `test extracts an interface from exported methods`() {
        loadFixture("basic")
        val result = callTool { toolset.extractInterface(project, "Rect", "Shaper", emptyList(), "") }

        assertTrue(result.code, result.code.contains("type Shaper interface"))
        assertTrue(result.code, result.code.contains("Area() float64"))
        assertTrue(result.code, result.code.contains("Name() string"))
    }

    fun `test extracts only the named methods`() {
        loadFixture("basic")
        val result = callTool { toolset.extractInterface(project, "Rect", "Areaer", listOf("Area"), "") }

        assertTrue(result.code, result.code.contains("Area() float64"))
        assertFalse("Name was not requested, was: ${result.code}", result.code.contains("Name() string"))
    }

    fun `test rejects a method the type does not have`() {
        loadFixture("basic")
        val error = org.junit.Assert.assertThrows(Exception::class.java) {
            callTool { toolset.extractInterface(project, "Rect", "X", listOf("Nope"), "") }
        }
        assertTrue(
            "error should list what the type does declare, was: ${error.message}",
            error.message!!.contains("Nope") && error.message!!.contains("Area"),
        )
    }

    fun `test rejects a type with no methods`() {
        loadFixture("basic")
        val error = org.junit.Assert.assertThrows(Exception::class.java) {
            callTool { toolset.extractInterface(project, "User", "X", emptyList(), "") }
        }
        assertTrue(error.message!!.contains("no methods"))
    }

    /**
     * The optimizer edits PSI and the platform may hold the document back, which reported an
     * unchanged file while the import block had in fact been rewritten.
     */
    fun `test optimizing reports the imports it actually removed`() {
        loadFixture("basic")
        callTool { toolset.fixImports(project, "shapes.go", listOf("fmt", "strings"), optimize = false) }

        val result = callTool { toolset.fixImports(project, "shapes.go", emptyList(), optimize = true) }
        val content = callTool { batch.readFiles(project, listOf("shapes.go")) }.files.single().content

        val stillThere = listOf("\"fmt\"", "\"strings\"").filter { content.contains(it) }
        assertTrue("unused imports should be gone, file was:\n$content", stillThere.isEmpty())
        assertTrue(
            "the result must not claim the file is unchanged; hint was: ${result.hint}",
            result.applied,
        )
        assertTrue("a change should come with a diff", result.diff.isNotEmpty())
    }
}
