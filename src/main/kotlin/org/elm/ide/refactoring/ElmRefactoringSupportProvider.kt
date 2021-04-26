package org.elm.ide.refactoring

import com.intellij.lang.refactoring.RefactoringSupportProvider
import com.intellij.psi.PsiElement
import com.intellij.refactoring.RefactoringActionHandler


class ElmRefactoringSupportProvider : RefactoringSupportProvider() {

    override fun isMemberInplaceRenameAvailable(element: PsiElement, context: PsiElement?): Boolean {
        // TODO [kl] eventually we will want to be more selective
        return true
    }

    override fun getIntroduceVariableHandler(): RefactoringActionHandler? {
        return ElmIntroduceVariableHandler()
    }

    override fun getExtractMethodHandler(): RefactoringActionHandler? {
        return ElmExtractMethodHandler()
    }
}