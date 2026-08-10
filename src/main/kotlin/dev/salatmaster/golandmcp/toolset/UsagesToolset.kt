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

    private val facts = dev.salatmaster.golandmcp.go.GoInterfaceFactsImpl()

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

        // A method called through an interface does not reference the concrete declaration,
        // so it cannot appear here. Left unsaid, `callCount: 0, truncated: false` reads as
        // "nothing calls this" -- which, with go_safe_delete next in the chain, is a path to
        // deleting live code.
        val dispatchNote = interfaceDispatchNote(project, ref, limit)

        // Writes are called out separately because they are the sites a signature or
        // semantics change is most likely to break.
        return GoFindUsagesResult(
            target = result.target,
            usages = entries,
            callCount = entries.count { it.kind == "CALL" },
            writeCount = entries.count { it.kind == "WRITE" },
            truncated = result.truncated,
            hint = listOf(
                if (result.truncated) {
                    "Stopped at $limit usages; more exist. Raise limit to see the rest."
                } else {
                    ""
                },
                dispatchNote,
            ).filter { it.isNotEmpty() }.joinToString(" "),
        )
    }

    /**
     * What this search cannot see, said out loud.
     *
     * Find Usages resolves references to the declaration, and a call written against an
     * interface references the interface method, not the concrete one. The IDE behaves the
     * same way, but there a human sees the scope; here the caller sees a number.
     */
    private suspend fun interfaceDispatchNote(
        project: Project,
        ref: dev.salatmaster.golandmcp.common.SymbolRef,
        limit: Int,
    ): String {
        val owner = when (ref) {
            is dev.salatmaster.golandmcp.common.SymbolRef.Bare -> ref.typeName
            is dev.salatmaster.golandmcp.common.SymbolRef.Qualified -> ref.typeName
            else -> null
        } ?: return ""

        val ownerRef = when (ref) {
            is dev.salatmaster.golandmcp.common.SymbolRef.Qualified ->
                dev.salatmaster.golandmcp.common.SymbolRef.Qualified(ref.packagePath, null, owner)
            else -> dev.salatmaster.golandmcp.common.SymbolRef.Bare(null, owner)
        }

        val satisfied = readAction { facts.interfacesOf(project, ownerRef, limit) }?.items.orEmpty()
        val base = "Only references to the concrete method are counted; a call made through " +
            "an interface resolves to the interface method and does not appear here."

        return if (satisfied.isEmpty()) {
            base
        } else {
            "$base $owner satisfies ${satisfied.joinToString(", ") { it.reference }}, so a " +
                "call through any of those is not included."
        }
    }
}
