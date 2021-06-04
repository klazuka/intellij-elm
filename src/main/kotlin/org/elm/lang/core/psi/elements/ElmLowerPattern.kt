package org.elm.lang.core.psi.elements

import com.intellij.lang.ASTNode
import com.intellij.psi.PsiElement
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.SearchScope
import org.elm.ide.icons.ElmIcons
import org.elm.lang.core.psi.*
import org.elm.lang.core.resolve.ElmReferenceElement
import org.elm.lang.core.resolve.reference.ElmReference
import org.elm.lang.core.resolve.reference.RecordFieldReference
import javax.swing.Icon


/**
 * A lower-case identifier within a pattern
 *
 * e.g. the `x` parameter in `f x = 0`
 * e.g. `a` and `b` in the declaration `(a, b) = (0, 0)`
 */
class ElmLowerPattern(node: ASTNode) : ElmNamedElementImpl(node, IdentifierCase.LOWER),
        ElmReferenceElement, ElmNameDeclarationPatternTag,
        ElmFunctionParamTag, ElmPatternChildTag, ElmUnionPatternChildTag {

    override fun getIcon(flags: Int): Icon {
        return ElmIcons.VALUE
    }

    val identifier: PsiElement
        get() = findNotNullChildByType(ElmTypes.LOWER_CASE_IDENTIFIER)

    override fun getUseScope(): SearchScope {
        /*
         * Override IntelliJ's default so that a narrower scope can be returned.
         *
         * Motivation:
         *
         * Users frequently rename function parameters. And those parameter
         * names are generally very short (e.g. `x`) and/or very common (e.g. `index`).
         * IntelliJ's rename refactoring is built on top of "Find Usages" which must
         * first search for all strings that match and then resolve each reference
         * to see if it actually points at the thing that is being renamed.
         *
         * The string search is pretty fast since it uses an index, but resolving
         * each possible reference can be slow. In the case of function parameters,
         * their usages are constrained to just the body of the function. So we can
         * drastically reduce the number of candidates by returning a narrower
         * `SearchScope` in this case.
         *
         * Implementation:
         *
         * We could try and restrict the scope to be as narrow as possible, but
         * I'd rather err on the side of caution for now and keep this simple.
         * Restricting it just to the containing file is a huge win over scanning
         * the entire project.
         *
         * TODO make more restrictive, being careful to handle all of the cases
         */
        return LocalSearchScope(elmFile)
    }

    override val referenceNameElement: PsiElement
        get() = identifier

    override val referenceName: String
        get() = identifier.text

    override fun getReference(): ElmReference {
        return RecordFieldReference.fromElement(this) { it.parent as? ElmRecordPattern }
    }
}
