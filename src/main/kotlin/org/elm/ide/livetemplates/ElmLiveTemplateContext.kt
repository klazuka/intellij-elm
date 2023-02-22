package org.elm.ide.livetemplates

import com.intellij.codeInsight.template.TemplateContextType
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiUtilCore
import org.elm.lang.core.ElmLanguage
import org.elm.lang.core.psi.ElmExpressionTag
import org.elm.lang.core.psi.ElmFile
import org.elm.lang.core.psi.ancestors
import org.elm.lang.core.psi.elements.ElmLetInExpr
import org.elm.lang.core.psi.elements.ElmStringConstantExpr
import org.elm.lang.core.psi.elements.ElmTypeExpression

sealed class ElmLiveTemplateContext(
        presentableName: String,
) : TemplateContextType(presentableName) {
    override fun isInContext(file: PsiFile, offset: Int): Boolean {
        if (!PsiUtilCore.getLanguageAtOffset(file, offset).isKindOf(ElmLanguage)) {
            return false
        }

        val element = file.findElementAt(offset)
        if (element == null ||
                element is PsiComment ||
                element is ElmStringConstantExpr ||
                element.parent is ElmStringConstantExpr) {
            return false
        }

        return isInContext(element)
    }

    protected abstract fun isInContext(element: PsiElement): Boolean

    class Generic : ElmLiveTemplateContext("Elm") {
        override fun isInContext(element: PsiElement): Boolean = true
    }

    class TopLevel : ElmLiveTemplateContext("Top level statement") {
        override fun isInContext(element: PsiElement): Boolean {
            return isTopLevel(element)
        }
    }

    class ValueDecl : ElmLiveTemplateContext("Function declaration") {
        override fun isInContext(element: PsiElement): Boolean {
            return isTopLevel(element)
                    || element.parent is ElmLetInExpr
        }
    }

    class Expression : ElmLiveTemplateContext("Expression") {
        override fun isInContext(element: PsiElement): Boolean {
            if (element.parent is ElmLetInExpr) return false

            return element.ancestors
                    .takeWhile { it !is ElmTypeExpression && it !is ElmFile }
                    .any { it is ElmExpressionTag }
        }
    }
}

private fun isTopLevel(element: PsiElement): Boolean {
    return element.parent is ElmFile ||
            element.parent is PsiErrorElement && element.parent?.parent is ElmFile
}
