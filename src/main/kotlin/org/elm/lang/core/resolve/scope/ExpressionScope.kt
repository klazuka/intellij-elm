package org.elm.lang.core.resolve.scope

import com.intellij.psi.PsiElement
import org.elm.lang.core.psi.*
import org.elm.lang.core.psi.elements.ElmAnonymousFunctionExpr
import org.elm.lang.core.psi.elements.ElmCaseOfBranch
import org.elm.lang.core.psi.elements.ElmLetInExpr
import org.elm.lang.core.psi.elements.ElmValueDeclaration


class ExpressionScope(private val element: PsiElement) {
    /** Return a lazy sequence of all names visible to the [element] */
    fun getVisibleValues(includeTopLevel: Boolean = true): Sequence<ElmNamedElement> {
        val declAncestors = mutableListOf<ElmValueDeclaration>()
        return element.ancestorsStrict
                .takeUntil { it is ElmFile }
                .flatMap {
                    when (it) {
                        is ElmFile -> {
                            if (includeTopLevel) ModuleScope.getVisibleValues(it).all.asSequence()
                            else emptySequence()
                        }
                        is ElmValueDeclaration -> {
                            declAncestors += it
                            val isTopLevel = it.isTopLevel
                            // Don't include top-level assignees here, since they'll be included in
                            // ModuleScope.getVisibleValues
                            it.declaredNames(includeParameters = true).asSequence()
                                    .filter { n -> !isTopLevel || n !is ElmValueAssigneeTag  }
                        }
                        is ElmLetInExpr -> {
                            it.valueDeclarationList.asSequence()
                                    .filter { innerDecl -> innerDecl !in declAncestors } // already visited
                                    .flatMap { innerDecl ->
                                        innerDecl.declaredNames(includeParameters = false).asSequence()
                                    }
                        }
                        is ElmCaseOfBranch -> {
                            it.destructuredNames.asSequence()
                        }
                        is ElmAnonymousFunctionExpr -> {
                            it.namedParameters.asSequence()
                        }
                        else -> {
                            emptySequence()
                        }
                    }
                }
    }
}

private class TakeUntilSequence<T> (
        private val sequence: Sequence<T>,
        private val predicate: (T) -> Boolean
) : Sequence<T> {
    override fun iterator(): Iterator<T> = object : Iterator<T> {
        val iterator = sequence.iterator()
        var done = false

        override fun next(): T {
            if (!done && iterator.hasNext()) {
                val item = iterator.next()
                if (predicate(item)) {
                    done = true
                }
                return item
            }
            throw NoSuchElementException()
        }

        override fun hasNext(): Boolean {
            return !done && iterator.hasNext()
        }
    }
}

/** Return elements of this sequence up to and including the first element for which [predicate] returns true */
private fun <T> Sequence<T>.takeUntil(predicate: (T) -> Boolean): Sequence<T> {
    return TakeUntilSequence(this, predicate)
}
