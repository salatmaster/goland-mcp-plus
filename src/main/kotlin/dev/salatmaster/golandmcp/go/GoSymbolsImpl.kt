package dev.salatmaster.golandmcp.go

import com.goide.psi.GoConstDefinition
import com.goide.psi.GoFile
import com.goide.psi.GoInterfaceType
import com.goide.psi.GoMethodDeclaration
import com.goide.psi.GoNamedElement
import com.goide.psi.GoTypeSpec
import com.goide.psi.GoVarDefinition
import com.goide.stubs.index.GoAllPrivateNamesIndex
import com.goide.stubs.index.GoAllPublicNamesIndex
import com.goide.stubs.index.GoFunctionIndex
import com.goide.stubs.index.GoTypesIndex
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.stubs.StubIndex
import com.intellij.util.Processor
import dev.salatmaster.golandmcp.common.SymbolRef
import dev.salatmaster.golandmcp.common.formatLocation
import dev.salatmaster.golandmcp.common.isInProjectContent
import dev.salatmaster.golandmcp.common.projectFirst

class GoSymbolsImpl(
    private val docs: GoDocs = GoDocsImpl(),
) : GoSymbols {

    override fun lookup(project: Project, ref: SymbolRef): GoLookupResult = guardGoApi("lookup") {
        val scope = GlobalSearchScope.allScope(project)
        val candidates = candidatesFor(project, scope, ref)

        // Project code first: a bare name like `Rect` also matches image, cmplx and
        // windows once the Go SDK is indexed, and the stub index order is arbitrary.
        val ranked = candidates.projectFirst(project)

        when (ranked.size) {
            0 -> GoLookupResult.NotFound
            1 -> GoLookupResult.Found(describe(project, ranked.single()))
            else -> GoLookupResult.Ambiguous(ranked.map { describe(project, it) })
        }
    }

    override fun sourceOf(project: Project, ref: SymbolRef): GoSourceResult? =
        guardGoApi("source of") {
            val found = lookupElement(project, ref) ?: return@guardGoApi null

            // The declaration, not the spec: `type Rect struct{...}` reads as Go, whereas the
            // spec alone drops the leading `type` keyword.
            val declaration = generateSequence(found as PsiElement) { it.parent }
                .takeWhile { it !is GoFile }
                .lastOrNull { it.text.isNotBlank() }
                ?: found

            GoSourceResult(
                qualifiedName = found.qualifiedName.orEmpty(),
                reference = referenceTo(found),
                packagePath = found.containingFile.getImportPath(false).orEmpty(),
                location = formatLocation(project, found),
                doc = docs.docComment(found).orEmpty(),
                source = declaration.text.trim(),
                external = !isInProjectContent(project, found),
            )
        }

    override fun declaration(project: Project, ref: SymbolRef): GoNamedElement? =
        guardGoApi("declaration") { lookupElement(project, ref) }

    private fun lookupElement(project: Project, ref: SymbolRef): GoNamedElement? {
        val scope = GlobalSearchScope.allScope(project)
        return candidatesFor(project, scope, ref).projectFirst(project).firstOrNull()
    }

    /**
     * Resolves a reference, falling back for the shape `pkg.Symbol`.
     *
     * Two dotted segments and no slash are ambiguous: `store.User` is a package and a type
     * just as plausibly as it is a type and a method. Reading it as `Type.Member` first keeps
     * the common case fast, and retrying as a package qualifier is what makes the form an
     * agent naturally writes after seeing an import resolve at all.
     */
    private fun candidatesFor(
        project: Project,
        scope: GlobalSearchScope,
        ref: SymbolRef,
    ): List<GoNamedElement> = when (ref) {
        is SymbolRef.Bare -> {
            val direct = lookupByName(project, scope, ref.typeName, ref.memberName)
            if (direct.isNotEmpty() || ref.typeName == null) {
                direct
            } else {
                lookupByName(project, scope, null, ref.memberName)
                    .filter { matchesPackage(it, ref.typeName) }
            }
        }

        is SymbolRef.Qualified -> lookupByName(project, scope, ref.typeName, ref.memberName)
            .filter { matchesPackage(it, ref.packagePath) }

        is SymbolRef.AtPosition -> emptyList()
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
        val declared = (types + funcs).toList()
        // Only when nothing else matched: this one scans index keys, so it should not sit on
        // the common path.
        return declared.ifEmpty { packageLevelValues(project, scope, memberName) }
    }

    /**
     * Package-level `var` and `const` declarations.
     *
     * They live in neither the type nor the function index, so every tool built on this
     * lookup answered "no such symbol" for an ordinary `var ErrNotFound = ...`, while
     * go_package_api listed it happily because it walks the file instead.
     *
     * The all-names indexes carry them, but keyed by `package.Name` rather than the bare
     * name, so a bare lookup has to find the key first.
     */
    private fun packageLevelValues(
        project: Project,
        scope: GlobalSearchScope,
        name: String,
    ): List<GoNamedElement> {
        val index = StubIndex.getInstance()
        val found = mutableListOf<GoNamedElement>()
        val collect = Processor<GoNamedElement> { element ->
            if (element is GoVarDefinition || element is GoConstDefinition) found += element
            true
        }

        for (indexKey in listOf(GoAllPublicNamesIndex.ALL_PUBLIC_NAMES, GoAllPrivateNamesIndex.ALL_PRIVATE_NAMES)) {
            val suffix = ".$name"
            for (key in index.getAllKeys(indexKey, project)) {
                if (key != name && !key.endsWith(suffix)) continue
                index.processElements(indexKey, key, project, scope, GoNamedElement::class.java, collect)
            }
        }
        return found
    }

    private fun matchesPackage(element: GoNamedElement, packagePath: String): Boolean {
        val file = element.containingFile as? GoFile ?: return false
        val normalized = packagePath.removePrefix("./").trim('/')
        if (normalized.isEmpty()) return false

        val importPath = file.getImportPath(false)
        // A relative reference like ./internal/store matches by suffix.
        if (importPath != null && (importPath == normalized || importPath.endsWith("/$normalized"))) {
            return true
        }
        // The import path needs a resolved module and is null without one; the package clause
        // is always there, and it is what 'store.User' names anyway.
        return file.packageName == normalized
    }

    /**
     * Builds `<import path>.<Type>[.<Member>]` — the one form every tool here accepts.
     *
     * Falls back to the package clause when the import path is unknown, which happens for a
     * file whose module has not been resolved.
     */
    private fun referenceTo(element: GoNamedElement): String {
        val file = element.containingFile as? GoFile
        val pkg = file?.getImportPath(false)?.takeIf { it.isNotEmpty() }
            ?: file?.packageName.orEmpty()
        val owner = (element as? GoMethodDeclaration)
            ?.receiverType
            ?.text
            ?.removePrefix("*")
            ?.substringAfterLast('.')
            ?.trim()
            .orEmpty()

        return listOf(pkg, owner, element.name.orEmpty())
            .filter { it.isNotEmpty() }
            .joinToString(".")
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
            reference = referenceTo(element),
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
