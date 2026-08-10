package dev.salatmaster.golandmcp.go

import com.goide.psi.GoCallExpr
import com.goide.psi.GoAssignmentStatement
import com.goide.psi.GoReferenceExpression
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.Processor
import dev.salatmaster.golandmcp.common.SymbolRef
import dev.salatmaster.golandmcp.common.formatLocation

class GoUsagesImpl(
    private val symbols: GoSymbols = GoSymbolsImpl(),
) : GoUsages {

    override fun find(
        project: Project,
        ref: SymbolRef,
        includeTests: Boolean,
        limit: Int,
    ): GoUsagesResult? = guardGoApi("find usages") {
        val target = when (val found = symbols.lookup(project, ref)) {
            is GoLookupResult.Found -> found.symbol
            is GoLookupResult.Ambiguous -> found.candidates.first()
            GoLookupResult.NotFound -> return@guardGoApi null
        }

        val element = symbols.declaration(project, ref) ?: return@guardGoApi null
        val collected = ArrayList<GoUsage>()
        var overflow = false

        // Processor, not Kotlin's forEach: Query is Iterable, so forEach would materialise
        // every reference before the limit could stop the search. On a common method name
        // that is the difference between a fast answer and scanning the whole SDK.
        ReferencesSearch.search(element, GlobalSearchScope.allScope(project)).forEach(
            Processor { reference ->
                val usageElement = reference.element
                // A doc comment mentioning the symbol is not a usage of it. Go's own
                // convention is that the comment opens with the declared name, so counting
                // these would report every documented declaration as referenced -- and make
                // go_safe_delete refuse to delete anything written the idiomatic way.
                if (isInsideComment(usageElement)) return@Processor true
                val file = usageElement.containingFile ?: return@Processor true
                val isTest = file.name.endsWith("_test.go")
                if (!includeTests && isTest) return@Processor true

                if (collected.size >= limit) {
                    overflow = true
                    return@Processor false
                }

                collected += GoUsage(
                    kind = classify(usageElement),
                    location = formatLocation(project, usageElement),
                    snippet = lineOf(project, usageElement),
                    inTestFile = isTest,
                    packagePath = (file as? com.goide.psi.GoFile)
                        ?.getImportPath(false).orEmpty(),
                )
                true
            },
        )

        GoUsagesResult(
            target = target.qualifiedName.ifEmpty { target.name },
            usages = collected,
            truncated = overflow,
        )
    }

    private fun isInsideComment(element: PsiElement): Boolean =
        PsiTreeUtil.getParentOfType(element, PsiComment::class.java, false) != null

    /**
     * Classifies a reference by the shape of its surroundings.
     *
     * Distinguishing a call from a write matters for the question agents actually ask —
     * "what would break if I change this" — where assignments are the risky sites.
     */
    private fun classify(element: PsiElement): GoUsageKind {
        val call = PsiTreeUtil.getParentOfType(element, GoCallExpr::class.java)
        if (call != null && PsiTreeUtil.isAncestor(call.expression, element, false)) {
            return GoUsageKind.CALL
        }

        val assignment = PsiTreeUtil.getParentOfType(element, GoAssignmentStatement::class.java)
        if (assignment != null) {
            val leftSide = assignment.leftHandExprList
            if (PsiTreeUtil.isAncestor(leftSide, element, false)) return GoUsageKind.WRITE
        }

        if (element.parent is GoReferenceExpression || element is GoReferenceExpression) {
            return GoUsageKind.READ
        }
        return GoUsageKind.READ
    }

    private fun lineOf(project: Project, element: PsiElement): String {
        val file = element.containingFile ?: return ""
        val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: return ""
        val line = document.getLineNumber(element.textOffset)
        return document.getText(
            com.intellij.openapi.util.TextRange(
                document.getLineStartOffset(line),
                document.getLineEndOffset(line),
            ),
        ).trim()
    }
}
