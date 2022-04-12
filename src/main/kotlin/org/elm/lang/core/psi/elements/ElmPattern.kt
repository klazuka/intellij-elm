package org.elm.lang.core.psi.elements

import com.intellij.lang.ASTNode
import com.intellij.psi.PsiElement
import org.elm.lang.core.psi.*


class ElmPattern(node: ASTNode) : ElmPsiElementImpl(node), ElmFunctionParamTag, ElmPatternChildTag, ElmUnionPatternChildTag, ElmValueAssigneeTag {

    /**
     * The actual type of this pattern.
     *
     * If this patten is wrapped in parenthesis, the child will be another [ElmPattern]
     */
    val child: ElmPatternChildTag
        get() = findNotNullChildByClass(ElmPatternChildTag::class.java)

    val unwrapped: ElmPatternChildTag
        get() {
            var nextChild = child
            while (nextChild is ElmPattern) {
                nextChild = nextChild.child
            }
            return nextChild
        }

    /**
     * The name after the `as` that this pattern is bound to.
     *
     * e.g. `record` in `({field} as record)`
     */
    val patternAs: ElmLowerPattern?
        get() {
            val asToken = findChildByType<PsiElement>(ElmTypes.AS) ?: return null
            return asToken.nextSiblings.filterIsInstance<ElmLowerPattern>().firstOrNull()
        }
}
