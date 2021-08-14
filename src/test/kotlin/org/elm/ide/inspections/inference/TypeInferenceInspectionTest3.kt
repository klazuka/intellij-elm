package org.elm.ide.inspections.inference

import org.elm.ide.inspections.ElmInspectionsTestBase
import org.elm.ide.inspections.ElmTypeInferenceInspection

class TypeInferenceInspectionTest3 : ElmInspectionsTestBase(ElmTypeInferenceInspection()) {
    override fun getProjectDescriptor() = ElmWithStdlibDescriptor

    fun `test compappend inference from compare before append`() = checkByText("""
f x y = if ( x < y ) then ( x ++ y ) else ( y ++ x )

main : ()
main = <error descr="Type mismatch.Required: ()Found: compappend → compappend → compappend">f</error>
""")

    fun `test compappend inference from append before compare`() = checkByText("""
f x y =
    let
        b = x ++ y
        c = x < y
    in
        x

main : ()
main = <error descr="Type mismatch.Required: ()Found: compappend → compappend → compappend">f</error>
""")

    fun `test compappend inference from comparable assigned to compappend`() = checkByText("""
f x y =
    let
        b = x < x
        c = x ++ x
        d = y < y
        e = x < y
    in
        y

main : ()
main = <error descr="Type mismatch.Required: ()Found: compappend → compappend → compappend">f</error>
""")

    fun `test compappend inference from appendable assigned to compappend`() = checkByText("""
f x y =
    let
        b = x < x
        c = x ++ x
        d = y ++ y
        e = x < y
    in
        y

main : ()
main = <error descr="Type mismatch.Required: ()Found: compappend → compappend → compappend">f</error>
""")

    fun `test mismatched return value from rigid vars`() = checkByText("""
foo : a -> a
foo a = a

main : Int
main = <error descr="Type mismatch.Required: IntFound: String">foo ""</error>
""")

    fun `test mismatched return value from nested vars`() = checkByText("""
foo : List a -> List a
foo a = a

main : List Int
main = <error descr="Type mismatch.Required: List IntFound: List String">foo [""]</error>
""")

    fun `test mismatched return value from multiple rigid vars`() = checkByText("""
foo : List a -> List b -> List b
foo a b = b

main : List Int
main = <error descr="Type mismatch.Required: List IntFound: List String">foo [1] [""]</error>
""")

    fun `test mismatched return value from doubly nested vars`() = checkByText("""
foo : List (List a) -> List (List a)
foo a = a

main : List (List Int)
main = <error descr="Type mismatch.Required: List (List Int)Found: List (List String)">foo [[""]]</error>
""")

    fun `test fixing var value at first occurrence`() = checkByText("""
foo : List a -> List a -> List a
foo a b = a

main : List String
main = foo [""] <error descr="Type mismatch.Required: List StringFound: List (List a)">[[]]</error>
""")

    fun `test passing vars through operator`() = checkByText("""
main : ()
main =
    <error descr="Type mismatch.Required: ()Found: String">List.head [""] |> Maybe.withDefault ""</error>
""")

    fun `test passing function type with vars`() = checkByText("""
map : (c -> d) -> List c -> List d
map f xs = []

foo : String -> String
foo s = s

main : ()
main =
    <error descr="Type mismatch.Required: ()Found: List String → List String">map foo</error>
""")

    fun `test passing function type with vars to function`() = checkByText("""
appL : (a -> b) -> a -> b
appL f x = f x

map : (c -> d) -> List c -> List d
map f xs = []

foo : String -> String
foo s = s

main : ()
main =
    <error descr="Type mismatch.Required: ()Found: List String → List String">appL map foo</error>
""")

    fun `test field access on rigid var`() = checkByText("""
main : a -> a
main a = <error descr="Type must be a record.Found: a">a</error>.foo
""")

    fun `test field accessor function on rigid var`() = checkByText("""
main : a -> a
main a = .foo <error descr="Type mismatch.Required: { a | foo : b }Found: a">a</error>
""")

    fun `test passing function type with vars to operator`() = checkByText("""
appL : (a -> b) -> a -> b
appL f x = f x
infix right 0 (<:) = appL

map : (c -> d) -> List c -> List d
map f xs = []

foo : String -> String
foo s = s

main : ()
main =
    <error descr="Type mismatch.Required: ()Found: List String → List String">map <: foo</error>
""")

    fun `test passing record constructor to operator`() = checkByText("""
appR : a -> (a -> b) -> b
appR x f = f x
infix left 0 (:>) = appR

map : (c -> d) -> List c -> List d
map f xs = []

type alias Rec = { field : () }

main : ()
main =
    <error descr="Type mismatch.Required: ()Found: List () → List Rec">Rec :> map</error>
""")

    fun `test non function to composition operator`() = checkByText("""
compo : (b -> c) -> (a -> b) -> (a -> c)
compo g f x = g (f x)
infix left  9 (<<<) = compo

foo : d -> d
foo a = a

main =
    foo <<< <error descr="Type mismatch.Required: a → dFound: String">""</error>
""")


    fun `test multiple empty lists`() = checkByText("""
foo : z -> z -> z -> z
foo a b c = a

main : ()
main =
    <error descr="Type mismatch.Required: ()Found: List String">foo [] [] [""]</error>
""")

    fun `test multiple mismatched lists`() = checkByText("""
foo : z -> z -> z -> z -> z
foo a b c d = a

main : ()
main =
    foo [] [] [""] <error descr="Type mismatch.Required: List StringFound: List ()">[()]</error>
""")

    fun `test multiple empty list functions ending with concrete type`() = checkByText("""
foo : z -> z -> z -> z
foo a b c = a

listA : List b -> List b
listA a = a

listStr : List String -> List String
listStr a = a

main : ()
main =
    <error descr="Type mismatch.Required: ()Found: List String → List String">foo listA listA listStr</error>
""")

    fun `test multiple empty list functions starting with concrete type`() = checkByText("""
foo : z -> z -> z -> z
foo a b c = a

listA : List b -> List b
listA a = a

listStr : List String -> List String
listStr a = a

main : ()
main =
    <error descr="Type mismatch.Required: ()Found: List String → List String">foo listStr listA listA</error>
""")

    fun `test function composition`() = checkByText("""
main : ()
main = <error descr="Type mismatch.Required: ()Found: String → Bool">String.isEmpty >> not</error>
""")

    fun `test constraint number int literals`() = checkByText("""
foo : number -> number -> number
foo a b = a

main : ()
main =
    <error descr="Type mismatch.Required: ()Found: number">foo 1 2</error>
""")

    fun `test constraint number float literals`() = checkByText("""
foo : number -> number -> number
foo a b = a

main : ()
main =
    <error descr="Type mismatch.Required: ()Found: Float">foo 1.1 2.2</error>
""")

    fun `test constraint number int and float literal`() = checkByText("""
foo : number -> number -> number
foo a b = a

main : ()
main =
    <error descr="Type mismatch.Required: ()Found: Float">foo 1 2.2</error>
""")

    fun `test constraint number number`() = checkByText("""
foo : number -> number -> number
foo a b = a

main : number -> number -> ()
main a b =
    <error descr="Type mismatch.Required: ()Found: number">foo a b</error>
""")

    fun `test constraint number mismatched`() = checkByText("""
foo : number -> number -> number
foo a b = a

main =
    foo 1 <error descr="Type mismatch.Required: numberFound: ()">()</error>
""")

    fun `test constraint number comparable`() = checkByText("""
foo : number -> number
foo a = a

bar : () -> ()
bar a = a

main a b =
    bar <error descr="Type mismatch.Required: ()Found: number">(foo (if a < b then a else b))</error>
""")

    fun `test constraint appendable string`() = checkByText("""
foo : appendable -> appendable -> appendable
foo a b = a

main : ()
main =
    <error descr="Type mismatch.Required: ()Found: String">foo "" ""</error>
""")

    fun `test constraint appendable list`() = checkByText("""
foo : appendable -> appendable -> appendable
foo a b = a

main : ()
main =
    <error descr="Type mismatch.Required: ()Found: List a">foo [] []</error>
""")

    fun `test constraint appendable appendable`() = checkByText("""
foo : appendable -> appendable -> appendable
foo a b = a

main : appendable -> appendable -> ()
main a b =
    <error descr="Type mismatch.Required: ()Found: appendable">foo a b</error>
""")

    fun `test constraint appendable comparable mismatch`() = checkByText("""
foo : appendable -> appendable
foo a = a

main : comparable -> ()
main a =
    foo <error descr="Type mismatch.Required: appendableFound: comparable">a</error>
""")

    fun `test constraint appendable string and list mismatch`() = checkByText("""
foo : appendable -> appendable -> appendable
foo a b = a

main =
    foo "" <error descr="Type mismatch.Required: StringFound: List a">[]</error>
""")

    fun `test constraint appendable list mismatch`() = checkByText("""
foo : appendable -> appendable -> appendable
foo a b = a

main =
    foo [""] <error descr="Type mismatch.Required: List StringFound: List ()">[()]</error>
""")

    fun `test constraint appendable number mismatch`() = checkByText("""
foo : appendable -> appendable
foo a = a

main =
    foo <error descr="Type mismatch.Required: appendableFound: number">1</error>
""")

    fun `test constraint comparable int`() = checkByText("""
foo : comparable -> comparable -> comparable
foo a b = a

main : Int -> ()
main a =
    <error descr="Type mismatch.Required: ()Found: Int">foo a a</error>
""")

    fun `test constraint comparable number`() = checkByText("""
foo : comparable -> comparable -> comparable
foo a b = a

main : number -> ()
main a =
    <error descr="Type mismatch.Required: ()Found: number">foo a a</error>
""")

    fun `test constraint comparable int literal`() = checkByText("""
foo : comparable -> comparable -> comparable
foo a b = a

main : ()
main =
    <error descr="Type mismatch.Required: ()Found: number">foo 1 2</error>
""")

    fun `test constraint comparable float`() = checkByText("""
foo : comparable -> comparable -> comparable
foo a b = a

main : ()
main =
    <error descr="Type mismatch.Required: ()Found: Float">foo 1.1 2.2</error>
""")

    fun `test constraint comparable float literal`() = checkByText("""
foo : comparable -> comparable -> comparable
foo a b = a

main : ()
main =
    <error descr="Type mismatch.Required: ()Found: Float">foo 1.1 2.2</error>
""")

    fun `test constraint comparable char`() = checkByText("""
foo : comparable -> comparable -> comparable
foo a b = a

main : ()
main =
    <error descr="Type mismatch.Required: ()Found: Char">foo 'a' 'b'</error>
""")

    fun `test constraint comparable string`() = checkByText("""
foo : comparable -> comparable -> comparable
foo a b = a

main : ()
main =
    <error descr="Type mismatch.Required: ()Found: String">foo "a" "b"</error>
""")

    fun `test constraint comparable list string`() = checkByText("""
foo : comparable -> comparable -> comparable
foo a b = a

main : ()
main =
    <error descr="Type mismatch.Required: ()Found: List String">foo ["a"] []</error>
""")

    fun `test constraint comparable list comparable`() = checkByText("""
foo : comparable -> comparable -> comparable
foo a b = a

main : List comparable -> List comparable -> ()
main a b =
    <error descr="Type mismatch.Required: ()Found: List comparable">foo a b</error>
""")

    fun `test constraint comparable list number`() = checkByText("""
foo : comparable -> comparable -> comparable
foo a b = a

main : List number -> List number -> ()
main a b =
    <error descr="Type mismatch.Required: ()Found: List number">foo a b</error>
""")

    fun `test constraint comparable tuple number`() = checkByText("""
foo : comparable -> comparable -> comparable
foo a b = a

main : (number, number) -> (number, number) -> ()
main a b =
    <error descr="Type mismatch.Required: ()Found: (number, number)">foo a b</error>
""")

    fun `test constraint comparable tuple float`() = checkByText("""
foo : comparable -> comparable -> comparable
foo a b = a

main : ()
main =
    <error descr="Type mismatch.Required: ()Found: (Float, Float)">foo (1.1, 2.2) (3.3, 4.4)</error>
""")

    fun `test constraint comparable appendable mismatch`() = checkByText("""
foo : comparable -> comparable
foo a = a

main : appendable -> ()
main a =
    foo <error descr="Type mismatch.Required: comparableFound: appendable">a</error>
""")

    fun `test constraint comparable tuple mismatch`() = checkByText("""
foo : comparable -> comparable -> comparable
foo a b = a

main : ()
main =
    foo ("", "") <error descr="Type mismatch.Required: (String, String)Found: (String, Float)">("", 1.1)</error>
""")

    fun `test constraint comparable comparable`() = checkByText("""
foo : comparable -> comparable -> comparable
foo a b = a

main : comparable -> comparable -> ()
main a b =
    <error descr="Type mismatch.Required: ()Found: comparable">foo a b</error>
""")

    fun `test constraint compappend string`() = checkByText("""
foo : compappend -> compappend -> compappend
foo a b = a

main : ()
main =
    <error descr="Type mismatch.Required: ()Found: String">foo "" ""</error>
""")

    fun `test constraint compappend list string`() = checkByText("""
foo : compappend -> compappend -> compappend
foo a b = a

main : ()
main =
    <error descr="Type mismatch.Required: ()Found: List String">foo [""] []</error>
""")

    fun `test constraint compappend list comparable`() = checkByText("""
foo : compappend -> compappend -> compappend
foo a b = a

main : List comparable -> List comparable -> ()
main a b =
    <error descr="Type mismatch.Required: ()Found: List comparable">foo a b</error>
""")

    fun `test constraint compappend list compappend`() = checkByText("""
foo : compappend -> compappend -> compappend
foo a b = a

main : List compappend -> List compappend -> ()
main a b =
    <error descr="Type mismatch.Required: ()Found: List compappend">foo a b</error>
""")

    fun `test constraint compappend compappend`() = checkByText("""
foo : compappend -> compappend -> compappend
foo a b = a

main : compappend -> compappend -> ()
main a b =
    <error descr="Type mismatch.Required: ()Found: compappend">foo a b</error>
""")

    fun `test numbered constraint appendable`() = checkByText("""
foo : appendable1 -> appendable2 -> appendable3 -> appendable2
foo a b c = b

main : ()
main =
    <error descr="Type mismatch.Required: ()Found: List String">foo "" [""] [()]</error>
""")

    fun `test named constraint appendable`() = checkByText("""
foo : appendableOne -> appendableTwo -> appendableThree -> appendableTwo
foo a b c = b

main : ()
main =
    <error descr="Type mismatch.Required: ()Found: List String">foo "" [""] [()]</error>
""")

    fun `test numbered constraint appendable mismatch`() = checkByText("""
foo : appendable1 -> appendable2 -> appendable1 -> appendable2
foo a b c = b

main : ()
main =
    foo "" [""] <error descr="Type mismatch.Required: StringFound: List String">[""]</error>
""")

    fun `test named constraint appendable mismatch`() = checkByText("""
foo : appendableOne -> appendableTwo -> appendableOne -> appendableTwo
foo a b c = b

main : ()
main =
    foo "" [""] <error descr="Type mismatch.Required: StringFound: List String">[""]</error>
""")

    fun `test numbered constraint appendable invalid value`() = checkByText("""
foo : appendable1 -> appendable1
foo a = a

main : ()
main =
    foo <error descr="Type mismatch.Required: appendable1Found: ()">()</error>
""")

    fun `test named constraint appendable invalid value`() = checkByText("""
foo : appendableOne -> appendableOne
foo a = a

main : ()
main =
    foo <error descr="Type mismatch.Required: appendableOneFound: ()">()</error>
""")

    fun `test numbered constraints in different functions`() = checkByText("""
foo : number1 -> appendable1 -> comparable1 -> compappend1 -> appendable1
foo a b c d = b

main : number2 -> appendable2 -> comparable2 -> compappend2 -> ()
main a b c d  =
    <error descr="Type mismatch.Required: ()Found: appendable2">foo a b c d</error>
""")

    fun `test named constraints in different functions`() = checkByText("""
foo : numberOne -> appendableOne -> comparableOne -> compappendOne -> appendableOne
foo a b c d = b

main : numberTwo -> appendableTwo -> comparableTwo -> compappendTwo -> ()
main a b c d  =
    <error descr="Type mismatch.Required: ()Found: appendableTwo">foo a b c d</error>
""")

    fun `test numbered constraint assigned to unnumbered constraint`() = checkByText("""
foo : number -> number
foo a = a

main : number1 -> ()
main a =
    <error descr="Type mismatch.Required: ()Found: number1">foo a</error>
""")

    fun `test named constraint assigned to unnumbered constraint`() = checkByText("""
foo : number -> number
foo a = a

main : numberOne -> ()
main a =
    <error descr="Type mismatch.Required: ()Found: numberOne">foo a</error>
""")

    fun `test calling function with its own return value`() = checkByText("""
type Foo a b = Foo
type Bar c = Bar

foo : Foo d (e -> f) -> Bar e -> Foo d f
foo a b = Foo

bar : Foo g g
bar = Foo

baz : Bar String
baz = Bar

qux : Bar Float
qux = Bar

main : ()
main =
    <error descr="Type mismatch.Required: ()Found: Foo (String → Float → f) f">(foo (foo bar baz) qux)</error>
""")

    // https://github.com/klazuka/intellij-elm/issues/296
    fun `test tuple with repeated unfixed vars`() = checkByText("""
type alias Example = ( Maybe String, Maybe Int )

foo : Example -> Example
foo model = model

main : Example
main = ( Nothing, Nothing ) |> foo
""")

    fun `test constraining param via function call`() = checkByText("""
foo : Int -> Int
foo a = a

bar a = foo a

main : Int
main = bar <error descr="Type mismatch.Required: IntFound: String">""</error>
""")

    fun `test using constrained var in tuple`() = checkByText("""
foo a = ((), "", a + 1)

main : ()
main =
    <error descr="Type mismatch.Required: ()Found: number → ((), String, number)">foo</error>
""")

    fun `test using constrained var in let`() = checkByText("""
bar : String -> String
bar a = a

main a =
    let
        x = a + 1
    in
        bar <error descr="Type mismatch.Required: StringFound: number">a</error>
""")

    fun `test using constrained var in lambda`() = checkByText("""
foo f = (\t -> f t)

main : ()
main =
    <error descr="Type mismatch.Required: ()Found: (a → b) → a → b">foo</error>
""")

    fun `test using constrained var in multiple lambdas`() = checkByText("""
foo f x = if x then (\t -> f t) else (\_ -> f x)

main : ()
main =
    <error descr="Type mismatch.Required: ()Found: (Bool → a) → Bool → Bool → a">foo</error>
""")

    fun `test using unconstrained var in record extension`() = checkByText("""
foo r = { r | field = () }

main : ()
main =
    <error descr="Type mismatch.Required: ()Found: { field : () }">foo { field = () }</error>
""")

    fun `test unconstrained case branch with unit pattern`() = checkByText("""
foo : String -> String
foo a = a
main a =
    let
        b = case a of
            () -> ()
    in
        foo <error descr="Type mismatch.Required: StringFound: ()">a</error>
""")

    fun `test unconstrained case branch with cons pattern head`() = checkByText("""
foo : () -> ()
foo a = a
main a =
    let
        b = case a of
            x :: xs -> x
            _ -> ""
    in
        ( foo <error descr="Type mismatch.Required: ()Found: List String">a</error>
        , foo <error descr="Type mismatch.Required: ()Found: String">b</error>
        )
""")

    fun `test unconstrained case branch with tuple pattern`() = checkByText("""
foo : () -> ()
foo a = a
main a =
    let
        b = case a of
            (x, y) -> x
            (x, ()) -> x
            _ -> ""
    in
        ( foo <error descr="Type mismatch.Required: ()Found: (String, ())">a</error>
        , foo <error descr="Type mismatch.Required: ()Found: String">b</error>
        )
""")

    fun `test unconstrained case branch with record pattern`() = checkByText("""
foo : () -> ()
foo a = a
main a =
    let
        b = case a of
            {x, y} -> x
            _ -> ""
    in
        ( foo <error descr="Type mismatch.Required: ()Found: { a | x : String, y : b }">a</error>
        , foo <error descr="Type mismatch.Required: ()Found: String">b</error>
        )
""")

    fun `test unconstrained case branch with union pattern`() = checkByText("""
type Foo a = Bar a
foo : String -> String
foo a = a
main a =
    let
        b = case a of
            Bar c -> c
            Bar () -> ()
    in
        ( foo <error descr="Type mismatch.Required: StringFound: Foo ()">a</error>
        , foo <error descr="Type mismatch.Required: StringFound: ()">b</error>
        )
""")

    fun `test field access on unconstrained var`() = checkByText("""
foo : () -> ()
foo a = a
main a =
    let
        b = a.x + 1
        c = a.y ++ ""
    in
        foo <error descr="Type mismatch.Required: ()Found: { a | x : number, y : String }">a</error>
""")

    fun `test field accessors on unconstrained var`() = checkByText("""
foo : () -> ()
foo a = a
main a =
    let
        b = (.x a) + 1
        c = (.y a) ++ ""
    in
        foo <error descr="Type mismatch.Required: ()Found: { a | x : number, y : String }">a</error>
""")

    fun `test updating record fields with own field on unconstrained var`() = checkByText("""
foo : () -> ()
foo a = a
main a =
    let
        b = { a | x = a.x + 1 }
        c = { a | y = a.y ++ "" }
    in
        foo <error descr="Type mismatch.Required: ()Found: { a | x : number, y : String }">a</error>
""")

    fun `test extension record param with unconstrained var`() = checkByText("""
foo : () -> ()
foo a = a
bar a b = { a | x = b }
baz a b = { a | y = b }

main a =
    let
        b = bar a 1
        c = baz a ""
    in
        foo <error descr="Type mismatch.Required: ()Found: { a | x : number, y : String }">a</error>
""")

    fun `test unconstrained record pattern used as extension base`() = checkByText("""
foo : () -> ()
foo a = a

main ({x} as a) ({y} as b) =
    let
        c = { a | z = { b | w = () } }
        d = x + 1
        e = y ++ ""
    in
        foo <error descr="Type mismatch.Required: ()Found: { a | x : number, z : { b | y : String, w : () } }">a</error>
""")

    fun `test passing rigid var to function expecting concrete type`() = checkByText("""
foo : () -> ()
foo a = a

main : a -> a
main a =
    foo <error descr="Type mismatch.Required: ()Found: a">a</error>
""")

    fun `test returning rigid var from function expecting concrete type`() = checkByText("""
main : a -> ()
main a =
    <error descr="Type mismatch.Required: ()Found: a">a</error>
""")

    fun `test returning rigid var from function expecting different rigid var`() = checkByText("""
main : a -> b
main a =
    <error descr="Type mismatch.Required: bFound: a">a</error>
""")

    fun `test rigid vars within tuple`() = checkByText("""
foo : number -> number
foo a = a
main : (a, a) -> a
main (x, y) =
  foo <error descr="Type mismatch.Required: numberFound: a">x</error>
""")

    fun `test rigid number assigned to rigid comparable`() = checkByText("""
main : number -> (comparable -> ()) -> ()
main a f =
    f <error descr="Type mismatch.Required: comparableFound: number">a</error>
""")

    fun `test rigid comparable assigned to rigid number`() = checkByText("""
main : comparable -> (number -> ()) -> ()
main a f =
    f <error descr="Type mismatch.Required: numberFound: comparable">a</error>
""")

    fun `test passing function with vars in annotation in let to flex vars`() = checkByText("""
foo : (a -> b) -> a -> b
foo f a = f a

main : ()
main =
    let
        b : a -> a
        b a = a
    in
    <error descr="Type mismatch.Required: ()Found: String">foo b ""</error>
""")

    fun `test passing function with mixed-rigidity vars in annotation`() = checkByText("""
main : a -> ()
main a =
    let
        foo : a -> b -> a
        foo aa bb = aa
    in
    <error descr="Type mismatch.Required: ()Found: a">foo a ""</error>
""")

    fun `test calling rigid var in parent scope`() = checkByText("""
main : (a -> a) -> ()
main f =
    let
        b : a -> a
        b a = f a
    in
    b <error descr="Type mismatch.Required: aFound: String">""</error>
""")

    fun `test passing rigid var through unannotated function stored as value`() = checkByText("""
main : (() -> a -> a) -> a -> ()
main f a =
    let
        g = f ()
        h = g a
    in
    <error descr="Type mismatch.Required: ()Found: a">h</error>
""")

    fun `test assigning flex typeclass to rigid typeclass`() = checkByText("""
main : (number -> number) -> ()
main f =
    <error descr="Type mismatch.Required: ()Found: number">f 1</error>
""")

    fun `test assigning rigid typeclass to flex var`() = checkByText("""
foo : a -> b -> c -> d -> b
foo a b c d = b

main : number -> appendable -> comparable -> compappend -> ()
main a b c d =
    <error descr="Type mismatch.Required: ()Found: appendable">foo a b c d</error>
""")

    fun `test assigning flex var to rigid typeclass`() = checkByText("""
main : (number -> appendable -> comparable -> compappend -> appendable) -> ()
main f =
    let
        g a b c d = f a b c d
    in
    <error descr="Type mismatch.Required: ()Found: number → appendable → comparable → compappend → appendable">g</error>
""")

    fun `test assigning flex var to flex typeclass`() = checkByText("""
foo : number -> appendable -> comparable -> compappend -> appendable
foo a b c d = b
bar : () -> ()
bar a = a
main a b c d =
    bar <error descr="Type mismatch.Required: ()Found: appendable">(foo a b c d)</error>
""")

    fun `test assigning flex typeclass to rigid var`() = checkByText("""
a : number
a = Debug.todo ""
b : appendable
b = Debug.todo ""
c : comparable
c = Debug.todo ""
d : compappend
d = Debug.todo ""

main : (a -> b -> c -> d -> b) -> b
main foo =
    foo
        <error descr="Type mismatch.Required: aFound: number">a</error>
        <error descr="Type mismatch.Required: bFound: appendable">b</error>
        <error descr="Type mismatch.Required: cFound: comparable">c</error>
        <error descr="Type mismatch.Required: dFound: compappend">d</error>
""")

    fun `test infinite type in record base`() = checkByText("""
main x =
    { <error descr="Infinite self-referential type">x</error> | ff = x.ff "" }
""")

    fun `test flex arg to rigid param 1`() = checkByText("""
type Foo a = Foo
type Bar b = Bar { f2 : Foo ( (), b ) }
type Baz c d = Baz { f4 : Foo d -> Foo d }

main : Baz (e -> ()) e -> Bar e
main (Baz baz) =
    Bar <error descr="Type mismatch.Required: { f2 : Foo ((), b) }Found: { f2 : Foo e }Mismatched fields:   Field f2:    Required: Foo ((), b)    Found: Foo e">{ f2 = baz.f4 Foo }</error>
""")

    fun `test flex arg to rigid param 2`() = checkByText("""
type Foo a = Foo
type Bar b = Bar { f1 : b -> () , f2 : Foo ( (), b ) }
type Baz c d = Baz { f3 : c , f4 : Foo d -> Foo d }

main : Baz (e -> ()) e -> Bar e
main (Baz baz) =
    Bar
        <error descr="Type mismatch.Required: { f1 : e → (), f2 : Foo ((), e) }Found: { f1 : e → (), f2 : Foo e }Mismatched fields:   Field f2:    Required: Foo ((), e)    Found: Foo e">{ f1 = baz.f3
        , f2 = baz.f4 Foo
        }</error>
""")
}
