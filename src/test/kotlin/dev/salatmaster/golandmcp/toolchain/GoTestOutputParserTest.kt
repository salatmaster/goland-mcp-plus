package dev.salatmaster.golandmcp.toolchain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoTestOutputParserTest {

    private val stream = """
        {"Action":"run","Package":"example.com/basic","Test":"TestArea"}
        {"Action":"output","Package":"example.com/basic","Test":"TestArea","Output":"=== RUN   TestArea\n"}
        {"Action":"pass","Package":"example.com/basic","Test":"TestArea","Elapsed":0.01}
        {"Action":"run","Package":"example.com/basic","Test":"TestName"}
        {"Action":"output","Package":"example.com/basic","Test":"TestName","Output":"    shapes_test.go:12: got rect, want circle\n"}
        {"Action":"fail","Package":"example.com/basic","Test":"TestName","Elapsed":0.02}
        {"Action":"run","Package":"example.com/basic","Test":"TestSkipped"}
        {"Action":"skip","Package":"example.com/basic","Test":"TestSkipped","Elapsed":0}
    """.trimIndent()

    @Test
    fun `counts outcomes`() {
        val run = GoTestOutputParser.parse(stream)

        assertEquals(1, run.passedCount)
        assertEquals(1, run.failedCount)
        assertEquals(1, run.skippedCount)
    }

    @Test
    fun `attaches output to the test that produced it`() {
        val run = GoTestOutputParser.parse(stream)

        val failure = run.cases.single { !it.passed && !it.skipped }
        assertEquals("TestName", failure.name)
        assertTrue(
            "failure output should carry the assertion detail, was: ${failure.output}",
            failure.output.contains("got rect, want circle"),
        )
    }

    @Test
    fun `does not attribute another test's output to a failure`() {
        val run = GoTestOutputParser.parse(stream)

        val failure = run.cases.single { it.name == "TestName" }
        assertFalse(failure.output.contains("RUN   TestArea"))
    }

    @Test
    fun `keeps build errors printed before the json stream`() {
        val withBuildError = """
            # example.com/basic
            ./shapes.go:5:2: undefined: missing
        """.trimIndent() + "\n" + stream

        val run = GoTestOutputParser.parse(withBuildError)
        assertTrue(
            "build errors should survive, were ${run.buildErrors}",
            run.buildErrors.any { it.contains("undefined: missing") },
        )
    }

    @Test
    fun `ignores malformed lines instead of failing the run`() {
        val run = GoTestOutputParser.parse("""{"Action":"pass","Package":"p","Test":"T"}""" + "\n{not json")
        assertEquals(1, run.passedCount)
    }
}
