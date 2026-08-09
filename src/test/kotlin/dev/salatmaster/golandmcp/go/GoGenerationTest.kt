package dev.salatmaster.golandmcp.go

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GoGenerationTest {

    @Test
    fun `renders stubs with a value receiver`() {
        val stubs = GoGeneration.methodStubs(
            "Circle",
            listOf(GoMethodRequirement("Area", "() float64", null, false)),
            pointerReceiver = false,
        )

        assertTrue("receiver should follow convention, was: $stubs", stubs.contains("func (c Circle) Area() float64"))
        assertTrue("stub should fail loudly, was: $stubs", stubs.contains("panic("))
    }

    @Test
    fun `renders stubs with a pointer receiver`() {
        val stubs = GoGeneration.methodStubs(
            "Circle",
            listOf(GoMethodRequirement("Area", "() float64", null, false)),
            pointerReceiver = true,
        )

        assertTrue(stubs.contains("func (c *Circle) Area() float64"))
    }

    @Test
    fun `renders several stubs separated by blank lines`() {
        val stubs = GoGeneration.methodStubs(
            "Pipe",
            listOf(
                GoMethodRequirement("Read", "(p []byte) (int, error)", null, false),
                GoMethodRequirement("Write", "(p []byte) (int, error)", null, false),
            ),
            pointerReceiver = false,
        )

        assertTrue(stubs.contains("func (p Pipe) Read(p []byte) (int, error)"))
        assertTrue(stubs.contains("func (p Pipe) Write(p []byte) (int, error)"))
        assertTrue("stubs should be separated", stubs.contains("}\n\nfunc"))
    }

    @Test
    fun `renders nothing for an empty requirement list`() {
        assertEquals("", GoGeneration.methodStubs("Circle", emptyList(), false))
    }

    @Test
    fun `renders a table driven test skeleton`() {
        val test = GoGeneration.tableTest("Area", "Rect")

        assertTrue("should name the test after the function, was: $test", test.contains("func TestArea(t *testing.T)"))
        assertTrue("should be table driven, was: $test", test.contains("tests := []struct"))
        assertTrue("should use subtests, was: $test", test.contains("t.Run(tt.name"))
        assertTrue("should mention the subject, was: $test", test.contains("Rect.Area"))
    }
}
