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
package dev.salatmaster.golandmcp.go

import com.intellij.openapi.application.runReadAction
import dev.salatmaster.golandmcp.GoMcpToolTestCase
import dev.salatmaster.golandmcp.common.parseSymbolRef

class GoSymbolsTest : GoMcpToolTestCase() {

    private val symbols: GoSymbols = GoSymbolsImpl()

    fun `test finds a struct type by bare name`() {
        loadFixture("basic")
        val result = runReadAction { symbols.lookup(project, parseSymbolRef("Rect")) }

        val found = result as GoLookupResult.Found
        assertEquals(GoSymbolKind.TYPE, found.symbol.kind)
        assertEquals("Rect", found.symbol.name)
        assertTrue(found.symbol.exported)
        assertTrue(
            "location should point at the declaration line, was ${found.symbol.location}",
            found.symbol.location.endsWith("shapes.go:10"),
        )
    }

    fun `test finds an interface and reports its kind`() {
        loadFixture("basic")
        val result = runReadAction { symbols.lookup(project, parseSymbolRef("Shape")) }

        val found = result as GoLookupResult.Found
        assertEquals(GoSymbolKind.INTERFACE, found.symbol.kind)
    }

    fun `test finds a method by type and name`() {
        loadFixture("basic")
        val result = runReadAction { symbols.lookup(project, parseSymbolRef("Rect.Area")) }

        val found = result as GoLookupResult.Found
        assertEquals(GoSymbolKind.METHOD, found.symbol.kind)
        assertEquals("Area", found.symbol.name)
        assertTrue(
            "signature should show the return type, was ${found.symbol.signature}",
            found.symbol.signature.contains("float64"),
        )
    }

    fun `test reports not found for an unknown symbol`() {
        loadFixture("basic")
        val result = runReadAction { symbols.lookup(project, parseSymbolRef("Hexagon")) }

        assertEquals(GoLookupResult.NotFound, result)
    }

    fun `test carries the doc comment`() {
        loadFixture("basic")
        val result = runReadAction { symbols.lookup(project, parseSymbolRef("Shape")) }

        val found = result as GoLookupResult.Found
        assertTrue(
            "doc should carry the comment above the declaration, was ${found.symbol.doc}",
            found.symbol.doc?.contains("something with an area") == true,
        )
    }
}
