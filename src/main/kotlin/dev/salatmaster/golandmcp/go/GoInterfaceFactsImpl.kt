package dev.salatmaster.golandmcp.go

import com.goide.psi.GoAnonymousFieldDefinition
import com.goide.psi.GoInterfaceType
import com.goide.psi.GoMethodDeclaration
import com.goide.psi.GoMethodSpec
import com.goide.psi.GoNamedSignatureOwner
import com.goide.psi.GoParameters
import com.goide.psi.GoSignature
import com.goide.psi.GoStructType
import com.goide.psi.GoType
import com.goide.psi.GoTypeSpec
import com.goide.psi.impl.GoTypeUtil
import com.goide.stubs.index.GoMethodFingerprintIndex
import com.goide.stubs.index.GoMethodSpecFingerprintIndex
import com.goide.stubs.index.GoMethodSpecInheritanceIndex
import com.goide.stubs.index.GoTypeSpecInheritanceIndex
import com.intellij.openapi.project.Project
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.stubs.StubIndex
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.Processor
import dev.salatmaster.golandmcp.common.SymbolRef
import dev.salatmaster.golandmcp.common.formatLocation
import dev.salatmaster.golandmcp.common.projectFirst

class GoInterfaceFactsImpl(
    private val symbols: GoSymbols = GoSymbolsImpl(),
) : GoInterfaceFacts {

    override fun check(
        project: Project,
        typeRef: SymbolRef,
        interfaceRef: SymbolRef,
    ): GoCheckOutcome = guardGoApi("interface check") {
        val type = findType(project, typeRef) ?: return@guardGoApi GoCheckOutcome.TypeNotFound
        val iface = findType(project, interfaceRef)
            ?: return@guardGoApi GoCheckOutcome.InterfaceNotFound
        val ifaceType = iface.specType?.type as? GoInterfaceType
            ?: return@guardGoApi GoCheckOutcome.NotAnInterface
        GoCheckOutcome.Checked(satisfaction(type, ifaceType))
    }

    /**
     * Finds implementing types by collecting candidates from the stub indexes and then
     * deciding satisfaction on each with [satisfaction] — the same routine `check` uses, so
     * the two tools cannot disagree about a given type and interface.
     *
     * Two mechanisms were rejected for finding candidates. `DefinitionsScopedSearch` (backed
     * by the Go plugin's `GoInheritorsSearch`) computes its scope through `GoPathUseScope`,
     * which is empty without a configured Go SDK and silently returns nothing. `GoMethodIndex`
     * is keyed by the receiver's qualified name rather than the method name, so it cannot
     * answer "who declares Area".
     */
    override fun implementors(
        project: Project,
        interfaceRef: SymbolRef,
        limit: Int,
    ): GoSearch<GoImplementor>? = guardGoApi("implementors") {
        val scope = GlobalSearchScope.allScope(project)
        val iface = findType(project, interfaceRef) ?: return@guardGoApi null
        val ifaceType = iface.specType?.type as? GoInterfaceType
            ?: return@guardGoApi null

        val methodNames = ifaceType.allMethods.mapNotNull { it.name }.toSet()
        // Every type satisfies the empty interface, so listing them would say nothing — but
        // an empty list must not be reported as "nothing implements this", which is the
        // opposite of the truth.
        if (methodNames.isEmpty()) {
            return@guardGoApi GoSearch(
                items = emptyList(),
                complete = true,
                note = "This interface declares no methods, so every type satisfies it and " +
                    "listing implementations would say nothing.",
            )
        }

        val candidates = implementorCandidates(project, scope, iface, methodNames)

        val found = LinkedHashMap<String, GoImplementor>()
        for (candidate in candidates.toList().projectFirst(project)) {
            if (found.size >= limit) break
            // Interfaces are reached as seeds, not as answers: an interface value is not an
            // implementation of anything.
            if (candidate.specType?.type is GoInterfaceType) continue
            val name = candidate.name ?: continue
            // Keyed by reference, not by name: two packages may each declare a Client, and
            // deduplicating on the bare name silently dropped the second one.
            val key = referenceTo(candidate)
            if (found.containsKey(key)) continue

            val result = satisfaction(candidate, ifaceType)
            // pointerOnly alone still counts: *T implements even when T does not.
            if (result.missing.isNotEmpty() || result.mismatched.isNotEmpty()) continue

            found[key] = GoImplementor(
                typeName = name,
                qualifiedName = candidate.qualifiedName.orEmpty(),
                reference = key,
                packagePath = candidate.containingFile.getImportPath(false).orEmpty(),
                location = formatLocation(project, candidate),
                requiresPointer = result.checkedAs.startsWith("*"),
            )
        }

        GoSearch(found.values.toList(), complete = !candidates.truncated)
    }

    /**
     * Every type that could implement the interface, from three sources.
     *
     * Each source alone misses a shape of Go code that really does implement:
     *
     * - Types **declaring** a method of the right name (`GoMethodFingerprintIndex`) — the
     *   ordinary case. Probed on every interface method rather than the first, because a type
     *   may declare one method and inherit the rest.
     * - Interfaces declaring one (`GoMethodSpecFingerprintIndex`), and the interface itself.
     *   Neither implements anything, but a struct that embeds one gets its whole method set,
     *   which is how mocks are usually written.
     * - Anything **embedding** a candidate, transitively. A wrapper that embeds a client and
     *   declares nothing appears in no method index at all, yet Go promotes every one of the
     *   client's methods into it — the false negative this search exists to avoid.
     *
     * Seeds are ordered project-first so that reaching the cap costs SDK types, not the
     * project's own.
     */
    private fun implementorCandidates(
        project: Project,
        scope: GlobalSearchScope,
        iface: GoTypeSpec,
        methodNames: Set<String>,
    ): CandidateSet {
        val candidates = CandidateSet(MAX_CANDIDATES)
        val roots = typesDeclaringAny(project, scope, methodNames) +
            interfacesDeclaringAny(project, scope, methodNames) +
            iface

        val seeds = mutableListOf<GoTypeSpec>()
        for (spec in roots.projectFirst(project)) {
            if (candidates.add(spec)) seeds += spec
        }
        addEmbedders(seeds, candidates) { name ->
            structsEmbedding(project, scope, name) + interfacesEmbedding(project, scope, name)
        }
        return candidates
    }

    override fun methodsOf(
        project: Project,
        typeRef: SymbolRef,
    ): List<GoMethodRequirement>? = guardGoApi("methods of") {
        val type = findType(project, typeRef) ?: return@guardGoApi null

        type.methods.mapNotNull { method ->
            val name = method.name ?: return@mapNotNull null
            GoMethodRequirement(
                name = name,
                signature = method.signature?.text?.replace(Regex("\\s+"), " ")?.trim().orEmpty(),
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
        typeRef: SymbolRef,
        limit: Int,
    ): GoSearch<GoSatisfiedInterface>? = guardGoApi("interfaces of") {
        val scope = GlobalSearchScope.allScope(project)
        val type = findType(project, typeRef) ?: return@guardGoApi null
        // getAllMethods, not methods: a type whose whole method set comes from an embedded
        // field declares nothing, and searching on declarations answered "satisfies nothing"
        // for exactly the types hardest to reason about by reading the source.
        val methodNames = type.getAllMethods(type).mapNotNull { it.name }.toSet()
        if (methodNames.isEmpty()) return@guardGoApi GoSearch(emptyList(), complete = true)

        val candidates = CandidateSet(MAX_CANDIDATES)
        val seeds = mutableListOf<GoTypeSpec>()
        for (spec in interfacesDeclaringAny(project, scope, methodNames).projectFirst(project)) {
            if (candidates.add(spec)) seeds += spec
        }
        addEmbedders(seeds, candidates) { name -> interfacesEmbedding(project, scope, name) }

        val found = LinkedHashMap<String, GoSatisfiedInterface>()
        for (candidate in candidates.toList().projectFirst(project)) {
            if (found.size >= limit) break
            val ifaceType = candidate.specType?.type as? GoInterfaceType ?: continue
            // An empty interface is satisfied by everything, so reporting it says nothing.
            if (ifaceType.allMethods.isEmpty()) continue
            // Asking what an interface satisfies reaches the interface itself, and "Reader
            // satisfies Reader" is not an answer.
            if (candidate.isEquivalentTo(type)) continue
            val name = candidate.name ?: continue
            val key = referenceTo(candidate)
            if (found.containsKey(key)) continue

            val result = satisfaction(type, ifaceType)
            if (result.missing.isNotEmpty() || result.mismatched.isNotEmpty()) continue

            found[key] = GoSatisfiedInterface(
                interfaceName = name,
                qualifiedName = candidate.qualifiedName.orEmpty(),
                reference = key,
                packagePath = candidate.containingFile.getImportPath(false).orEmpty(),
                location = formatLocation(project, candidate),
                requiresPointer = result.checkedAs.startsWith("*"),
            )
        }

        GoSearch(found.values.toList(), complete = !candidates.truncated)
    }

    /**
     * Grows a candidate set by repeatedly adding the types that embed one already in it.
     *
     * Embedding chains, so one pass is not enough: a wrapper may embed a wrapper, and an
     * interface may embed one that itself embeds another. The depth cap keeps a cyclic or
     * pathological hierarchy from looping; real Go code nests far shallower than this.
     */
    private fun addEmbedders(
        seeds: List<GoTypeSpec>,
        into: CandidateSet,
        embeddersOf: (String) -> Sequence<GoTypeSpec>,
    ) {
        var frontier = seeds
        repeat(MAX_EMBEDDING_DEPTH) {
            val next = mutableListOf<GoTypeSpec>()
            for (spec in frontier) {
                val name = spec.name ?: continue
                for (embedder in embeddersOf(name)) {
                    if (into.add(embedder)) next += embedder
                }
            }
            if (next.isEmpty()) return
            frontier = next
        }
    }

    /**
     * Structs with an anonymous field named [name].
     *
     * `GoTypeSpecInheritanceIndex` is keyed by the bare name of each embedded field, so
     * `struct { *billing.Client }` is found under `Client`. The name is unqualified, so this
     * also returns structs embedding some other type of the same name; they are candidates
     * only, and [satisfaction] rejects them.
     */
    private fun structsEmbedding(
        project: Project,
        scope: GlobalSearchScope,
        name: String,
    ): Sequence<GoTypeSpec> =
        GoTypeSpecInheritanceIndex.find(name, project, scope).asSequence()

    /** Interfaces that embed an interface named [name]. */
    private fun interfacesEmbedding(
        project: Project,
        scope: GlobalSearchScope,
        name: String,
    ): Sequence<GoTypeSpec> =
        GoMethodSpecInheritanceIndex.find(name, project, scope).asSequence()
            .mapNotNull { PsiTreeUtil.getParentOfType(it, GoTypeSpec::class.java) }

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
     * Types declaring a method named by any of [methodNames].
     *
     * `GoMethodFingerprintIndex` is keyed by `name/arity` (`Area/0`). Rather than computing
     * the arity — which would mean replicating how the plugin counts grouped and variadic
     * parameters — this matches every key whose name part agrees, so only the `name/…` shape
     * is assumed.
     */
    private fun typesDeclaringAny(
        project: Project,
        scope: GlobalSearchScope,
        methodNames: Set<String>,
    ): List<GoTypeSpec> {
        val keys = StubIndex.getInstance()
            .getAllKeys(GoMethodFingerprintIndex.KEY, project)
            .filter { it.substringBefore('/') in methodNames }

        return keys.asSequence()
            .flatMap { GoMethodFingerprintIndex.find(it, project, scope, null).asSequence() }
            .mapNotNull { it.resolveTypeSpec() }
            .toList()
    }

    /** `<import path>.<Type>`, falling back to the package clause when the module is unresolved. */
    private fun referenceTo(spec: GoTypeSpec): String {
        val file = spec.containingFile
        val pkg = file.getImportPath(false)?.takeIf { it.isNotEmpty() } ?: file.packageName.orEmpty()
        return listOf(pkg, spec.name.orEmpty()).filter { it.isNotEmpty() }.joinToString(".")
    }

    /**
     * One resolver, shared with go_symbol and its siblings.
     *
     * These tools used to call the type index with the raw string, so `billing.Client` and a
     * fully-qualified path both failed while the very same reference worked in every other
     * tool — and a bare name silently picked whichever `Client` the index returned first.
     */
    private fun findType(project: Project, ref: SymbolRef): GoTypeSpec? =
        symbols.declaration(project, ref) as? GoTypeSpec

    /**
     * Compares the interface's full method set (embedding included) against the type's own,
     * tracking which methods exist only on the pointer receiver — the reason a type can look
     * complete yet still fail to satisfy.
     */
    private fun satisfaction(type: GoTypeSpec, iface: GoInterfaceType): GoSatisfaction {
        // allMethods, not methods: Go promotes an embedded type's methods into the outer
        // one, so a struct that embeds a client and declares nothing satisfies the same
        // interfaces. Walking only declared methods reported every such type as missing all
        // of them.
        val provided: Map<String, GoNamedSignatureOwner> = type.getAllMethods(type)
            .mapNotNull { m -> m.name?.let { it to m } }
            .toMap()
        val declaredHere: Set<String> = type.methods.mapNotNull { it.name }.toSet()

        val missing = mutableListOf<String>()
        val missingSignatures = mutableListOf<GoMethodRequirement>()
        val pointerOnly = mutableListOf<String>()
        val pointerOnlyPromoted = mutableListOf<String>()
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

            val declaredBy = name in declaredHere
            val onlyOnPointer = onlyOnPointer(type, impl, declaredBy)
            if (onlyOnPointer) {
                pointerOnly += name
                if (!declaredBy) pointerOnlyPromoted += name
            }

            if (!signaturesMatch(spec, impl)) {
                mismatched += GoMethodRequirement(
                    name = name,
                    signature = spec.signature?.text.orEmpty(),
                    satisfiedBy = impl.signature?.text,
                    pointerReceiverOnly = onlyOnPointer,
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
            pointerOnlyPromoted = pointerOnlyPromoted,
            mismatched = mismatched,
            missingSignatures = missingSignatures,
        )
    }

    /**
     * Whether [type] provides this method only through its pointer form.
     *
     * For a method the type declares itself, that is exactly "the receiver is a pointer".
     * For a promoted one it depends on how the field is embedded: `struct { *Client }` puts
     * the methods of `*Client` into the method set of the wrapper value, while
     * `struct { Client }` puts them into `*Wrapper` alone — the same trap as a pointer
     * receiver, one level further away from the reader.
     *
     * Promotion through more than one hop is reported as pointer-only. `*T` is usable
     * wherever `T` is, so erring that way costs a caller nothing, while the opposite answer
     * does not compile.
     */
    private fun onlyOnPointer(
        type: GoTypeSpec,
        impl: GoNamedSignatureOwner,
        declaredBy: Boolean,
    ): Boolean {
        // An interface's method specs have no receiver, so nothing here is pointer-only.
        val method = impl as? GoMethodDeclaration ?: return false
        if (!isPointerReceiver(method)) return false
        if (declaredBy) return true
        return !embedsByPointer(type, receiverTypeName(method))
    }

    private fun embedsByPointer(type: GoTypeSpec, embedded: String): Boolean =
        anonymousFields(type).any { field ->
            field.name == embedded && field.text.trimStart().startsWith("*")
        }

    private fun anonymousFields(type: GoTypeSpec): List<GoAnonymousFieldDefinition> =
        (type.specType?.type as? GoStructType)
            ?.fieldDeclarationList
            ?.mapNotNull { it.anonymousFieldDefinition }
            .orEmpty()

    /** `*billing.Client` -> `Client`, matching how an embedded field is named. */
    private fun receiverTypeName(method: GoMethodDeclaration): String =
        method.receiver?.type?.text.orEmpty()
            .trim()
            .removePrefix("*")
            .substringBefore('[')
            .substringAfterLast('.')
            .trim()

    private fun isPointerReceiver(method: GoMethodDeclaration): Boolean =
        method.receiver?.type?.text?.trimStart()?.startsWith("*") == true

    /**
     * Whether a declared method satisfies an interface method.
     *
     * Two comparisons, because neither alone is right everywhere.
     *
     * The Go plugin's own [GoTypeUtil.areSignaturesIdentical] resolves types, so it settles
     * what text cannot: `[]byte` against `[]uint8`, an alias against its target. It is the
     * same call `GoImplementMethodsHandler` makes for this question. But it answers false
     * when it cannot resolve — without a configured SDK that is every builtin — so it counts
     * only as a positive.
     *
     * The fallback compares types by text with package qualifiers stripped. An interface
     * declared at the use site must write `billing.Spec` where the implementing package
     * writes `Spec`; comparing that text called the method mismatched and dropped the real
     * implementation, which is the failure this pair exists to prevent. Stripping qualifiers
     * can in principle match `a.T` against `b.T`, and that is the deliberate trade: claiming
     * a type does not satisfy an interface it does satisfy is the worse error, and the
     * compiler catches the other one immediately.
     */
    private fun signaturesMatch(spec: GoMethodSpec, impl: GoNamedSignatureOwner): Boolean =
        GoTypeUtil.areSignaturesIdentical(spec, impl, true) ||
            signatureShape(spec.signature) == signatureShape(impl.signature)

    /**
     * Renders a signature as parameter and result types alone.
     *
     * Go ignores parameter names when deciding satisfaction, so `Read(p []byte)` and
     * `Read(b []byte)` are the same method; comparing raw text marked every such pair as a
     * mismatch.
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

    /** Type text without package qualifiers, so `billing.Spec` and `Spec` compare equal. */
    private fun typeText(type: GoType?): String =
        type?.text
            ?.replace(WHITESPACE, " ")
            ?.replace(PACKAGE_QUALIFIER, "")
            ?.trim()
            .orEmpty()

    private companion object {
        const val MAX_EMBEDDING_DEPTH = 8

        /**
         * How many candidate types one search may collect.
         *
         * Probing on every interface method and then following embedding reaches far more
         * types than the old single-method probe did, and a pathological project should not
         * turn one tool call into an unbounded scan. Hitting the cap is reported rather than
         * hidden — see [CandidateSet].
         */
        const val MAX_CANDIDATES = 2_000

        val WHITESPACE = Regex("""\s+""")

        /** A leading `pkg.` on a type name, and nothing else: `[]billing.Spec` -> `[]Spec`. */
        val PACKAGE_QUALIFIER = Regex("""\b[\p{L}_][\p{L}\p{N}_]*\.""")
    }
}

/**
 * The types a search has collected, bounded.
 *
 * Stopping early and then reporting a complete list is the failure this whole area is
 * fixing, so the cap is recorded instead of hidden: [truncated] lets the caller say the
 * answer may be partial.
 */
private class CandidateSet(private val max: Int) {
    private val types = LinkedHashSet<GoTypeSpec>()

    var truncated = false
        private set

    /** Stores [spec], returning true when it is new and there was room for it. */
    fun add(spec: GoTypeSpec): Boolean {
        if (types.size >= max) {
            truncated = true
            return false
        }
        return types.add(spec)
    }

    fun toList(): List<GoTypeSpec> = types.toList()
}
