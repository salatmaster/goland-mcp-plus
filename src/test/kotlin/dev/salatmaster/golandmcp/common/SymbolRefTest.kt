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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SymbolRefTest {

    @Test
    fun `parses import path with type and method`() {
        val ref = parseSymbolRef("net/http.Client.Do")
        assertEquals(SymbolRef.Qualified("net/http", "Client", "Do"), ref)
    }

    @Test
    fun `parses import path with a single symbol`() {
        val ref = parseSymbolRef("net/http.Client")
        assertEquals(SymbolRef.Qualified("net/http", null, "Client"), ref)
    }

    @Test
    fun `parses relative package path`() {
        val ref = parseSymbolRef("./internal/store.Store")
        assertEquals(SymbolRef.Qualified("./internal/store", null, "Store"), ref)
    }

    @Test
    fun `parses bare type and method`() {
        val ref = parseSymbolRef("Handler.ServeHTTP")
        assertEquals(SymbolRef.Bare("Handler", "ServeHTTP"), ref)
    }

    @Test
    fun `parses bare symbol`() {
        val ref = parseSymbolRef("ServeHTTP")
        assertEquals(SymbolRef.Bare(null, "ServeHTTP"), ref)
    }

    @Test
    fun `parses domain qualified import path`() {
        val ref = parseSymbolRef("github.com/gin-gonic/gin.Context.JSON")
        assertEquals(SymbolRef.Qualified("github.com/gin-gonic/gin", "Context", "JSON"), ref)
    }

    @Test
    fun `rejects blank input`() {
        assertThrows(SymbolRefParseException::class.java) { parseSymbolRef("  ") }
    }

    @Test
    fun `rejects trailing dot`() {
        assertThrows(SymbolRefParseException::class.java) { parseSymbolRef("net/http.") }
    }

    @Test
    fun `rejects more than two segments after the package path`() {
        assertThrows(SymbolRefParseException::class.java) { parseSymbolRef("net/http.Client.Do.Extra") }
    }
}
