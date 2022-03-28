package org.elm.ide.inspections

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import org.elm.lang.core.psi.ElmPsiFactory
import org.elm.lang.core.psi.elements.ElmFunctionCallExpr
import org.elm.lang.core.psi.elements.ElmValueExpr

class MakeDeclarationFromUsageFix : NamedQuickFix("Create") {
    override fun applyFix(element: PsiElement, project: Project) {
        if (element !is ElmValueExpr) {
           return
        }

        // addTopLevelFunction("greet", psiFactory)
        val psiFactory = ElmPsiFactory(project)
        val s = element.referenceName


        val arity = (element.parent as? ElmFunctionCallExpr)?.arguments?.count() ?: 0

        val argList: String = argListFromArity(arity)
        val valueDeclaration =
           psiFactory.createTopLevelFunction("$s $argList= Debug.todo \"TODO\"")
        // spliceIntoTopLevel
        element.elmFile.add(valueDeclaration)
    }

    private fun argListFromArity(arity: Int): String {
        return when (arity) {
            0 -> ""
            else -> {
                (1..arity).map { "arg$it"}.joinToString(separator = " ", postfix = " ")
            }
        }
    }


}
