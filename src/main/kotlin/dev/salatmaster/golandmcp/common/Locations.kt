package dev.salatmaster.golandmcp.common

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement

/**
 * Formats an element's position as `relative/path.go:line`, falling back to the
 * absolute path for files outside the project (stdlib, module cache).
 */
fun formatLocation(project: Project, element: PsiElement): String {
    val containingFile = element.containingFile ?: return "<unknown>"
    val file = containingFile.virtualFile ?: return "<unknown>"

    val document = PsiDocumentManager.getInstance(project).getDocument(containingFile)
    val line = document?.getLineNumber(element.textOffset)?.plus(1) ?: 0

    val relative = ProjectFileIndex.getInstance(project)
        .getContentRootForFile(file)
        ?.let { root -> VfsUtilCore.getRelativePath(file, root) }

    return "${relative ?: file.path}:$line"
}
