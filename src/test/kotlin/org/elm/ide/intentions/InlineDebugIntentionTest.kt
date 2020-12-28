package org.elm.ide.intentions


class InlineDebugIntentionTest : ElmIntentionTestBase(InlineDebugIntention()) {

    fun `test debugging function argument`() = doAvailableTest(
            """
module Foo exposing (f0)
f0 = 
    List.map f ite{-caret-}ms
""", """
module Foo exposing (f0)
f0 = 
    List.map f (Debug.log "items" (items))
""")

    fun `test debugging function call`() = doAvailableTest(
            """
module Foo exposing (f0)
f0 = 
    Li{-caret-}st.map f items
""", """
module Foo exposing (f0)
f0 = 
    (Debug.log "List.map f items" (List.map f items))
""")

    fun `test debugging expr with double quotes`() = doAvailableTest(
        """
module Foo exposing (f0)
f0 = 
    Str{-caret-}ing.length "foo"
""", """
module Foo exposing (f0)
f0 = 
    (Debug.log "String.length \"foo\"" (String.length "foo"))
""")

    fun `test debugging pattern matching input`() = doAvailableTest(
            """
module Foo exposing (f0)
f0 =
    [ 1 ]

f1 = 
    case f{-caret-}0 of
        _ -> 1
""", """
module Foo exposing (f0)
f0 =
    [ 1 ]

f1 = 
    case (Debug.log "f0" (f0)) of
        _ -> 1
""")

    fun `test debugging pattern matching output`() = doAvailableTest(
            """
module Foo exposing (f0)
f0 =
    [ 1 ]

f1 = 
    case{-caret-} f0 of
        _ -> 1
""", """
module Foo exposing (f0)
f0 =
    [ 1 ]

f1 = 
    (Debug.log
        "case f0 of ..."
        (
            case f0 of
                _ -> 1
        )
    )
""")

    fun `test debugging case branch`() = doAvailableTest(
            """
module Foo exposing (f0)
f0 =
    [ 1 ]

f1 = 
    case f0 of
        {-caret-}_ -> 1
""", """
module Foo exposing (f0)
f0 =
    [ 1 ]

f1 = 
    (Debug.log
        "case f0 of ..."
        (
            case f0 of
                _ -> 1
        )
    )
""")

    fun `test debugging case branch value`() = doAvailableTest(
            """
module Foo exposing (f0)
f0 =
    [ 1 ]

f1 = 
    case f0 of
        _ -> 1 + 1{-caret-}
""", """
module Foo exposing (f0)
f0 =
    [ 1 ]

f1 = 
    case f0 of
        _ -> (Debug.log "1 + 1" (1 + 1))
""")

    fun `test debugging case on function call`() = doAvailableTest(
            """
module Foo exposing (f0)
f0 _ =
    [ 1 ]

f1 = 
    case ({-caret-}f0 42) of
        _ -> 1
""", """
module Foo exposing (f0)
f0 _ =
    [ 1 ]

f1 = 
    case ((Debug.log "f0 42" (f0 42))) of
        _ -> 1
""")

    fun `test debugging constants with binary operators`() = doAvailableTest(
            """
module Foo exposing (f0)
f1 = 1

f0 = 
    1 + f1{-caret-}
""", """
module Foo exposing (f0)
f1 = 1

f0 = 
    1 + (Debug.log "f1" (f1))
""")

    fun `test debugging binary operator operation`() = doAvailableTest(
            """
module Foo exposing (f0)
f0 = 
    1 +{-caret-} 1
""", """
module Foo exposing (f0)
f0 = 
    (Debug.log "1 + 1" (1 + 1))
""")

    fun `test debugging multiple binary operator operations`() = doAvailableTest(
            """
module Foo exposing (f0)
f0 = 
    1 +{-caret-} 1 + 1
""", """
module Foo exposing (f0)
f0 = 
    (Debug.log "1 + 1 + 1" (1 + 1 + 1))
""")

    fun `test debugging piped operations`() = doAvailableTest(
            """
module Foo exposing (f0)
f0 = 
    1 + 1 +{-caret-} 1 |> (+) 1
""", """
module Foo exposing (f0)
f0 = 
    (Debug.log "1 + 1 + 1 |> (+) 1" (1 + 1 + 1 |> (+) 1))
""")

    fun `test debugging piped operations with function`() = doAvailableTest(
            """
module Foo exposing (f0)
f0 = 
    1 + 1 + 1 |> String.fr{-caret-}omInt
""", """
module Foo exposing (f0)
f0 = 
    (Debug.log "1 + 1 + 1 |> String.fromInt" (1 + 1 + 1 |> String.fromInt))
""")

    fun `test debugging multiline piped operations with function`() = doAvailableTest(
            """
module Foo exposing (f0)
f0 = 
    1 + 1 + 1
        |> String.fr{-caret-}omInt
""", """
module Foo exposing (f0)
f0 = 
    (Debug.log
        "1 + 1 + 1 ..."
        (
            1 + 1 + 1
                |> String.fromInt
        )
    )
""")

    fun `test debugging function composition`() = doUnavailableTest(
            """
module Foo exposing (f0)
f0 = 
    (+) 1 >> String.fr{-caret-}omInt
""")

    fun `test debugging log statements`() = doUnavailableTest(
            """
module Foo exposing (f0)
f0 = 
    Debug.l{-caret-}og "hello" "hello"
""")

    fun `test debugging log arguments`() = doUnavailableTest(
            """
module Foo exposing (f0)
f0 = 
    Debug.log "hello" (2 + 2){-caret-}
""")

    fun `test debugging ignoring nested log function calls`() = doUnavailableTest(
            """
module Foo exposing (f0)
f0 = 
    Debug.log "hello" (List.ma{-caret-}p identity [ 0 ])
""")

    fun `test debugging arguments for nested log function calls`() = doAvailableTest(
            """
module Foo exposing (f0)
f0 = 
    Debug.log "hello" (List.map ident{-caret-}ity [ 0 ])
""", """
module Foo exposing (f0)
f0 = 
    Debug.log "hello" (List.map (Debug.log "identity" (identity)) [ 0 ])
""")

    fun `test debugging nested log function calls with binary operators`() = doAvailableTest(
            """
module Foo exposing (f0)
f0 = 
    Debug.log "hello" (2 + ({-caret-}1 + 1))
""", """
module Foo exposing (f0)
f0 = 
    Debug.log "hello" (2 + ((Debug.log "1 + 1" (1 + 1))))
""")

    fun `test debugging todo statements`() = doUnavailableTest(
            """
module Foo exposing (f0)
f0 = 
    Debug.tod{-caret-}o "hello"
""")

    fun `test debugging todo arguments`() = doUnavailableTest(
            """
module Foo exposing (f0)
f0 = 
    Debug.todo (2 + 2){-caret-}
""")

    fun `test debugging string literals`() = doUnavailableTest(
            """
module Foo exposing (f0)
f0 = 
    "he{-caret-}llo"
""")

    fun `test debugging char literals`() = doUnavailableTest(
            """
module Foo exposing (f0)
f0 = 
    'e{-caret-}'
""")

    fun `test debugging numeric literals`() = doUnavailableTest(
            """
module Foo exposing (f0)
f0 = 
    1{-caret-}
""")

    fun `test debugging float literals`() = doUnavailableTest(
            """
module Foo exposing (f0)
f0 = 
    1{-caret-}.2
""")

    fun `test debugging constants`() = doAvailableTest(
            """
module Foo exposing (f0)
f0 = 
    1
    
f1 =
    f0{-caret-}
""", """
module Foo exposing (f0)
f0 = 
    1
    
f1 =
    (Debug.log "f0" (f0))
""")

}