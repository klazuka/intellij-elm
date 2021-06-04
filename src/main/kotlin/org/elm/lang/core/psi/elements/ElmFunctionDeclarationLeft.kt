package org.elm.lang.core.psi.elements

import com.intellij.lang.ASTNode
import com.intellij.psi.PsiElement
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.SearchScope
import com.intellij.psi.stubs.IStubElementType
import com.intellij.psi.util.PsiTreeUtil
import org.elm.ide.icons.ElmIcons
import org.elm.lang.core.psi.*
import org.elm.lang.core.psi.ElmTypes.LOWER_CASE_IDENTIFIER
import org.elm.lang.core.stubs.ElmFunctionDeclarationLeftStub


/**
 * The left-hand side of a value or function declaration.
 *
 * Examples:
 *
 * A simple value:
 * `foo = 42`
 *
 * A function that takes 2 arguments:
 * `update msg model = model`
 *
 * A declaration inside a `let/in` expression:
 * `let
 *      foo = 42
 *  in ...`
 */
class ElmFunctionDeclarationLeft : ElmStubbedNamedElementImpl<ElmFunctionDeclarationLeftStub>, ElmExposableTag, ElmValueAssigneeTag {

    constructor(node: ASTNode) :
            super(node, IdentifierCase.LOWER)

    constructor(stub: ElmFunctionDeclarationLeftStub, stubType: IStubElementType<*, *>) :
            super(stub, stubType, IdentifierCase.LOWER)

    override fun getIcon(flags: Int) =
        ElmIcons.FUNCTION

    /**
     * The name given to the function/value
     */
    val lowerCaseIdentifier: PsiElement
        get() = findNotNullChildByType(LOWER_CASE_IDENTIFIER)

    /**
     * Zero or more parameters to the function
     */
    val patterns: Sequence<ElmFunctionParamTag>
        get() = directChildren.filterIsInstance<ElmFunctionParamTag>()


    /**
     * All parameter names declared in this function.
     *
     * e.g. `a`, `b`, `c`, `d`, and `e` in `foo a (b, (c, d)) {e} = 42`
     */
    val namedParameters: Collection<ElmNameDeclarationPatternTag>
        get() = PsiTreeUtil.collectElementsOfType(this, ElmNameDeclarationPatternTag::class.java)


    /** Return true if this declaration is not nested in a let-in expression */
    val isTopLevel: Boolean
        get() {
            val p = parent
            return p is ElmValueDeclaration && p.isTopLevel
        }

    override fun getUseScope(): SearchScope {
        /*
        Performance optimization to limit the [PsiReference] candidates that IntelliJ has to resolve
        when finding occurrences of a function. The default search scope is the entire project.

        If a function is declared in a `let` expression, we can restrict the scope to just the
        `let` expression and its children.
         */

        if (!isTopLevel) {
            return ancestors.firstOrNull { it is ElmLetInExpr }
                    ?.let { LocalSearchScope(it) }
                    ?: super.getUseScope()
        }

        // TODO consider optimizing this further for top-level functions which are not exposed by the module

        return super.getUseScope()
    }
}
