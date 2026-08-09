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
import dev.salatmaster.golandmcp.go.GoGeneration
import dev.salatmaster.golandmcp.go.GoImportsImpl
import dev.salatmaster.golandmcp.go.GoInterfaceFactsImpl
import dev.salatmaster.golandmcp.go.GoSymbolsImpl
import dev.salatmaster.golandmcp.go.GoTypeFromJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

@Serializable
data class GoGeneratedCode(
    val code: String,
    /** Set when the code was written to a file; empty when it was only returned. */
    val path: String,
    val applied: Boolean,
    val diff: String,
    val hint: String,
)

class GenerationToolset : McpToolset {

    private val facts = GoInterfaceFactsImpl()
    private val symbols = GoSymbolsImpl()
    private val imports = GoImportsImpl()

    @McpTool
    @McpDescription(
        "Generate the method stubs a Go type needs to satisfy an interface, with correct " +
            "signatures and receiver. Only the missing methods are generated. Set apply to " +
            "append them to the file declaring the type; otherwise the code is returned for " +
            "review.",
    )
    suspend fun go_implement_interface(
        @McpDescription("Type name, e.g. 'Circle'")
        typeName: String,
        @McpDescription("Interface name, e.g. 'Shape'")
        interfaceName: String,
        @McpDescription("Use a pointer receiver")
        pointerReceiver: Boolean,
        @McpDescription("Append the stubs to the file declaring the type")
        apply: Boolean,
    ): GoGeneratedCode = implementInterface(
        currentCoroutineContext().project, typeName, interfaceName, pointerReceiver, apply,
    )

    /** Testable core; the project is explicit so tests need no MCP call context. */
    internal suspend fun implementInterface(
        project: Project,
        typeName: String,
        interfaceName: String,
        pointerReceiver: Boolean,
        apply: Boolean,
    ): GoGeneratedCode {
        val satisfaction = readAction { facts.check(project, typeName, interfaceName) }
            ?: mcpFail(
                "Could not resolve type '$typeName' or interface '$interfaceName'. " +
                    "Both must exist in the project or its dependencies.",
            )

        if (satisfaction.missingSignatures.isEmpty()) {
            return GoGeneratedCode(
                code = "",
                path = "",
                applied = false,
                diff = "",
                hint = if (satisfaction.satisfied) {
                    "$typeName already satisfies $interfaceName; nothing to generate."
                } else {
                    "$typeName declares every method of $interfaceName, but " +
                        "${satisfaction.checkedAs} is the form that satisfies it. " +
                        "Use go_interface_check for the detail."
                },
            )
        }

        val code = GoGeneration.methodStubs(
            typeName, satisfaction.missingSignatures, pointerReceiver,
        )
        if (!apply) {
            return GoGeneratedCode(code, "", false, "", "Set apply to append this to the source file.")
        }

        val location = readAction {
            symbols.lookup(project, dev.salatmaster.golandmcp.common.parseSymbolRef(typeName))
        }
        val path = (location as? dev.salatmaster.golandmcp.go.GoLookupResult.Found)
            ?.symbol?.location?.substringBeforeLast(':')
            ?: mcpFail("Generated the stubs but could not locate the file declaring '$typeName'.")

        return appendToFile(project, path, code)
            .withSuccessHint("Appended ${satisfaction.missingSignatures.size} stub(s).")
    }

    @McpTool
    @McpDescription(
        "Generate Go struct declarations from a JSON sample, with json tags matching the " +
            "original keys and names following Go conventions, including initialisms such as " +
            "ID and URL. Nested objects become their own types.",
    )
    suspend fun go_type_from_json(
        @McpDescription("A JSON object, or an array whose first element is representative")
        jsonSample: String,
        @McpDescription("Name for the root type, e.g. 'User'")
        rootTypeName: String,
        @McpDescription("File to append the declarations to, relative to the project root; empty to only return them")
        path: String,
    ): GoGeneratedCode =
        typeFromJson(currentCoroutineContext().project, jsonSample, rootTypeName, path)

    /** Testable core; the project is explicit so tests need no MCP call context. */
    internal suspend fun typeFromJson(
        project: Project,
        jsonSample: String,
        rootTypeName: String,
        path: String,
    ): GoGeneratedCode {
        if (rootTypeName.isBlank()) mcpFail("rootTypeName must not be blank")

        val code = try {
            GoTypeFromJson.convert(jsonSample, rootTypeName)
        } catch (e: GoTypeFromJson.ConversionException) {
            mcpFail(e.message ?: "Could not convert the sample")
        }

        return if (path.isBlank()) {
            GoGeneratedCode(code, "", false, "", "Pass a path to append these declarations.")
        } else {
            appendToFile(project, path, code)
        }
    }

    @McpTool
    @McpDescription(
        "Generate a table-driven test skeleton for a Go function or method — the idiom Go " +
            "reviewers expect. The cases are left as TODOs for the caller to fill in.",
    )
    suspend fun go_generate_test(
        @McpDescription("Function or method name, e.g. 'Area'")
        functionName: String,
        @McpDescription("Receiver type for a method, e.g. 'Rect'; empty for a plain function")
        receiverType: String,
        @McpDescription("Test file to append to, relative to the project root; empty to only return the code")
        path: String,
    ): GoGeneratedCode =
        generateTest(currentCoroutineContext().project, functionName, receiverType, path)

    /** Testable core; the project is explicit so tests need no MCP call context. */
    internal suspend fun generateTest(
        project: Project,
        functionName: String,
        receiverType: String,
        path: String,
    ): GoGeneratedCode {
        if (functionName.isBlank()) mcpFail("functionName must not be blank")

        val code = GoGeneration.tableTest(functionName, receiverType)
        return if (path.isBlank()) {
            GoGeneratedCode(code, "", false, "", "Pass a path to append this test.")
        } else {
            appendToFile(project, path, code)
                .withSuccessHint("Remember that the file needs an import of \"testing\".")
        }
    }

    @McpTool
    @McpDescription(
        "Add imports to a Go file and tidy the import block: unused imports are removed and " +
            "the rest sorted. Adding an import that is already present is a no-op, so this " +
            "is safe to call repeatedly after generating code.",
    )
    suspend fun go_fix_imports(
        @McpDescription("Path to the Go file, relative to the project root")
        path: String,
        @McpDescription("Import paths to add, e.g. ['fmt', 'net/http']; may be empty")
        importsToAdd: List<String>,
        @McpDescription("Remove unused imports and sort the block")
        optimize: Boolean,
    ): GoGeneratedCode =
        fixImports(currentCoroutineContext().project, path, importsToAdd, optimize)

    /** Testable core; the project is explicit so tests need no MCP call context. */
    internal suspend fun fixImports(
        project: Project,
        path: String,
        importsToAdd: List<String>,
        optimize: Boolean,
    ): GoGeneratedCode = withContext(Dispatchers.EDT) {
        val resolved = resolveFile(project, path)
            ?: return@withContext GoGeneratedCode(
                "", path, false, "", "File not found: $path",
            )
        if (!imports.supports(resolved.psiFile)) {
            return@withContext GoGeneratedCode(
                "", path, false, "", "Not a Go file: $path",
            )
        }

        val before = resolved.document.text
        // The optimizer's Runnable must be produced before the edit and run inside the same
        // write action, which is the contract the platform's ImportOptimizer expects.
        val optimizeTask = if (optimize) imports.optimizeTask(resolved.psiFile) else null

        writeToDocument(project, "Fix imports in $path") {
            importsToAdd.filter { it.isNotBlank() }.forEach {
                imports.addImport(resolved.psiFile, it, "")
            }
            optimizeTask?.run()
        }

        val after = resolved.document.text
        GoGeneratedCode(
            code = "",
            path = path,
            applied = before != after,
            diff = unifiedDiff(path, before, after),
            hint = if (before == after) "Imports were already correct; nothing changed." else "",
        )
    }

    /**
     * Adds a hint only when the write succeeded.
     *
     * Overwriting it unconditionally would replace the reason a write failed with advice
     * about the write that never happened.
     */
    private fun GoGeneratedCode.withSuccessHint(hint: String): GoGeneratedCode =
        if (applied) copy(hint = hint) else this

    private suspend fun appendToFile(
        project: Project,
        path: String,
        code: String,
    ): GoGeneratedCode = withContext(Dispatchers.EDT) {
        val resolved = resolveFile(project, path)
            ?: return@withContext GoGeneratedCode(
                code, path, false, "", "File not found: $path. The code above was not written.",
            )

        val document = resolved.document
        val before = document.text
        val separator = if (before.endsWith("\n")) "\n" else "\n\n"

        writeToDocument(project, "Generate Go code in $path") {
            document.setText(before + separator + code + "\n")
        }
        GoGeneratedCode(code, path, true, unifiedDiff(path, before, document.text), "")
    }
}
