package dev.salatmaster.golandmcp.go

import com.intellij.openapi.project.Project

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
    val mismatched: List<GoMethodRequirement>,
    /** Signatures of the missing methods, in interface order, ready for stub generation. */
    val missingSignatures: List<GoMethodRequirement> = emptyList(),
)

/** An interface a given type satisfies. */
data class GoSatisfiedInterface(
    val interfaceName: String,
    val qualifiedName: String,
    val packagePath: String,
    val location: String,
    val requiresPointer: Boolean,
)

data class GoImplementor(
    val typeName: String,
    val qualifiedName: String,
    val packagePath: String,
    val location: String,
    val requiresPointer: Boolean,
)

/** Interface satisfaction analysis. Call inside a read action. */
interface GoInterfaceFacts {
    /** Returns null when either name does not resolve. */
    fun check(project: Project, typeSpecName: String, interfaceName: String): GoSatisfaction?

    fun implementors(project: Project, interfaceName: String, limit: Int): List<GoImplementor>

    /**
     * The methods declared on a type, with signatures — the input for extracting an
     * interface from it.
     */
    fun methodsOf(project: Project, typeName: String): List<GoMethodRequirement>

    /**
     * The interfaces a type satisfies — the reverse of [implementors].
     *
     * Empty interfaces are excluded: everything satisfies them, so listing them is noise.
     */
    fun interfacesOf(project: Project, typeName: String, limit: Int): List<GoSatisfiedInterface>
}
