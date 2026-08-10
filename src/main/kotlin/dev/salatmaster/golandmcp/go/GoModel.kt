package dev.salatmaster.golandmcp.go

enum class GoSymbolKind { TYPE, INTERFACE, FUNC, METHOD, CONST, VAR }

/** A Go symbol described without any reference to PSI, so callers stay decoupled. */
data class GoSymbolInfo(
    val kind: GoSymbolKind,
    val name: String,
    /** Human-readable, as the Go plugin writes it. Not guaranteed to identify the symbol. */
    val qualifiedName: String,
    /**
     * A reference every tool here accepts, and that identifies exactly this symbol:
     * `<import path>.<Type>[.<Member>]`.
     *
     * [qualifiedName] cannot do that job — for a method the Go plugin drops the package, so
     * `Client.CancelInvoice` is ambiguous in any project with more than one `Client`. Chaining
     * one tool's output into the next is the normal way an agent works, so an identifier that
     * does not round-trip is a defect rather than a cosmetic detail.
     */
    val reference: String,
    val packagePath: String,
    val signature: String,
    val doc: String?,
    val location: String,
    val exported: Boolean,
    val deprecated: Boolean,
)

sealed interface GoLookupResult {
    data class Found(val symbol: GoSymbolInfo) : GoLookupResult
    data class Ambiguous(val candidates: List<GoSymbolInfo>) : GoLookupResult
    data object NotFound : GoLookupResult
}

/** A symbol's declaration as written, plus where it lives. */
data class GoSourceResult(
    val qualifiedName: String,
    /** Round-trippable reference; see [GoSymbolInfo.reference]. */
    val reference: String,
    val packagePath: String,
    val location: String,
    val doc: String,
    val source: String,
    /** True when the declaration is outside the project (SDK or a dependency). */
    val external: Boolean,
)
