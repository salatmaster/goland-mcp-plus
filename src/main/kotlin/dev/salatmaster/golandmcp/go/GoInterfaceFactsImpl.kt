package dev.salatmaster.golandmcp.go

import com.goide.psi.GoInterfaceType
import com.goide.psi.GoMethodDeclaration
import com.goide.psi.GoMethodSpec
import com.goide.psi.GoParameters
import com.goide.psi.GoSignature
import com.goide.psi.GoType
import com.goide.psi.GoTypeSpec
import com.goide.stubs.index.GoMethodFingerprintIndex
import com.goide.stubs.index.GoMethodSpecFingerprintIndex
import com.goide.stubs.index.GoMethodSpecInheritanceIndex
import com.goide.stubs.index.GoTypesIndex
import com.intellij.openapi.project.Project
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.stubs.StubIndex
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.Processor
import dev.salatmaster.golandmcp.common.formatLocation
import dev.salatmaster.golandmcp.common.projectFirst

class GoInterfaceFactsImpl : GoInterfaceFacts {

    override fun check(
        project: Project,
        typeSpecName: String,
        interfaceName: String,
    ): GoSatisfaction? = guardGoApi("interface check") {
        val scope = GlobalSearchScope.allScope(project)
        val type = findType(project, scope, typeSpecName) ?: return@guardGoApi null
        val iface = findInterfaceSpec(project, scope, interfaceName) ?: return@guardGoApi null
        val ifaceType = iface.specType?.type as? GoInterfaceType ?: return@guardGoApi null
        satisfaction(type, ifaceType)
    }

    /**
     * Finds implementing types by taking the interface's first method, collecting every type
     * that declares a method of that name, and then checking full satisfaction on each.
     *
     * Two mechanisms were rejected. `DefinitionsScopedSearch` (backed by the Go plugin's
     * `GoInheritorsSearch`) computes its scope through `GoPathUseScope`, which is empty
     * without a configured Go SDK and silently returns nothing. `GoMethodIndex` is keyed by
     * the receiver's qualified name rather than the method name, so it cannot answer "who
     * declares Area".
     *
     * Deciding satisfaction with [satisfaction] — the same routine `check` uses — also means
     * the two tools cannot disagree about whether a given type implements a given interface.
     */
    override fun implementors(
        project: Project,
        interfaceName: String,
        limit: Int,
    ): List<GoImplementor> = guardGoApi("implementors") {
        val scope = GlobalSearchScope.allScope(project)
        val iface = findInterfaceSpec(project, scope, interfaceName)
            ?: return@guardGoApi emptyList()
        val ifaceType = iface.specType?.type as? GoInterfaceType
            ?: return@guardGoApi emptyList()

        val probeMethod = ifaceType.allMethods.firstNotNullOfOrNull { it.name }
            ?: return@guardGoApi emptyList() // empty interface: everything satisfies it

        val found = LinkedHashMap<String, GoImplementor>()
        for (candidate in candidatesDeclaring(project, scope, probeMethod)) {
            if (found.size >= limit) break
            if (candidate.specType?.type is GoInterfaceType) continue
            val name = candidate.name ?: continue
            if (found.containsKey(name)) continue

            val result = satisfaction(candidate, ifaceType)
            // pointerOnly alone still counts: *T implements even when T does not.
            if (result.missing.isNotEmpty() || result.mismatched.isNotEmpty()) continue

            found[name] = GoImplementor(
                typeName = name,
                qualifiedName = candidate.qualifiedName.orEmpty(),
                packagePath = candidate.containingFile.getImportPath(false).orEmpty(),
                location = formatLocation(project, candidate),
                requiresPointer = result.checkedAs.startsWith("*"),
            )
        }

        found.values.toList()
    }

    override fun methodsOf(
        project: Project,
        typeName: String,
    ): List<GoMethodRequirement> = guardGoApi("methods of") {
        val scope = GlobalSearchScope.allScope(project)
        val type = findType(project, scope, typeName) ?: return@guardGoApi emptyList()

        type.methods.mapNotNull { method ->
            val name = method.name ?: return@mapNotNull null
            GoMethodRequirement(
                name = name,
                signature = signatureShape(method.signature)
                    .let { method.signature?.text?.replace(Regex("\\s+"), " ")?.trim().orEmpty() },
                satisfiedBy = null,
                pointerReceiverOnly = isPointerReceiver(method),
            )
        }
    }

    /**
     * The interfaces a type satisfies.
     *
     * Candidates come from two directions, because neither alone is complete. Interfaces
     * that declare a method of the right name are found through `GoMethodSpecFingerprintIndex`.
     * Interfaces assembled purely by embedding — `ReadWriter` declares nothing itself — never
     * appear there, so each direct candidate is then widened through
     * `GoMethodSpecInheritanceIndex`, which is keyed by the embedded interface's name.
     */
    override fun interfacesOf(
        project: Project,
        typeName: String,
        limit: Int,
    ): List<GoSatisfiedInterface> = guardGoApi("interfaces of") {
        val scope = GlobalSearchScope.allScope(project)
        val type = findType(project, scope, typeName) ?: return@guardGoApi emptyList()
        val methodNames = type.methods.mapNotNull { it.name }.toSet()
        if (methodNames.isEmpty()) return@guardGoApi emptyList()

        val direct = interfacesDeclaringAny(project, scope, methodNames)
        val candidates = withEmbedders(project, scope, direct).projectFirst(project)

        val found = LinkedHashMap<String, GoSatisfiedInterface>()
        for (candidate in candidates) {
            if (found.size >= limit) break
            val ifaceType = candidate.specType?.type as? GoInterfaceType ?: continue
            // An empty interface is satisfied by everything, so reporting it says nothing.
            if (ifaceType.allMethods.isEmpty()) continue
            val name = candidate.name ?: continue
            if (found.containsKey(name)) continue

            val result = satisfaction(type, ifaceType)
            if (result.missing.isNotEmpty() || result.mismatched.isNotEmpty()) continue

            found[name] = GoSatisfiedInterface(
                interfaceName = name,
                qualifiedName = candidate.qualifiedName.orEmpty(),
                packagePath = candidate.containingFile.getImportPath(false).orEmpty(),
                location = formatLocation(project, candidate),
                requiresPointer = result.checkedAs.startsWith("*"),
            )
        }

        found.values.toList()
    }

    /**
     * Grows a candidate set by repeatedly adding the interfaces that embed it.
     *
     * Embedding chains, so one pass is not enough: an interface may embed one that itself
     * embeds another. The depth cap keeps a cyclic or pathological hierarchy from looping;
     * real Go code nests far shallower than this.
     */
    private fun withEmbedders(
        project: Project,
        scope: GlobalSearchScope,
        seeds: List<GoTypeSpec>,
    ): List<GoTypeSpec> {
        val all = LinkedHashSet(seeds)
        var frontier = seeds
        repeat(MAX_EMBEDDING_DEPTH) {
            val next = frontier.asSequence()
                .mapNotNull { it.name }
                .flatMap { GoMethodSpecInheritanceIndex.find(it, project, scope).asSequence() }
                .mapNotNull { PsiTreeUtil.getParentOfType(it, GoTypeSpec::class.java) }
                .filter { all.add(it) }
                .toList()
            if (next.isEmpty()) return all.toList()
            frontier = next
        }
        return all.toList()
    }

    /** Interface declarations containing a method spec named by any of [methodNames]. */
    private fun interfacesDeclaringAny(
        project: Project,
        scope: GlobalSearchScope,
        methodNames: Set<String>,
    ): List<GoTypeSpec> {
        val keys = StubIndex.getInstance()
            .getAllKeys(GoMethodSpecFingerprintIndex.KEY, project)
            .filter { it.substringBefore('/') in methodNames }

        val owners = LinkedHashSet<GoTypeSpec>()
        for (key in keys) {
            GoMethodSpecFingerprintIndex.process(
                key, project, scope,
                Processor { spec ->
                    PsiTreeUtil.getParentOfType(spec, GoTypeSpec::class.java)?.let(owners::add)
                    true
                },
            )
        }
        return owners.toList()
    }

    /**
     * Types declaring a method called [methodName].
     *
     * `GoMethodFingerprintIndex` is keyed by `name/arity` (`Area/0`). Rather than computing
     * the arity — which would mean replicating how the plugin counts grouped and variadic
     * parameters — this matches every key whose name part agrees, so only the `name/…` shape
     * is assumed.
     */
    private fun candidatesDeclaring(
        project: Project,
        scope: GlobalSearchScope,
        methodName: String,
    ): List<GoTypeSpec> {
        val keys = StubIndex.getInstance()
            .getAllKeys(GoMethodFingerprintIndex.KEY, project)
            .filter { it.substringBefore('/') == methodName }

        return keys.asSequence()
            .flatMap { GoMethodFingerprintIndex.find(it, project, scope, null).asSequence() }
            .mapNotNull { it.resolveTypeSpec() }
            .toList()
            .projectFirst(project)
    }

    /**
     * Project declarations win over SDK ones. `Reader` matches io, bufio, csv and more once
     * the Go SDK is indexed, and answering about the wrong one is worse than answering slowly.
     */
    private fun findType(project: Project, scope: GlobalSearchScope, name: String): GoTypeSpec? =
        GoTypesIndex.find(name, project, scope, null).toList().projectFirst(project).firstOrNull()

    private fun findInterfaceSpec(
        project: Project,
        scope: GlobalSearchScope,
        name: String,
    ): GoTypeSpec? = GoTypesIndex.find(name, project, scope, null)
        .filter { it.specType?.type is GoInterfaceType }
        .projectFirst(project)
        .firstOrNull()

    /**
     * Compares the interface's full method set (embedding included) against the type's own
     * methods, tracking which of them exist only on the pointer receiver — the reason a type
     * can look complete yet still fail to satisfy.
     */
    private fun satisfaction(type: GoTypeSpec, iface: GoInterfaceType): GoSatisfaction {
        val provided: Map<String, GoMethodDeclaration> = type.methods
            .mapNotNull { m -> m.name?.let { it to m } }
            .toMap()

        val missing = mutableListOf<String>()
        val missingSignatures = mutableListOf<GoMethodRequirement>()
        val pointerOnly = mutableListOf<String>()
        val mismatched = mutableListOf<GoMethodRequirement>()

        for (spec: GoMethodSpec in iface.allMethods) {
            val name = spec.name ?: continue
            val impl = provided[name]
            if (impl == null) {
                missing += name
                missingSignatures += GoMethodRequirement(
                    name = name,
                    signature = spec.signature?.text?.replace(Regex("\\s+"), " ")?.trim().orEmpty(),
                    satisfiedBy = null,
                    pointerReceiverOnly = false,
                )
                continue
            }
            if (isPointerReceiver(impl)) pointerOnly += name

            val want = signatureShape(spec.signature)
            val got = signatureShape(impl.signature)
            if (want != got) {
                mismatched += GoMethodRequirement(
                    name = name,
                    signature = spec.signature?.text.orEmpty(),
                    satisfiedBy = impl.signature?.text,
                    pointerReceiverOnly = isPointerReceiver(impl),
                )
            }
        }

        val typeName = type.name.orEmpty()
        val satisfied = missing.isEmpty() && mismatched.isEmpty() && pointerOnly.isEmpty()
        val checkedAs = if (pointerOnly.isEmpty()) typeName else "*$typeName"

        return GoSatisfaction(
            satisfied = satisfied,
            checkedAs = checkedAs,
            missing = missing,
            pointerOnly = pointerOnly,
            mismatched = mismatched,
            missingSignatures = missingSignatures,
        )
    }

    private fun isPointerReceiver(method: GoMethodDeclaration): Boolean =
        method.receiver?.type?.text?.trimStart()?.startsWith("*") == true

    /**
     * Renders a signature as parameter and result types alone.
     *
     * Comparing signature text is wrong: Go ignores parameter names when deciding whether a
     * method satisfies an interface, so `Read(p []byte)` and `Read(b []byte)` are the same
     * method. Comparing the raw text marked every such pair as a mismatch.
     *
     * Type identity is still textual, so a declaration written as `[]uint8` will not match one
     * written as `[]byte`. Resolving those to canonical types is a deeper change; the shape
     * comparison already covers the case that actually occurs in practice.
     */
    private fun signatureShape(signature: GoSignature?): String {
        if (signature == null) return ""
        val params = parameterTypes(signature.parameters)
        val result = signature.result
        val results = when {
            result == null || result.isVoid -> emptyList()
            result.parameters != null -> parameterTypes(result.parameters)
            else -> listOfNotNull(typeText(result.type).takeIf { it.isNotEmpty() })
        }
        return "(${params.joinToString(",")})(${results.joinToString(",")})"
    }

    /** Expands grouped declarations: `a, b int` counts as two parameters of type int. */
    private fun parameterTypes(parameters: GoParameters?): List<String> {
        if (parameters == null) return emptyList()
        return parameters.parameterDeclarationList.flatMap { declaration ->
            val rendered = typeText(declaration.type) + if (declaration.isVariadic) "..." else ""
            val count = maxOf(1, declaration.paramDefinitionList.size)
            List(count) { rendered }
        }
    }

    private fun typeText(type: GoType?): String =
        type?.text?.replace(Regex("\\s+"), " ")?.trim().orEmpty()

    private companion object {
        const val MAX_EMBEDDING_DEPTH = 8
    }
}
