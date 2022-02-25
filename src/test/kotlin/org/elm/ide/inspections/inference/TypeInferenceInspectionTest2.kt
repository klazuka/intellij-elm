package org.elm.ide.inspections.inference

import org.elm.ide.inspections.ElmInspectionsTestBase
import org.elm.ide.inspections.ElmTypeInferenceInspection

class TypeInferenceInspectionTest2 : ElmInspectionsTestBase(ElmTypeInferenceInspection()) {
    override fun getProjectDescriptor() = ElmWithStdlibDescriptor

    fun `test mismatched record subset pattern in parameter`() = checkByText("""
type alias Foo = { foo : (), bar : Int }
main : Foo -> ()
main {bar} = <error descr="Type mismatch.Required: ()Found: Int">bar</error>
""")

    // https://github.com/klazuka/intellij-elm/issues/122
    fun `test matched record pattern from extension alias`() = checkByText("""
type alias Foo a = { a | foo : ()}
type alias Bar = { bar : () }

main : Foo Bar -> ()
main {bar} = bar
""")

    fun `test mismatched record pattern from extension alias`() = checkByText("""
type alias Foo a = { a | foo : ()}
type alias Bar = { bar : () }

main : Foo Bar -> Int
main {bar} = <error descr="Type mismatch.Required: IntFound: ()">bar</error>
""")

    fun `test mismatched record pattern from extension alias redefining a field`() = checkByText("""
type alias Foo a = { a | foo : ()}
type alias Bar = Foo { foo : Int }

main : Bar -> Int
main {foo} = <error descr="Type mismatch.Required: IntFound: ()">foo</error>
""")

    fun `test extension record parameter`() = checkByText("""
type alias Extension base = { base | field : () }

foo : Extension base -> ()
foo e = ()

main = foo { field = (), field2 = () }
""")

    fun `test record pattern parameter for record type literal`() = checkByText("""
main : { field : () } -> Int
main { field } = 
  <error descr="Type mismatch.Required: IntFound: ()">field</error>
""")

    fun `test let-in with mismatched type in annotated inner func`() = checkByText("""
main : ()
main =
    let
        foo : ()
        foo = <error descr="Type mismatch.Required: ()Found: String">""</error>
    in
        foo
""")

    // https://github.com/klazuka/intellij-elm/issues/153
    fun `test let-in with tuple with too small arity`() = checkByText("""
main : ()
main =
    let
        <error descr="Invalid pattern.Required type: ( (), () )Pattern type: ( a, b, c )">(x, y, z)</error> = ((), ())
    in
        y
""")

    fun `test let-in with tuple with too large arity`() = checkByText("""
main : ()
main =
    let
        <error descr="Invalid pattern.Required type: ( (), (), () )Pattern type: ( a, b )">(x, y)</error> = ((), (), ())
    in
        y
""")


    fun `test let-in with mismatched type from annotated inner func`() = checkByText("""
type Foo = Bar
main : Foo
main =
    let
        foo : ()
        foo = ()
    in
        <error descr="Type mismatch.Required: FooFound: ()">foo</error>
""")

    fun `test let-in function without annotation and one parameter`() = checkByText("""
main : ()
main =
    let
        foo a = ()
    in
        foo 1
""")

    fun `test let-in function without annotation and two parameters`() = checkByText("""
main : ()
main =
    let
        foo a b = ()
    in
        foo 1 2
""")

    fun `test mismatched return value from let-in record binding`() = checkByText("""
main : ()
main =
    let
        {x, y} = {x = 1, y = ""}
    in
        <error descr="Type mismatch.Required: ()Found: String">y</error>
""")

    fun `test mismatched return value from let-in tuple binding`() = checkByText("""
main : ()
main =
    let
        (x, y) = (1, "")
    in
        <error descr="Type mismatch.Required: ()Found: String">y</error>
""")

    fun `test matched record argument to let-in function`() = checkByText("""
main : ()
main =
    let
        foo {x, y} = x
    in
        foo {x=(), y=()}
""")

    fun `test cyclic definition in let-in record binding`() = checkByText("""
main : ()
main =
    let
        {x, y} = {x = 1, y = <error descr="Value cannot be defined in terms of itself">y</error>}
    in
        y
""")

    fun `test invalid let-in record binding`() = checkByText("""
main : ()
main =
    let
        <error descr="Invalid pattern.Required type: ()Pattern type: { x : a, y : b }">{x, y}</error> = ()
    in
        y
""")

    fun `test invalid let-in tuple binding`() = checkByText("""
main : ()
main =
    let
        <error descr="Invalid pattern.Required type: ()Pattern type: ( a, b )">(x, y)</error> = ()
    in
        y
""")

    fun `test mismatch in chained let-in tuple binding`() = checkByText("""
main : ()
main =
    let
        (x, y) = ((), "")
        (z, w) = (x, y)
    in
        <error descr="Type mismatch.Required: ()Found: String">w</error>
""")


    fun `test returning function`() = checkByText("""
main : () -> ()
main =
    let
        foo a = a
    in
        foo
""")

    fun `test returning partially applied function`() = checkByText("""
main : () -> ()
main =
    let
        foo a b = b
    in
        foo ()
""")

    fun `test matched function type alias in annotation`() = checkByText("""
type Foo = Bar
type alias F = Foo -> ()

foo : F -> F
foo a b = a b
""")

    fun `test mismatched function type alias in annotation`() = checkByText("""
type Foo = Bar
type alias F = Foo -> ()

foo : F -> F
foo a b = <error descr="Type mismatch.Required: ()Found: Foo">b</error>
""")

    fun `test partial pattern in function parameter from cons`() = checkByText("""
main (<error descr="Pattern does not cover all possibilities">x :: []</error>) = ()
""")

    fun `test partial pattern in function parameter from list`() = checkByText("""
main (<error descr="Pattern does not cover all possibilities">[x]</error>) = ()
""")

    fun `test partial pattern in function parameter from constant`() = checkByText("""
main (<error descr="Pattern does not cover all possibilities">""</error>) = ()
""")

    fun `test partial pattern in lambda parameter from constant`() = checkByText("""
main = (\<error descr="Pattern does not cover all possibilities">""</error> -> "")
""")

    fun `test bad self-recursion in annotated value`() = checkByText("""
main : ()
<error descr="Infinite recursion">main = main</error>
""")

    fun `test bad self-recursion in unannotated value`() = checkByText("""
<error descr="Infinite recursion">main = main</error>
""")

    // #https://github.com/klazuka/intellij-elm/issues/142
    // this tests for infinite recursion; the diagnostic is tested in TypeDeclarationInspectionTest
    fun `test bad self-recursion in type alias`() = checkByText("""
type alias A = A
foo : A
foo = ()
""")

    fun `test allowed self-recursion in annotated function`() = checkByText("""
main : () -> ()
main a = main a -- This is a runtime error, not compile time
""")

    fun `test allowed self-recursion in unannotated function`() = checkByText("""
main a = main a -- This is a runtime error, not compile time
""")

    fun `test allowed self-recursion in lambda`() = checkByText("""
main : ()
main = (\_ -> main) 1
""")

    fun `test allowed self-recursion in lambda in unannotated function`() = checkByText("""
main = (\_ -> main) 1
""")

    fun `test bad mutual recursion`() = checkByText("""
<error descr="Infinite recursion">foo = bar</error>
bar = foo
""")

    fun `test bad mutual recursion in let`() = checkByText("""
main =
    let
        <error descr="Infinite recursion">foo = bar</error>
        bar = foo
    in
    ()
""")

    fun `test mapping over recursive container in annotated function`() = checkByText("""
type Box a = Box a
type Tree a = Node (List (Tree a))

main : Tree (Box a) -> Box (Tree a)
main (Node trees) =
    <error descr="Type mismatch.Required: Box (Tree a)Found: List (Box (Tree a))">List.map main trees</error>
""")

    fun `test uncurrying function passed as argument`() = checkByText("""
foo : a -> a
foo a = a

main : ()
main =
    <error descr="Type mismatch.Required: ()Found: String">foo foo foo ""</error>

""")

    fun `test uncurrying return value from unannotated function`() = checkByText("""
lazy3 : (a -> b -> c -> ()) -> a -> b -> c -> ()
lazy3 a = a

apply1 fn a = embed (fn a)

embed : () -> () -> ()
embed a b = a

main : (a -> ()) -> a -> a -> ()
main fn a = <error descr="Type mismatch.Required: a → ()Found: () → ()">lazy3 apply1 fn a</error>
""")

    fun `test function argument mismatch in case expression`() = checkByText("""
foo : () -> ()
foo a = a

main =
    case foo <error descr="Type mismatch.Required: ()Found: String">""</error> of
        _ -> ()
""")

    fun `test function argument mismatch in case branch`() = checkByText("""
foo : () -> ()
foo a = a

main =
    case () of
        _ -> foo <error descr="Type mismatch.Required: ()Found: String">""</error>
""")

    fun `test value mismatch from case`() = checkByText("""
main : ()
main =
    case () of
        _ -> <error descr="Type mismatch.Required: ()Found: String">""</error>
""")

    fun `test case branches with mismatched types`() = checkByText("""
main : ()
main =
    case () of
        "" -> ()
        "x" -> <error descr="Type mismatch.Required: ()Found: String">""</error>
        _ -> ()
""")

    fun `test case branches with multiple mismatched types`() = checkByText("""
main : ()
main =
    case () of
        "" -> <error descr="Type mismatch.Required: ()Found: String">""</error>
        "x" -> <error descr="Type mismatch.Required: ()Found: String">""</error>
        _ -> ()
""")

    fun `test case branches with mismatched types from pattern`() = checkByText("""
main =
    case Just 42 of
        Nothing -> ""
        Just x -> <error descr="Type mismatch.Required: StringFound: number">x</error>
""")

    fun `test case branches with mismatched types in lambda`() = checkByText("""
main =
    \_ ->
        case "" of
            "" ->
                ()

            _ ->
                <error descr="Type mismatch.Required: ()Found: String">""</error>
""")

    fun `test case branches with mismatched types in let`() = checkByText("""
main =
    let
        f =
            ()
    in
    case "" of
        "" ->
            ()

        _ ->
            <error descr="Type mismatch.Required: ()Found: String">""</error>
""")

    // https://github.com/klazuka/intellij-elm/issues/113
    fun `test case branches with union value call`() = checkByText("""
foo : Maybe (List a)
foo = Nothing

main =
    case foo of
       Just [] -> ()
       _ -> ()
""")

    // https://github.com/klazuka/intellij-elm/issues/113
    fun `test field access on field subset`() = checkByText("""
type alias Subset a =
    { a | extra : () }


type alias Foo =
    { bar : () }

main : Subset Foo -> ()
main a =
    a.bar
""")


    fun `test case branches with mismatched tuple type`() = checkByText("""
type Foo = Bar
type Baz = Qux(Foo, Foo)

main : Baz -> ()
main x =
    case x of
        Qux (y, z) -> <error descr="Type mismatch.Required: ()Found: Foo">z</error>
""")

    fun `test case branches with mismatched union pattern`() = checkByText("""
type Foo = Bar
type Baz = Qux

main : Foo -> ()
main arg =
    case arg of
         <error descr="Type mismatch.Required: BazFound: Foo">Qux</error> -> ()
""")

    fun `test case branches with mismatched record pattern`() = checkByText("""
type alias Foo = { a : (), b : ()}

main : Foo -> ()
main arg =
    case arg of
         <error descr="Invalid pattern.Required type: FooPattern type: { b : a, c : b }Extra fields: { c : b }">{ b, c }</error> -> ()
""")

    fun `test case branches using union patterns with constructor argument`() = checkByText("""
type Foo
    = Bar ()
    | Baz ()
    | Qux (Maybe ()) ()

main : Foo -> ()
main arg =
    case arg of
        Bar x -> ()
        Baz x -> x
        Qux Nothing x -> x
""")

    fun `test case branches using union patterns with tuple destructuring of var`() = checkByText("""
type Foo
    = Bar (Maybe ((), ()))
    | Baz ()

main : Foo -> ()
main arg =
    case arg of
        Bar (Just (x, y)) -> y
        Baz x -> x
        _ -> ()
""")

    fun `test case branches using union patterns with record destructuring of var`() = checkByText("""
type Foo
    = Bar (Maybe {x: ()})
    | Baz ()

main : Foo -> ()
main arg =
    case arg of
        Bar (Just {x}) -> x
        Baz x -> x
        _ -> ()
""")

    fun `test case branches using union patterns to unresolved type`() = checkByText("""
-- This will lead to unresolved reference errors, but we need to test that we're still
-- binding the parameters so that we can infer the branch expressions.
main arg =
    case arg of
        Bar (Just {x}) -> x
        Baz x -> x
        _ -> ()
""")

    fun `test case branches using union patterns to mismatched type`() = checkByText("""
type Foo a = Bar (Maybe a) | Baz a
type Qux = Qux

main : Qux -> ()
main arg =
    case arg of
        <error descr="Type mismatch.Required: Foo aFound: Qux">Bar (Just {x})</error> -> x
        <error descr="Type mismatch.Required: Foo aFound: Qux">Baz x</error> -> x
        Qux -> ()
        _ -> ()
""")

    fun `test case branches with union pattern referencing invalid arg`() = checkByText("""
type Foo = Bar

main : Foo -> ()
main arg =
    case arg of
         <error descr="The type expects 0 arguments, but it got 1 instead.">Bar foo</error> -> foo
""")

    fun `test mismatched case branch union pattern`() = checkByText("""
main =
    case Just 42 of
        Just x ->
            "" ++ <error descr="Type mismatch.Required: StringFound: number">x</error>
        Nothing -> ""
""")

    fun `test case branch with cons pattern head`() = checkByText("""
main =
    case [()] of
        x :: xs -> x
        _ -> <error descr="Type mismatch.Required: ()Found: String">""</error>
""")

    fun `test case branch with cons pattern tail`() = checkByText("""
main : List ()
main =
    case [""] of
        x :: xs -> <error descr="Type mismatch.Required: List ()Found: List String">xs</error>
        _ -> []
""")

    fun `test case branch with list pattern`() = checkByText("""
main =
    case [()] of
        [x, y] -> y
        _ -> <error descr="Type mismatch.Required: ()Found: String">""</error>
""")

    fun `test case branch with cons and list pattern`() = checkByText("""
main =
    case [()] of
        z :: [x, y] -> y
        _ -> <error descr="Type mismatch.Required: ()Found: String">""</error>
""")

    fun `test case branches binding the same names`() = checkByText("""
type Foo a = Foo () | Bar String | Baz ()  | Qux () | Quz ()

bar : () -> ()
bar a = a

foo : Foo a -> ()
foo f =
    case f of
       Foo x -> bar x
       Bar x -> bar <error descr="Type mismatch.Required: ()Found: String">x</error>
       Baz x -> bar x
       Qux x -> bar x
       Quz x -> bar x
""")

    fun `test case branches with record pattern on different field than previously accessed through pipeline`() = checkByText("""
foo : (a -> ()) -> a -> a
foo _ a = a

main : String
main =
    case { f1 = (), f2 = () } |> foo .f2 of
        { f1 }  ->
            <error descr="Type mismatch.Required: StringFound: ()">f1</error>
""")

    fun `test case branch with record pattern from previous mutable record`() = checkByText("""
foo r =
    let
        f = r.f1
    in
    case r of
        { f2 } -> r

main : ()
main = 
  <error descr="Type mismatch.Required: ()Found: { f1 : (), f2 : String }">foo { f1 = (), f2 = "" }</error>
""")

    fun `test invalid return value from cons pattern`() = checkByText("""
main : ()
main =
    case [""] of
        x :: xs -> <error descr="Type mismatch.Required: ()Found: String">x</error>
""")

    // https://github.com/klazuka/intellij-elm/issues/247
    fun `test nested destructuring`() = checkByText("""
main : ()
main =
    let
        (a, b) =
            let
                (c, d) = ("", "")
            in
                (c, d)
    in
        <error descr="Type mismatch.Required: ()Found: String">a</error>
""")

    fun `test forward reference to destructured pattern`() = checkByText("""
main : ()
main =
    let
        a = b
        (b, c) = ("", "")
    in
       <error descr="Type mismatch.Required: ()Found: String">a</error>
""")

    fun `test nested forward references`() = checkByText("""
main : () -> ()
main m =
  let
    x a = y a
    y a = z r m
    z a b = a
    (q, r) = (m, ())
  in
  x ()
""")

    fun `test referenced pattern as`() = checkByText("""
main : { field : String } -> ()
main r =
    let
        ({field} as record) = r
    in
    <error descr="Type mismatch.Required: ()Found: String">record.field</error>
""")

    fun `test nested param as`() = checkByText("""
main : (String, String) -> ()
main ( (x) as foo, _ ) =
     <error descr="Type mismatch.Required: ()Found: String">foo</error>
""")

    fun `test mismatched left operand to non-associative operator`() = checkByText("""
foo : () -> () -> ()
foo a b = a
infix non 4 (~~) = foo

main a = <error descr="Type mismatch.Required: ()Found: String">""</error> ~~ ()
""")

    fun `test mismatched right operand to non-associative operator`() = checkByText("""
foo : () -> () -> ()
foo a b = a
infix non 4 (~~) = foo

main a = () ~~ <error descr="Type mismatch.Required: ()Found: String">""</error>
""")

    fun `test chained non-associative operator`() = checkByText("""
foo : () -> () -> ()
foo a b = a
infix non 4 (~~) = foo

main a = <error descr="Operator (~~) is not associative, and so cannot be chained">() ~~ () ~~ ()</error>
""")

    fun `test matched left associative chain`() = checkByText("""
type Foo = Bar
foo : Foo -> () -> Foo
foo a b = a
infix left 4 (~~) = foo

main : Foo
main = Bar ~~ () ~~ ()
""")

    fun `test matched right associative chain`() = checkByText("""
type Foo = Bar
foo : () -> Foo -> Foo
foo a b = b
infix right 4 (~~) = foo

main : Foo
main = () ~~ () ~~ Bar
""")

    fun `test mismatched left associative chain`() = checkByText("""
type Foo = Bar
foo : () -> () -> Foo
foo a b = Bar
infix left 4 (~~) = foo

main a = <error descr="Type mismatch.Required: ()Found: Foo">() ~~ ()</error> ~~ ()
""")

    fun `test mismatched right associative chain`() = checkByText("""
type Foo = Bar
foo : () -> () -> Foo
foo a b = Bar
infix right 4 (~~) = foo

main a = () ~~ <error descr="Type mismatch.Required: ()Found: Foo">() ~~ ()</error>
""")

    fun `test apply-right into Maybe`() = checkByText("""
apR : a -> (a -> b) -> b
apR x f = f x
infix left  0 (|>) = apR

main : Maybe ()
main = () |> Just
""")

    fun `test multiple non-associative operators`() = checkByText("""
lt : a -> a -> Bool
lt a b = True
and : Bool -> Bool -> Bool
and a b = False

infix right 3 (&&) = and
infix non   4 (<)  = lt

main : Bool
main = 1 < 2 && 3 < 4
""")

    fun `test operator mixed with function call`() = checkByText("""
type Foo = Bar
foo : Foo -> () -> Foo
foo a b = a
infix left 4 (~~) = foo

main = foo Bar () ~~ ()
""")

    fun `test self reference in union variant`() = checkByText("""
type Foo a = FooVariant Foo a
type Bar = BarVariant Bar (Foo Bar)

main : Foo ()
main = <error descr="Type mismatch.Required: Foo ()Found: Bar → Foo Bar → Bar">BarVariant</error>
""")

    // https://github.com/klazuka/intellij-elm/issues/201
    fun `test returning bound recursive union variant`() = checkByText("""
type Tree a = Tree a (List (Tree a))

directChildren : Tree a -> ()
directChildren tree =
    case tree of
        Tree _ children ->
            <error descr="Type mismatch.Required: ()Found: List (Tree a)">children</error>
""")
}
