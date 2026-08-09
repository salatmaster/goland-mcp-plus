package dev.salatmaster.golandmcp.go

import com.goide.psi.GoNamedElement
import com.intellij.openapi.project.Project
import dev.salatmaster.golandmcp.common.SymbolRef

/**
 * Symbol lookup over the Go stub indices.
 *
 * Implementations touch `com.goide.*`; callers must not. Call inside a read action.
 */
interface GoSymbols {
    fun lookup(project: Project, ref: SymbolRef): GoLookupResult

    /**
     * The full source text of a symbol's declaration, with its doc comment.
     *
     * Reading dependency or standard-library code otherwise means knowing where the module
     * cache put it; this resolves the symbol and returns the declaration directly.
     */
    fun sourceOf(project: Project, ref: SymbolRef): GoSourceResult?

    /**
     * The declaration element itself, for callers inside this layer that need PSI —
     * reference search, for one. Stays within `go` so PSI never reaches a toolset.
     */
    fun declaration(project: Project, ref: SymbolRef): GoNamedElement?
}
