package dev.salatmaster.golandmcp.go

import com.intellij.openapi.project.Project
import dev.salatmaster.golandmcp.common.SymbolRef

/** One interface method paired with how (or whether) a type provides it. */
data class GoMethodRequirement(
    val name: String,
    val signature: String,
    val satisfiedBy: String?,
    val pointerReceiverOnly: Boolean,
)

/**
 * Why a type does or does not satisfy an interface.
 *
 * [checkedAs] reports which form actually satisfies it: `T` when value receivers
 * suffice, `*T` when at least one method needs a pointer receiver. Go's compiler
 * says only "does not implement"; this is the detail it withholds.
 */
data class GoSatisfaction(
    val satisfied: Boolean,
    val checkedAs: String,
    val missing: List<String>,
    val pointerOnly: List<String>,
    /**
     * Of [pointerOnly], the methods the type does not declare — it gets them from an
     * embedded field whose type is not a pointer. The fix is a different one (embed `*T`),
     * and the method is usually in another package where "add a value receiver" is not
     * advice the caller can act on.
     */
    val pointerOnlyPromoted: List<String> = emptyList(),
    val mismatched: List<GoMethodRequirement>,
    /** Signatures of the missing methods, in interface order, ready for stub generation. */
    val missingSignatures: List<GoMethodRequirement> = emptyList(),
)

/**
 * The outcome of checking one type against one interface.
 *
 * "No such type", "no such interface" and "found, and here is why it does not satisfy" are
 * different facts for the caller: the first two mean *try another spelling*, the third means
 * *stop, you have your answer*. Collapsing them into one empty result is undiagnosable.
 */
sealed interface GoCheckOutcome {
    data class Checked(val satisfaction: GoSatisfaction) : GoCheckOutcome
    data object TypeNotFound : GoCheckOutcome
    data object InterfaceNotFound : GoCheckOutcome
    data object NotAnInterface : GoCheckOutcome
}

/** An interface a given type satisfies. */
data class GoSatisfiedInterface(
    val interfaceName: String,
    val qualifiedName: String,
    /** `<import path>.<Type>`, accepted by every tool here. */
    val reference: String,
    val packagePath: String,
    val location: String,
    val requiresPointer: Boolean,
)

/**
 * The result of a search that is allowed to give up.
 *
 * [complete] is false when the scan stopped at its own cap rather than because it ran out of
 * candidates. A partial answer presented as the whole truth is the failure these tools are
 * built to avoid — with this flag the caller can say so, and fall back to checking one named
 * type with go_interface_check.
 */
data class GoSearch<T>(
    val items: List<T>,
    val complete: Boolean,
    /**
     * What the search itself needs to say about its answer, when an empty list would
     * otherwise be read as "nothing matches". Empty when there is nothing to add.
     */
    val note: String = "",
)

data class GoImplementor(
    val typeName: String,
    val qualifiedName: String,
    /** `<import path>.<Type>`, accepted by every tool here. */
    val reference: String,
    val packagePath: String,
    val location: String,
    val requiresPointer: Boolean,
)

/**
 * Interface satisfaction analysis. Call inside a read action.
 *
 * Every entry point takes a [SymbolRef], so these tools accept the same references as
 * go_symbol and its siblings rather than a bare name their own resolver happened to support.
 */
interface GoInterfaceFacts {
    fun check(project: Project, typeRef: SymbolRef, interfaceRef: SymbolRef): GoCheckOutcome

    /** Null when the interface does not resolve; empty when nothing implements it. */
    fun implementors(project: Project, interfaceRef: SymbolRef, limit: Int): GoSearch<GoImplementor>?

    /**
     * The methods declared on a type, with signatures — the input for extracting an
     * interface from it. Null when the type does not resolve.
     */
    fun methodsOf(project: Project, typeRef: SymbolRef): List<GoMethodRequirement>?

    /**
     * The interfaces a type satisfies — the reverse of [implementors]. Null when the type
     * does not resolve; empty when it satisfies nothing.
     *
     * Empty interfaces are excluded: everything satisfies them, so listing them is noise.
     */
    fun interfacesOf(project: Project, typeRef: SymbolRef, limit: Int): GoSearch<GoSatisfiedInterface>?
}
