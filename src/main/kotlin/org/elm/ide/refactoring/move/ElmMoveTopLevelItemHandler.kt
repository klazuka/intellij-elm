package org.elm.ide.refactoring.move

import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.refactoring.move.MoveCallback
import com.intellij.refactoring.move.MoveHandlerDelegate
import org.elm.lang.core.psi.elements.*

class ElmMoveTopLevelItemHandler: MoveHandlerDelegate() {

    override fun canMove(dataContext: DataContext?): Boolean {
        return true
    }

    override fun canMove(elements: Array<out PsiElement>?, targetContainer: PsiElement?): Boolean {
        return true
    }

    override fun tryToMove(
        element: PsiElement?,
        project: Project?,
        dataContext: DataContext?,
        reference: PsiReference?,
        editor: Editor?
    ): Boolean {
        if (element != null) {
            val declarations = findDeclarations(element)
            doMove(project, declarations, element.containingFile, null)
        }
        return true
    }

    private fun findDeclarations(chosenExpr: PsiElement?): Array<out PsiElement>? {
        // find the nearest variable declaration
        var current: PsiElement? = chosenExpr
        while (current != null) {
            when (current) {
                is ElmValueDeclaration -> return getRelevantDeclarations(current)
                is ElmTypeAliasDeclaration -> return arrayOf(current)
                is ElmTypeDeclaration -> return arrayOf(current)
            }
            current = current.parent
        }
        return null
    }

    private fun getRelevantDeclarations(declaration: ElmValueDeclaration): Array<out PsiElement> =
        if (declaration.typeAnnotation != null) {
            arrayOf(declaration.typeAnnotation  as PsiElement, declaration as PsiElement)
        } else {
            arrayOf(declaration as PsiElement)
        }

    override fun doMove(
        project: Project?,
        elements: Array<out PsiElement>?,
        targetContainer: PsiElement?,
        callback: MoveCallback?
    ) {
        if (project != null && targetContainer != null && elements != null) {
            ElmMoveTopLevelItemsDialog(project, targetContainer.containingFile.virtualFile, elements).show()
        }
    }
}
