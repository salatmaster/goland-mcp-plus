package dev.salatmaster.golandmcp.common

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager

/** A file resolved to everything the write path needs. */
data class ResolvedFile(
    val virtualFile: VirtualFile,
    val document: Document,
    val psiFile: com.intellij.psi.PsiFile,
)

/**
 * Resolves a path relative to a content root, or an absolute one.
 *
 * Content roots come first on purpose: they are the only thing that works in both a real
 * IDE and a light test fixture, whose files live in an in-memory filesystem that
 * `LocalFileSystem` cannot see at all.
 *
 * Returns null rather than throwing so callers can report which of several paths failed.
 */
fun resolveFile(project: Project, path: String): ResolvedFile? {
    val normalized = path.trim().removePrefix("./")

    val fromContentRoot = ProjectRootManager.getInstance(project).contentRoots
        .asSequence()
        .mapNotNull { root -> root.findFileByRelativePath(normalized) }
        .firstOrNull { !it.isDirectory }

    val virtualFile = fromContentRoot
        ?: LocalFileSystem.getInstance().findFileByPath(normalized)?.takeIf { !it.isDirectory }
        ?: project.basePath
            ?.let { LocalFileSystem.getInstance().findFileByPath("$it/$normalized") }
            ?.takeIf { !it.isDirectory }
        ?: return null

    val psiFile = PsiManager.getInstance(project).findFile(virtualFile) ?: return null
    val document = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: return null
    return ResolvedFile(virtualFile, document, psiFile)
}

/**
 * Applies [mutate] to a document inside a write command.
 *
 * Going through [WriteCommandAction] puts the edit on the IDE's undo stack, so a developer
 * can revert an agent's change with a single Cmd+Z — which is the difference between an
 * agent that is safe to let near a codebase and one that is not.
 */
fun <T> writeToDocument(project: Project, commandName: String, mutate: () -> T): T {
    var result: T? = null
    WriteCommandAction.runWriteCommandAction(project, commandName, null, { result = mutate() })
    @Suppress("UNCHECKED_CAST")
    return result as T
}

/** A minimal unified diff, enough for an agent to see what actually changed. */
fun unifiedDiff(path: String, before: String, after: String): String {
    if (before == after) return ""
    val beforeLines = before.lines()
    val afterLines = after.lines()

    var prefix = 0
    while (prefix < beforeLines.size && prefix < afterLines.size &&
        beforeLines[prefix] == afterLines[prefix]
    ) {
        prefix++
    }

    var suffix = 0
    while (suffix < beforeLines.size - prefix && suffix < afterLines.size - prefix &&
        beforeLines[beforeLines.size - 1 - suffix] == afterLines[afterLines.size - 1 - suffix]
    ) {
        suffix++
    }

    val removed = beforeLines.subList(prefix, beforeLines.size - suffix)
    val added = afterLines.subList(prefix, afterLines.size - suffix)

    return buildString {
        appendLine("--- $path")
        appendLine("+++ $path")
        appendLine("@@ -${prefix + 1},${removed.size} +${prefix + 1},${added.size} @@")
        removed.forEach { appendLine("-$it") }
        added.forEach { appendLine("+$it") }
    }.trimEnd()
}
