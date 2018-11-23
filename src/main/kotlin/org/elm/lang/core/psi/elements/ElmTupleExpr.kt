package org.elm.lang.core.psi.elements

import com.intellij.lang.ASTNode
import com.intellij.psi.util.PsiTreeUtil
import org.elm.lang.core.psi.ElmAtomTag
import org.elm.lang.core.psi.ElmPsiElementImpl


class ElmTupleExpr(node: ASTNode) : ElmPsiElementImpl(node), ElmAtomTag {

    val expressionList: List<ElmExpression>
        get() = PsiTreeUtil.getChildrenOfTypeAsList(this, ElmExpression::class.java)

}
