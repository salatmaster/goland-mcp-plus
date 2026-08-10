package dev.salatmaster.golandmcp.toolset

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.project
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.psi.PsiDocumentManager
import dev.salatmaster.golandmcp.common.cleanPath
import dev.salatmaster.golandmcp.common.createFile
import dev.salatmaster.golandmcp.common.resolveFile
import dev.salatmaster.golandmcp.common.unifiedDiff
import dev.salatmaster.golandmcp.common.writeToDocument
import dev.salatmaster.golandmcp.go.GoGeneration
import dev.salatmaster.golandmcp.go.GoMethodRequirement
import dev.salatmaster.golandmcp.go.GoImportsImpl
import dev.salatmaster.golandmcp.go.GoInterfaceFactsImpl
import dev.salatmaster.golandmcp.go.GoSymbolsImpl
import dev.salatmaster.golandmcp.go.GoTypeFromJson
import dev.salatmaster.golandmcp.metrics.tracked
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
    ): GoGeneratedCode =
        tracked("go_implement_interface") {
            implementInterface(
                currentCoroutineContext().project, typeName, interfaceName, pointerReceiver, apply,
            )
        }

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
        "Extract an interface from a Go type's methods, with the signatures the type " +
            "actually declares. By default only exported methods are included, since " +
            "unexported ones cannot be satisfied from another package. Note this generates " +
            "the interface; it does not rewrite existing uses of the type to refer to it.",
    )
    suspend fun go_extract_interface(
        @McpDescription("Type to extract from, e.g. 'Rect'")
        typeName: String,
        @McpDescription("Name for the new interface, e.g. 'Shaper'")
        interfaceName: String,
        @McpDescription("Method names to include; empty means every exported method")
        methodNames: List<String>,
        @McpDescription("File to append the interface to, relative to the project root; empty to only return it")
        path: String,
    ): GoGeneratedCode =
        tracked("go_extract_interface") {
            extractInterface(
                currentCoroutineContext().project, typeName, interfaceName, methodNames, path,
            )
        }

    /** Testable core; the project is explicit so tests need no MCP call context. */
    internal suspend fun extractInterface(
        project: Project,
        typeName: String,
        interfaceName: String,
        methodNames: List<String>,
        path: String,
    ): GoGeneratedCode {
        if (interfaceName.isBlank()) mcpFail("interfaceName must not be blank")

        val declared = readAction { facts.methodsOf(project, typeName) }
        if (declared.isEmpty()) {
            mcpFail(
                "No type named '$typeName' was found, or it declares no methods. " +
                    "An interface can only be extracted from a type that has some.",
            )
        }

        val selected: List<GoMethodRequirement> = if (methodNames.isEmpty()) {
            declared.filter { it.name.firstOrNull()?.isUpperCase() == true }
        } else {
            val known = declared.associateBy { it.name }
            val missing = methodNames.filterNot { known.containsKey(it) }
            if (missing.isNotEmpty()) {
                mcpFail(
                    "$typeName has no method(s) named ${missing.joinToString(", ")}. " +
                        "It declares: ${declared.joinToString(", ") { it.name }}.",
                )
            }
            methodNames.mapNotNull { known[it] }
        }

        if (selected.isEmpty()) {
            mcpFail(
                "$typeName declares no exported methods, so the interface would be empty. " +
                    "Name the methods explicitly if you meant to include unexported ones.",
            )
        }

        val code = GoGeneration.interfaceDeclaration(
            interfaceName,
            selected,
            "$interfaceName is the behaviour extracted from $typeName.",
        )

        return if (path.isBlank()) {
            GoGeneratedCode(code, "", false, "", "Pass a path to append this interface.")
        } else {
            appendToFile(project, path, code)
                .withSuccessHint("Extracted ${selected.size} method(s) into $interfaceName.")
        }
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
        tracked("go_type_from_json") {
            typeFromJson(currentCoroutineContext().project, jsonSample, rootTypeName, path)
        }

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
        tracked("go_generate_test") {
            generateTest(currentCoroutineContext().project, functionName, receiverType, path)
        }

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
            appendToFile(project, path, code, importsForNewFile = listOf("testing"))
                .withSuccessHint("If the file already existed, make sure it imports \"testing\".")
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
        tracked("go_fix_imports") {
            fixImports(currentCoroutineContext().project, path, importsToAdd, optimize)
        }

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
            // The optimizer edits PSI and the platform may hold the document back until the
            // command ends. Reading the document before this reported an unchanged file
            // while the import block had in fact been rewritten.
            PsiDocumentManager.getInstance(project)
                .doPostponedOperationsAndUnblockDocument(resolved.document)
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
        importsForNewFile: List<String> = emptyList(),
    ): GoGeneratedCode = withContext(Dispatchers.EDT) {
        val resolved = resolveFile(project, path)
            ?: return@withContext createFileWithCode(project, path, code, importsForNewFile)

        val document = resolved.document
        val before = document.text
        val separator = if (before.endsWith("\n")) "\n" else "\n\n"

        writeToDocument(project, "Generate Go code in $path") {
            document.setText(before + separator + code + "\n")
        }
        GoGeneratedCode(code, path, true, unifiedDiff(path, before, document.text), "")
    }

    /**
     * Writes the generated code into a file that does not exist yet.
     *
     * Generating into a new file is the normal case rather than a failure: a table test
     * almost always means a `_test.go` nobody has created. The package clause is taken from a
     * sibling Go file so the new file joins the package it sits in, and falls back to the
     * directory name, which is the convention Go tooling itself assumes.
     */
    private fun createFileWithCode(
        project: Project,
        path: String,
        code: String,
        importsForNewFile: List<String>,
    ): GoGeneratedCode {
        if (!path.trim().endsWith(".go")) {
            return GoGeneratedCode(
                code, path, false, "",
                "Refusing to create '$path': generated Go must go in a .go file. " +
                    "The code above was not written.",
            )
        }

        val packageName = packageNameFor(project, path)
            ?: return GoGeneratedCode(
                code, path, false, "",
                "Cannot tell which package '$path' belongs to. The code above was not written.",
            )

        val header = buildString {
            append("package ").append(packageName).append("\n")
            val wanted = importsForNewFile.filter { it.isNotBlank() }
            if (wanted.isNotEmpty()) {
                append("\n")
                if (wanted.size == 1) {
                    append("import \"").append(wanted.single()).append("\"\n")
                } else {
                    append("import (\n")
                    wanted.forEach { append("\t\"").append(it).append("\"\n") }
                    append(")\n")
                }
            }
        }
        val content = header + "\n" + code + "\n"

        val created = createFile(project, path, content)
            ?: return GoGeneratedCode(
                code, path, false, "", "Could not create '$path'. The code above was not written.",
            )

        return GoGeneratedCode(
            code = code,
            path = path,
            applied = true,
            diff = unifiedDiff(path, "", created.document.text),
            hint = "Created $path in package $packageName.",
        )
    }

    /** The package a new file in this directory belongs to, read off a sibling Go file. */
    private fun packageNameFor(project: Project, path: String): String? {
        val relative = cleanPath(path).removePrefix("/")
        val directory = relative.substringBeforeLast('/', "")
        val siblingPackage = ProjectRootManager.getInstance(project).contentRoots
            .asSequence()
            .mapNotNull { root ->
                if (directory.isEmpty()) root else root.findFileByRelativePath(directory)
            }
            .filter { it.isDirectory }
            .flatMap { it.children.asSequence() }
            .filter { !it.isDirectory && it.name.endsWith(".go") }
            .mapNotNull { PACKAGE_CLAUSE.find(String(it.contentsToByteArray()))?.groupValues?.get(1) }
            .firstOrNull()
        if (siblingPackage != null) return siblingPackage

        val fromDirectory = directory.substringAfterLast('/')
            .replace('-', '_')
            .filter { it.isLetterOrDigit() || it == '_' }
        return fromDirectory.takeIf { it.isNotEmpty() && !it.first().isDigit() }
    }

    private companion object {
        val PACKAGE_CLAUSE = Regex("""^\s*package\s+(\w+)""", RegexOption.MULTILINE)
    }
}
