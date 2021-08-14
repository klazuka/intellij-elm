package org.elm.ide.inspections

import com.intellij.codeInsight.intention.PriorityAction
import com.intellij.codeInspection.*
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import org.elm.lang.core.psi.ElmPsiElement

abstract class ElmLocalInspection : LocalInspectionTool() {
    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean) = object : PsiElementVisitor() {
        override fun visitElement(element: PsiElement) {
            super.visitElement(element)
            if (element is ElmPsiElement) {
                visitElement(element, holder, isOnTheFly)
            }
        }
    }

    abstract fun visitElement(element: ElmPsiElement, holder: ProblemsHolder, isOnTheFly: Boolean)
}

/**
 * A [LocalQuickFix] base class that takes care of some of the boilerplate
 *
 * Note: [LocalQuickFix] implementations should never store a reference to a [PsiElement], since the
 * PSI may change between the time that they're created and called, causing the elements to be
 * invalid or leak memory.
 *
 * If you really need a reference to an element other than the one this fix is shown on, you can implement
 * [LocalQuickFixOnPsiElement], which holds a weak reference to an element.
 */
abstract class NamedQuickFix(
        private val fixName: String,
        private val fixPriority: PriorityAction.Priority = PriorityAction.Priority.NORMAL
) : LocalQuickFix, PriorityAction {
    override fun getName(): String = fixName
    override fun getFamilyName(): String = name
    override fun getPriority(): PriorityAction.Priority = fixPriority
    final override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        this.applyFix(descriptor.psiElement ?: return, project)
    }

    abstract fun applyFix(element: PsiElement, project: Project)
}
