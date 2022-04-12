/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.elm.ide.hints.parameter

import com.intellij.codeInsight.daemon.impl.HintRenderer
import org.elm.lang.ElmTestBase
import org.intellij.lang.annotations.Language

class ElmInlayParameterHintsProviderTest : ElmTestBase() {

    fun `test type hint in lambda`() = checkByText("""module Foo exposing (..)

example =
    (\(greeting{-hint text=": String"-}, name{-hint text=": String"-}) -> greeting ++ ", " ++ name ) ("Hello", "World")
""")

    fun `test let binding type hint`() = checkByText("""module Foo exposing (..)

example ={-hint text=" -- Float"-}
    let
        foo ={-hint text=" -- Float"-}
            3.14
    in
    foo
""")

    fun `test no hint for annotated let binding`() = checkByText("""module Foo exposing (..)

example : List Float
example =
    let
        foo : List Float
        foo =
            1 :: [ 2.3 ]
    in
    foo
""")

    fun `test don't show type hint when unknown`() = checkByText("""module Foo exposing (..)

example =
    let
        foo =
            123 + ""
    in
    foo
""")

    fun `test show annotation for top-level definitions`() = checkByText("""module Foo exposing (..)
 

example ={-hint text=" -- String"-}
    "Hello!"
""")
    fun `test show type annotation next to pattern matched values`() = checkByText("""module Foo exposing (..)
 
greet : { first : String, last : String, age : Int } -> String
greet { first{-hint text=": String"-}, age{-hint text=": Int"-} }=
    "Hello " ++ first ++ "!"
""")

    @Suppress("UnstableApiUsage")
    private fun checkByText(@Language("Elm") code: String) {
        InlineFile(code.replace(HINT_COMMENT_PATTERN, "<$1/>"))

        ElmInlayParameterHints.enabledOption.set(true)
        myFixture.testInlays({ (it.renderer as HintRenderer).text }) { it.renderer is HintRenderer }
    }

    companion object {
        private val HINT_COMMENT_PATTERN = Regex("""\{-(hint.*?)-\}""")
    }
}