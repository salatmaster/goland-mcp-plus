package dev.salatmaster.golandmcp.go

import com.goide.psi.GoFunctionOrMethodDeclaration
import com.goide.refactor.inline.function.GoInlineFunctionProcessor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.refactoring.safeDelete.SafeDeleteProcessor
import dev.salatmaster.golandmcp.common.SymbolRef

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

    override fun safeDelete(project: Project, ref: SymbolRef): GoRefactoringOutcome {
        val element: PsiElement = symbols.declaration(project, ref)
            ?: return GoRefactoringOutcome.NotApplicable("no such symbol")

        return runProcessor {
            SafeDeleteProcessor
                .createInstance(project, null, arrayOf(element), false, false, true)
                .run()
        }
    }

    private fun runProcessor(action: () -> Unit): GoRefactoringOutcome =
        guardGoApi("refactoring") {
            runCatching(action).fold(
                onSuccess = { GoRefactoringOutcome.Done },
                onFailure = { GoRefactoringOutcome.Failed("${it::class.simpleName}: ${it.message}") },
            )
        }
}
