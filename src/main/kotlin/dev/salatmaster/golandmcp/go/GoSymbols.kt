package dev.salatmaster.golandmcp.go

import com.intellij.openapi.project.Project
import dev.salatmaster.golandmcp.common.SymbolRef

/**
 * Symbol lookup over the Go stub indices.
 *
 * Implementations touch `com.goide.*`; callers must not. Call inside a read action.
 */
interface GoSymbols {
    fun lookup(project: Project, ref: SymbolRef): GoLookupResult
}
