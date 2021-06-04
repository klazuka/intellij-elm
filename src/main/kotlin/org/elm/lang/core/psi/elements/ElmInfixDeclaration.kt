package org.elm.lang.core.psi.elements

import com.intellij.lang.ASTNode
import com.intellij.psi.PsiElement
import com.intellij.psi.stubs.IStubElementType
import org.elm.ide.icons.ElmIcons
import org.elm.lang.core.psi.*
import org.elm.lang.core.stubs.ElmInfixDeclarationStub

/**
 * A top-level declaration that describes the associativity and the precedence
 * of a binary operator.
 *
 * For example, `infix right 5 (++) = append` means that the operator `++` has right associativity
 * at precedence level of 5.
 *
 * As of Elm 0.19, these are now restricted to packages owned by elm-lang (and elm-explorations?)
 */
class ElmInfixDeclaration : ElmStubbedNamedElementImpl<ElmInfixDeclarationStub>, ElmExposableTag {

    constructor(node: ASTNode) :
            super(node, IdentifierCase.OPERATOR)

    constructor(stub: ElmInfixDeclarationStub, stubType: IStubElementType<*, *>) :
            super(stub, stubType, IdentifierCase.OPERATOR)

    override fun getIcon(flags: Int) =
        ElmIcons.FUNCTION

    val operatorIdentifier: PsiElement
        get() = findNotNullChildByType(ElmTypes.OPERATOR_IDENTIFIER)

    val precedenceElement: PsiElement
        get() = findNotNullChildByType(ElmTypes.NUMBER_LITERAL)

    val precedence: Int?
        get() = stub?.precedence ?: precedenceElement.text.toIntOrNull()

    val associativityElement: PsiElement
        get() = findNotNullChildByType(ElmTypes.LOWER_CASE_IDENTIFIER)

    val associativity: OperatorAssociativity
        get() = when (stub?.associativity ?: associativityElement.text) {
            "left" -> OperatorAssociativity.LEFT
            "right" -> OperatorAssociativity.RIGHT
            else -> OperatorAssociativity.NON
        }

    /**
     * A ref to the function which implements the infix operator.
     *
     * This will be non-null in a well-formed program.
     */
    val funcRef: ElmInfixFuncRef?
        get() = stubDirectChildrenOfType<ElmInfixFuncRef>().singleOrNull()
}
