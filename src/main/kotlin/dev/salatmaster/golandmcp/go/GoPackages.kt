package dev.salatmaster.golandmcp.go

import com.intellij.openapi.project.Project

/** A struct field, including its tag — the part agents most often need to get right. */
data class GoFieldInfo(
    val name: String,
    val type: String,
    val tag: String,
    val embedded: Boolean,
    val exported: Boolean,
)

data class GoFunctionInfo(
    val name: String,
    val signature: String,
    val doc: String,
    val location: String,
    /** Empty for plain functions. */
    val receiver: String,
)

data class GoTypeInfo(
    val name: String,
    val kind: GoSymbolKind,
    val underlying: String,
    val doc: String,
    val location: String,
    val fields: List<GoFieldInfo>,
    val methods: List<GoFunctionInfo>,
)

data class GoValueInfo(
    val name: String,
    val type: String,
    val doc: String,
    val location: String,
)

data class GoPackageApi(
    val packagePath: String,
    val packageName: String,
    val files: List<String>,
    val types: List<GoTypeInfo>,
    val functions: List<GoFunctionInfo>,
    val constants: List<GoValueInfo>,
    val variables: List<GoValueInfo>,
)

/**
 * Reads a package's declarations.
 *
 * A Go package is spread across files, so the unit an agent actually reasons about has no
 * single file to open. Call inside a read action.
 */
interface GoPackages {
    /**
     * Resolves [reference] as an import path (`example.com/basic`), a path relative to the
     * project root, or a bare package name. Returns null when nothing matches.
     */
    fun api(project: Project, reference: String, includeUnexported: Boolean): GoPackageApi?
}
