package dev.salatmaster.golandmcp.go

import com.goide.psi.GoInterfaceType
import com.goide.psi.GoMethodDeclaration
import com.goide.psi.GoMethodSpec
import com.goide.psi.GoTypeSpec
import com.goide.stubs.index.GoMethodFingerprintIndex
import com.goide.stubs.index.GoTypesIndex
import com.intellij.openapi.project.Project
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.stubs.StubIndex
import dev.salatmaster.golandmcp.common.formatLocation

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
    }

    private fun findType(project: Project, scope: GlobalSearchScope, name: String): GoTypeSpec? =
        GoTypesIndex.find(name, project, scope, null).firstOrNull()

    private fun findInterfaceSpec(
        project: Project,
        scope: GlobalSearchScope,
        name: String,
    ): GoTypeSpec? = GoTypesIndex.find(name, project, scope, null)
        .firstOrNull { it.specType?.type is GoInterfaceType }

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
        val pointerOnly = mutableListOf<String>()
        val mismatched = mutableListOf<GoMethodRequirement>()

        for (spec: GoMethodSpec in iface.allMethods) {
            val name = spec.name ?: continue
            val impl = provided[name]
            if (impl == null) {
                missing += name
                continue
            }
            if (isPointerReceiver(impl)) pointerOnly += name

            val want = normalizeSignature(spec.signature?.text)
            val got = normalizeSignature(impl.signature?.text)
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
        )
    }

    private fun isPointerReceiver(method: GoMethodDeclaration): Boolean =
        method.receiver?.type?.text?.trimStart()?.startsWith("*") == true

    /** Signatures differ in whitespace; compare with runs collapsed. */
    private fun normalizeSignature(text: String?): String =
        text.orEmpty().replace(Regex("\\s+"), " ").trim()
}
