package dev.salatmaster.golandmcp.common

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.PsiElement

/**
 * True when the element lives in the project's own sources rather than in the SDK or a
 * dependency.
 *
 * Bare names collide constantly once the Go SDK is indexed — `Rect` alone matches the
 * project plus `image`, `cmplx` and `windows`. Ranking project code first means the answer
 * an agent almost always wants comes first, instead of whichever entry the stub index
 * happened to return.
 */
fun isInProjectContent(project: Project, element: PsiElement): Boolean {
    val file = element.containingFile?.virtualFile ?: return false
    val index = ProjectFileIndex.getInstance(project)
    return index.isInContent(file) && !index.isInLibrary(file)
}

/** Sorts project symbols ahead of library and SDK ones, preserving order within each group. */
fun <T : PsiElement> List<T>.projectFirst(project: Project): List<T> =
    sortedBy { if (isInProjectContent(project, it)) 0 else 1 }
