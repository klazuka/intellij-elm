package org.elm.ide.inspections

import org.intellij.lang.annotations.Language

class ElmIncompletePatternInspectionTest : ElmInspectionsTestBase(ElmIncompletePatternInspection()) {

    fun `test all branches present`() = checkFixIsUnavailable("Add missing case branches", """
type Foo = Bar | Baz

foo : Foo -> ()
foo it =
    case{-caret-} it of
        Bar ->
            ()

        Baz ->
            ()
""")

    fun `test no existing branch`() = checkFixByText("Add missing case branches", """
type Foo = Bar | Baz | Qux

foo : Foo -> ()
foo it =
    <error>case{-caret-}</error> it of
""", """
type Foo = Bar | Baz | Qux

foo : Foo -> ()
foo it =
    case it of
        Bar ->
            --EOL

        Baz ->
            --EOL

        Qux ->
            --EOL
""")

    fun `test one existing branch`() = checkFixByText("Add missing case branches", """
type Foo = Bar | Baz | Qux

foo : Foo -> ()
foo it =
    <error>case{-caret-}</error> it of
        Baz ->
            ()
""", """
type Foo = Bar | Baz | Qux

foo : Foo -> ()
foo it =
    case it of
        Baz ->
            ()

        Bar ->
            --EOL

        Qux ->
            --EOL
""")

    fun `test two existing branches`() = checkFixByText("Add missing case branches", """
type Foo = Bar | Baz | Qux

foo : Foo -> ()
foo it =
    <error>case{-caret-}</error> it of
        Baz ->
            ()

        Qux ->
            ()
""", """
type Foo = Bar | Baz | Qux

foo : Foo -> ()
foo it =
    case it of
        Baz ->
            ()

        Qux ->
            ()

        Bar ->
            --EOL
""")

//    fun `test no existing branch in parenthesis`() = checkFixByText("Add missing case branches", """
//type Foo = Bar | Baz | Qux
//
//foo : Foo -> ()
//foo it =
//    (<error>case{-caret-}</error> it of<error>)</error>
//""", """
//type Foo = Bar | Baz | Qux
//
//foo : Foo -> ()
//foo it =
//    (case it of
//        Bar ->
//            --EOL
//
//        Baz ->
//            --EOL
//
//        Qux ->
//            --EOL
//    )
//""")

//    fun `test one existing branch in parenthesis`() = checkFixByText("Add missing case branches", """
//type Foo = Bar | Baz | Qux
//
//foo : Foo -> ()
//foo it =
//    (<error>case{-caret-}</error> it of
//        Baz ->
//            ()<error>)</error>
//""", """
//type Foo = Bar | Baz | Qux
//
//foo : Foo -> ()
//foo it =
//    (case it of
//        Baz ->
//            ()
//
//        Bar ->
//            --EOL
//
//        Qux ->
//            --EOL
//    )
//""")

    fun `test one existing branch in parenthesis on new line`() = checkFixByText("Add missing case branches", """
type Foo = Bar | Baz | Qux

foo : Foo -> ()
foo it =
    (<error>case{-caret-}</error> it of
        Baz ->
            ()
    )
""", """
type Foo = Bar | Baz | Qux

foo : Foo -> ()
foo it =
    (case it of
        Baz ->
            ()

        Bar ->
            --EOL

        Qux ->
            --EOL
    )
""")

//    fun `test two existing branches in parenthesis`() = checkFixByText("Add missing case branches", """
//type Foo = Bar | Baz | Qux
//
//foo : Foo -> ()
//foo it =
//    (<error>case{-caret-}</error> it of
//        Baz ->
//            ()
//
//        Qux ->
//            ()<error>)</error>
//""", """
//type Foo = Bar | Baz | Qux
//
//foo : Foo -> ()
//foo it =
//    (case it of
//        Baz ->
//            ()
//
//        Qux ->
//            ()
//
//        Bar ->
//            --EOL
//    )
//""")

    fun `test two existing branches in parenthesis on new line`() = checkFixByText("Add missing case branches", """
type Foo = Bar | Baz | Qux

foo : Foo -> ()
foo it =
    (<error>case{-caret-}</error> it of
        Baz ->
            ()

        Qux ->
            ()
    )
""", """
type Foo = Bar | Baz | Qux

foo : Foo -> ()
foo it =
    (case it of
        Baz ->
            ()

        Qux ->
            ()

        Bar ->
            --EOL
    )
""")

    fun `test params`() = checkFixByText("Add missing case branches", """
type Foo = Foo
type alias BarBaz = Foo
type Maybe a = Just a | Nothing
type Msg a b
    = MsgOne Foo BarBaz
    | MsgTwo (Maybe (Maybe Foo))
    | MsgThree b a
    | MsgFour {x: ()}
    | MsgFive (a, b)
    | MsgSix (Msg () ())

foo : Msg a b -> ()
foo it =
    <error>case{-caret-}</error> it of
""", """
type Foo = Foo
type alias BarBaz = Foo
type Maybe a = Just a | Nothing
type Msg a b
    = MsgOne Foo BarBaz
    | MsgTwo (Maybe (Maybe Foo))
    | MsgThree b a
    | MsgFour {x: ()}
    | MsgFive (a, b)
    | MsgSix (Msg () ())

foo : Msg a b -> ()
foo it =
    case it of
        MsgOne foo barBaz ->
            --EOL

        MsgTwo maybe ->
            --EOL

        MsgThree b a ->
            --EOL

        MsgFour record ->
            --EOL

        MsgFive (a, b) ->
            --EOL

        MsgSix msg ->
            --EOL
""")

    fun `test one existing branch, wildcard pattern`() = checkFixByText("Add '_' branch", """
type Foo = Bar | Baz | Qux

foo : Foo -> ()
foo it =
    <error>case{-caret-}</error> it of
        Baz ->
            ()
""", """
type Foo = Bar | Baz | Qux

foo : Foo -> ()
foo it =
    case it of
        Baz ->
            ()

        _ ->
            --EOL
""")

    fun `test no branches with non-union, wildcard pattern`() = checkFixByText("Add '_' branch", """
foo =
    <error>case{-caret-}</error> 1 of
""", """
foo =
    case 1 of
        _ ->
            --EOL
""")

    fun `test no leading whitespace`() = checkFixByText("Add missing case branches", """
type Foo = Bar

foo : Foo -> ()
foo it = <error>case{-caret-}</error> it of
""", """
type Foo = Bar

foo : Foo -> ()
foo it = case it of
    Bar ->
        --EOL
""")

    fun `test nesting in let`() = checkFixByText("Add missing case branches", """
type Foo = Bar

foo : Foo -> ()
foo it =
    let
        bar =
            <error>case{-caret-}</error> it of
    in
        ()
""", """
type Foo = Bar

foo : Foo -> ()
foo it =
    let
        bar =
            case it of
                Bar ->
                    --EOL
    in
        ()
""")

    fun `test nesting in let-in`() = checkFixByText("Add missing case branches", """
type Foo = Bar

foo : Foo -> ()
foo it =
    let
        bar = ()
    in
    <error>case{-caret-}</error> it of
""", """
type Foo = Bar

foo : Foo -> ()
foo it =
    let
        bar = ()
    in
    case it of
        Bar ->
            --EOL
""")


    fun `test nesting in case`() = checkFixByText("Add missing case branches", """
type Foo = Bar

foo : Foo -> ()
foo it =
    case () of
        () ->
            <error>case{-caret-}</error> it of
""", """
type Foo = Bar

foo : Foo -> ()
foo it =
    case () of
        () ->
            case it of
                Bar ->
                    --EOL
""")

    fun `test qualified import`() = checkFixByFileTree("Add missing case branches", """
--@ Data/User.elm
module Data.User exposing (..)
type Foo = Bar | Baz | Qux

--@ main.elm
import Data.User
foo : Data.User.Foo -> ()
foo it =
    <error>case{-caret-}</error> it of
""", """
import Data.User
foo : Data.User.Foo -> ()
foo it =
    case it of
        Data.User.Bar ->
            --EOL

        Data.User.Baz ->
            --EOL

        Data.User.Qux ->
            --EOL
""")

    fun `test import with alias`() = checkFixByFileTree("Add missing case branches", """
--@ Data/User.elm
module Data.User exposing (..)
type Foo = Bar | Baz | Qux

--@ main.elm
import Data.User as UserData
foo : UserData.Foo -> ()
foo it =
    <error>case{-caret-}</error> it of
""", """
import Data.User as UserData
foo : UserData.Foo -> ()
foo it =
    case it of
        UserData.Bar ->
            --EOL

        UserData.Baz ->
            --EOL

        UserData.Qux ->
            --EOL
""")

    fun `test exposed import`() = checkFixByFileTree("Add missing case branches", """
--@ Data/User.elm
module Data.User exposing (..)
type Foo = Bar | Baz | Qux

--@ main.elm
import Data.User exposing (..)
foo : Data.User.Foo -> ()
foo it =
    <error>case{-caret-}</error> it of
""", """
import Data.User exposing (..)
foo : Data.User.Foo -> ()
foo it =
    case it of
        Bar ->
            --EOL

        Baz ->
            --EOL

        Qux ->
            --EOL
""")

    /*
    https://github.com/klazuka/intellij-elm/issues/260

    A project with multiple Elm files each with the same module name and each defining
    a union type by the same name. This causes trouble for the 'missing case branch adder'
    because it needs to lookup Psi based on just a `TyUnion`, which only has a type name
    and module name. These tests ensure that the ambiguity is resolved correctly.
    */

    fun `test issue 260 - needs branches`() = checkFixByFileTree("Add missing case branches", """
--@ File1.elm
type Msg = Qux

foo : Msg -> ()
foo it =
    <error>case{-caret-}</error> it of
--@ File2.elm
type Msg = Foo | Bar
--@ File3.elm
type Msg = Bar | Baz
""", """
type Msg = Qux

foo : Msg -> ()
foo it =
    case it of
        Qux ->
            --EOL

""".trimIndent())

    fun `test issue 260 - ok`() = checkFixIsUnavailableByFileTree("Add missing case branches", """
--@ File1.elm
type Msg = Qux

foo : Msg -> ()
foo it =
    case{-caret-} it of
        Qux ->
            ()
--@ File2.elm
type Msg = Foo | Bar
--@ File3.elm
type Msg = Bar | Baz
""")

    private fun checkFixByText(
            fixName: String,
            @Language("Elm") before: String,
            @Language("Elm") after: String) {
        // We use the --EOL marker to avoid editors trimming trailing whitespace, which is
        // significant for this test.
        super.checkFixByText(fixName, before, after.replace("--EOL", ""), true, false, false)
    }

    private fun checkFixByFileTree(
            fixName: String,
            @Language("Elm") before: String,
            @Language("Elm") after: String) {
        super.checkFixByFileTree(fixName, before, after.replace("--EOL", ""), true, false, false)
    }
}
