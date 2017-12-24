package org.elm.ide.intentions

import org.elm.fileTreeFromText
import org.intellij.lang.annotations.Language

class ElmImportIntentionTest: ElmIntentionTestBase(ElmImportIntentionAction()) {


    fun `test un-qualified value`() = check(
"""
--@ main.elm
main = bar{-caret-}
--@ Foo.elm
module Foo exposing (bar)
bar = 42
""",
"""
import Foo exposing (bar)
main = bar
""")


    fun `test qualified value`() = check(
"""
--@ main.elm
main = Foo.bar{-caret-}
--@ Foo.elm
module Foo exposing (bar)
bar = 42
""",
"""
import Foo
main = Foo.bar
""")


    fun `test import between module decl and value-decl`() = check(
"""
--@ main.elm
module Main exposing (..)
main = Foo.bar{-caret-}
--@ Foo.elm
module Foo exposing (bar)
bar = 42
""".trimStart(),
"""
module Main exposing (..)
import Foo
main = Foo.bar{-caret-}
""".trimStart())


    fun `test import between module decl and doc-comment`() = check(
"""
--@ main.elm
module Main exposing (..)
{-| this is a doc comment. it must be above imports -}
main = Foo.bar{-caret-}
--@ Foo.elm
module Foo exposing (bar)
bar = 42
""".trimStart(),
"""
module Main exposing (..)
{-| this is a doc comment. it must be above imports -}
import Foo
main = Foo.bar{-caret-}
""".trimStart())


    fun `test expose a value with existing import`() = check(
"""
--@ main.elm
import Foo
main = bar{-caret-}
--@ Foo.elm
module Foo exposing (bar)
bar = 42
""",
"""
import Foo exposing (bar)
main = bar
""")


    fun `test merge with existing exposed values`() = check(
"""
--@ main.elm
import Foo exposing (quux)
main = quux + bar{-caret-}
--@ Foo.elm
module Foo exposing (bar, quux)
bar = 42
quux = 99
""",
"""
import Foo exposing (bar, quux)
main = quux + bar
""")


    fun `test merge with existing exposed union constructors`() = check(
"""
--@ main.elm
import App exposing (Page(Home))
main = Settings{-caret-}
--@ App.elm
module App exposing (Page(..))
type Page = Home | Settings
""",
"""
import App exposing (Page(Home, Settings))
main = Settings
""")


    fun `test merge with existing exposed union type`() = check(
"""
--@ main.elm
import App exposing (Page)
main = Settings{-caret-}
--@ App.elm
module App exposing (Page(..))
type Page = Home | Settings
""",
"""
import App exposing (Page(Settings))
main = Settings
""")


    fun `test verify unavailable when value not exposed`() = verifyUnavailable(
"""
--@ main.elm
main = bar{-caret-}
--@ Foo.elm
module Foo exposing ()
bar = 42
""")

    protected fun check(@Language("Elm") before: String, @Language("Elm") after: String) {
        fileTreeFromText(before).createAndOpenFileWithCaretMarker()
        myFixture.launchAction(intention)
        myFixture.checkResult(replaceCaretMarker(after).trim())
    }

    protected fun verifyUnavailable(@Language("Elm") before: String) {
        fileTreeFromText(before).createAndOpenFileWithCaretMarker()
        check(intention.familyName !in myFixture.availableIntentions.mapNotNull { it.familyName }) {
            "\"$intention\" intention should not be available"
        }
    }
}