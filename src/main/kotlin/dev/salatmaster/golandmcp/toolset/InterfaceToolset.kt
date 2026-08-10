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
import dev.salatmaster.golandmcp.go.GoCheckOutcome
import dev.salatmaster.golandmcp.go.GoInterfaceFactsImpl
import dev.salatmaster.golandmcp.go.GoSatisfaction
import dev.salatmaster.golandmcp.metrics.tracked
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.Serializable

@Serializable
data class GoImplementationEntry(
    val typeName: String,
    val qualifiedName: String,
    /** A reference every tool here accepts and that identifies exactly this symbol. */
    val reference: String,
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
    /** A reference every tool here accepts and that identifies exactly this symbol. */
    val reference: String,
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

    /** Parses a reference the same way every other tool does, failing with its advice. */
    private fun reference(raw: String) = try {
        parseSymbolRef(raw)
    } catch (e: SymbolRefParseException) {
        mcpFail(e.message ?: "Could not parse '$raw'")
    }

    private val facts = GoInterfaceFactsImpl()

    @McpTool
    @McpDescription(
        "List the Go types that implement an interface. Go interfaces are satisfied " +
            "structurally with no 'implements' keyword, so text search cannot answer this. " +
            "Includes types that implement it without declaring a single method, by " +
            "embedding a type or the interface itself. Each result states whether the value " +
            "type satisfies the interface or only its pointer form does.",
    )
    suspend fun go_implementations(
        @McpDescription("Interface name, e.g. 'Shape' or 'Reader'")
        interfaceName: String,
        @McpDescription("Maximum number of implementations to return")
        limit: Int,
    ): GoImplementationsResult =
        tracked("go_implementations") {
            implementations(currentCoroutineContext().project, interfaceName, limit)
        }

    /** Testable core; the project is explicit so tests need no MCP call context. */
    internal suspend fun implementations(
        project: Project,
        interfaceName: String,
        limit: Int,
    ): GoImplementationsResult {
        if (limit <= 0) mcpFail("limit must be positive, got $limit")

        // Fetch one extra to detect truncation without running the search twice.
        val search = readAction { facts.implementors(project, reference(interfaceName), limit + 1) }
            ?: mcpFail(
                "No Go interface matches '$interfaceName'. Check the spelling, or qualify it " +
                    "with its package, e.g. 'orders.billingService'.",
            )
        val found = search.items

        // Deliberately no count of what was dropped. The search stops at limit + 1, so any
        // such number would always be 1 — for a widely implemented interface like io.Reader
        // that would report "1 more" when hundreds remain. Stating the fact and how to act on
        // it beats stating a confident falsehood.
        //
        // A scan that hit its own cap is truncation too. Reporting that case as a complete
        // list is what made an incomplete answer indistinguishable from an exhaustive one.
        val truncated = found.size > limit || !search.complete
        return GoImplementationsResult(
            interfaceName = interfaceName,
            implementations = found.take(limit).map {
                GoImplementationEntry(
                    typeName = it.typeName,
                    qualifiedName = it.qualifiedName,
                    reference = it.reference,
                    packagePath = it.packagePath,
                    location = it.location,
                    requiresPointer = it.requiresPointer,
                )
            },
            truncated = truncated,
            hint = buildList {
                if (found.size > limit) {
                    add(
                        "More implementations exist than were returned; the count is unknown. " +
                            "Project types are listed first. Raise limit to see more.",
                    )
                }
                if (!search.complete) {
                    add(
                        "The search stopped at its candidate cap, so implementations may be " +
                            "missing from this list. To settle one type, call " +
                            "go_interface_check with its name.",
                    )
                }
                if (search.note.isNotEmpty()) {
                    add(search.note)
                } else if (found.isEmpty() && search.complete) {
                    add("'$interfaceName' resolves, and no type in this project implements it.")
                }
            }.joinToString(" "),
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
        tracked("go_interfaces_of") {
            interfacesOf(currentCoroutineContext().project, typeName, limit)
        }

    /** Testable core; the project is explicit so tests need no MCP call context. */
    internal suspend fun interfacesOf(
        project: Project,
        typeName: String,
        limit: Int,
    ): GoInterfacesOfResult {
        if (limit <= 0) mcpFail("limit must be positive, got $limit")

        // Resolution failure and "satisfies nothing" are different answers: the first means
        // try another spelling, the second means stop. Reporting both as one error left the
        // caller unable to tell which.
        val search = readAction { facts.interfacesOf(project, reference(typeName), limit + 1) }
            ?: mcpFail(
                "No Go type matches '$typeName'. Check the spelling, or qualify it with its " +
                    "package, e.g. 'billing.Client'.",
            )
        val found = search.items

        val truncated = found.size > limit || !search.complete
        return GoInterfacesOfResult(
            typeName = typeName,
            interfaces = found.take(limit).map {
                GoSatisfiedInterfaceEntry(
                    interfaceName = it.interfaceName,
                    qualifiedName = it.qualifiedName,
                    reference = it.reference,
                    packagePath = it.packagePath,
                    location = it.location,
                    requiresPointer = it.requiresPointer,
                )
            },
            truncated = truncated,
            hint = buildList {
                if (found.size > limit) {
                    add(
                        "More interfaces exist than were returned; the count is unknown. " +
                            "Project interfaces are listed first. Raise limit to see more.",
                    )
                }
                if (!search.complete) {
                    add(
                        "The search stopped at its candidate cap, so interfaces may be " +
                            "missing from this list. To settle one, call go_interface_check.",
                    )
                }
                if (search.note.isNotEmpty()) {
                    add(search.note)
                } else if (found.isEmpty() && search.complete) {
                    add(
                        "'$typeName' resolves, and satisfies no interface in this project. " +
                            "A type with no methods satisfies only the empty interface.",
                    )
                }
            }.joinToString(" "),
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
        tracked("go_interface_check") {
            interfaceCheck(currentCoroutineContext().project, typeName, interfaceName)
        }

    /** Testable core; the project is explicit so tests need no MCP call context. */
    internal suspend fun interfaceCheck(
        project: Project,
        typeName: String,
        interfaceName: String,
    ): GoInterfaceCheckResult {
        val result = when (
            val outcome = readAction { facts.check(project, reference(typeName), reference(interfaceName)) }
        ) {
            is GoCheckOutcome.Checked -> outcome.satisfaction
            GoCheckOutcome.TypeNotFound -> mcpFail("No Go type matches '$typeName'.")
            GoCheckOutcome.InterfaceNotFound -> mcpFail("No Go type matches '$interfaceName'.")
            GoCheckOutcome.NotAnInterface ->
                mcpFail("'$interfaceName' resolves, but it is not an interface.")
        }

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

        result.pointerOnly.isNotEmpty() -> {
            // "Declares" would be a lie for a promoted method, and so would the advice: the
            // method is usually in another package, and what the caller controls is how the
            // field is embedded.
            val promoted = result.pointerOnlyPromoted
            val declared = result.pointerOnly.filterNot { it in promoted }
            val fixes = buildList {
                add("use *$typeName at the call site")
                if (declared.isNotEmpty()) {
                    add("give ${declared.joinToString(", ")} a value receiver")
                }
                if (promoted.isNotEmpty()) {
                    add(
                        "embed a pointer to the type providing " +
                            promoted.joinToString(", "),
                    )
                }
            }
            "$typeName provides ${result.pointerOnly.joinToString(", ")} only through a " +
                "pointer receiver, so *$typeName satisfies $interfaceName but $typeName " +
                "does not. To fix it, ${fixes.joinToString(", or ")}."
        }

        else ->
            "Signatures differ from what $interfaceName requires. " +
                "Compare the required and actual signatures in signatureMismatches."
    }
