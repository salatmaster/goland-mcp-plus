package dev.salatmaster.golandmcp.toolset

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.project
import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.Project
import dev.salatmaster.golandmcp.common.SymbolRefParseException
import dev.salatmaster.golandmcp.common.parseSymbolRef
import dev.salatmaster.golandmcp.go.GoUsagesImpl
import dev.salatmaster.golandmcp.metrics.tracked
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.Serializable

@Serializable
data class GoUsageEntry(
    /** CALL, WRITE, READ, IMPORT or DECLARATION. */
    val kind: String,
    val location: String,
    val snippet: String,
    val inTestFile: Boolean,
    val packagePath: String,
)

@Serializable
data class GoFindUsagesResult(
    val target: String,
    val usages: List<GoUsageEntry>,
    val callCount: Int,
    val writeCount: Int,
    val truncated: Boolean,
    val hint: String,
)

class UsagesToolset : McpToolset {

    private val usages = GoUsagesImpl()

    @McpTool
    @McpDescription(
        "Find where a Go symbol is used, with each site classified as a call, a write or a " +
            "read, and test files marked. Unlike a text search this resolves through the type " +
            "system, so it will not match same-named symbols in other packages. Use it before " +
            "changing a signature to see what depends on it.",
    )
    suspend fun go_find_usages(
        @McpDescription("Symbol reference, e.g. 'Rect.Area' or 'net/http.Client.Do'")
        reference: String,
        @McpDescription("Include usages inside _test.go files")
        includeTests: Boolean,
        @McpDescription("Maximum number of usages to return")
        limit: Int,
    ): GoFindUsagesResult =
        tracked("go_find_usages") {
            findUsages(currentCoroutineContext().project, reference, includeTests, limit)
        }

    /** Testable core; the project is explicit so tests need no MCP call context. */
    internal suspend fun findUsages(
        project: Project,
        reference: String,
        includeTests: Boolean,
        limit: Int,
    ): GoFindUsagesResult {
        if (limit <= 0) mcpFail("limit must be positive, got $limit")

        val ref = try {
            parseSymbolRef(reference)
        } catch (e: SymbolRefParseException) {
            mcpFail(e.message ?: "Could not parse '$reference'")
        }

        val result = readAction { usages.find(project, ref, includeTests, limit) }
            ?: mcpFail(
                "No Go symbol matches '$reference'. Check the spelling, or qualify it with a " +
                    "package path such as 'net/http.Client'.",
            )

        val entries = result.usages.map {
            GoUsageEntry(
                kind = it.kind.name,
                location = it.location,
                snippet = it.snippet,
                inTestFile = it.inTestFile,
                packagePath = it.packagePath,
            )
        }

        // Writes are called out separately because they are the sites a signature or
        // semantics change is most likely to break.
        return GoFindUsagesResult(
            target = result.target,
            usages = entries,
            callCount = entries.count { it.kind == "CALL" },
            writeCount = entries.count { it.kind == "WRITE" },
            truncated = result.truncated,
            hint = if (result.truncated) {
                "Stopped at $limit usages; more exist. Raise limit to see the rest."
            } else {
                ""
            },
        )
    }
}
