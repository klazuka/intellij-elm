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

package org.elmPerformanceTests

import com.intellij.psi.util.PsiModificationTracker
import org.elm.lang.core.psi.descendantsOfType
import org.elm.lang.core.resolve.ElmReferenceElement
import org.elm.utils.Timings

class ElmHighlightingPerformanceTest : ElmRealProjectTestBase() {

    fun `test highlighting elm-json-tree-view`() =
            repeatTest { highlightProjectFile(JSON_TREE_VIEW, "src/JsonTree.elm") }

    fun `test highlighting elm-spa-example`() =
            repeatTest { highlightProjectFile(SPA, "src/Page/Article/Editor.elm") }

    fun `test highlighting elm-css`() =
            repeatTest { highlightProjectFile(ELM_CSS, "src/Css/Transitions.elm") }

    fun `test highlighting elm-list-extra`() =
            repeatTest { highlightProjectFile(LIST_EXTRA, "src/List/Extra.elm") }

    fun `test highlighting elm-dev-tools`() =
            repeatTest { highlightProjectFile(DEV_TOOLS, "src/Browser/DevTools/Main.elm") }

    private fun repeatTest(f: () -> Timings) {
        var result = Timings()
        println("${name.substring("test ".length)}:")
        repeat(10) {
            result = result.merge(f())
            tearDown()
            setUp()
        }
        result.report()
    }

    private fun highlightProjectFile(info: RealProjectInfo, filePath: String): Timings {
        val timings = Timings()
        openRealProject(info) ?: return timings

        myFixture.configureFromTempProjectFile(filePath)

        val modificationCount = currentPsiModificationCount()

        val refs = timings.measure("collecting") {
            myFixture.file.descendantsOfType<ElmReferenceElement>()
        }

        timings.measure("resolve") {
            refs.forEach { it.reference.resolve() }
        }
        timings.measure("highlighting") {
            myFixture.doHighlighting()
        }

        check(modificationCount == currentPsiModificationCount()) {
            "PSI changed during resolve and highlighting, resolve might be double counted"
        }

        timings.measure("resolve_cached") {
            refs.forEach { it.reference.resolve() }
        }

        return timings
    }

    private fun currentPsiModificationCount() =
            PsiModificationTracker.getInstance(project).modificationCount
}
