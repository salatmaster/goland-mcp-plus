/*
 * Copyright 2026 salatmaster
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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
            "'$input' looks like a package path but names no symbol. " +
                "Expected e.g. 'net/http.Client' or 'net/http.Client.Do'.",
        )
    }
    val packagePath = input.substring(0, lastSlash + 1) + tail.substring(0, firstDotInTail)
    val member = tail.substring(firstDotInTail + 1)
    val (typeName, memberName) = splitMember(member, input)
    return SymbolRef.Qualified(packagePath, typeName, memberName)
}

/** Splits `Client.Do` into type and member; `Client` into null and member. */
private fun splitMember(member: String, whole: String): Pair<String?, String> {
    if (member.isEmpty()) {
        throw SymbolRefParseException("'$whole' names no symbol after the package path")
    }
    val parts = member.split('.')
    if (parts.any { it.isEmpty() }) {
        throw SymbolRefParseException("'$whole' contains an empty name segment")
    }
    return when (parts.size) {
        1 -> null to parts[0]
        2 -> parts[0] to parts[1]
        else -> throw SymbolRefParseException(
            "'$whole' has too many segments. Expected at most 'Type.Member' after the package path.",
        )
    }
}
