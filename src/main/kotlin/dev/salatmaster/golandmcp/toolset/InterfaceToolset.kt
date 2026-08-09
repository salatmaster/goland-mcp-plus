package dev.salatmaster.golandmcp.toolset

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.project
import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.Project
import dev.salatmaster.golandmcp.go.GoInterfaceFactsImpl
import dev.salatmaster.golandmcp.go.GoSatisfaction
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.Serializable

@Serializable
data class GoImplementationEntry(
    val typeName: String,
    val qualifiedName: String,
    val packagePath: String,
    val location: String,
    /** True when only the pointer form satisfies the interface. */
    val requiresPointer: Boolean,
)

@Serializable
data class GoImplementationsResult(
    val interfaceName: String,
    val implementations: List<GoImplementationEntry>,
    /** True when more implementations exist than were returned. */
    val truncated: Boolean,
    /** How to narrow or widen the query; empty when the list is complete. */
    val hint: String,
)

@Serializable
data class GoSatisfiedInterfaceEntry(
    val interfaceName: String,
    val qualifiedName: String,
    val packagePath: String,
    val location: String,
    /** True when only the pointer form satisfies this interface. */
    val requiresPointer: Boolean,
)

@Serializable
data class GoInterfacesOfResult(
    val typeName: String,
    val interfaces: List<GoSatisfiedInterfaceEntry>,
    val truncated: Boolean,
    val hint: String,
)

@Serializable
data class GoSignatureMismatch(
    val method: String,
    val required: String,
    val actual: String,
)

@Serializable
data class GoInterfaceCheckResult(
    val satisfied: Boolean,
    val typeName: String,
    val interfaceName: String,
    /** Which form satisfies the interface: `T` or `*T`. */
    val checkedAs: String,
    val missingMethods: List<String>,
    val pointerReceiverOnly: List<String>,
    val signatureMismatches: List<GoSignatureMismatch>,
    /** Plain-language next step; empty when the type already satisfies. */
    val hint: String,
)

class InterfaceToolset : McpToolset {

    private val facts = GoInterfaceFactsImpl()

    @McpTool
    @McpDescription(
        "List the Go types that implement an interface. Go interfaces are satisfied " +
            "structurally with no 'implements' keyword, so text search cannot answer this. " +
            "Each result states whether the value type satisfies the interface or only its " +
            "pointer form does.",
    )
    suspend fun go_implementations(
        @McpDescription("Interface name, e.g. 'Shape' or 'Reader'")
        interfaceName: String,
        @McpDescription("Maximum number of implementations to return")
        limit: Int,
    ): GoImplementationsResult =
        implementations(currentCoroutineContext().project, interfaceName, limit)

    /** Testable core; the project is explicit so tests need no MCP call context. */
    internal suspend fun implementations(
        project: Project,
        interfaceName: String,
        limit: Int,
    ): GoImplementationsResult {
        if (limit <= 0) mcpFail("limit must be positive, got $limit")

        // Fetch one extra to detect truncation without running the search twice.
        val found = readAction { facts.implementors(project, interfaceName, limit + 1) }
        if (found.isEmpty()) {
            mcpFail(
                "No interface named '$interfaceName' was found, or nothing implements it. " +
                    "Check the name, or qualify it with its package.",
            )
        }

        // Deliberately no count of what was dropped. The search stops at limit + 1, so any
        // such number would always be 1 — for a widely implemented interface like io.Reader
        // that would report "1 more" when hundreds remain. Stating the fact and how to act on
        // it beats stating a confident falsehood.
        val truncated = found.size > limit
        return GoImplementationsResult(
            interfaceName = interfaceName,
            implementations = found.take(limit).map {
                GoImplementationEntry(
                    typeName = it.typeName,
                    qualifiedName = it.qualifiedName,
                    packagePath = it.packagePath,
                    location = it.location,
                    requiresPointer = it.requiresPointer,
                )
            },
            truncated = truncated,
            hint = if (truncated) {
                "More implementations exist than were returned; the count is unknown. " +
                    "Project types are listed first. Raise limit to see more."
            } else {
                ""
            },
        )
    }

    @McpTool
    @McpDescription(
        "List the interfaces a Go type satisfies. Because Go interfaces are structural, a " +
            "type implements them without naming them anywhere, so this cannot be read off " +
            "the source. Useful for learning what a type can be passed as, and which " +
            "contracts a change to its methods would break.",
    )
    suspend fun go_interfaces_of(
        @McpDescription("Type name, e.g. 'Rect'")
        typeName: String,
        @McpDescription("Maximum number of interfaces to return")
        limit: Int,
    ): GoInterfacesOfResult =
        interfacesOf(currentCoroutineContext().project, typeName, limit)

    /** Testable core; the project is explicit so tests need no MCP call context. */
    internal suspend fun interfacesOf(
        project: Project,
        typeName: String,
        limit: Int,
    ): GoInterfacesOfResult {
        if (limit <= 0) mcpFail("limit must be positive, got $limit")

        val found = readAction { facts.interfacesOf(project, typeName, limit + 1) }
        if (found.isEmpty()) {
            mcpFail(
                "No type named '$typeName' was found, or it satisfies no interface. " +
                    "Note that a type with no methods satisfies only the empty interface.",
            )
        }

        val truncated = found.size > limit
        return GoInterfacesOfResult(
            typeName = typeName,
            interfaces = found.take(limit).map {
                GoSatisfiedInterfaceEntry(
                    interfaceName = it.interfaceName,
                    qualifiedName = it.qualifiedName,
                    packagePath = it.packagePath,
                    location = it.location,
                    requiresPointer = it.requiresPointer,
                )
            },
            truncated = truncated,
            hint = if (truncated) {
                "More interfaces exist than were returned; the count is unknown. " +
                    "Project interfaces are listed first. Raise limit to see more."
            } else {
                ""
            },
        )
    }

    @McpTool
    @McpDescription(
        "Check whether a Go type satisfies an interface and, when it does not, report " +
            "exactly why: missing methods, mismatched signatures, or methods declared on a " +
            "pointer receiver. Use this before and after writing an implementation — the Go " +
            "compiler only says 'does not implement' without saying what is wrong.",
    )
    suspend fun go_interface_check(
        @McpDescription("Type name, e.g. 'Circle'")
        typeName: String,
        @McpDescription("Interface name, e.g. 'Shape'")
        interfaceName: String,
    ): GoInterfaceCheckResult =
        interfaceCheck(currentCoroutineContext().project, typeName, interfaceName)

    /** Testable core; the project is explicit so tests need no MCP call context. */
    internal suspend fun interfaceCheck(
        project: Project,
        typeName: String,
        interfaceName: String,
    ): GoInterfaceCheckResult {
        val result = readAction { facts.check(project, typeName, interfaceName) }
            ?: mcpFail(
                "Could not resolve type '$typeName' or interface '$interfaceName'. " +
                    "Both must exist in the project or its dependencies.",
            )

        return GoInterfaceCheckResult(
            satisfied = result.satisfied,
            typeName = typeName,
            interfaceName = interfaceName,
            checkedAs = result.checkedAs,
            missingMethods = result.missing,
            pointerReceiverOnly = result.pointerOnly,
            signatureMismatches = result.mismatched.map {
                GoSignatureMismatch(
                    method = it.name,
                    required = it.signature,
                    actual = it.satisfiedBy.orEmpty(),
                )
            },
            hint = hintFor(typeName, interfaceName, result),
        )
    }
}

/** Turns the analysis into the single next action an agent should take. */
private fun hintFor(typeName: String, interfaceName: String, result: GoSatisfaction): String =
    when {
        result.satisfied -> ""

        result.missing.isNotEmpty() ->
            "$typeName is missing ${result.missing.joinToString(", ")}. " +
                "Add ${if (result.missing.size == 1) "this method" else "these methods"} " +
                "to satisfy $interfaceName."

        result.pointerOnly.isNotEmpty() ->
            "$typeName declares ${result.pointerOnly.joinToString(", ")} on a pointer " +
                "receiver, so *$typeName satisfies $interfaceName but $typeName does not. " +
                "Either use *$typeName at the call site, or change those methods to value " +
                "receivers."

        else ->
            "Signatures differ from what $interfaceName requires. " +
                "Compare the required and actual signatures in signatureMismatches."
    }
