package dev.salatmaster.golandmcp.go

import com.goide.codeInsight.imports.GoImportOptimizer
import com.goide.psi.GoFile
import com.intellij.psi.PsiFile

/** Import maintenance for a Go file. Mutating calls must run inside a write action. */
interface GoImports {
    /** True when the file is Go and can be processed. */
    fun supports(file: PsiFile): Boolean

    /** Adds an import, doing nothing when it is already present. */
    fun addImport(file: PsiFile, importPath: String, alias: String)

    /**
     * Removes unused imports and sorts the rest.
     *
     * Returns the optimizer's work as a [Runnable]; the caller decides when to run it, which
     * is what the platform's ImportOptimizer contract expects.
     */
    fun optimizeTask(file: PsiFile): Runnable?
}

class GoImportsImpl : GoImports {

    private val optimizer = GoImportOptimizer()

    override fun supports(file: PsiFile): Boolean =
        file is GoFile && optimizer.supports(file)

    override fun addImport(file: PsiFile, importPath: String, alias: String) {
        val goFile = file as? GoFile ?: return
        val existing = goFile.imports.any { it.path == importPath }
        if (existing) return
        goFile.addImport(importPath, alias.ifBlank { null })
    }

    override fun optimizeTask(file: PsiFile): Runnable? =
        if (supports(file)) optimizer.processFile(file) else null
}
