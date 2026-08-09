package dev.salatmaster.golandmcp.toolset

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.project
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.Project
import dev.salatmaster.golandmcp.common.resolveFile
import dev.salatmaster.golandmcp.common.unifiedDiff
import dev.salatmaster.golandmcp.common.writeToDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

@Serializable
data class GoFileContent(
    val path: String,
    val content: String,
    val lineCount: Int,
    /** Empty when the file was read; otherwise why it was not. */
    val error: String,
)

@Serializable
data class GoReadFilesResult(
    val files: List<GoFileContent>,
    val failed: Int,
)

@Serializable
data class GoLineReplacement(
    @McpDescription("Path to the file, relative to the project root")
    val path: String,
    @McpDescription("First line to replace, 1-based and inclusive")
    val startLine: Int,
    @McpDescription("Last line to replace, 1-based and inclusive")
    val endLine: Int,
    @McpDescription("Replacement text; may span several lines, or be empty to delete")
    val text: String,
)

@Serializable
data class GoTextReplacement(
    @McpDescription("Exact text to find")
    val find: String,
    @McpDescription("Text to put in its place")
    val replace: String,
    @McpDescription("Replace every occurrence rather than requiring exactly one")
    val replaceAll: Boolean,
)

@Serializable
data class GoEditResult(
    val path: String,
    val applied: Boolean,
    val diff: String,
    val error: String,
)

@Serializable
data class GoBatchEditResult(
    val results: List<GoEditResult>,
    val failed: Int,
)

class BatchToolset : McpToolset {

    @McpTool
    @McpDescription(
        "Read several files in one call. Saves a round trip per file when surveying a " +
            "package. Files that cannot be read are reported individually rather than " +
            "failing the whole call.",
    )
    suspend fun go_read_files(
        @McpDescription("Paths relative to the project root")
        paths: List<String>,
    ): GoReadFilesResult = readFiles(currentCoroutineContext().project, paths)

    /** Testable core; the project is explicit so tests need no MCP call context. */
    internal suspend fun readFiles(project: Project, paths: List<String>): GoReadFilesResult {
        if (paths.isEmpty()) mcpFail("paths must not be empty")

        val files = readAction {
            paths.map { path ->
                val resolved = resolveFile(project, path)
                if (resolved == null) {
                    GoFileContent(path, "", 0, "File not found: $path")
                } else {
                    val text = resolved.document.text
                    GoFileContent(path, text, text.lines().size, "")
                }
            }
        }
        return GoReadFilesResult(files, files.count { it.error.isNotEmpty() })
    }

    @McpTool
    @McpDescription(
        "Replace line ranges, optionally across several files, in one undoable step. Line " +
            "numbers are 1-based and inclusive. Each edit reports a diff of what actually " +
            "changed. Edits within a file are applied bottom-up so earlier line numbers stay " +
            "valid.",
    )
    suspend fun go_replace_lines(
        @McpDescription("Line-range replacements to apply")
        replacements: List<GoLineReplacement>,
    ): GoBatchEditResult = replaceLines(currentCoroutineContext().project, replacements)

    /** Testable core; the project is explicit so tests need no MCP call context. */
    internal suspend fun replaceLines(
        project: Project,
        replacements: List<GoLineReplacement>,
    ): GoBatchEditResult {
        if (replacements.isEmpty()) mcpFail("replacements must not be empty")

        val results = withContext(Dispatchers.EDT) {
            replacements.groupBy { it.path }.map { (path, edits) ->
                applyLineEdits(project, path, edits)
            }
        }
        return GoBatchEditResult(results, results.count { !it.applied })
    }

    private fun applyLineEdits(
        project: Project,
        path: String,
        edits: List<GoLineReplacement>,
    ): GoEditResult {
        val resolved = resolveFile(project, path)
            ?: return GoEditResult(path, false, "", "File not found: $path")
        val document = resolved.document
        val before = document.text

        // Bottom-up: replacing a later range first leaves earlier line numbers untouched,
        // so the caller's numbers all refer to the file as they saw it.
        val ordered = edits.sortedByDescending { it.startLine }
        for (edit in ordered) {
            val lastLine = document.lineCount
            if (edit.startLine < 1 || edit.endLine < edit.startLine || edit.endLine > lastLine) {
                return GoEditResult(
                    path, false, "",
                    "Invalid range ${edit.startLine}-${edit.endLine}; file has $lastLine lines",
                )
            }
        }

        return try {
            writeToDocument(project, "Replace lines in $path") {
                for (edit in ordered) {
                    val start = document.getLineStartOffset(edit.startLine - 1)
                    val end = document.getLineEndOffset(edit.endLine - 1)
                    document.replaceString(start, end, edit.text)
                }
            }
            GoEditResult(path, true, unifiedDiff(path, before, document.text), "")
        } catch (e: RuntimeException) {
            GoEditResult(path, false, "", "${e::class.simpleName}: ${e.message}")
        }
    }

    @McpTool
    @McpDescription(
        "Apply several exact-text replacements to one file in a single undoable step. By " +
            "default each search text must occur exactly once, so a stale or ambiguous match " +
            "fails loudly instead of editing the wrong place.",
    )
    suspend fun go_batch_replace_text(
        @McpDescription("Path to the file, relative to the project root")
        path: String,
        @McpDescription("Replacements to apply, in order")
        replacements: List<GoTextReplacement>,
    ): GoEditResult = batchReplaceText(currentCoroutineContext().project, path, replacements)

    /** Testable core; the project is explicit so tests need no MCP call context. */
    internal suspend fun batchReplaceText(
        project: Project,
        path: String,
        replacements: List<GoTextReplacement>,
    ): GoEditResult {
        if (replacements.isEmpty()) mcpFail("replacements must not be empty")

        return withContext(Dispatchers.EDT) {
            val resolved = resolveFile(project, path)
                ?: return@withContext GoEditResult(path, false, "", "File not found: $path")
            val document = resolved.document
            val before = document.text

            var text = before
            for (replacement in replacements) {
                val occurrences = countOccurrences(text, replacement.find)
                when {
                    occurrences == 0 -> return@withContext GoEditResult(
                        path, false, "",
                        "Text not found: '${replacement.find.take(60)}'",
                    )
                    occurrences > 1 && !replacement.replaceAll -> return@withContext GoEditResult(
                        path, false, "",
                        "Text occurs $occurrences times: '${replacement.find.take(60)}'. " +
                            "Add more context to make it unique, or set replaceAll.",
                    )
                }
                text = if (replacement.replaceAll) {
                    text.replace(replacement.find, replacement.replace)
                } else {
                    text.replaceFirst(replacement.find, replacement.replace)
                }
            }

            writeToDocument(project, "Replace text in $path") {
                document.setText(text)
            }
            GoEditResult(path, true, unifiedDiff(path, before, text), "")
        }
    }

    private fun countOccurrences(haystack: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var index = haystack.indexOf(needle)
        while (index >= 0) {
            count++
            index = haystack.indexOf(needle, index + needle.length)
        }
        return count
    }
}
