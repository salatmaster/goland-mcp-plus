package dev.salatmaster.golandmcp.toolset

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.project
import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.Project
import dev.salatmaster.golandmcp.go.GoFieldInfo
import dev.salatmaster.golandmcp.go.GoFunctionInfo
import dev.salatmaster.golandmcp.go.GoPackagesImpl
import dev.salatmaster.golandmcp.go.GoTypeInfo
import dev.salatmaster.golandmcp.go.GoValueInfo
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.Serializable

@Serializable
data class GoFieldEntry(
    val name: String,
    val type: String,
    /** Raw struct tag, e.g. `json:"id" db:"user_id"`. Empty when absent. */
    val tag: String,
    val embedded: Boolean,
    val exported: Boolean,
)

@Serializable
data class GoFunctionEntry(
    val name: String,
    val signature: String,
    val doc: String,
    val location: String,
    /** Empty for plain functions. */
    val receiver: String,
)

@Serializable
data class GoTypeEntry(
    val name: String,
    val kind: String,
    val underlying: String,
    val doc: String,
    val location: String,
    val fields: List<GoFieldEntry>,
    val methods: List<GoFunctionEntry>,
)

@Serializable
data class GoValueEntry(
    val name: String,
    val type: String,
    val doc: String,
    val location: String,
)

@Serializable
data class GoPackageApiResult(
    val packagePath: String,
    val packageName: String,
    val files: List<String>,
    val types: List<GoTypeEntry>,
    val functions: List<GoFunctionEntry>,
    val constants: List<GoValueEntry>,
    val variables: List<GoValueEntry>,
    val truncated: Boolean,
    val hint: String,
)

class PackageToolset : McpToolset {

    private val packages = GoPackagesImpl()

    @McpTool
    @McpDescription(
        "Summarise a Go package's API: its types with fields and struct tags, methods, " +
            "functions, constants and variables, each with its doc comment and location. " +
            "A Go package spans many files, so this replaces opening them one by one. " +
            "Works for project packages, dependencies and the standard library.",
    )
    suspend fun go_package_api(
        @McpDescription(
            "Import path ('example.com/basic'), a project-relative directory, or a bare " +
                "package name",
        )
        packageReference: String,
        @McpDescription("Include unexported declarations")
        includeUnexported: Boolean,
        @McpDescription("Maximum number of entries per category (types, functions, and so on)")
        limit: Int,
    ): GoPackageApiResult =
        packageApi(currentCoroutineContext().project, packageReference, includeUnexported, limit)

    /** Testable core; the project is explicit so tests need no MCP call context. */
    internal suspend fun packageApi(
        project: Project,
        packageReference: String,
        includeUnexported: Boolean,
        limit: Int,
    ): GoPackageApiResult {
        if (limit <= 0) mcpFail("limit must be positive, got $limit")

        val api = readAction { packages.api(project, packageReference, includeUnexported) }
            ?: mcpFail(
                "No Go package matches '$packageReference'. Try its import path " +
                    "(e.g. 'net/http'), a directory relative to the project root, or the " +
                    "bare package name.",
            )

        // A standard-library package can carry hundreds of declarations; returning all of
        // them would cost more context than reading the source the agent was avoiding.
        val truncated = listOf(api.types, api.functions, api.constants, api.variables)
            .any { it.size > limit }

        return GoPackageApiResult(
            packagePath = api.packagePath,
            packageName = api.packageName,
            files = api.files,
            types = api.types.take(limit).map { it.toEntry() },
            functions = api.functions.take(limit).map { it.toEntry() },
            constants = api.constants.take(limit).map { it.toEntry() },
            variables = api.variables.take(limit).map { it.toEntry() },
            truncated = truncated,
            hint = if (truncated) {
                "Some categories were cut to $limit entries. Raise limit, or use go_symbol " +
                    "to look up a specific declaration."
            } else {
                ""
            },
        )
    }
}

private fun GoFieldInfo.toEntry() = GoFieldEntry(name, type, tag, embedded, exported)

private fun GoFunctionInfo.toEntry() = GoFunctionEntry(name, signature, doc, location, receiver)

private fun GoTypeInfo.toEntry() = GoTypeEntry(
    name = name,
    kind = kind.name,
    underlying = underlying,
    doc = doc,
    location = location,
    fields = fields.map { it.toEntry() },
    methods = methods.map { it.toEntry() },
)

private fun GoValueInfo.toEntry() = GoValueEntry(name, type, doc, location)
