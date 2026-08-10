package dev.salatmaster.golandmcp.toolset

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.project
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.Project
import dev.salatmaster.golandmcp.go.GoFixOutcome
import dev.salatmaster.golandmcp.go.GoInspectionsImpl
import dev.salatmaster.golandmcp.metrics.tracked
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

@Serializable
data class GoFixEntry(
    /** Pass this back to go_apply_quick_fix. */
    val name: String,
    /** False when the fix needs an editor or asks something interactively. */
    val applicable: Boolean,
    /**
     * Why not, when it is not; empty otherwise. Applicable means the fix can be run, not
     * that it will change the file: a few are navigation or settings, and those come back
     * from go_apply_quick_fix as applied=false with the reason.
     */
    val whyNot: String,
)

@Serializable
data class GoProblemEntry(
    val line: Int,
    val severity: String,
    val description: String,
    /** The inspection that reported it. */
    val inspection: String,
    val fixes: List<GoFixEntry>,
)

@Serializable
data class GoQuickFixesResult(
    val path: String,
    val problems: List<GoProblemEntry>,
    val errorCount: Int,
    val hint: String,
)

@Serializable
data class GoQuickFixResult(
    val applied: Boolean,
    val path: String,
    /** What actually changed; empty when nothing was applied. */
    val diff: String,
    val hint: String,
)

class InspectionToolset : McpToolset {

    private val inspections = GoInspectionsImpl()

    @McpTool
    @McpDescription(
        "List what the IDE's Go inspections find in a file, each with the fixes the IDE " +
            "itself offers for it -- what Alt+Enter would put in front of a developer. Every " +
            "fix is named, and go_apply_quick_fix applies it, so the repair comes from the " +
            "IDE rather than from guessing. Run it after editing Go code. This is the " +
            "inspection layer, which is where fixes live; for build and type errors use the " +
            "IDE's own get_file_problems, which reports them but knows no fixes.",
    )
    suspend fun go_quick_fixes(
        @McpDescription("Path to a .go file, relative to the project root")
        path: String,
        @McpDescription("Include weak warnings and hints, not just errors and warnings")
        includeWeak: Boolean,
    ): GoQuickFixesResult =
        tracked("go_quick_fixes") {
            quickFixes(currentCoroutineContext().project, path, includeWeak)
        }

    /** Testable core; the project is explicit so tests need no MCP call context. */
    internal suspend fun quickFixes(
        project: Project,
        path: String,
        includeWeak: Boolean,
    ): GoQuickFixesResult {
        val found = readAction { inspections.problems(project, path, includeWeak) }
            ?: mcpFail(
                "No Go file at '$path'. The path is relative to the project root, and this " +
                    "tool only analyses .go files.",
            )

        val errors = found.count { it.severity == "ERROR" }
        val fixable = found.count { p -> p.fixes.any { it.applicable } }
        return GoQuickFixesResult(
            path = path,
            problems = found.map { problem ->
                GoProblemEntry(
                    line = problem.line,
                    severity = problem.severity,
                    description = problem.description,
                    inspection = problem.inspection,
                    fixes = problem.fixes.map { GoFixEntry(it.name, it.applicable, it.whyNot) },
                )
            },
            errorCount = errors,
            hint = when {
                found.isEmpty() && !includeWeak ->
                    "No errors or warnings. Weak warnings and hints were not included; " +
                        "pass includeWeak to see them."

                fixable > 0 ->
                    "$fixable of these carry a fix the IDE can apply — call " +
                        "go_apply_quick_fix with the line and the fix name."

                else -> ""
            },
        )
    }

    @McpTool
    @McpDescription(
        "Apply one of the fixes go_quick_fixes reported, by line and name. The IDE performs " +
            "its own fix, so the result compiles and matches project style, and the change " +
            "lands on the undo stack. Refuses rather than guessing when the name matches " +
            "nothing, matches two problems on the same line, or names a fix that needs an " +
            "editor or asks something interactively.",
    )
    suspend fun go_apply_quick_fix(
        @McpDescription("Path to a .go file, relative to the project root")
        path: String,
        @McpDescription("Line the problem is on, 1-based, as reported by go_quick_fixes")
        line: Int,
        @McpDescription("Exact fix name from go_quick_fixes, e.g. 'Remove unused import'")
        fixName: String,
    ): GoQuickFixResult =
        tracked("go_apply_quick_fix") {
            applyQuickFix(currentCoroutineContext().project, path, line, fixName)
        }

    /** Testable core; the project is explicit so tests need no MCP call context. */
    internal suspend fun applyQuickFix(
        project: Project,
        path: String,
        line: Int,
        fixName: String,
    ): GoQuickFixResult {
        if (line < 1) mcpFail("line must be 1-based and positive, got $line")

        // Applying a fix mutates PSI, which is a write action, which belongs on the EDT.
        val outcome = withContext(Dispatchers.EDT) {
            inspections.applyFix(project, path, line, fixName)
        }

        return when (outcome) {
            is GoFixOutcome.Applied -> GoQuickFixResult(true, path, outcome.diff, "")

            GoFixOutcome.NoChange -> GoQuickFixResult(
                false, path, "",
                "'$fixName' ran and left the file unchanged. The problem may already be " +
                    "fixed, or this fix needs a context a tool call does not have.",
            )

            GoFixOutcome.FileNotFound -> mcpFail("No file at '$path'.")
            GoFixOutcome.NotAGoFile -> mcpFail("'$path' is not a Go file.")

            is GoFixOutcome.NoSuchFix -> mcpFail(
                if (outcome.available.isEmpty()) {
                    "No problem on line $line of '$path' offers a fix. Call go_quick_fixes to " +
                        "see what is reported there."
                } else {
                    "No fix named '$fixName' on line $line. Available: " +
                        outcome.available.joinToString(", ") { "'$it'" }
                },
            )

            is GoFixOutcome.Ambiguous -> mcpFail(
                "Line $line has more than one problem offering '$fixName': " +
                    outcome.problems.joinToString("; ") +
                    ". Fix them one at a time from a narrower line, or edit directly.",
            )

            is GoFixOutcome.NotApplicable -> GoQuickFixResult(false, path, "", outcome.reason)

            is GoFixOutcome.Failed -> mcpFail("Applying '$fixName' failed: ${outcome.reason}")
        }
    }
}
