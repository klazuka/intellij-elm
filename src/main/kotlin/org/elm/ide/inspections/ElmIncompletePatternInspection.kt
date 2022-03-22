package org.elm.ide.inspections

import com.intellij.codeInsight.intention.PriorityAction.Priority.LOW
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import org.elm.ide.inspections.MissingCaseBranchAdder.Result.MissingVariants
import org.elm.ide.inspections.MissingCaseBranchAdder.Result.NoMissing
import org.elm.lang.core.psi.ElmPsiElement
import org.elm.lang.core.psi.ElmTypes
import org.elm.lang.core.psi.directChildren
import org.elm.lang.core.psi.elementType
import org.elm.lang.core.psi.elements.ElmCaseOfExpr

class ElmIncompletePatternInspection : ElmLocalInspection() {
    override fun visitElement(element: ElmPsiElement, holder: ProblemsHolder, isOnTheFly: Boolean) {
        if (element !is ElmCaseOfExpr) return
        val fixer = MissingCaseBranchAdder(element)

        if (fixer.result == NoMissing) return

        val fixes = when (fixer.result) {
            is MissingVariants -> arrayOf(AddMissingBranchesFix(), AddWildcardBranchFix())
            else -> arrayOf(AddWildcardBranchFix())
        }

        val description = "Case expression is not exhaustive"
        holder.registerProblem(element.firstChild, description, *fixes)
        val ofKeyword = element.directChildren.find { it.elementType == ElmTypes.OF }
        if (ofKeyword !== null) {
            holder.registerProblem(ofKeyword, description, *fixes)
        }
    }
}

private class AddMissingBranchesFix : NamedQuickFix("Add missing case branches") {
    override fun applyFix(element: PsiElement, project: Project) {
        val parent = element.parent as? ElmCaseOfExpr ?: return
        MissingCaseBranchAdder(parent).addMissingBranches()
    }
}

private class AddWildcardBranchFix : NamedQuickFix("Add '_' branch", LOW) {
    override fun applyFix(element: PsiElement, project: Project) {
        val parent = element.parent as? ElmCaseOfExpr ?: return
        MissingCaseBranchAdder(parent).addWildcardBranch()
    }
}
