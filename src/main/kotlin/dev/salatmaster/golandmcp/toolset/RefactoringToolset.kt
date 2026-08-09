package dev.salatmaster.golandmcp.toolset

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.project
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiManager
import com.intellij.refactoring.move.moveFilesOrDirectories.MoveFilesOrDirectoriesProcessor
import dev.salatmaster.golandmcp.common.SymbolRefParseException
import dev.salatmaster.golandmcp.common.parseSymbolRef
import dev.salatmaster.golandmcp.common.resolveFile
import dev.salatmaster.golandmcp.go.GoParameterChange
import dev.salatmaster.golandmcp.go.GoRefactoringOutcome
import dev.salatmaster.golandmcp.go.GoRefactoringsImpl
import dev.salatmaster.golandmcp.go.GoSignatureChangeOutcome
import dev.salatmaster.golandmcp.go.GoUsagesImpl
import dev.salatmaster.golandmcp.metrics.tracked
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

@Serializable
data class GoBlockingUsage(
    val location: String,
    val snippet: String,
    val inTestFile: Boolean,
)

@Serializable
data class GoSafeDeleteResult(
    val target: String,
    val deleted: Boolean,
    /** Populated when the deletion was refused because the symbol is still referenced. */
    val blockingUsages: List<GoBlockingUsage>,
    val hint: String,
)

@Serializable
data class GoInlineResult(
    val target: String,
    val inlined: Boolean,
    val declarationRemoved: Boolean,
    val hint: String,
)

/** One parameter or result of the signature being requested. */
@Serializable
data class GoSignatureEntry(
    @McpDescription(
        "0-based position this entry holds in the CURRENT signature, or -1 if it is new. " +
            "This is what lets arguments already written at call sites follow a reorder " +
            "instead of being dropped.",
    )
    val fromIndex: Int,
    @McpDescription(
        "Name, or empty for an unnamed entry. Go requires parameters to be either all named " +
            "or all unnamed, and results likewise.",
    )
    val name: String,
    @McpDescription(
        "Go type as written in source: 'int', '[]byte', '*User', 'map[string]int', " +
            "'context.Context'. Write the plain element type for a variadic entry and set " +
            "variadic instead of spelling '...'.",
    )
    val type: String,
    @McpDescription("True for the trailing '...T' parameter. Not allowed on a result.")
    val variadic: Boolean,
    @McpDescription(
        "Expression to write at existing call sites for a NEW parameter, or in existing " +
            "return statements for a new result: 'nil', '0', '\"\"', 'context.Background()'. " +
            "Leave empty to use the type's zero value. Ignored for entries that already exist.",
    )
    val defaultValue: String,
)

@Serializable
data class GoChangeSignatureResult(
    val target: String,
    val applied: Boolean,
    /** The signature as it was, so the change is auditable from the result alone. */
    val before: String,
    val after: String,
    val hint: String,
)

@Serializable
data class GoMoveFilesResult(
    val moved: List<String>,
    val targetDirectory: String,
    val succeeded: Boolean,
    val hint: String,
)

class RefactoringToolset : McpToolset {

    private val usages = GoUsagesImpl()
    private val refactorings = GoRefactoringsImpl()

    @McpTool
    @McpDescription(
        "Delete a Go declaration only when nothing references it. If references remain the " +
            "deletion is refused and they are listed, so dead code can be removed without " +
            "guessing whether it is truly dead.",
    )
    suspend fun go_safe_delete(
        @McpDescription("Symbol reference, e.g. 'Rect.Area' or 'helper'")
        reference: String,
        @McpDescription("Count usages in _test.go files as blocking")
        testUsagesBlock: Boolean,
    ): GoSafeDeleteResult =
        tracked("go_safe_delete") {
            safeDelete(currentCoroutineContext().project, reference, testUsagesBlock)
        }

    /** Testable core; the project is explicit so tests need no MCP call context. */
    internal suspend fun safeDelete(
        project: Project,
        reference: String,
        testUsagesBlock: Boolean,
    ): GoSafeDeleteResult {
        val ref = try {
            parseSymbolRef(reference)
        } catch (e: SymbolRefParseException) {
            mcpFail(e.message ?: "Could not parse '$reference'")
        }

        // Check usages ourselves first: the platform processor would otherwise pop a
        // conflicts dialog, which cannot be answered from here.
        val found = readAction { usages.find(project, ref, testUsagesBlock, BLOCKING_LIMIT) }
        val blocking = found?.usages
            ?.filter { it.kind != dev.salatmaster.golandmcp.go.GoUsageKind.DECLARATION }
            ?.filter { testUsagesBlock || !it.inTestFile }
            .orEmpty()

        if (blocking.isNotEmpty()) {
            return GoSafeDeleteResult(
                target = reference,
                deleted = false,
                blockingUsages = blocking.map {
                    GoBlockingUsage(it.location, it.snippet, it.inTestFile)
                },
                hint = "Still referenced in ${blocking.size} place(s); nothing was deleted. " +
                    "Remove or update these first.",
            )
        }

        return when (val outcome = withContext(Dispatchers.EDT) {
            refactorings.safeDelete(project, ref)
        }) {
            GoRefactoringOutcome.Done -> GoSafeDeleteResult(reference, true, emptyList(), "")
            is GoRefactoringOutcome.NotApplicable ->
                mcpFail("Cannot delete '$reference': ${outcome.reason}.")
            is GoRefactoringOutcome.Failed ->
                GoSafeDeleteResult(reference, false, emptyList(), "The refactoring failed: ${outcome.reason}")
        }
    }

    @McpTool
    @McpDescription(
        "Inline a Go function or method: replace every call with its body, substituting the " +
            "arguments. Set removeDeclaration to delete the function afterwards. Doing this " +
            "by hand means rewriting each call site consistently, which is where mistakes " +
            "creep in.",
    )
    suspend fun go_inline(
        @McpDescription("Function or method reference, e.g. 'double' or 'Rect.Area'")
        reference: String,
        @McpDescription("Delete the declaration once its calls have been inlined")
        removeDeclaration: Boolean,
    ): GoInlineResult =
        tracked("go_inline") {
            inline(currentCoroutineContext().project, reference, removeDeclaration)
        }

    /** Testable core; the project is explicit so tests need no MCP call context. */
    internal suspend fun inline(
        project: Project,
        reference: String,
        removeDeclaration: Boolean,
    ): GoInlineResult {
        val ref = try {
            parseSymbolRef(reference)
        } catch (e: SymbolRefParseException) {
            mcpFail(e.message ?: "Could not parse '$reference'")
        }

        return when (val outcome = withContext(Dispatchers.EDT) {
            refactorings.inlineFunction(project, ref, removeDeclaration)
        }) {
            GoRefactoringOutcome.Done -> GoInlineResult(reference, true, removeDeclaration, "")
            is GoRefactoringOutcome.NotApplicable -> mcpFail("Cannot inline '$reference': ${outcome.reason}.")
            is GoRefactoringOutcome.Failed ->
                GoInlineResult(reference, false, false, "Inlining failed: ${outcome.reason}")
        }
    }

    @McpTool
    @McpDescription(
        "Move Go files to another package directory, updating the package clause and the " +
            "imports of every consumer. Doing this by editing text reliably misses import " +
            "sites in other packages.",
    )
    suspend fun go_move_files(
        @McpDescription("Paths of the files to move, relative to the project root")
        paths: List<String>,
        @McpDescription("Target directory, relative to the project root")
        targetDirectory: String,
    ): GoMoveFilesResult =
        tracked("go_move_files") {
            moveFiles(currentCoroutineContext().project, paths, targetDirectory)
        }

    /** Testable core; the project is explicit so tests need no MCP call context. */
    internal suspend fun moveFiles(
        project: Project,
        paths: List<String>,
        targetDirectory: String,
    ): GoMoveFilesResult {
        if (paths.isEmpty()) mcpFail("paths must not be empty")

        return withContext(Dispatchers.EDT) {
            val files = paths.map { path ->
                resolveFile(project, path)?.psiFile
                    ?: return@withContext GoMoveFilesResult(
                        emptyList(), targetDirectory, false, "File not found: $path",
                    )
            }

            val targetVirtualFile = resolveDirectory(project, targetDirectory)
                ?: return@withContext GoMoveFilesResult(
                    emptyList(), targetDirectory, false,
                    "Target directory not found: $targetDirectory",
                )
            val target = PsiManager.getInstance(project).findDirectory(targetVirtualFile)
                ?: return@withContext GoMoveFilesResult(
                    emptyList(), targetDirectory, false,
                    "Target is not a directory: $targetDirectory",
                )

            runCatching {
                MoveFilesOrDirectoriesProcessor(
                    project, files.toTypedArray(), target as PsiDirectory,
                    /* searchInComments = */ false,
                    /* searchInNonJavaFiles = */ true,
                    /* moveCallback = */ null,
                    /* prepareSuccessfulCallback = */ null,
                ).run()
            }.fold(
                onSuccess = { GoMoveFilesResult(paths, targetDirectory, true, "") },
                onFailure = { error ->
                    GoMoveFilesResult(
                        emptyList(), targetDirectory, false,
                        "The move failed: ${error::class.simpleName}: ${error.message}",
                    )
                },
            )
        }
    }

    @McpTool
    @McpDescription(
        "Change a Go function, method or interface method signature - rename it, add, drop, " +
            "reorder or retype parameters and results - and rewrite every call site to match. " +
            "Pass the COMPLETE new signature, not a patch: every entry carries fromIndex, the " +
            "position it holds in the current signature, and that mapping is what moves existing " +
            "arguments with a reordered parameter instead of dropping them. Read the current " +
            "signature first with go_symbol or go_source_of. Editing a signature by hand means " +
            "finding every caller and getting each argument order right, which is exactly where " +
            "this goes wrong silently.",
    )
    suspend fun go_change_signature(
        @McpDescription("Function, method or interface method, e.g. 'Double', 'Rect.Area', 'Repo.Get'")
        reference: String,
        @McpDescription("New name, or empty to keep the current one")
        newName: String,
        @McpDescription("The complete new parameter list, in order; empty for no parameters")
        parameters: List<GoSignatureEntry>,
        @McpDescription("The complete new result list, in order; empty for no results")
        results: List<GoSignatureEntry>,
        @McpDescription(
            "When the target is an interface method, also rewrite the types that implement it. " +
                "Ignored otherwise.",
        )
        updateImplementations: Boolean,
    ): GoChangeSignatureResult =
        tracked("go_change_signature") {
            changeSignature(
                currentCoroutineContext().project,
                reference,
                newName,
                parameters,
                results,
                updateImplementations,
            )
        }

    /** Testable core; the project is explicit so tests need no MCP call context. */
    internal suspend fun changeSignature(
        project: Project,
        reference: String,
        newName: String,
        parameters: List<GoSignatureEntry>,
        results: List<GoSignatureEntry>,
        updateImplementations: Boolean,
    ): GoChangeSignatureResult {
        val ref = try {
            parseSymbolRef(reference)
        } catch (e: SymbolRefParseException) {
            mcpFail(e.message ?: "Could not parse '$reference'")
        }

        val outcome = withContext(Dispatchers.EDT) {
            refactorings.changeSignature(
                project,
                ref,
                newName.trim(),
                parameters.map { it.toChange() },
                results.map { it.toChange() },
                updateImplementations,
            )
        }

        return when (outcome) {
            is GoSignatureChangeOutcome.Done -> GoChangeSignatureResult(
                target = reference,
                applied = true,
                before = outcome.before,
                after = outcome.after,
                hint = "Call sites were rewritten by the IDE" +
                    (if (updateImplementations) " together with the implementations" else "") +
                    ". Types named in a new parameter or defaultValue are not imported " +
                    "automatically, so run go_build_check to confirm the package still compiles.",
            )

            is GoSignatureChangeOutcome.Unchanged -> GoChangeSignatureResult(
                target = reference,
                applied = false,
                before = outcome.signature,
                after = outcome.signature,
                hint = "The requested signature is the one it already has; nothing was changed.",
            )

            is GoSignatureChangeOutcome.Rejected ->
                mcpFail("Cannot change the signature of '$reference': ${outcome.reason}.")

            is GoSignatureChangeOutcome.Failed -> GoChangeSignatureResult(
                target = reference,
                applied = false,
                before = outcome.before,
                after = outcome.before,
                hint = "The refactoring failed: ${outcome.reason}. Undo it in the IDE if any " +
                    "part of it was applied.",
            )
        }
    }

    private fun GoSignatureEntry.toChange() =
        GoParameterChange(
            fromIndex = fromIndex,
            name = name.trim(),
            type = type.trim(),
            variadic = variadic,
            defaultValue = defaultValue.trim(),
        )

    private fun resolveDirectory(project: Project, path: String) =
        com.intellij.openapi.roots.ProjectRootManager.getInstance(project).contentRoots
            .asSequence()
            .mapNotNull { it.findFileByRelativePath(path.trim().removePrefix("./")) }
            .firstOrNull { it.isDirectory }

    private companion object {
        const val BLOCKING_LIMIT = 50
    }
}
