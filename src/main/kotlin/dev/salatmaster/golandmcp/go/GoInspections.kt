package dev.salatmaster.golandmcp.go

import com.intellij.openapi.project.Project

/**
 * A fix the IDE offers for a problem — what Alt+Enter would put in front of a developer.
 *
 * [applicable] is false for a fix that cannot be run from a tool call: one that needs an
 * editor, opens a dialog or starts a rename template. There is nobody to answer a dialog
 * here, and a platform action whose UI cannot be shown is exactly how `SafeDeleteProcessor`
 * once deleted an unrelated const, var and import. The reason is carried in [whyNot] rather
 * than hidden, so the caller can see what it is missing instead of wondering.
 *
 * [applicable] means the fix can be *run* from here, not that it will change this file.
 * Some pass every check and still do nothing — "Navigate to shadowed declaration" is not a
 * repair at all, "Change import sort settings" edits the settings. The platform has no
 * reliable signal for the difference: `generatePreview` was tried and returns "no change"
 * for "Sort imports", which demonstrably does change the file. So the honest answer arrives
 * at apply time, where an unchanged file is reported as such rather than as success.
 */
data class GoFix(
    val name: String,
    val applicable: Boolean,
    val whyNot: String = "",
)

/** One problem the IDE's inspections report, with the fixes it knows for it. */
data class GoProblem(
    val path: String,
    /** 1-based, as an editor counts. */
    val line: Int,
    val severity: String,
    val description: String,
    /** The inspection that reported it, so a caller can suppress or look it up. */
    val inspection: String,
    val fixes: List<GoFix>,
)

/** Why applying a fix did not happen, or that it did. */
sealed interface GoFixOutcome {
    data class Applied(val diff: String) : GoFixOutcome

    /** The fix ran and the file came out unchanged — reported rather than called success. */
    data object NoChange : GoFixOutcome
    data object FileNotFound : GoFixOutcome
    data object NotAGoFile : GoFixOutcome

    /** No problem on that line offers a fix by that name; [available] is what it does offer. */
    data class NoSuchFix(val available: List<String>) : GoFixOutcome

    /** More than one problem on the line offers that name; the caller must be specific. */
    data class Ambiguous(val problems: List<String>) : GoFixOutcome

    /** The fix exists but cannot run without an editor or a dialog. */
    data class NotApplicable(val reason: String) : GoFixOutcome

    data class Failed(val reason: String) : GoFixOutcome
}

/**
 * The IDE's own inspections, and the fixes attached to them.
 *
 * The MCP server built into the IDE reports problems — severity, description, line — but its
 * model has no room for a fix, so an agent is told what is wrong and left to invent the
 * repair. That is where it writes plausible, wrong Go. gopls has the same gap from the other
 * side: its diagnostics are text.
 */
interface GoInspections {
    /** Null when the file does not resolve; the outcome distinguishes why. */
    fun problems(project: Project, path: String, includeWeak: Boolean): List<GoProblem>?

    fun applyFix(project: Project, path: String, line: Int, fixName: String): GoFixOutcome
}
