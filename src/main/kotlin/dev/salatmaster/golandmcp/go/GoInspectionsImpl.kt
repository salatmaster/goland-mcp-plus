package dev.salatmaster.golandmcp.go

import com.goide.psi.GoFile
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ex.LocalInspectionToolWrapper
import com.intellij.codeInspection.InspectionEngine
import com.intellij.codeInsight.daemon.HighlightDisplayKey
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.profile.codeInspection.InspectionProjectProfileManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.util.PairProcessor
import dev.salatmaster.golandmcp.common.resolveFile
import dev.salatmaster.golandmcp.common.unifiedDiff
import dev.salatmaster.golandmcp.common.writeToDocument

class GoInspectionsImpl : GoInspections {

    override fun problems(
        project: Project,
        path: String,
        includeWeak: Boolean,
    ): List<GoProblem>? = guardGoApi("problems") {
        val found = analyse(project, path) ?: return@guardGoApi null
        found
            .map { toProblem(path, it) }
            .filter { includeWeak || it.severity !in QUIET }
            .sortedBy { it.line }
    }

    override fun applyFix(
        project: Project,
        path: String,
        line: Int,
        fixName: String,
    ): GoFixOutcome = guardGoApi("apply quick fix") {
        val resolved = resolveFile(project, path) ?: return@guardGoApi GoFixOutcome.FileNotFound
        if (resolved.psiFile !is GoFile) return@guardGoApi GoFixOutcome.NotAGoFile

        // Re-analysed on every call on purpose: a ProblemDescriptor is a snapshot, and the
        // file may have moved on since the caller listed the problems. Matching against a
        // stale one would apply a fix at the wrong place.
        val found = analyse(project, path) ?: return@guardGoApi GoFixOutcome.FileNotFound
        val onLine = found.filter { it.descriptor.lineNumber + 1 == line }

        val matches = onLine.flatMap { analysed ->
            val descriptor = analysed.descriptor
            descriptor.fixes.orEmpty()
                .filter { it.name.equals(fixName, ignoreCase = true) }
                .map { descriptor to it }
        }

        if (matches.isEmpty()) {
            return@guardGoApi GoFixOutcome.NoSuchFix(
                onLine.flatMap { a -> a.descriptor.fixes.orEmpty().map { it.name } }.distinct(),
            )
        }
        // Two problems on one line can offer a same-named fix — "Remove unused" on each of
        // two variables. Picking one would be a coin toss the caller cannot see.
        if (matches.size > 1) {
            return@guardGoApi GoFixOutcome.Ambiguous(
                matches.map { (d, _) -> d.descriptionTemplate }.distinct(),
            )
        }

        val (descriptor, fix) = matches.single()
        if (fix !is LocalQuickFix) {
            return@guardGoApi GoFixOutcome.NotApplicable(
                "'$fixName' is an intention that needs an open editor, which a tool call has not.",
            )
        }
        if (!fix.availableInBatchMode()) {
            return@guardGoApi GoFixOutcome.NotApplicable(
                "'$fixName' asks something interactively — a dialog or a rename template — " +
                    "and there is nobody here to answer it. Make this edit directly instead.",
            )
        }

        val document = resolved.document
        val before = document.text
        try {
            writeToDocument(project, "Apply '$fixName'") {
                fix.applyFix(project, descriptor)
                PsiDocumentManager.getInstance(project).doPostponedOperationsAndUnblockDocument(document)
            }
        } catch (e: RuntimeException) {
            return@guardGoApi GoFixOutcome.Failed("${e::class.simpleName}: ${e.message}")
        }

        val after = document.text
        // A fix that ran and changed nothing is not a success. Saying so is the difference
        // between an agent that moves on and one that keeps applying the same no-op.
        if (before == after) GoFixOutcome.NoChange else GoFixOutcome.Applied(unifiedDiff(path, before, after))
    }

    /**
     * Runs every enabled local inspection over the file, exactly as the profile the developer
     * sees in Settings has them configured — so what the agent is told matches what the
     * editor shows them.
     *
     * Local inspections only. A global inspection needs a whole-project analysis context and
     * a scope, which is not a per-file question and is far too slow for a tool call.
     */
    private fun analyse(
        project: Project,
        path: String,
    ): List<Analysed>? {
        val resolved = resolveFile(project, path) ?: return null
        val psiFile = resolved.psiFile
        if (psiFile !is GoFile) return null

        val profile = InspectionProjectProfileManager.getInstance(project).currentProfile
        val wrappers: List<LocalInspectionToolWrapper> = profile.getAllEnabledInspectionTools(project)
            .filter { it.isEnabled }
            .mapNotNull { it.tool as? LocalInspectionToolWrapper }

        // The five-argument overload is deprecated and scheduled for removal; the verifier
        // reports it, and a release that ships it breaks the day the platform drops it.
        val results = InspectionEngine.inspectEx(
            wrappers,
            psiFile,
            psiFile.textRange,
            psiFile.textRange,
            false, // not on the fly: this is a tool call, not a typing session
            false, // injected fragments are somebody else's language
            true, // honour //nolint-style suppressions, as the editor does
            EmptyProgressIndicator(),
            PairProcessor.alwaysTrue(),
        )

        return results.entries.flatMap { (wrapper, descriptors) ->
            val inspection = wrapper.shortName
            // The severity the developer configured, not the one the descriptor carries.
            // Half of GoLand's inspections hand out GENERIC_ERROR_OR_WARNING and are shown
            // in the editor as a hint or not at all -- reading the descriptor called every
            // one of them a warning, and buried the real ones under string-literal noise.
            val key = HighlightDisplayKey.find(inspection)
            descriptors.map { descriptor ->
                val level = key?.let { profile.getErrorLevel(it, descriptor.psiElement) }
                Analysed(inspection, descriptor, level?.name ?: severityOf(descriptor.highlightType))
            }
        }
    }

    /** One problem, with the severity the developer's profile gives it. */
    private data class Analysed(
        val inspection: String,
        val descriptor: ProblemDescriptor,
        val severity: String,
    )

    private fun toProblem(path: String, analysed: Analysed): GoProblem {
        val descriptor = analysed.descriptor
        return GoProblem(
            path = path,
            line = descriptor.lineNumber + 1,
            severity = normalise(analysed.severity),
            description = descriptor.descriptionTemplate.replace(TAGS, "").trim(),
            inspection = analysed.inspection,
            fixes = descriptor.fixes.orEmpty().map { fix ->
                when {
                    fix !is LocalQuickFix ->
                        GoFix(fix.name, false, "needs an open editor")

                    !fix.availableInBatchMode() ->
                        GoFix(fix.name, false, "asks something interactively")

                    else -> GoFix(fix.name, true)
                }
            },
        )
    }

    /** `HighlightDisplayLevel` names are display strings: "WEAK WARNING", "Typo". */
    private fun normalise(level: String): String = when (level.uppercase()) {
        "ERROR" -> "ERROR"
        "WARNING" -> "WARNING"
        "WEAK WARNING" -> "WEAK_WARNING"
        else -> "INFO"
    }

    private fun severityOf(type: ProblemHighlightType): String = when (type) {
        ProblemHighlightType.ERROR,
        ProblemHighlightType.GENERIC_ERROR,
        ProblemHighlightType.LIKE_UNKNOWN_SYMBOL,
        -> "ERROR"

        ProblemHighlightType.WARNING,
        ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
        -> "WARNING"

        ProblemHighlightType.WEAK_WARNING -> "WEAK_WARNING"
        ProblemHighlightType.INFORMATION -> "INFO"
        else -> "WARNING"
    }

    private companion object {
        /** Descriptions carry `<code>` and `#ref` markup meant for a tooltip, not for a model. */
        val TAGS = Regex("</?[a-zA-Z][^>]*>")

        /** Hidden unless asked for: an agent drowning in hints stops reading the errors. */
        val QUIET = setOf("WEAK_WARNING", "INFO")
    }
}
