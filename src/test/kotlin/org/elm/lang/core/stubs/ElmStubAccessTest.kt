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

package org.elm.lang.core.stubs

import com.intellij.openapi.util.Iconable
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileFilter
import com.intellij.openapi.vfs.VirtualFileVisitor
import com.intellij.psi.PsiElement
import com.intellij.psi.impl.source.PsiFileImpl
import com.intellij.psi.stubs.StubElement
import com.intellij.testFramework.LoggedErrorProcessor
import org.elm.lang.ElmTestBase
import org.elm.lang.core.psi.ElmNamedElement
import org.elm.lang.core.psi.ElmPsiElement
import java.util.ArrayDeque
import java.util.HashMap


class ElmStubAccessTest : ElmTestBase() {
    override val dataPath = "org/elm/lang/core/stubs/fixtures"

    override fun setUp() {
        super.setUp()
        myFixture.copyDirectoryToProject(".", "src")
    }

    fun `test presentation does not need ast`() {
        processStubsWithoutAstAccess<ElmNamedElement> { element ->
            element.getIcon(0)
            element.getIcon(Iconable.ICON_FLAG_VISIBILITY)
            element.name
            element.presentation?.let {
                it.locationString
                it.presentableText
                it.getIcon(false)
            }
        }
    }

    fun `test getting reference does not need ast`() {
        processStubsWithoutAstAccess<ElmPsiElement> { it.reference }
    }

    fun `test parent works correctly for stubbed elements`() {
        val parentsByStub: MutableMap<PsiElement, PsiElement> = HashMap()
        try {
            LoggedErrorProcessor.setNewInstance(object : LoggedErrorProcessor() {
                override fun processError(category: String, message: String?, t: Throwable?, details: Array<out String>): Boolean {
                    return super.processError(category, message, t, details)
                }
            })
            processStubsWithoutAstAccess<ElmPsiElement> {
                val parent = try {
                    it.parent
                } catch (e: AssertionError) {
                    null
                }
                if (parent != null) {
                    parentsByStub += it to it.parent
                }
            }
        } finally {
            LoggedErrorProcessor.restoreDefaultProcessor()
        }

        checkAstNotLoaded(VirtualFileFilter.NONE)

        for ((element, stubParent) in parentsByStub) {
            element.node // force AST loading
            check(element.parent == stubParent) {
                "parentByStub returned wrong result for $element\n${element.text}"
            }
        }
    }

    private inline fun <reified T : PsiElement> processStubsWithoutAstAccess(block: (T) -> Unit) {
        checkAstNotLoaded(VirtualFileFilter.ALL)

        val work = ArrayDeque<StubElement<*>>()

        VfsUtilCore.visitChildrenRecursively(myFixture.findFileInTempDir("src"), object : VirtualFileVisitor<Void>() {
            override fun visitFileEx(file: VirtualFile): Result {
                if (!file.isDirectory) {
                    work.push((psiManager.findFile(file) as PsiFileImpl).stub!!)
                }

                return CONTINUE
            }
        })

        var processed = 0
        var visited = 0
        while (work.isNotEmpty()) {
            val stub = work.pop()
            val psi = stub.psi
            visited += 1
            if (psi is T) {
                block(psi)
                processed += 1
            }
            work += stub.childrenStubs
        }

        check(visited > 10)
        check(processed > 0)
    }
}
