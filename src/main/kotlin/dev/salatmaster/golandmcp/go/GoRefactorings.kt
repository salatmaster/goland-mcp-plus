package dev.salatmaster.golandmcp.go

import com.goide.psi.GoConstDeclaration
import com.goide.psi.GoConstSpec
import com.goide.psi.GoFunctionOrMethodDeclaration
import com.goide.psi.GoTypeDeclaration
import com.goide.psi.GoTypeSpec
import com.goide.psi.GoVarDeclaration
import com.goide.psi.GoVarSpec
import com.goide.psi.GoVarOrConstDefinition
import com.goide.refactor.inline.function.GoInlineFunctionProcessor
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import dev.salatmaster.golandmcp.common.SymbolRef
import dev.salatmaster.golandmcp.common.isInProjectContent

sealed interface GoRefactoringOutcome {
    data object Done : GoRefactoringOutcome
    data class NotApplicable(val reason: String) : GoRefactoringOutcome
    data class Failed(val reason: String) : GoRefactoringOutcome
}

/**
 * Refactorings driven through the IDE's processors.
 *
 * Every call must run on the EDT. The processors manage their own write actions, so they
 * must not be wrapped in one.
 */
interface GoRefactorings {
    fun inlineFunction(
        project: Project,
        ref: SymbolRef,
        removeDeclaration: Boolean,
    ): GoRefactoringOutcome

    fun safeDelete(project: Project, ref: SymbolRef): GoRefactoringOutcome

    /**
     * Rewrites a function, method or interface method signature and every call site with it.
     *
     * The lists describe the signature in full, not a patch: each entry carries the index it
     * had in the current signature, which is how existing arguments follow a reorder.
     */
    fun changeSignature(
        project: Project,
        ref: SymbolRef,
        newName: String,
        parameters: List<GoParameterChange>,
        results: List<GoParameterChange>,
        updateImplementations: Boolean,
    ): GoSignatureChangeOutcome
}

class GoRefactoringsImpl(
    private val symbols: GoSymbols = GoSymbolsImpl(),
) : GoRefactorings {

    override fun inlineFunction(
        project: Project,
        ref: SymbolRef,
        removeDeclaration: Boolean,
    ): GoRefactoringOutcome {
        val declaration = symbols.declaration(project, ref)
            ?: return GoRefactoringOutcome.NotApplicable("no such symbol")

        val function = declaration as? GoFunctionOrMethodDeclaration
            ?: return GoRefactoringOutcome.NotApplicable(
                "'${declaration.name}' is not a function or method; only those can be inlined",
            )

        return runProcessor {
            // A null reference inlines every call site rather than one occurrence.
            GoInlineFunctionProcessor(project, function, null, false, removeDeclaration).run()
        }
    }

    /**
     * Deletes exactly the declaration and its doc comment, and nothing else.
     *
     * The platform's SafeDeleteProcessor is deliberately not used here. From a tool call
     * there is no way to answer its conflicts dialog, so conflicts are suppressed — and a
     * suppressed conflict is how a request to delete one unused function also removed an
     * unrelated const, an unrelated var and an import that was still in use, leaving the
     * package uncompilable. Whether a symbol may go is already decided by the caller's own
     * reference check; what is left is a deletion that must be exactly as wide as it says.
     */
    override fun safeDelete(project: Project, ref: SymbolRef): GoRefactoringOutcome =
        guardGoApi("safe delete") {
            val plan = runReadActionBlocking {
                val element = symbols.declaration(project, ref)
                    ?: return@runReadActionBlocking null
                if (!isInProjectContent(project, element)) {
                    return@runReadActionBlocking DeletionPlan(null, emptyList(), element.name)
                }
                val target = deletionTarget(element)
                DeletionPlan(target, docCommentsAbove(target), element.name)
            } ?: return@guardGoApi GoRefactoringOutcome.NotApplicable("no such symbol")

            val target = plan.target
                ?: return@guardGoApi GoRefactoringOutcome.NotApplicable(
                    "'${plan.name}' is not in this project's sources; only project code can be deleted",
                )

            runCatchingCancellable {
                WriteCommandAction.runWriteCommandAction(project, "Delete ${plan.name}", null, {
                    // Comments first: deleting the declaration would leave them orphaned, and
                    // their positions are already resolved.
                    plan.comments.forEach { if (it.isValid) it.delete() }
                    if (target.isValid) target.delete()
                })
            }.fold(
                onSuccess = { GoRefactoringOutcome.Done },
                onFailure = { GoRefactoringOutcome.Failed("${it::class.simpleName}: ${it.message}") },
            )
        }

    private class DeletionPlan(
        val target: PsiElement?,
        val comments: List<PsiElement>,
        val name: String?,
    )

    /**
     * Widens the named element to the declaration that owns it, but only while that
     * declaration holds nothing else.
     *
     * Deleting the `X` in `var X = 1` on its own would leave a bare `var`; deleting it in
     * `var X, Y = 1, 2` must leave `Y` alone.
     */
    private fun deletionTarget(element: PsiElement): PsiElement {
        val spec = when (element) {
            is GoTypeSpec -> element
            is GoVarOrConstDefinition -> element.parent?.takeIf { it is GoVarSpec || it is GoConstSpec }
            else -> null
        } ?: return element

        if (spec is GoVarSpec && spec.definitionList.size > 1) return element
        if (spec is GoConstSpec && spec.definitionList.size > 1) return element

        val declaration = spec.parent
        val siblings = when (declaration) {
            is GoTypeDeclaration -> declaration.typeSpecList.size
            is GoVarDeclaration -> declaration.varSpecList.size
            is GoConstDeclaration -> declaration.constSpecList.size
            else -> return spec
        }
        return if (siblings == 1) declaration else spec
    }

    /** The unbroken run of `//` lines directly above a declaration, which Go treats as its doc. */
    private fun docCommentsAbove(element: PsiElement): List<PsiElement> {
        val comments = mutableListOf<PsiElement>()
        var sibling: PsiElement? = element.prevSibling
        while (sibling != null) {
            when {
                sibling is PsiComment -> comments += sibling
                sibling is PsiWhiteSpace && sibling.text.count { it == '\n' } <= 1 -> Unit
                else -> break
            }
            sibling = sibling.prevSibling
        }
        return comments
    }

    override fun changeSignature(
        project: Project,
        ref: SymbolRef,
        newName: String,
        parameters: List<GoParameterChange>,
        results: List<GoParameterChange>,
        updateImplementations: Boolean,
    ): GoSignatureChangeOutcome =
        changeGoSignature(project, symbols, ref, newName, parameters, results, updateImplementations)

    private fun runProcessor(action: () -> Unit): GoRefactoringOutcome =
        guardGoApi("refactoring") {
            runCatchingCancellable(action).fold(
                onSuccess = { GoRefactoringOutcome.Done },
                onFailure = { GoRefactoringOutcome.Failed("${it::class.simpleName}: ${it.message}") },
            )
        }
}
