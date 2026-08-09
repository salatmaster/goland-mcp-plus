package dev.salatmaster.golandmcp.common

class SymbolRefParseException(message: String) : IllegalArgumentException(message)

/**
 * A reference to a Go symbol as an agent would naturally write it.
 *
 * Agents rarely know file coordinates, so the primary forms are name-based;
 * [AtPosition] exists for the cases where coordinates are already known.
 */
sealed interface SymbolRef {

    /** `net/http.Client.Do`, `./internal/store.Store` */
    data class Qualified(
        val packagePath: String,
        val typeName: String?,
        val memberName: String,
    ) : SymbolRef

    /** `Handler.ServeHTTP`, `ServeHTTP` */
    data class Bare(
        val typeName: String?,
        val memberName: String,
    ) : SymbolRef

    /** Explicit coordinates. */
    data class AtPosition(
        val file: String,
        val line: Int,
        val column: Int,
    ) : SymbolRef
}

/**
 * Parses a symbol reference.
 *
 * The package path may itself contain dots (`github.com/gin-gonic/gin`), so the
 * split point is the last `/`, not the first `.`.
 */
fun parseSymbolRef(raw: String): SymbolRef {
    val input = raw.trim()
    if (input.isEmpty()) throw SymbolRefParseException("Symbol reference is blank")

    val lastSlash = input.lastIndexOf('/')
    return if (lastSlash >= 0) {
        parseQualified(input, lastSlash)
    } else {
        val (typeName, memberName) = splitMember(input, input)
        SymbolRef.Bare(typeName, memberName)
    }
}

private fun parseQualified(input: String, lastSlash: Int): SymbolRef.Qualified {
    val tail = input.substring(lastSlash + 1)
    val firstDotInTail = tail.indexOf('.')
    if (firstDotInTail < 0) {
        throw SymbolRefParseException(
            "'$input' looks like a package path but names no symbol. $EXAMPLES",
        )
    }
    val packagePath = input.substring(0, lastSlash + 1) + tail.substring(0, firstDotInTail)
    val member = tail.substring(firstDotInTail + 1)
    val (typeName, memberName) = splitMember(member, input)
    return SymbolRef.Qualified(packagePath, typeName, memberName)
}

/**
 * Splits `Client.Do` into type and member; `Client` into null and member.
 *
 * Every failure message carries an example of the accepted form: the caller is usually a
 * model, and an error that only says what is wrong leaves it guessing at the fix.
 */
private fun splitMember(member: String, whole: String): Pair<String?, String> {
    if (member.isEmpty()) {
        throw SymbolRefParseException("'$whole' names no symbol after the package path. $EXAMPLES")
    }
    val parts = member.split('.')
    if (parts.any { it.isEmpty() }) {
        throw SymbolRefParseException("'$whole' contains an empty name segment. $EXAMPLES")
    }
    return when (parts.size) {
        1 -> null to parts[0]
        2 -> parts[0] to parts[1]
        else -> throw SymbolRefParseException(
            "'$whole' has too many segments; expected at most 'Type.Member'. $EXAMPLES",
        )
    }
}

private const val EXAMPLES =
    "Expected e.g. 'net/http.Client', 'net/http.Client.Do', or a bare name like 'ServeHTTP'."
