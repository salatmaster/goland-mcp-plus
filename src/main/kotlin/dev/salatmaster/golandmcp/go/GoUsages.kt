package dev.salatmaster.golandmcp.go

import com.intellij.openapi.project.Project
import dev.salatmaster.golandmcp.common.SymbolRef

enum class GoUsageKind { CALL, WRITE, READ, IMPORT, DECLARATION }

data class GoUsage(
    val kind: GoUsageKind,
    val location: String,
    /** The source line, trimmed. */
    val snippet: String,
    val inTestFile: Boolean,
    val packagePath: String,
)

data class GoUsagesResult(
    val target: String,
    val usages: List<GoUsage>,
    val truncated: Boolean,
)

/** Reference search for Go symbols. Call inside a read action. */
interface GoUsages {
    /** Returns null when the reference resolves to nothing. */
    fun find(
        project: Project,
        ref: SymbolRef,
        includeTests: Boolean,
        limit: Int,
    ): GoUsagesResult?
}
