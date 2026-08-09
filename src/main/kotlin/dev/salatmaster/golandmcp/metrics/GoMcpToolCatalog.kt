package dev.salatmaster.golandmcp.metrics

import com.intellij.mcpserver.annotations.McpTool
import dev.salatmaster.golandmcp.toolset.BatchToolset
import dev.salatmaster.golandmcp.toolset.GenerationToolset
import dev.salatmaster.golandmcp.toolset.InterfaceToolset
import dev.salatmaster.golandmcp.toolset.PackageToolset
import dev.salatmaster.golandmcp.toolset.RefactoringToolset
import dev.salatmaster.golandmcp.toolset.SymbolToolset
import dev.salatmaster.golandmcp.toolset.ToolchainToolset
import dev.salatmaster.golandmcp.toolset.UsagesToolset

/**
 * Every tool this plugin contributes, read off the annotations rather than kept in a list
 * that would drift.
 *
 * The usage table needs this so a tool the agent has *never* called still appears, with a
 * zero. That absence is the most useful thing the table can show.
 *
 * Java reflection only: `@McpTool` has runtime retention, so this needs no kotlin-reflect,
 * which the plugin deliberately does not ship.
 */
object GoMcpToolCatalog {

    private val toolsets = listOf(
        SymbolToolset::class.java,
        InterfaceToolset::class.java,
        PackageToolset::class.java,
        UsagesToolset::class.java,
        BatchToolset::class.java,
        ToolchainToolset::class.java,
        GenerationToolset::class.java,
        RefactoringToolset::class.java,
    )

    val toolNames: List<String> by lazy {
        toolsets
            .flatMap { it.methods.asSequence().filter { m -> m.isAnnotationPresent(McpTool::class.java) } }
            .map { it.name }
            .distinct()
            .sorted()
    }
}
