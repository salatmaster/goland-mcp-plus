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

import com.goide.psi.GoFile
import com.intellij.openapi.application.runReadAction
import com.intellij.psi.PsiManager

class HarnessSmokeTest : GoMcpToolTestCase() {

    fun `test fixture loads and Go PSI parses it`() {
        loadFixture("basic")
        val virtualFile = myFixture.findFileInTempDir("shapes.go")
        assertNotNull("shapes.go should exist in the fixture project", virtualFile)

        val typeNames = runReadAction {
            val psi = PsiManager.getInstance(project).findFile(virtualFile!!)
            assertTrue("shapes.go should parse as a GoFile, was ${psi?.javaClass}", psi is GoFile)
            (psi as GoFile).types.mapNotNull { it.name }.sorted()
        }

        assertEquals(listOf("Circle", "Rect", "Shape", "Triangle"), typeNames)
    }

    fun `test methods resolve with their receivers`() {
        loadFixture("basic")
        val virtualFile = myFixture.findFileInTempDir("shapes.go")

        val rectMethods = runReadAction {
            val psi = PsiManager.getInstance(project).findFile(virtualFile!!) as GoFile
            psi.methods
                .filter { it.receiverType?.text?.contains("Rect") == true }
                .mapNotNull { it.name }
                .sorted()
        }

        assertEquals(listOf("Area", "Name"), rectMethods)
    }
}
