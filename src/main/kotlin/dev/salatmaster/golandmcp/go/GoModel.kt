package dev.salatmaster.golandmcp.go

enum class GoSymbolKind { TYPE, INTERFACE, FUNC, METHOD, CONST, VAR }

/** A Go symbol described without any reference to PSI, so callers stay decoupled. */
data class GoSymbolInfo(
    val kind: GoSymbolKind,
    val name: String,
    val qualifiedName: String,
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
