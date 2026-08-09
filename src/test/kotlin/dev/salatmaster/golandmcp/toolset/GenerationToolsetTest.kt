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

    fun `test reports a missing target file instead of silently dropping the code`() {
        loadFixture("basic")
        val result = callTool { toolset.generateTest(project, "Area", "Rect", "nosuch.go") }

        assertFalse(result.applied)
        assertTrue("code should still come back, was empty", result.code.isNotEmpty())
        assertTrue("hint should say the file was not found, was: ${result.hint}", result.hint.contains("not found"))
    }
}
