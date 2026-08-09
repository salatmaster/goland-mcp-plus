package dev.salatmaster.golandmcp.go

/**
 * Generation of Go source fragments.
 *
 * These are string builders rather than PSI manipulation on purpose: the output is small,
 * deterministic and easy to test, and the IDE reformats it afterwards anyway.
 */
object GoGeneration {

    /**
     * Renders method stubs implementing [requirements] for [typeName].
     *
     * The receiver name follows Go convention — the type's initial, lowercased — and the body
     * panics rather than returning zero values, so a forgotten stub fails loudly at runtime
     * instead of silently doing nothing.
     */
    fun methodStubs(
        typeName: String,
        requirements: List<GoMethodRequirement>,
        pointerReceiver: Boolean,
    ): String {
        if (requirements.isEmpty()) return ""

        val receiverName = typeName.firstOrNull()?.lowercaseChar()?.toString() ?: "r"
        val receiverType = if (pointerReceiver) "*$typeName" else typeName

        return requirements.joinToString("\n\n") { requirement ->
            val signature = requirement.signature.ifBlank { "()" }
            buildString {
                append("func ($receiverName $receiverType) ")
                append(requirement.name)
                append(signature)
                append(" {\n")
                append("\tpanic(\"not implemented\")\n")
                append("}")
            }
        }
    }

    /**
     * Renders an interface declaration covering [requirements].
     *
     * Only exported methods are worth putting in an interface by default; unexported ones
     * cannot be satisfied from another package at all.
     */
    fun interfaceDeclaration(
        interfaceName: String,
        requirements: List<GoMethodRequirement>,
        doc: String,
    ): String {
        val methods = requirements.joinToString("\n") { "\t${it.name}${it.signature}" }
        return buildString {
            if (doc.isNotBlank()) appendLine("// $doc")
            append("type $interfaceName interface {\n")
            append(methods)
            append("\n}")
        }
    }

    /**
     * Renders a table-driven test skeleton for a function.
     *
     * Table-driven is the idiom Go reviewers expect, and starting from the shape means the
     * agent fills in cases rather than inventing a structure.
     */
    fun tableTest(functionName: String, receiverType: String): String {
        val subject = if (receiverType.isBlank()) functionName else "$receiverType.$functionName"
        val testName = "Test" + functionName.replaceFirstChar { it.uppercaseChar() }

        return buildString {
            append("func $testName(t *testing.T) {\n")
            append("\ttests := []struct {\n")
            append("\t\tname string\n")
            append("\t\t// TODO: inputs for $subject\n")
            append("\t\t// TODO: expected results\n")
            append("\t}{\n")
            append("\t\t{name: \"TODO: describe the case\"},\n")
            append("\t}\n\n")
            append("\tfor _, tt := range tests {\n")
            append("\t\tt.Run(tt.name, func(t *testing.T) {\n")
            append("\t\t\tt.Fatal(\"TODO: call $subject and assert\")\n")
            append("\t\t})\n")
            append("\t}\n")
            append("}")
        }
    }
}
