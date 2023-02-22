package org.elm.ide.inspections

import com.intellij.codeInsight.daemon.impl.ShowAutoImportPass
import com.intellij.codeInsight.hint.HintManager
import com.intellij.codeInsight.intention.PriorityAction
import com.intellij.codeInspection.HintAction
import com.intellij.codeInspection.LocalQuickFixOnPsiElement
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiModificationTracker
import org.elm.lang.core.psi.ElmPsiElement
import org.elm.lang.core.psi.endOffset


/**
 * A hint is a popup that shows up automatically and can apply a quick fix even if the cursor is not
 * on the targeted element.
 */
class NamedQuickFixHint(
        element: ElmPsiElement,
        private val delegate: NamedQuickFix,
        private val hint: String,
        private val multiple: Boolean
) : LocalQuickFixOnPsiElement(element), HintAction, PriorityAction {
    override fun getFamilyName(): String = delegate.familyName
    override fun getText(): String = delegate.name
    override fun getPriority(): PriorityAction.Priority = delegate.priority
    override fun startInWriteAction() = delegate.startInWriteAction()
    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean = super.isAvailable()

    private val project = element.project
    private val modificationCountOnCreate = project.currentModCount()

    private fun isOutdated() = modificationCountOnCreate != project.currentModCount()

    override fun showHint(editor: Editor): Boolean {
        if (HintManager.getInstance().hasShownHintsThatWillHideByOtherHint(true)) return false
        if (isOutdated()) return false

        val message = ShowAutoImportPass.getMessage(multiple, hint)
        val element = startElement
        HintManager.getInstance().showQuestionHint(editor, message, element.textOffset, element.endOffset) {
            delegate.applyFix(element, element.project)
            true
        }
        return true
    }

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        delegate.applyFix(startElement, project)
    }

    override fun invoke(project: Project, file: PsiFile, startElement: PsiElement, endElement: PsiElement) {
        delegate.applyFix(startElement, project)
    }
}

private fun Project.currentModCount() = PsiModificationTracker.getInstance(this).modificationCount
