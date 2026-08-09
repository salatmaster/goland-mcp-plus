package dev.salatmaster.golandmcp

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The `com.goide.*` API is closed-source and unversioned. Confining it to the `go` package
 * means a GoLand upgrade breaks one layer rather than every toolset.
 */
class ApiIsolationTest {

    @Test
    fun `toolsets do not import Go PSI directly`() {
        val toolsetDir = File("src/main/kotlin/dev/salatmaster/golandmcp/toolset")
        assertTrue("toolset sources should exist at ${toolsetDir.absolutePath}", toolsetDir.isDirectory)

        val offenders = toolsetDir.walkTopDown()
            .filter { it.extension == "kt" }
            .filter { file -> file.readLines().any { it.trimStart().startsWith("import com.goide.") } }
            .map { it.name }
            .toList()

        assertTrue(
            "These toolsets import com.goide.* directly; route the access through " +
                "dev.salatmaster.golandmcp.go instead: $offenders",
            offenders.isEmpty(),
        )
    }
}
