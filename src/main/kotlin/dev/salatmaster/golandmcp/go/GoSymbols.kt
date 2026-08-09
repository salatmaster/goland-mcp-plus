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

import com.intellij.openapi.project.Project
import dev.salatmaster.golandmcp.common.SymbolRef

/**
 * Symbol lookup over the Go stub indices.
 *
 * Implementations touch `com.goide.*`; callers must not. Call inside a read action.
 */
interface GoSymbols {
    fun lookup(project: Project, ref: SymbolRef): GoLookupResult
}
