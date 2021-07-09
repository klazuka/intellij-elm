package org.elm.ide.inspections.import

import com.intellij.openapi.vfs.VirtualFileFilter
import org.elm.fileTreeFromText
import org.elm.ide.inspections.ElmInspectionsTestBase
import org.elm.ide.inspections.ElmUnresolvedReferenceInspection
import org.elm.lang.core.imports.ImportAdder.Import
import org.intellij.lang.annotations.Language

class MakeDeclarationFromUsageFixTest : ElmInspectionsTestBase(ElmUnresolvedReferenceInspection()) {
    fun `test basic top-level value`() = checkFixByTextWithoutHighlighting("Create",
"""module Foo exposing (..)

myString = greet{-caret-}

""",
            """module Foo exposing (..)

myString = greet
greet = Debug.todo "TODO"
"""
    )

    fun `test function with 1 argument`() = checkFixByTextWithoutHighlighting("Create",
            """module Foo exposing (..)

myString = exclaim{-caret-} "Hello"

""",
            """module Foo exposing (..)

myString = exclaim "Hello"
exclaim arg1 = Debug.todo "TODO"
"""
    )
    fun `test function with 2 arguments`() = checkFixByTextWithoutHighlighting("Create",
            """module Foo exposing (..)

myString = greet{-caret-} "Hello" "World"

""",
            """module Foo exposing (..)

myString = greet "Hello" "World"
greet arg1 arg2 = Debug.todo "TODO"
"""
    )

}
