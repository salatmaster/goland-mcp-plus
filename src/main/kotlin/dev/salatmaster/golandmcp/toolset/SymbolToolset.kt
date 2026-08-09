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
import dev.salatmaster.golandmcp.go.GoLookupResult
import dev.salatmaster.golandmcp.go.GoSymbolInfo
import dev.salatmaster.golandmcp.go.GoSourceResult
import dev.salatmaster.golandmcp.go.GoSymbolsImpl
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.Serializable

@Serializable
data class GoSymbolResult(
    val kind: String,
    val name: String,
    val qualifiedName: String,
    val packagePath: String,
    val signature: String,
    val doc: String,
    val location: String,
    val exported: Boolean,
    val deprecated: Boolean,
    /** Populated only when the reference matched more than one symbol. */
    val candidates: List<String>,
)

@Serializable
data class GoSourceOfResult(
    val qualifiedName: String,
    val packagePath: String,
    val location: String,
    val doc: String,
    val source: String,
    /** True when the declaration is outside the project (SDK or a dependency). */
    val external: Boolean,
)

@Serializable
data class GoDocResult(
    val qualifiedName: String,
    val packagePath: String,
    val location: String,
    val signature: String,
    val doc: String,
)

class SymbolToolset : McpToolset {

    private val symbols = GoSymbolsImpl()

    @McpTool
    @McpDescription(
        "Look up a Go symbol and return its signature, doc comment, declaration site, " +
            "and whether it is exported. Accepts references such as 'net/http.Client.Do', " +
            "'./internal/store.Store', 'Handler.ServeHTTP', or a bare name. Prefer this over " +
            "text search: it resolves through the Go type system, so it does not confuse " +
            "same-named symbols in different packages.",
    )
    suspend fun go_symbol(
        @McpDescription("Symbol reference, e.g. 'net/http.Client.Do' or 'Rect.Area'")
        reference: String,
    ): GoSymbolResult = symbolInfo(currentCoroutineContext().project, reference)

    /**
     * Testable core.
     *
     * The project is explicit here because injecting one into the coroutine context would
     * require constructing `McpCallInfo`, which depends on an internal class of the MCP
     * server plugin.
     */
    internal suspend fun symbolInfo(project: Project, reference: String): GoSymbolResult {
        val ref = try {
            parseSymbolRef(reference)
        } catch (e: SymbolRefParseException) {
            mcpFail(e.message ?: "Could not parse '$reference'")
        }

        return when (val result = readAction { symbols.lookup(project, ref) }) {
            is GoLookupResult.Found -> result.symbol.toResult(emptyList())
            is GoLookupResult.Ambiguous -> result.candidates.first()
                .toResult(result.candidates.map { "${it.qualifiedName} (${it.location})" })
            GoLookupResult.NotFound -> mcpFail(
                "No Go symbol matches '$reference'. Check the spelling, or qualify it with a " +
                    "package path such as 'net/http.Client'.",
            )
        }
    }

    @McpTool
    @McpDescription(
        "Read the doc comment and signature of a Go symbol, including symbols from the " +
            "standard library and dependencies. Use this instead of recalling an API from " +
            "memory: it reports what the installed version actually declares.",
    )
    suspend fun go_doc(
        @McpDescription("Symbol reference, e.g. 'net/http.Client.Do' or 'Rect.Area'")
        reference: String,
    ): GoDocResult = doc(currentCoroutineContext().project, reference)

    /** Testable core; the project is explicit so tests need no MCP call context. */
    internal suspend fun doc(project: Project, reference: String): GoDocResult {
        val info = symbolInfo(project, reference)
        return GoDocResult(
            qualifiedName = info.qualifiedName,
            packagePath = info.packagePath,
            location = info.location,
            signature = info.signature,
            doc = info.doc,
        )
    }

    @McpTool
    @McpDescription(
        "Return the full source of a Go symbol's declaration, including symbols from the " +
            "standard library and dependencies. Those live in the module cache rather than " +
            "the project tree, so reading them normally means knowing where the toolchain " +
            "put them.",
    )
    suspend fun go_source_of(
        @McpDescription("Symbol reference, e.g. 'net/http.Client' or 'Rect.Area'")
        reference: String,
    ): GoSourceOfResult = sourceOf(currentCoroutineContext().project, reference)

    /** Testable core; the project is explicit so tests need no MCP call context. */
    internal suspend fun sourceOf(project: Project, reference: String): GoSourceOfResult {
        val ref = try {
            parseSymbolRef(reference)
        } catch (e: SymbolRefParseException) {
            mcpFail(e.message ?: "Could not parse '$reference'")
        }

        val result = readAction { symbols.sourceOf(project, ref) }
            ?: mcpFail(
                "No Go symbol matches '$reference'. Check the spelling, or qualify it with a " +
                    "package path such as 'net/http.Client'.",
            )
        return result.toResult()
    }
}

private fun GoSourceResult.toResult() = GoSourceOfResult(
    qualifiedName = qualifiedName,
    packagePath = packagePath,
    location = location,
    doc = doc,
    source = source,
    external = external,
)

private fun GoSymbolInfo.toResult(candidates: List<String>) = GoSymbolResult(
    kind = kind.name,
    name = name,
    qualifiedName = qualifiedName,
    packagePath = packagePath,
    signature = signature,
    doc = doc.orEmpty(),
    location = location,
    exported = exported,
    deprecated = deprecated,
    candidates = candidates,
)
