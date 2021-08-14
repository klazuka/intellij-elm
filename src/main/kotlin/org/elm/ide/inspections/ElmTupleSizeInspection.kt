package org.elm.ide.inspections

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import org.elm.lang.core.psi.elements.ElmTupleExpr

/**
 * Report errors on tuples with more than three elements.
 */
class ElmTupleSizeInspection : LocalInspectionTool() {
    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        return object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                super.visitElement(element)
                if (element !is ElmTupleExpr) return
                if (element.expressionList.size > 3) {
                    holder.registerProblem(element, "Tuples may only have two or three items.")
                }
            }
        }
    }
}
