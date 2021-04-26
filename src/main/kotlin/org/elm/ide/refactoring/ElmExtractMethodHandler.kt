package org.elm.ide.refactoring

import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import org.elm.lang.core.psi.*
import org.elm.lang.core.psi.elements.ElmValueDeclaration
import org.elm.lang.core.psi.elements.ElmValueExpr
import org.elm.openapiext.runWriteCommandAction

class ElmExtractMethodHandler : ElmExtractorBase() {

    override fun process(editor: Editor, chosenExpr: ElmExpressionTag) {
        if (!chosenExpr.isValid) return
        val project = editor.project ?: return

        val psiFactory = ElmPsiFactory(project)

        val fn: ElmValueDeclaration = getValueDeclaration(chosenExpr) ?: return
        val fnEndOffset = getTopLevelValueEnd(fn)

        val parameters = chosenExpr
            .descendantsOfType<ElmValueExpr>()
            .asSequence()
            .mapNotNull { it.reference.resolve() as ElmNameIdentifierOwner? }
            .filter { it.elmFile == fn.elmFile && it.parent.parent != it.elmFile }
            .filter { !chosenExpr.textRange.contains(it.textRange) }
            .map { it.name }
            .toHashSet()

        val name = chosenExpr.suggestedNames().default

        project.runWriteCommandAction {
            val valueDeclaration: ElmValueDeclaration = psiFactory.createValue(name, chosenExpr.text, parameters)

            editor.document.insertString(fnEndOffset, "\n")
            editor.document.insertString(fnEndOffset, "\n")
            editor.document.insertString(fnEndOffset + 2, valueDeclaration.text)
            valueDeclaration.functionDeclarationLeft?.text?.let {
                editor.document.replaceString(chosenExpr.startOffset, chosenExpr.endOffset, it)
            }
        }
    }

    private fun getTopLevelValueEnd(fn: ElmValueDeclaration): Int {
        var topLevelFn: PsiElement = fn

        while (topLevelFn.parent !is ElmFile)
            topLevelFn = topLevelFn.parent

        return topLevelFn.textRange.endOffset
    }

    private fun getValueDeclaration(chosenExpr: ElmExpressionTag): ElmValueDeclaration? {
        var fn: ElmPsiElement? = chosenExpr

        while (fn != null) {
            when (fn) {
                is ElmValueDeclaration -> return fn
                else -> fn = fn.parent as ElmPsiElement?
            }
        }

        return null
    }
}
