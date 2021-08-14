package org.elm.ide.inspections

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import org.elm.lang.core.psi.elements.ElmExposedType
import org.elm.lang.core.psi.elements.ElmTypeAliasDeclaration

/**
 * Find statements like `import A exposing B(..)` where `B` is a type alias, making the statement invalid.
 */
class ElmExposingAliasVariantsInspection : LocalInspectionTool() {
    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        return object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                super.visitElement(element)
                if (element !is ElmExposedType) return

                if (element.exposesAll && element.reference.resolve() is ElmTypeAliasDeclaration) {
                    holder.registerProblem(element, "Invalid (..) on alias import", RemoveExposingListQuickFix())
                }
            }
        }
    }
}

private class RemoveExposingListQuickFix : NamedQuickFix("Remove (..)") {
    override fun applyFix(element: PsiElement, project: Project) {
        if (element !is ElmExposedType) return
        element.deleteParensAndDoubleDot()
    }
}
