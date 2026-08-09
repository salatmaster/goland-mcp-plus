package dev.salatmaster.golandmcp.go

import com.goide.psi.GoExpression
import com.goide.psi.GoMethodDeclaration
import com.goide.psi.GoMethodSpec
import com.goide.psi.GoNamedSignatureOwner
import com.goide.psi.GoType
import com.goide.psi.impl.GoElementFactory
import com.goide.refactor.changeSignature.GoChangeSignatureBuilder
import com.goide.refactor.changeSignature.GoParameterInfo
import com.goide.util.GoZeroValue
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.project.Project
import dev.salatmaster.golandmcp.common.SymbolRef

/**
 * One parameter or result of a requested new signature.
 *
 * [fromIndex] is what makes the refactoring safe: it names the entry of the *current*
 * signature that this one continues, so arguments already written at call sites move with
 * it instead of being dropped. [NEW_ENTRY] marks an entry that has no predecessor.
 */
data class GoParameterChange(
    val fromIndex: Int,
    val name: String,
    val type: String,
    val variadic: Boolean,
    val defaultValue: String,
) {
    companion object {
        const val NEW_ENTRY = -1
    }
}

sealed interface GoSignatureChangeOutcome {
    /** The refactoring ran; [before] and [after] are the rendered signatures. */
    data class Done(val before: String, val after: String) : GoSignatureChangeOutcome

    /** The request describes the signature the symbol already has, so nothing was touched. */
    data class Unchanged(val signature: String) : GoSignatureChangeOutcome

    /** The request could not be carried out; nothing was touched. */
    data class Rejected(val reason: String) : GoSignatureChangeOutcome

    /** The refactoring started and threw, so the code may be partly rewritten. */
    data class Failed(val before: String, val reason: String) : GoSignatureChangeOutcome
}

/**
 * Rewrites a signature through the IDE's own change-signature refactoring.
 *
 * Must be called on the EDT, and not from inside a write action: the refactoring processor
 * takes one itself. Everything that reads or builds PSI happens in a read action; only
 * [GoChangeSignatureBuilder.run] runs outside it.
 */
internal fun changeGoSignature(
    project: Project,
    symbols: GoSymbols,
    ref: SymbolRef,
    newName: String,
    parameters: List<GoParameterChange>,
    results: List<GoParameterChange>,
    updateImplementations: Boolean,
): GoSignatureChangeOutcome = guardGoApi("change signature") {
    val plan = runReadActionBlocking {
        planSignatureChange(project, symbols, ref, newName, parameters, results, updateImplementations)
    }

    when (plan) {
        is Plan.Rejected -> GoSignatureChangeOutcome.Rejected(plan.reason)
        is Plan.Unchanged -> GoSignatureChangeOutcome.Unchanged(plan.signature)
        is Plan.Ready ->
            runCatchingCancellable { plan.builder.run() }.fold(
                onSuccess = {
                    val after = runReadActionBlocking {
                        if (plan.owner.isValid) renderSignature(plan.owner) else ""
                    }
                    GoSignatureChangeOutcome.Done(
                        plan.before,
                        after.ifEmpty { "(applied; re-read the declaration to see it)" },
                    )
                },
                onFailure = { error ->
                    GoSignatureChangeOutcome.Failed(
                        plan.before,
                        "${error::class.simpleName}: ${error.message}",
                    )
                },
            )
    }
}

private sealed interface Plan {
    data class Ready(
        val builder: GoChangeSignatureBuilder,
        val owner: GoNamedSignatureOwner,
        val before: String,
    ) : Plan

    data class Unchanged(val signature: String) : Plan
    data class Rejected(val reason: String) : Plan
}

/** Either a built parameter, or the reason the request cannot be honoured. */
private sealed interface Built {
    data class Ok(val info: GoParameterInfo) : Built
    data class Bad(val reason: String) : Built
}

/**
 * Resolves the symbol, checks the request against its current signature, and configures the
 * refactoring. Every failure path returns [Plan.Rejected] before anything is modified.
 */
private fun planSignatureChange(
    project: Project,
    symbols: GoSymbols,
    ref: SymbolRef,
    newName: String,
    parameters: List<GoParameterChange>,
    results: List<GoParameterChange>,
    updateImplementations: Boolean,
): Plan {
    val declaration = symbols.declaration(project, ref)
        ?: return Plan.Rejected("no such symbol")
    val owner = declaration as? GoNamedSignatureOwner
        ?: return Plan.Rejected(
            "'${declaration.name}' is not a function, method or interface method, so it has " +
                "no signature to change",
        )
    val signature = owner.signature
        ?: return Plan.Rejected("'${owner.name}' has no signature the IDE can read")

    val before = renderSignature(owner)
    val currentParameters = GoChangeSignatureBuilder.getParameters(signature)
    val currentResults = GoChangeSignatureBuilder.getResultParameters(signature)

    if (newName.isNotEmpty() && !isGoIdentifier(newName)) {
        return Plan.Rejected("'$newName' is not a valid Go identifier")
    }
    val targetName = newName.ifEmpty { owner.name.orEmpty() }

    invalidIndices("parameter", parameters, currentParameters.size, before)?.let {
        return Plan.Rejected(it)
    }
    invalidIndices("result", results, currentResults.size, before)?.let {
        return Plan.Rejected(it)
    }
    results.firstOrNull { it.variadic }?.let {
        return Plan.Rejected("a result cannot be variadic; only the last parameter can be")
    }
    parameters.dropLast(1).firstOrNull { it.variadic }?.let {
        return Plan.Rejected(
            "only the last parameter can be variadic, but '${describe(it)}' is not last",
        )
    }
    inconsistentNaming("parameters", parameters)?.let { return Plan.Rejected(it) }
    inconsistentNaming("results", results)?.let { return Plan.Rejected(it) }
    duplicateName(parameters + results)?.let {
        return Plan.Rejected("'$it' is declared twice in the requested signature")
    }

    val newParameters = mutableListOf<GoParameterInfo>()
    for (entry in parameters) {
        when (val built = buildInfo(project, owner, entry, currentParameters, isResult = false)) {
            is Built.Bad -> return Plan.Rejected(built.reason)
            is Built.Ok -> newParameters += built.info
        }
    }
    val newResults = mutableListOf<GoParameterInfo>()
    for (entry in results) {
        when (val built = buildInfo(project, owner, entry, currentResults, isResult = true)) {
            is Built.Bad -> return Plan.Rejected(built.reason)
            is Built.Ok -> newResults += built.info
        }
    }

    if (targetName == owner.name &&
        isUnchanged(parameters, currentParameters) &&
        isUnchanged(results, currentResults)
    ) {
        return Plan.Unchanged(before)
    }

    val builder = GoChangeSignatureBuilder.create(owner)
        .withNewMethodName(targetName)
        .withParameters(newParameters)
        .withResultParameters(newResults)
        .refactorImplementations(updateImplementations)

    return Plan.Ready(builder, owner, before)
}

private fun buildInfo(
    project: Project,
    context: GoNamedSignatureOwner,
    entry: GoParameterChange,
    current: List<GoParameterInfo>,
    isResult: Boolean,
): Built {
    val what = if (isResult) "result" else "parameter"

    if (entry.name.isNotEmpty() && !isGoIdentifier(entry.name)) {
        return Built.Bad("$what name '${entry.name}' is not a valid Go identifier")
    }
    if (entry.type.isBlank()) {
        return Built.Bad("$what '${describe(entry)}' has no type")
    }
    if (entry.type.trimStart().startsWith("...")) {
        return Built.Bad(
            "write the plain element type for '${describe(entry)}' and set variadic " +
                "instead of spelling '...'",
        )
    }

    val goType = GoElementFactory.createType(project, entry.type, context)
        ?: return Built.Bad("'${entry.type}' is not a Go type the parser accepts")
    // The parser stops at the first construct it understands and reports nothing, so
    // 'x ((((' silently becomes 'x'. Comparing the text back is what catches that.
    if (normalize(goType.text) != normalize(entry.type)) {
        return Built.Bad(
            "'${entry.type}' is not a well-formed Go type; the parser read it as '${goType.text}'",
        )
    }

    if (entry.fromIndex != GoParameterChange.NEW_ENTRY) {
        val existing = current[entry.fromIndex]
        if (matches(entry, existing)) return Built.Ok(existing)
        val updated = GoParameterInfo(existing, goType, entry.variadic, null)
        updated.name = entry.name
        return Built.Ok(updated)
    }

    val default = when (val resolved = defaultExpression(project, context, entry, goType, isResult)) {
        is Defaulted.Bad -> return Built.Bad(resolved.reason)
        is Defaulted.Use -> resolved.expression
    }
    val fresh = GoParameterInfo(GoParameterChange.NEW_ENTRY, entry.name, goType, entry.variadic)
    return Built.Ok(
        if (default == null) fresh else GoParameterInfo(fresh, goType, entry.variadic, default),
    )
}

private sealed interface Defaulted {
    /** Null means: let the refactoring derive the value itself. */
    data class Use(val expression: GoExpression?) : Defaulted
    data class Bad(val reason: String) : Defaulted
}

/**
 * What a new entry contributes at existing call sites, or in existing return statements.
 *
 * The refactoring can derive this itself, but only from a type it can resolve; for anything
 * else it writes nothing and leaves every call site short an argument. Refusing here is the
 * difference between a clear error and code that no longer compiles.
 */
private fun defaultExpression(
    project: Project,
    context: GoNamedSignatureOwner,
    entry: GoParameterChange,
    goType: GoType,
    isResult: Boolean,
): Defaulted {
    if (entry.defaultValue.isNotBlank()) {
        val expression = GoElementFactory.tryToCreateExpression(project, entry.defaultValue, context)
            ?: return Defaulted.Bad(
                "defaultValue '${entry.defaultValue}' is not a Go expression the parser accepts",
            )
        if (normalize(expression.text) != normalize(entry.defaultValue)) {
            return Defaulted.Bad(
                "defaultValue '${entry.defaultValue}' is not a well-formed Go expression; " +
                    "the parser read it as '${expression.text}'",
            )
        }
        return Defaulted.Use(expression)
    }

    // A variadic parameter may simply be omitted at a call site, and a named result is
    // already in scope inside the function, so neither needs anything written for it.
    if (entry.variadic) return Defaulted.Use(null)
    if (isResult && entry.name.isNotEmpty()) return Defaulted.Use(null)
    if (!GoZeroValue.of(goType)?.text.isNullOrEmpty()) return Defaulted.Use(null)

    val what = if (isResult) "result" else "parameter"
    val site = if (isResult) "return statements" else "call sites"
    return Defaulted.Bad(
        "the new $what '${describe(entry)}' needs a defaultValue: the zero value of " +
            "'${entry.type}' cannot be determined here, so existing $site would be left " +
            "incomplete and stop compiling",
    )
}

private fun invalidIndices(
    what: String,
    entries: List<GoParameterChange>,
    currentSize: Int,
    before: String,
): String? {
    val seen = mutableSetOf<Int>()
    for (entry in entries) {
        if (entry.fromIndex == GoParameterChange.NEW_ENTRY) continue
        if (entry.fromIndex < 0 || entry.fromIndex >= currentSize) {
            return "fromIndex ${entry.fromIndex} is out of range: '$before' has $currentSize " +
                "${what}(s), so valid values are 0..${currentSize - 1}, or " +
                "${GoParameterChange.NEW_ENTRY} for a new one"
        }
        if (!seen.add(entry.fromIndex)) {
            return "fromIndex ${entry.fromIndex} is used twice: one $what of the current " +
                "signature cannot become two"
        }
    }
    return null
}

/** Go requires every parameter, and every result, to be either all named or all unnamed. */
private fun inconsistentNaming(what: String, entries: List<GoParameterChange>): String? {
    if (entries.size < 2) return null
    val named = entries.count { it.name.isNotEmpty() }
    if (named == 0 || named == entries.size) return null
    return "Go requires $what to be either all named or all unnamed, but $named of " +
        "${entries.size} carry a name"
}

private fun duplicateName(entries: List<GoParameterChange>): String? =
    entries.map { it.name }
        .filter { it.isNotEmpty() && it != "_" }
        .groupingBy { it }
        .eachCount()
        .entries
        .firstOrNull { it.value > 1 }
        ?.key

private fun matches(entry: GoParameterChange, existing: GoParameterInfo): Boolean =
    entry.name == existing.name.orEmpty() &&
        normalize(entry.type) == normalize(existing.typeText.orEmpty()) &&
        entry.variadic == existing.isVariadic

private fun isUnchanged(entries: List<GoParameterChange>, current: List<GoParameterInfo>): Boolean =
    entries.size == current.size &&
        entries.withIndex().all { (position, entry) ->
            entry.fromIndex == position && matches(entry, current[position])
        }

private fun renderSignature(owner: GoNamedSignatureOwner): String {
    val prefix = if (owner is GoMethodSpec) "" else "func "
    val receiver = (owner as? GoMethodDeclaration)?.receiver?.text?.let { "$it " }.orEmpty()
    return "$prefix$receiver${owner.name.orEmpty()}${owner.signature?.text.orEmpty()}"
}

private fun describe(entry: GoParameterChange): String =
    listOf(entry.name, entry.type).filter { it.isNotEmpty() }.joinToString(" ")

private fun normalize(text: String): String = text.trim().replace(WHITESPACE, " ")

private fun isGoIdentifier(name: String): Boolean =
    name.isNotEmpty() &&
        name !in GO_KEYWORDS &&
        (name[0].isLetter() || name[0] == '_') &&
        name.all { it.isLetterOrDigit() || it == '_' }

private val WHITESPACE = Regex("\\s+")

private val GO_KEYWORDS = setOf(
    "break", "case", "chan", "const", "continue", "default", "defer", "else", "fallthrough",
    "for", "func", "go", "goto", "if", "import", "interface", "map", "package", "range",
    "return", "select", "struct", "switch", "type", "var",
)
