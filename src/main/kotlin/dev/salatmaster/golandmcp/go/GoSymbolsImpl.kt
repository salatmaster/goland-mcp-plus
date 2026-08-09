package dev.salatmaster.golandmcp.go

import com.goide.psi.GoInterfaceType
import com.goide.psi.GoMethodDeclaration
import com.goide.psi.GoNamedElement
import com.goide.psi.GoTypeSpec
import com.goide.stubs.index.GoFunctionIndex
import com.goide.stubs.index.GoTypesIndex
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import dev.salatmaster.golandmcp.common.SymbolRef
import dev.salatmaster.golandmcp.common.formatLocation
import dev.salatmaster.golandmcp.common.projectFirst

class GoSymbolsImpl(
    private val docs: GoDocs = GoDocsImpl(),
) : GoSymbols {

    override fun lookup(project: Project, ref: SymbolRef): GoLookupResult = guardGoApi("lookup") {
        val scope = GlobalSearchScope.allScope(project)
        val candidates = when (ref) {
            is SymbolRef.Bare -> lookupByName(project, scope, ref.typeName, ref.memberName)
            is SymbolRef.Qualified -> lookupByName(project, scope, ref.typeName, ref.memberName)
                .filter { matchesPackage(it, ref.packagePath) }
            is SymbolRef.AtPosition -> emptyList()
        }

        // Project code first: a bare name like `Rect` also matches image, cmplx and
        // windows once the Go SDK is indexed, and the stub index order is arbitrary.
        val ranked = candidates.projectFirst(project)

        when (ranked.size) {
            0 -> GoLookupResult.NotFound
            1 -> GoLookupResult.Found(describe(project, ranked.single()))
            else -> GoLookupResult.Ambiguous(ranked.map { describe(project, it) })
        }
    }

    /**
     * When [typeName] is present the reference names a method, so the lookup goes through
     * the owning type. Otherwise it may name a type or a function.
     *
     * Methods are deliberately not fetched from `GoMethodIndex`: that index is keyed by the
     * receiver's qualified name (`basic.Rect`), not by the method name, and reproducing that
     * key here would hard-code an undocumented format. Asking the type for its methods keeps
     * the index detail on the Go plugin's side and picks up embedded methods for free.
     */
    private fun lookupByName(
        project: Project,
        scope: GlobalSearchScope,
        typeName: String?,
        memberName: String,
    ): List<GoNamedElement> {
        if (typeName != null) {
            return GoTypesIndex.find(typeName, project, scope, null)
                .flatMap { typeSpec -> typeSpec.allMethods }
                .filterIsInstance<GoNamedElement>()
                .filter { it.name == memberName }
        }
        val types = GoTypesIndex.find(memberName, project, scope, null)
        val funcs = GoFunctionIndex.find(memberName, project, scope, null)
        return (types + funcs).toList()
    }

    private fun matchesPackage(element: GoNamedElement, packagePath: String): Boolean {
        val actual = element.containingFile.getImportPath(false) ?: return false
        if (actual == packagePath) return true
        // A relative reference like ./internal/store matches by suffix.
        val normalized = packagePath.removePrefix("./")
        return actual.endsWith("/$normalized") || actual == normalized
    }

    private fun describe(project: Project, element: GoNamedElement): GoSymbolInfo {
        val kind = when {
            element is GoTypeSpec && element.specType?.type is GoInterfaceType -> GoSymbolKind.INTERFACE
            element is GoTypeSpec -> GoSymbolKind.TYPE
            element is GoMethodDeclaration -> GoSymbolKind.METHOD
            else -> GoSymbolKind.FUNC
        }
        return GoSymbolInfo(
            kind = kind,
            name = element.name.orEmpty(),
            qualifiedName = element.qualifiedName.orEmpty(),
            packagePath = element.containingFile.getImportPath(false).orEmpty(),
            signature = signatureOf(element),
            doc = docs.docComment(element),
            location = formatLocation(project, element),
            exported = element.isPublic,
            deprecated = element.isDeprecated,
        )
    }

    private fun signatureOf(element: GoNamedElement): String = when (element) {
        is GoMethodDeclaration -> buildString {
            append("func ")
            element.receiver?.text?.let { append(it).append(' ') }
            append(element.name)
            append(element.signature?.text.orEmpty())
        }
        is GoTypeSpec -> "type ${element.name} ${element.specType?.type?.text.orEmpty()}"
        else -> element.text.substringBefore('{').trim()
    }

}
