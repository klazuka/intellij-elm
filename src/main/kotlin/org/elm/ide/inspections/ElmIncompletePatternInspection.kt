package org.elm.ide.inspections

import com.intellij.codeInsight.intention.PriorityAction.Priority.LOW
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import org.elm.ide.inspections.MissingCaseBranchAdder.Result.MissingVariants
import org.elm.ide.inspections.MissingCaseBranchAdder.Result.NoMissing
import org.elm.lang.core.psi.ElmPsiElement
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

        val message = "Case expression is not exhaustive"
        holder.registerProblem(element, TextRange(0, 4), message, *fixes)
        if (element.textRange.length > 4) {
            holder.registerProblem(element, message, ProblemHighlightType.INFORMATION, TextRange(5, element.textRange.length), *fixes)
        }
    }
}

private class AddMissingBranchesFix : NamedQuickFix("Add missing case branches") {
    override fun applyFix(element: PsiElement, project: Project) {
        val expr = element as? ElmCaseOfExpr ?: return
        MissingCaseBranchAdder(expr).addMissingBranches()
    }
}

private class AddWildcardBranchFix : NamedQuickFix("Add '_' branch", LOW) {
    override fun applyFix(element: PsiElement, project: Project) {
        val expr = element as? ElmCaseOfExpr ?: return
        MissingCaseBranchAdder(expr).addWildcardBranch()
    }
}
