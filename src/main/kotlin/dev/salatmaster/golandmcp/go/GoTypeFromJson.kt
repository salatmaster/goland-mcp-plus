package dev.salatmaster.golandmcp.go

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Derives Go struct declarations from a JSON sample.
 *
 * Hand-writing these is where struct tags silently drift from the wire format, which
 * produces fields that stay at their zero value with no error anywhere.
 */
object GoTypeFromJson {

    class ConversionException(message: String) : IllegalArgumentException(message)

    private val json = Json { isLenient = true }

    fun convert(sample: String, rootTypeName: String): String {
        val element = runCatching { json.parseToJsonElement(sample.trim()) }
            .getOrElse { throw ConversionException("Sample is not valid JSON: ${it.message}") }

        val declarations = LinkedHashMap<String, String>()
        when (element) {
            is JsonObject -> renderStruct(rootTypeName, element, declarations)
            is JsonArray -> {
                val first = element.firstOrNull() as? JsonObject
                    ?: throw ConversionException(
                        "Sample is an array of scalars; wrap it in an object or give one element.",
                    )
                renderStruct(rootTypeName, first, declarations)
            }
            else -> throw ConversionException("Sample must be a JSON object or array of objects.")
        }
        // Nested types are emitted before the root so the file reads top-down.
        return declarations.values.reversed().joinToString("\n\n")
    }

    private fun renderStruct(
        typeName: String,
        obj: JsonObject,
        declarations: MutableMap<String, String>,
    ): String {
        val fields = obj.entries.map { (key, value) ->
            val fieldType = goType(key, value, declarations)
            "\t${exportedName(key)} $fieldType `json:\"$key\"`"
        }

        declarations[typeName] = buildString {
            append("type $typeName struct {\n")
            append(fields.joinToString("\n"))
            append("\n}")
        }
        return typeName
    }

    private fun goType(
        key: String,
        value: kotlinx.serialization.json.JsonElement,
        declarations: MutableMap<String, String>,
    ): String = when (value) {
        is JsonObject -> renderStruct(exportedName(key), value, declarations)
        is JsonArray -> {
            val first = value.firstOrNull()
            if (first == null) "[]any" else "[]" + goType(singular(key), first, declarations)
        }
        JsonNull -> "any"
        is JsonPrimitive -> primitiveType(value)
    }

    private fun primitiveType(value: JsonPrimitive): String = when {
        value.isString -> "string"
        value.content == "true" || value.content == "false" -> "bool"
        value.content.toLongOrNull() != null -> "int64"
        value.content.toDoubleOrNull() != null -> "float64"
        else -> "string"
    }

    /**
     * `created_at` and `created-at` both become `CreatedAt`; Go exports by capitalisation.
     *
     * Initialisms stay fully capitalised — `id` becomes `ID`, not `Id`. Go vet and golint
     * both enforce this, so generating the other form produces code that lints dirty the
     * moment it lands.
     */
    private fun exportedName(key: String): String =
        key.split('_', '-', ' ')
            .filter { it.isNotEmpty() }
            .joinToString("") { part -> capitalizeSegment(part) }
            .ifEmpty { "Field" }

    /**
     * Capitalises one underscore-separated segment the way golint would.
     *
     * A plural initialism keeps its lowercase `s`: `photo_urls` is `PhotoURLs`, not
     * `PhotoUrls`, which is what a bare set lookup produced.
     */
    private fun capitalizeSegment(part: String): String {
        val upper = part.uppercase()
        if (upper in INITIALISMS) return upper
        if (upper.length > 1 && upper.endsWith("S") && upper.dropLast(1) in INITIALISMS) {
            return upper.dropLast(1) + "s"
        }
        return part.replaceFirstChar { it.uppercaseChar() }
    }

    /** The set golint recognises. */
    private val INITIALISMS = setOf(
        "ACL", "API", "ASCII", "CPU", "CSS", "DNS", "EOF", "GUID", "HTML", "HTTP", "HTTPS",
        "ID", "IP", "JSON", "LHS", "QPS", "RAM", "RHS", "RPC", "SLA", "SMTP", "SQL", "SSH",
        "TCP", "TLS", "TTL", "UDP", "UI", "UID", "UUID", "URI", "URL", "UTF8", "VM", "XML",
        "XMPP", "XSRF", "XSS",
    )

    private fun singular(key: String): String =
        if (key.endsWith("s") && key.length > 1) key.dropLast(1) else key
}
