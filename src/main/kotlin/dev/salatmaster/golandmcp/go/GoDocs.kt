package dev.salatmaster.golandmcp.go

import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace

/** Extraction of Go doc comments. Call inside a read action. */
interface GoDocs {
    /** The doc comment attached to [element], or null when it has none. */
    fun docComment(element: PsiElement): String?
}

class GoDocsImpl : GoDocs {

    /**
     * A Go doc comment is the unbroken run of `//` lines immediately above a declaration.
     *
     * The comment attaches to the enclosing declaration rather than to the spec inside it, so
     * when nothing sits above the element itself the search continues from its parent.
     */
    override fun docComment(element: PsiElement): String? =
        commentsAbove(element) ?: element.parent?.let { commentsAbove(it) }

    private fun commentsAbove(element: PsiElement): String? {
        val lines = ArrayDeque<String>()
        var sibling: PsiElement? = element.prevSibling
        while (sibling != null) {
            when {
                sibling is PsiComment -> lines.addFirst(sibling.text.removePrefix("//").trim())
                // A single newline keeps the run going; a blank line ends it.
                sibling is PsiWhiteSpace && sibling.text.count { it == '\n' } <= 1 -> Unit
                else -> break
            }
            sibling = sibling.prevSibling
        }
        return lines.takeIf { it.isNotEmpty() }?.joinToString(" ")
    }
}
