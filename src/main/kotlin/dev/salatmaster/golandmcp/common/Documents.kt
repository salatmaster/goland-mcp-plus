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
    val cleaned = cleanPath(path)
    if (cleaned.isEmpty()) return null

    val roots = ProjectRootManager.getInstance(project).contentRoots
    val fromContentRoot = candidatePaths(cleaned, roots.map { it.name })
        .asSequence()
        .flatMap { candidate -> roots.asSequence().mapNotNull { it.findFileByRelativePath(candidate) } }
        .firstOrNull { !it.isDirectory }

    val virtualFile = fromContentRoot
        ?: LocalFileSystem.getInstance().findFileByPath(cleaned)?.takeIf { !it.isDirectory }
        ?: project.basePath
            ?.let { LocalFileSystem.getInstance().findFileByPath("$it/$cleaned") }
            ?.takeIf { !it.isDirectory }
        ?: return null

    val psiFile = PsiManager.getInstance(project).findFile(virtualFile) ?: return null
    val document = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: return null
    return ResolvedFile(virtualFile, document, psiFile)
}

/**
 * Strips the decoration a model puts around a path: quoting, a `file://` scheme, Windows
 * separators. What remains is still a path, just a comparable one.
 */
internal fun cleanPath(path: String): String =
    path.trim()
        .trim('`', '\'', '"')
        .trim()
        .removePrefix("file://")
        .replace('\\', '/')
        .trim()

/**
 * The project-relative forms worth trying, most literal first.
 *
 * Agents habitually write a path the way it appeared in a shell prompt or an editor tab —
 * with a leading slash, or prefixed by the directory the project sits in. Both name the
 * intended file unambiguously once the prefix is dropped, and trying them costs one lookup.
 */
internal fun candidatePaths(cleaned: String, rootNames: List<String>): List<String> {
    val relative = cleaned.removePrefix("./").removePrefix("/")
    val candidates = linkedSetOf(cleaned, relative)
    for (name in rootNames) {
        if (relative.startsWith("$name/")) candidates += relative.removePrefix("$name/")
    }
    return candidates.filter { it.isNotEmpty() }
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
