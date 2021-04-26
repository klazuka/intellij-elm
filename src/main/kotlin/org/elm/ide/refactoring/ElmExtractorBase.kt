package org.elm.ide.refactoring

import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNamedElement
import com.intellij.refactoring.RefactoringActionHandler
import com.intellij.refactoring.RefactoringBundle
import com.intellij.refactoring.util.CommonRefactoringUtil
import org.elm.ide.utils.findExpressionAtCaret
import org.elm.ide.utils.findExpressionInRange
import org.elm.lang.core.psi.*
import org.elm.lang.core.psi.elements.ElmLetInExpr
import org.elm.lang.core.psi.elements.ElmValueDeclaration

abstract class ElmExtractorBase : RefactoringActionHandler {

    final override fun invoke(project: Project, editor: Editor, file: PsiFile, dataContext: DataContext?) {
        if (file !is ElmFile) return
        val exprs = findCandidateExpressions(editor, file)
        when (exprs.size) {
            0 -> {
                val message = RefactoringBundle.message(if (editor.selectionModel.hasSelection())
                    "selected.block.should.represent.an.expression"
                else
                    "refactoring.introduce.selection.error"
                )
                val title = RefactoringBundle.message("introduce.variable.title")
                val helpId = "refactoring.extractVariable"
                CommonRefactoringUtil.showErrorHint(project, editor, message, title, helpId)
            }
            1 -> process(editor, exprs.single())
            else -> showExpressionChooser(editor, exprs) { chosenExpr -> process(editor, chosenExpr) }
        }
    }

    private fun findCandidateExpressions(editor: Editor, file: ElmFile): List<ElmExpressionTag> {
        val selection = editor.selectionModel
        return if (selection.hasSelection()) {
            // If the user has some text selected, make a single suggestion based on the selection
            listOfNotNull(findExpressionInRange(file, selection.selectionStart, selection.selectionEnd))
        } else {
            // Suggest nested expressions at caret position
            val expr = findExpressionAtCaret(file, editor.caretModel.offset) ?: return emptyList()
            expr.ancestors
                .takeWhile { it !is ElmValueDeclaration && it !is ElmLetInExpr }
                .filterIsInstance<ElmExpressionTag>()
                .toList()
        }
    }

    protected abstract fun process(editor: Editor, chosenExpr: ElmExpressionTag)

    final override fun invoke(project: Project, elements: Array<out PsiElement>, dataContext: DataContext?) {
        // IntelliJ will never call this when introducing a variable
    }
}


fun moveEditorToNameElement(editor: Editor, element: PsiElement?): PsiNamedElement? {
    val newName = element?.descendants
        ?.filterIsInstance<ElmNameIdentifierOwner>()
        ?.firstOrNull { it.nameIdentifier.elementType == ElmTypes.LOWER_CASE_IDENTIFIER }
    if (newName != null) {
        editor.caretModel.moveToOffset(newName.nameIdentifier.startOffset)
    }
    return newName
}
