package dev.salatmaster.golandmcp.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PathNormalizationTest {

    @Test
    fun `strips quoting an agent adds around a path`() {
        assertEquals("main.go", cleanPath("  `main.go` "))
        assertEquals("main.go", cleanPath("\"main.go\""))
    }

    @Test
    fun `strips a file scheme`() {
        assertEquals("/src/main.go", cleanPath("file:///src/main.go"))
    }

    @Test
    fun `accepts windows separators`() {
        assertEquals("internal/store/user.go", cleanPath("internal\\store\\user.go"))
    }

    @Test
    fun `offers the path itself first`() {
        assertEquals("internal/user.go", candidatePaths("internal/user.go", emptyList()).first())
    }

    @Test
    fun `treats a leading slash as project relative`() {
        assertTrue(candidatePaths("/internal/user.go", emptyList()).contains("internal/user.go"))
    }

    @Test
    fun `drops a leading content root name`() {
        val candidates = candidatePaths("myproject/internal/user.go", listOf("myproject"))
        assertTrue(
            "should try the path without the project directory, got $candidates",
            candidates.contains("internal/user.go"),
        )
    }

    @Test
    fun `does not invent a candidate for an unrelated first segment`() {
        val candidates = candidatePaths("internal/user.go", listOf("myproject"))
        assertEquals(listOf("internal/user.go"), candidates)
    }

    @Test
    fun `never yields an empty candidate`() {
        assertTrue(candidatePaths("/", emptyList()).none { it.isEmpty() })
    }
}
