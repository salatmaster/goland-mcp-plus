package dev.salatmaster.golandmcp.go

import com.goide.psi.GoFile
import com.goide.psi.GoInterfaceType
import com.goide.psi.GoMethodDeclaration
import com.goide.psi.GoNamedElement
import com.goide.psi.GoStructType
import com.goide.psi.GoTypeSpec
import com.goide.stubs.index.GoPackagesIndex
import com.intellij.openapi.project.Project
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.stubs.StubIndex
import dev.salatmaster.golandmcp.common.formatLocation
import dev.salatmaster.golandmcp.common.projectFirst

class GoPackagesImpl(
    private val docs: GoDocs = GoDocsImpl(),
) : GoPackages {

    override fun api(
        project: Project,
        reference: String,
        includeUnexported: Boolean,
    ): GoPackageApi? = guardGoApi("package api") {
        val files = resolvePackageFiles(project, reference).takeIf { it.isNotEmpty() }
            ?: return@guardGoApi null

        val visible: (GoNamedElement) -> Boolean = { includeUnexported || it.isPublic }

        val types = files.flatMap { it.types }
            .filter(visible)
            .map { describeType(project, it, includeUnexported) }
            .sortedBy { it.name }

        // Methods belong to their receiver's type, not to the package listing.
        val functions = files.flatMap { it.functions }
            .filter(visible)
            .map { describeFunction(project, it, receiver = "") }
            .sortedBy { it.name }

        val constants = files.flatMap { it.constants }.filter(visible)
            .map { describeValue(project, it) }.sortedBy { it.name }
        val variables = files.flatMap { it.vars }.filter(visible)
            .map { describeValue(project, it) }.sortedBy { it.name }

        GoPackageApi(
            packagePath = files.first().getImportPath(false).orEmpty(),
            packageName = files.first().packageName.orEmpty(),
            files = files.mapNotNull { it.virtualFile?.name }.sorted(),
            types = types,
            functions = functions,
            constants = constants,
            variables = variables,
        )
    }

    /**
     * Accepts an import path, a project-relative directory, or a bare package name.
     *
     * Candidates come from the package-name index and are then filtered by import path, so
     * `example.com/basic`, `./src/.../basic` and `basic` all converge on the same files.
     */
    private fun resolvePackageFiles(project: Project, reference: String): List<GoFile> {
        val scope = GlobalSearchScope.allScope(project)
        val normalized = reference.trim().removePrefix("./").removeSuffix("/")
        val lastSegment = normalized.substringAfterLast('/')

        val byName = StubIndex.getElements(
            GoPackagesIndex.KEY, lastSegment, project, scope, GoFile::class.java,
        ).toList()
            .projectFirst(project)

        if (byName.isEmpty()) return emptyList()

        // A bare name is unambiguous only when one package carries it; otherwise the caller
        // gave a path and we match on it.
        val exact = byName.filter { file ->
            val path = file.getImportPath(false).orEmpty()
            path == normalized ||
                path.endsWith("/$normalized") ||
                file.virtualFile?.parent?.path?.endsWith(normalized) == true
        }
        val chosen = exact.ifEmpty { byName }

        // Keep only the files of a single package: the first match decides which.
        val target = chosen.first().getImportPath(false)
        return chosen.filter { it.getImportPath(false) == target }
    }

    private fun describeType(
        project: Project,
        spec: GoTypeSpec,
        includeUnexported: Boolean,
    ): GoTypeInfo {
        val underlyingType = spec.specType?.type
        val kind = when (underlyingType) {
            is GoInterfaceType -> GoSymbolKind.INTERFACE
            else -> GoSymbolKind.TYPE
        }
        val fields = (underlyingType as? GoStructType)
            ?.let { describeFields(it, includeUnexported) }
            ?: emptyList()

        return GoTypeInfo(
            name = spec.name.orEmpty(),
            kind = kind,
            underlying = underlyingType?.text?.lineSequence()?.first()?.trim().orEmpty(),
            doc = docs.docComment(spec).orEmpty(),
            location = formatLocation(project, spec),
            fields = fields,
            methods = spec.methods
                .filter { includeUnexported || it.isPublic }
                .map { describeFunction(project, it, receiver = it.receiver?.text.orEmpty()) }
                .sortedBy { it.name },
        )
    }

    private fun describeFields(struct: GoStructType, includeUnexported: Boolean): List<GoFieldInfo> =
        struct.fieldDeclarationList.flatMap { declaration ->
            val tag = declaration.tagText.orEmpty()
            val typeText = declaration.type?.text?.replace(Regex("\\s+"), " ")?.trim().orEmpty()

            val anonymous = declaration.anonymousFieldDefinition
            if (anonymous != null) {
                listOf(
                    GoFieldInfo(
                        name = anonymous.name.orEmpty(),
                        type = anonymous.text.trim(),
                        tag = tag,
                        embedded = true,
                        exported = anonymous.isPublic,
                    ),
                )
            } else {
                declaration.fieldDefinitionList
                    .filter { includeUnexported || it.isPublic }
                    .map { definition ->
                        GoFieldInfo(
                            name = definition.name.orEmpty(),
                            type = typeText,
                            tag = tag,
                            embedded = false,
                            exported = definition.isPublic,
                        )
                    }
            }
        }

    private fun describeFunction(
        project: Project,
        element: GoNamedElement,
        receiver: String,
    ): GoFunctionInfo {
        val signature = when (element) {
            is GoMethodDeclaration -> element.signature?.text
            is com.goide.psi.GoFunctionDeclaration -> element.signature?.text
            else -> null
        }.orEmpty().replace(Regex("\\s+"), " ").trim()

        return GoFunctionInfo(
            name = element.name.orEmpty(),
            signature = signature,
            doc = docs.docComment(element).orEmpty(),
            location = formatLocation(project, element),
            receiver = receiver.replace(Regex("\\s+"), " ").trim(),
        )
    }

    private fun describeValue(project: Project, element: GoNamedElement): GoValueInfo =
        GoValueInfo(
            name = element.name.orEmpty(),
            type = element.getGoType(null)?.text?.replace(Regex("\\s+"), " ")?.trim().orEmpty(),
            doc = docs.docComment(element).orEmpty(),
            location = formatLocation(project, element),
        )
}
