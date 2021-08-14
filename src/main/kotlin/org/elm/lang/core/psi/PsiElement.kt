/*
The MIT License (MIT)

Derived from intellij-rust
Copyright (c) 2015 Aleksey Kladov, Evgeny Kurbatsky, Alexey Kudinkin and contributors
Copyright (c) 2016 JetBrains

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
 */

package org.elm.lang.core.psi

import com.intellij.application.options.CodeStyle
import com.intellij.extapi.psi.StubBasedPsiElementBase
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.StubBasedPsiElement
import com.intellij.psi.codeStyle.CommonCodeStyleSettings
import com.intellij.psi.impl.source.PsiFileImpl
import com.intellij.psi.stubs.StubElement
import com.intellij.psi.tree.IElementType
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.PsiUtilCore
import com.intellij.util.SmartList
import org.elm.lang.core.lexer.ElmLayoutLexer
import org.elm.lang.core.psi.ElmTypes.VIRTUAL_END_DECL
import org.elm.lang.core.psi.elements.ElmValueDeclaration
import org.elm.lang.core.stubs.ElmFileStub


val PsiElement.descendants: Sequence<PsiElement> get() = directChildren.flatMap { sequenceOf(it) + it.descendants }
val PsiElement.ancestors: Sequence<PsiElement> get() = generateSequence(this) { it.parent }
val PsiElement.ancestorsStrict: Sequence<PsiElement> get() = ancestors.drop(1)
val PsiElement.prevSiblings: Sequence<PsiElement> get() = generateSequence(prevSibling) { it.prevSibling }
val PsiElement.nextSiblings: Sequence<PsiElement> get() = generateSequence(nextSibling) { it.nextSibling }
val PsiElement.prevLeaves: Sequence<PsiElement> get() = generateSequence(PsiTreeUtil.prevLeaf(this)) { PsiTreeUtil.prevLeaf(it) }
val PsiElement.nextLeaves: Sequence<PsiElement> get() = generateSequence(PsiTreeUtil.nextLeaf(this)) { PsiTreeUtil.nextLeaf(it) }
val PsiElement.directChildren: Sequence<PsiElement> get() = generateSequence(firstChild) { it.nextSibling }
val Sequence<PsiElement>.withoutWs get() = filter { it !is PsiWhiteSpace && it.elementType != VIRTUAL_END_DECL }
val Sequence<PsiElement>.withoutWsOrComments get() = withoutWs.filter { it !is PsiComment }
val Sequence<PsiElement>.withoutErrors get() = filter { it !is PsiErrorElement }


/**
 * Extracts node's element type, being careful not to switch from stubs to AST inadvertently.
 */
val PsiElement.elementType: IElementType
    get() = if (this is ElmFile) ElmFileStub.Type else PsiUtilCore.getElementType(this)

inline fun <reified T : PsiElement> PsiElement.parentOfType(strict: Boolean = true, minStartOffset: Int = -1): T? =
        PsiTreeUtil.getParentOfType(this, T::class.java, strict, minStartOffset)

inline fun <reified T : PsiElement> PsiElement.parentOfType(strict: Boolean = true, stopAt: Class<out PsiElement>): T? =
        PsiTreeUtil.getParentOfType(this, T::class.java, strict, stopAt)

fun <T : PsiElement> PsiElement.parentOfType(vararg classes: Class<out T>): T? {
    return PsiTreeUtil.getParentOfType(this, *classes)
}

inline fun <reified T : PsiElement> PsiElement.contextOfType(strict: Boolean = true): T? =
        PsiTreeUtil.getContextOfType(this, T::class.java, strict)

inline fun <reified T : PsiElement> PsiElement.descendantOfType(): T? =
        PsiTreeUtil.findChildrenOfType(this, T::class.java).find { it is T }

inline fun <reified T : PsiElement> PsiElement.descendantsOfType(): Collection<T> =
        PsiTreeUtil.findChildrenOfType(this, T::class.java)

/** Returns direct children of the specified type */
inline fun <reified T : PsiElement> PsiElement.directChildrenOfType(): List<T> =
        PsiTreeUtil.getChildrenOfTypeAsList(this, T::class.java)

/** Returns direct children stubs (or AST, if necessary) of the specified type */
inline fun <reified T : PsiElement> PsiElement.stubDirectChildrenOfType(): List<T> {
    return if (this is PsiFileImpl) {
        stub?.childrenStubs?.mapNotNull { it.psi as? T } ?: return directChildrenOfType()
    } else {
        PsiTreeUtil.getStubChildrenOfTypeAsList(this, T::class.java)
    }
}

inline fun <reified T : PsiElement> PsiElement.stubDescendantsOfTypeStrict(): Collection<T> =
        getStubDescendantsOfType(this, true, T::class.java)

inline fun <reified T : PsiElement> PsiElement.stubDescendantsOfTypeOrSelf(): Collection<T> =
        getStubDescendantsOfType(this, false, T::class.java)

inline fun <reified T : PsiElement> PsiElement.stubDescendantOfTypeOrStrict(): T? =
        getStubDescendantOfType(this, true, T::class.java)

inline fun <reified T : PsiElement> PsiElement.stubDescendantOfTypeOrSelf(): T? =
        getStubDescendantOfType(this, false, T::class.java)

fun <T : PsiElement> getStubDescendantsOfType(
        element: PsiElement?,
        strict: Boolean,
        aClass: Class<T>
): Collection<T> {
    if (element == null) return emptyList()
    val stub = (element as? PsiFileImpl)?.greenStub
            ?: (element as? StubBasedPsiElement<*>)?.greenStub
            ?: return PsiTreeUtil.findChildrenOfAnyType<T>(element, strict, aClass)

    val result = SmartList<T>()

    fun go(childrenStubs: List<StubElement<PsiElement>>) {
        for (childStub in childrenStubs) {
            val child = childStub.psi
            if (aClass.isInstance(child)) {
                result.add(aClass.cast(child))
            } else {
                go(childStub.childrenStubs)
            }
        }

    }

    if (strict) {
        go(stub.childrenStubs)
    } else {
        go(listOf(stub).map { it as StubElement<PsiElement> })
    }

    return result
}

fun <T : PsiElement> getStubDescendantOfType(
        element: PsiElement?,
        strict: Boolean,
        aClass: Class<T>
): T? {
    if (element == null) return null
    val stub = (element as? PsiFileImpl)?.greenStub
            ?: (element as? StubBasedPsiElement<*>)?.greenStub
            ?: return PsiTreeUtil.findChildOfType<T>(element, aClass, strict)

    fun go(childrenStubs: List<StubElement<PsiElement>>): T? {
        for (childStub in childrenStubs) {
            val child = childStub.psi
            if (aClass.isInstance(child)) {
                return aClass.cast(child)
            } else {
                go(childStub.childrenStubs)?.let { return it }
            }
        }

        return null
    }

    return if (strict) {
        go(stub.childrenStubs)
    } else {
        go(listOf(stub as StubElement<PsiElement>))
    }
}

@Suppress("UNCHECKED_CAST")
inline val <T : StubElement<*>> StubBasedPsiElement<T>.greenStub: T?
    get() = (this as? StubBasedPsiElementBase<T>)?.greenStub


val PsiElement.startOffset: Int
    get() = textRange.startOffset

val PsiElement.endOffset: Int
    get() = textRange.endOffset

/**
 * Computes the start offset of the receiver relative to [owner].
 *
 * The receiver must be a descendant of [owner]
 */
fun PsiElement.offsetIn(owner: PsiElement): Int =
        ancestors.takeWhile { it != owner }.sumBy { it.startOffsetInParent }

val PsiElement.hasErrors: Boolean
    get() = PsiTreeUtil.hasErrorElements(this)

val PsiFile.indentStyle: CommonCodeStyleSettings.IndentOptions
    get() = CodeStyle.getIndentOptions(this)

val PsiElement.indentStyle: CommonCodeStyleSettings.IndentOptions
    get() = containingFile.indentStyle

/**
 * Return a string containing a number of spaces equal to the current
 * [indent size][CommonCodeStyleSettings.IndentOptions.INDENT_SIZE]
 */
val CommonCodeStyleSettings.IndentOptions.oneLevelOfIndentation: String
    get() = " ".repeat(INDENT_SIZE)

// Elm-specific helpers

/**
 * Returns true if this element is a virtual token synthesized by [ElmLayoutLexer]
 */
fun PsiElement.isVirtualLayoutToken(): Boolean =
        elementType in ELM_VIRTUAL_TOKENS

/** Return true if this element is a direct child of an [ElmFile] */
val PsiElement.isTopLevel: Boolean
    get() = parent is ElmFile

/** Return the top level value declaration from this element's ancestors */
fun PsiElement.outermostDeclaration(strict: Boolean): ElmValueDeclaration? =
        ancestors.drop(if (strict) 1 else 0)
                .takeWhile { it !is ElmFile }
                .filterIsInstance<ElmValueDeclaration>()
                .firstOrNull { it.isTopLevel }

/**
 * Return the name from module declaration of the file containing this element, or the empty string
 * if there isn't one.
 */
val ElmPsiElement.moduleName: String
    get() = elmFile.getModuleDecl()?.name ?: ""
