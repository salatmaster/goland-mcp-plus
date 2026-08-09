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

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement

/**
 * Formats an element's position as `relative/path.go:line`, falling back to the
 * absolute path for files outside the project (stdlib, module cache).
 */
fun formatLocation(project: Project, element: PsiElement): String {
    val containingFile = element.containingFile ?: return "<unknown>"
    val file = containingFile.virtualFile ?: return "<unknown>"

    val document = PsiDocumentManager.getInstance(project).getDocument(containingFile)
    val line = document?.getLineNumber(element.textOffset)?.plus(1) ?: 0

    val relative = ProjectFileIndex.getInstance(project)
        .getContentRootForFile(file)
        ?.let { root -> VfsUtilCore.getRelativePath(file, root) }

    return "${relative ?: file.path}:$line"
}
