package org.elm.lang.core.psi.elements

import com.intellij.lang.ASTNode
import com.intellij.psi.PsiElement
import com.intellij.psi.stubs.IStubElementType
import com.intellij.psi.util.PsiTreeUtil
import org.elm.lang.core.psi.ElmStubbedNamedElementImpl
import org.elm.lang.core.psi.ElmTypes.LOWER_CASE_IDENTIFIER
import org.elm.lang.core.psi.IdentifierCase
import org.elm.lang.core.psi.tags.ElmNameDeclarationPattern
import org.elm.lang.core.stubs.ElmFunctionDeclarationLeftStub


/**
 * A value or function declaration.
 *
 * Examples:
 *
 * A simple value:
 * `foo = 42`
 *
 * A function that takes 2 arguments:
 * `update msg model = model`
 */
class ElmFunctionDeclarationLeft : ElmStubbedNamedElementImpl<ElmFunctionDeclarationLeftStub> {

    constructor(node: ASTNode) :
            super(node, IdentifierCase.LOWER)

    constructor(stub: ElmFunctionDeclarationLeftStub, stubType: IStubElementType<*, *>) :
            super(stub, stubType, IdentifierCase.LOWER)


    /**
     * The name given to the function/value
     */
    val lowerCaseIdentifier: PsiElement
        get() = findNotNullChildByType(LOWER_CASE_IDENTIFIER)

    /**
     * Zero or more parameters to the function
     */
    val patternList: List<ElmPattern>
        get() = PsiTreeUtil.getChildrenOfTypeAsList(this, ElmPattern::class.java)


    /**
     * All parameter names declared in this function.
     *
     * e.g. `a`, `b`, `c`, `d`, and `e` in `foo a (b, (c, d)) {e} = 42`
     */
    val namedParameters: List<ElmNameDeclarationPattern>
        get() = PsiTreeUtil.collectElementsOfType(this, ElmNameDeclarationPattern::class.java).toList()
}
