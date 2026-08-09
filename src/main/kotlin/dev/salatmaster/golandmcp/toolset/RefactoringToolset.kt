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
import dev.salatmaster.golandmcp.go.GoRefactoringOutcome
import dev.salatmaster.golandmcp.go.GoRefactoringsImpl
import dev.salatmaster.golandmcp.go.GoUsagesImpl
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
        safeDelete(currentCoroutineContext().project, reference, testUsagesBlock)

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
    ): GoInlineResult = inline(currentCoroutineContext().project, reference, removeDeclaration)

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
        moveFiles(currentCoroutineContext().project, paths, targetDirectory)

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

    private fun resolveDirectory(project: Project, path: String) =
        com.intellij.openapi.roots.ProjectRootManager.getInstance(project).contentRoots
            .asSequence()
            .mapNotNull { it.findFileByRelativePath(path.trim().removePrefix("./")) }
            .firstOrNull { it.isDirectory }

    private companion object {
        const val BLOCKING_LIMIT = 50
    }
}
