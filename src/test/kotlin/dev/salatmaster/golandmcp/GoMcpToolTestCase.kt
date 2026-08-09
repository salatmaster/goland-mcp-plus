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
package dev.salatmaster.golandmcp

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Base class for tests that need a Go project in a light IDE fixture.
 */
abstract class GoMcpToolTestCase : BasePlatformTestCase() {

    override fun getTestDataPath(): String = File("src/test/testData").absolutePath

    /** Copies a fixture directory into the in-memory project. */
    protected fun loadFixture(dir: String) {
        myFixture.copyDirectoryToProject(dir, "")
    }

    /**
     * Runs a suspending toolset function.
     *
     * Tests call the `internal` overloads that take a [com.intellij.openapi.project.Project]
     * explicitly, not the `@McpTool` methods. Putting a project into the coroutine context
     * would mean constructing `McpCallInfo`, whose constructor demands
     * `McpServerService.McpSessionOptions` — an internal class of the MCP server plugin.
     * Depending on it would couple this suite to another plugin's implementation details.
     */
    protected fun <T> callTool(block: suspend () -> T): T = runBlocking { block() }
}
