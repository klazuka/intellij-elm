package org.elm.ide.inspections

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import org.elm.lang.core.psi.ElmFile
import org.elm.lang.core.psi.ElmNameIdentifierOwner
import org.elm.lang.core.psi.ElmNamedElement
import org.elm.lang.core.psi.elements.ElmLetInExpr
import org.elm.lang.core.resolve.scope.ExpressionScope
import org.elm.lang.core.resolve.scope.ModuleScope

/**
 * Report errors when a file has multiple declarations with the same name.
 */
class ElmDuplicateDeclarationInspection : LocalInspectionTool() {
    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        return object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                super.visitElement(element)
                when (element) {
                    is ElmFile -> checkFile(element, holder)
                    is ElmLetInExpr -> checkLetIn(element, holder)
                }
            }
        }
    }
}

private fun checkFile(element: ElmFile, holder: ProblemsHolder) {
    val names = ModuleScope.getDeclaredTypes(element) + ModuleScope.getDeclaredValues(element)
    checkValues(holder, names.array.asSequence())

}

private fun checkLetIn(element: ElmLetInExpr, holder: ProblemsHolder) {
    val decls = element.valueDeclarationList
    if (decls.isEmpty()) return

    // We check all children of a let-in at once, rather than inspecting each declaration
    // individually. The values visible to all children are the same, so we only need to call
    // ExpressionScope once. This also prevents duplicate errors when sibling declarations have the
    // same name.
    //
    // Elm lets you shadow imported names, including auto-imported names, so only count names
    // declared in this file as shadowable.
    val values = ExpressionScope(decls[0]).getVisibleValues()
            .filter { it.containingFile == holder.file }
    checkValues(holder, values)
}

private fun checkValues(holder: ProblemsHolder, values: Sequence<ElmNamedElement>) {
    values.groupBy { it.name }
            .values
            .filter { it.size > 1 }
            .flatten()
            .forEach {
                val target = if (it is ElmNameIdentifierOwner) it.nameIdentifier else it
                holder.registerProblem(target, "Multiple declarations with name '${it.name}'")
            }
}
