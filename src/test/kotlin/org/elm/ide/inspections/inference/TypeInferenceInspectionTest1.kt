package org.elm.ide.inspections.inference

import org.elm.ide.inspections.ElmInspectionsTestBase
import org.elm.ide.inspections.ElmTypeInferenceInspection

// The type inference tests are broken up arbitrarily into several files, since IntelliJ gets bogged
// down when they're all combined.
class TypeInferenceInspectionTest1 : ElmInspectionsTestBase(ElmTypeInferenceInspection()) {
    override fun getProjectDescriptor() = ElmWithStdlibDescriptor

    fun `test too many arguments to value`() = checkByText("""
foo : ()
foo = ()

main = <error descr="This value is not a function, but it was given 1 argument.">foo 1</error>
""")

    fun `test too many arguments to function`() = checkByText("""
foo : () -> () -> ()
foo a b = a

main = <error descr="The function expects 2 arguments, but it got 3 instead.">foo () () ()</error>
""")

    fun `test too many arguments to operator`() = checkByText("""
add : () -> () -> ()
add a b = a
infix left 6 (+) = add
main = <error descr="The function expects 2 arguments, but it got 3 instead.">(+) () () ()</error>
""")

    fun `test too many parameters in value`() = checkByText("""
main: ()
main <error descr="The function expects 0 parameters, but it got 1 instead.">foo</error> = foo
""")

    fun `test too many parameters in function`() = checkByText("""
main: () -> ()
main <error descr="The function expects 1 parameter, but it got 2 instead.">foo bar</error> = (foo, bar)
""")

    fun `test calling non-function with errors in argument`() = checkByText("""
foo : () -> ()
foo i = i
main = 
  <error descr="This value is not a function, but it was given 1 argument.">()</error> 
  (foo <error descr="Type mismatch.Required: ()Found: String">""</error>)
""")

    fun `test mismatched int value type`() = checkByText("""
main : ()
main = <error descr="Type mismatch.Required: ()Found: Float">1.0</error>
""")

    fun `test matched int negation`() = checkByText("""
main : Int
main = -1
""")

    fun `test matched float negation`() = checkByText("""
main : Float
main = -1.0
""")

    fun `test mismatched negation`() = checkByText("""
main = -<error descr="Type mismatch.Required: numberFound: String">""</error>
""")

    fun `test negate from pattern match`() = checkByText("""
type alias N = Float
type Foo = Bar N

main : Foo -> ()
main foo =
    case foo of
        Bar n -> <error descr="Type mismatch.Required: ()Found: N">-n</error>
""")

    fun `test mismatched tuple value type from missing field`() = checkByText("""
main : ((), (), ())
main = <error descr="Type mismatch.Required: ( (), (), () )Found: ( (), () )">((), ())</error>
""")

    fun `test mismatched tuple value type from wrong field type`() = checkByText("""
main : ((), ())
main = (<error descr="Type mismatch.Required: ()Found: Float">1.0</error>, ())
""")

    fun `test mismatched tuple value type from extra field`() = checkByText("""
main : ((), ())
main = <error descr="Type mismatch.Required: ( (), () )Found: ( (), (), () )">((), (), ())</error>
""")

    fun `test matched tuple value type`() = checkByText("""
main : ((), ())
main = ((), ())
""")

    fun `test mismatched return type from argument`() = checkByText("""
type Foo = Bar
main : Foo -> ()
main a = <error descr="Type mismatch.Required: ()Found: Foo">a</error>
""")

    fun `test mismatched return type from List argument`() = checkByText("""
main : List Int -> List ()
main a = <error descr="Type mismatch.Required: List ()Found: List Int">a</error>
""")

    fun `test mismatched return type from shadowed List`() = checkByText("""
type List a = List a
main : List ()
main = <error descr="Type mismatch.Required: List ()Found: List.List a">[]</error>
""")

    fun `test mismatched return type from float literal`() = checkByText("""
type Foo = Bar
main : () -> Foo
main a = <error descr="Type mismatch.Required: FooFound: Float">1.0</error>
""")

    fun `test mismatched value type from function without annotation`() = checkByText("""
foo = ()
main : String
main = <error descr="Type mismatch.Required: StringFound: ()">foo</error>
""")

    fun `test calling function without annotation with unconstrained parameters`() = checkByText("""
foo a = a
main : ()
main = <error descr="Type mismatch.Required: ()Found: String">foo ""</error>
""")

    fun `test parameterized function type`() = checkByText("""
type Foo a = Bar
main : (Foo ty -> Foo ty) -> ()
main a =
    <error descr="Type mismatch.Required: ()Found: Foo ty → Foo ty">a</error>
""")

    fun `test correct value type from empty record`() = checkByText("""
main : {}
main = {}
""")

    fun `test correct value type from record`() = checkByText("""
main : {x: (), y: ()}
main = {x = (), y = ()}
""")

    fun `test correct value type from record alias`() = checkByText("""
type alias A = {x: (), y: ()}
main : A
main = {x = (), y = ()}
""")

    fun `test correct value type from called record constructor`() = checkByText("""
type alias A = {x: (), y: ()}
main : A
main = A () ()
""")

    fun `test calling record without constructor`() = checkByText("""
main : {x: (), y: ()} -> {x: (), y: ()}
main a = <error descr="This value is not a function, but it was given 2 arguments.">a () ()</error>
""")

    fun `test correct value type from record constructor as function`() = checkByText("""
type alias A = {x: (), y: ()}
main : () -> (() -> () -> A)
main _ = A
""")

    fun `test record constructor with no fields`() = checkByText("""
type alias R = {}
foo : ()
foo = <error descr="Type mismatch.Required: ()Found: R">R</error>
""")

    fun `test correct value from field accessor`() = checkByText("""
main : ()
main = .x {x=()}
""")

    fun `test value from parameter with base record identifier`() = checkByText("""
type alias R = {x: (), y: ()}
main : R -> R
main r = { r | x = () }
""")

    fun `test mismatched value from parameter with base record identifier`() = checkByText("""
type alias R = {x: (), y: ()}
main : R -> ()
main r = <error descr="Type mismatch.Required: ()Found: R">{ r | x = () }</error>
""")

    fun `test mismatched base record identifier`() = checkByText("""
main : () -> ()
main r = { <error descr="Type must be a record.Found: ()">r</error> | x = () }
""")

    fun `test unknown field from parameter with base record identifier`() = checkByText("""
type alias R = {x: (), y: ()}
main : R -> R
main r = { r | x = (), <error descr="Record does not have field 'z'">z</error> = () }
""")

    fun `test mismatched field from parameter with base record identifier`() = checkByText("""
type alias R = {x: (), y: ()}
main : R -> R
main r = { r | <error descr="Type mismatch.Required: ()Found: String">x</error> = "" }
""")

    fun `test matched parameter with base record identifier`() = checkByText("""
type alias R = {x: (), y: ()}
foo : { r | x : () } -> ()
foo r = r.x

main : R -> ()
main r = foo r
""")

    fun `test mismatched value from argument with base record identifier`() = checkByText("""
type Foo = Bar
type alias R = {x: (), y: ()}
foo : { r | x : ()} -> ()
foo r = r.x

main : R -> Foo
main r = <error descr="Type mismatch.Required: FooFound: ()">foo r</error>
""")

    fun `test mismatched parameter with base record identifier`() = checkByText("""
type alias R = {x: (), y: ()}
foo : { r | x : ()} -> { r | x : ()}
foo r = r

main : R
main = foo <error descr="Type mismatch.Required: { r | x : () }Found: { y : () }Missing fields: { x : () }">{ y = () }</error>
""")


    fun `test extension record argument with more fields than extension parameter`() = checkByText("""
type alias Large a = { a | field1 : String, field2 : String }
type alias Small a = { a | field1 : String }

foo : Small a -> String
foo _ = ""

main : Large a -> String
main arg = foo arg
""")

    fun `test extension record argument in function type with more fields than extension parameter`() = checkByText("""
type alias Large a = { a | field1 : String, field2 : String }
type alias Small a = { a | field1 : String }

foo : Small a -> String
foo _ = ""

main : Large a -> String
main = foo
""")

    fun `test modifying extended record through extension alias`() = checkByText("""
type alias Small a = { a | field1 : String }
type alias Large = Small { field2 : String }

foo : Small a -> Small a
foo a = a

main : Large -> ()
main r =
    let
        rr = foo r
    in
    <error descr="Type mismatch.Required: ()Found: Small Large">{ rr | field2 = "" }</error>
""")

    fun `test alias to extension record`() = checkByText("""
type alias Record a = { a | field : () }
type alias Alias a = Record a

foo : Alias a  -> Alias a
foo it = it

main : Alias a  -> ()
main model =
    <error descr="Type mismatch.Required: ()Found: Alias a">foo model</error>
""")

    fun `test field accessor as argument`() = checkByText("""
type alias R = {x: (), y: ()}
foo : (R -> ()) -> ()
foo _ = ()

main : ()
main = foo .x
""")

    fun `test correct value type from parametric record alias`() = checkByText("""
type alias A a = {x: a, y: ()}
main : A Float
main = {x = 1.0, y = ()}
""")

    fun `test mismatched value from chained alias`() = checkByText("""
type Foo a = Foo
type alias Bar = Foo ()

main : Bar -> Foo Int
main a = <error descr="Type mismatch.Required: Foo IntFound: Bar">a</error>
""")

    fun `test mismatched value type from parametric record alias`() = checkByText("""
type alias A a = {x: a, y: ()}
main : A ()
main = <error descr="Type mismatch.Required: A ()Found: { x : Float, y : () }Mismatched fields: &nbsp;&nbsp;Field x:&nbsp;&nbsp;&nbsp;&nbsp;Required: ()&nbsp;&nbsp;&nbsp;&nbsp;Found: Float">{x = 1.0, y = ()}</error>
""")

    fun `test mismatched value type from record subset`() = checkByText("""
type alias R = {x: (), y: ()}
main : R
main = <error descr="Type mismatch.Required: RFound: { x : () }Missing fields: { y : () }">{x = ()}</error>
""")

    fun `test mismatched value type from record superset`() = checkByText("""
type alias R = {x: (), y: ()}
main : R
main = <error descr="Type mismatch.Required: RFound: { x : (), y : (), z : () }Extra fields: { z : () }">{x = (), y=(), z=()}</error>
""")

    fun `test mismatched return type from propagated type vars`() = checkByText("""
type alias A a = {x: Maybe a}
type alias B a = A a
main : B () -> Maybe Int
main b = <error descr="Type mismatch.Required: Maybe IntFound: Maybe ()">b.x</error>
""")

    fun `test matched field accessor chains`() = checkByText("""
type alias A = {x: ()}
type alias B = {a: A}
type alias C = {b: B}
fieldAccessor : C -> ()
fieldAccessor c = c.b.a.x

exprAccessor : ()
exprAccessor = (C (B (A ()))).b.a.x

recordAccessor : ()
recordAccessor = {b = {a = { x = () } } }.b.a.x
""")

    fun `test mismatch in final part of field accessor chains`() = checkByText("""
type Foo = Bar
type alias A = {x: ()}
type alias B = {a: A}
type alias C = {b: B}
fieldAccessor : C -> Foo
fieldAccessor c = <error descr="Type mismatch.Required: FooFound: ()">c.b.a.x</error>

exprAccessor : Foo
exprAccessor = <error descr="Type mismatch.Required: FooFound: ()">(C (B (A ()))).b.a.x</error>

recordAccessor : Foo
recordAccessor = <error descr="Type mismatch.Required: FooFound: ()">{b = {a = { x = () } } }.b.a.x</error>
""")

    fun `test non-record in middle of accessor chain`() = checkByText("""
type alias A = {x: ()}
type alias B = {a: A}
type alias C = {b: B}
fieldAccessor : C -> ()
fieldAccessor c = <error descr="Value is not a record, cannot access fields.Type: ()">c.b.a.x</error>.z.z

exprAccessor : ()
exprAccessor = <error descr="Value is not a record, cannot access fields.Type: ()">(C (B (A ()))).b.a.x</error>.z.z

recordAccessor : ()
recordAccessor = <error descr="Value is not a record, cannot access fields.Type: ()">{b = {a = { x = () } } }.b.a.x</error>.z.z
""")

    fun `test missing field in accessor chains`() = checkByText("""
type alias A = {x: ()}
type alias B = {a: A}
type alias C = {b: B}
fieldAccessor : C -> ()
fieldAccessor c = c.b.<error descr="Record does not have field 'z'">z</error>.x

exprAccessor : ()
exprAccessor = (C (B (A ()))).b.<error descr="Record does not have field 'z'">z</error>.x

recordAccessor : ()
recordAccessor = {b = {a = { x = () } } }.b.<error descr="Record does not have field 'z'">z</error>.x
""")

    fun `test matched value type from union case`() = checkByText("""
main : Maybe a
main = Nothing
""")

    fun `test mismatched value type from union variant`() = checkByText("""
type Foo = Bar
main : Maybe a
main = <error descr="Type mismatch.Required: Maybe aFound: Foo">Bar</error>
""")

    fun `test invalid constructor as type annotation`() = checkByText("""
main : Just a -> ()
main a = ()
""")

    fun `test matched value from union constructor`() = checkByText("""
foo : (() -> Maybe a) -> () -> ()
foo _ _ = ()

main = foo Just ()
""")

    fun `test matched value from function call`() = checkByText("""
foo : a -> ()
foo _ = ()

main : ()
main = foo 1
""")

    fun `test matched value from function call with parenthesized arguments`() = checkByText("""
foo : Int -> Int -> Int
foo a b = a

main : Int
main = foo (1) (2)
""")

    fun `test mismatched value from function call`() = checkByText("""
type Foo = Bar
foo : a -> ()
foo _ = ()

main : Foo
main = <error descr="Type mismatch.Required: FooFound: ()">foo 1</error>
""")

    fun `test mismatched value from port call`() = checkByText("""
port module Main exposing (foo)
port foo : a -> ()
type Foo = Bar

main : Foo
main = <error descr="Type mismatch.Required: FooFound: ()">foo 1</error>
""")

    fun `test matched function call from parameter`() = checkByText("""
type Foo = Bar
main : (() -> Foo) -> Foo
main fn = fn ()
""")

    fun `test mismatched function call from parameter`() = checkByText("""
type Foo = Bar
main : (() -> Foo) -> ()
main fn = <error descr="Type mismatch.Required: ()Found: Foo">fn ()</error>
""")

    //  This tests that the type refs in function calls are resolved to the correct module
    fun `test mismatched function call with conflicting type name`() = checkByFileTree("""
--@ main.elm
import People.Washington exposing (person)
import People.Costanza exposing (People(..))
main = person <error descr="Type mismatch.Required: People.Washington.PeopleFound: People.Costanza.People">George</error>
--^

--@ People/Washington.elm
module People.Washington exposing (People(..), person)
type People = George

person : People -> ()
person _ = ()

--@ People/Costanza.elm
module People.Costanza exposing (People(..))
type People = George
""")

    //  This tests that the type refs in annotations are resolved to the correct module
    fun `test matched value annotation with concrete union type from other module`() = checkByFileTree("""
--@ main.elm
import People.Washington exposing (People(..), person)

main : Maybe People
main = person George
--^

--@ People/Washington.elm
module People.Washington exposing (People(..), person)

type People = George

person : People -> Maybe People
person a = Just a
""")

    fun `test duplicate function parameter`() = checkByText("""
main a <error descr="Conflicting name declaration">a</error> = ()
""")

    fun `test function parameter duplicating function name`() = checkByText("""
main <error descr="Conflicting name declaration">main</error> = ()
""")

    fun `test parameter name duplicating top level`() = checkByText("""
foo = ()
main <error descr="Conflicting name declaration">foo</error> = ()
""")

    fun `test duplicate name in anonymous function`() = checkByText("""
main a = (\<error descr="Conflicting name declaration">a</error> -> a)
""")

    fun `test allowed destructuring with same names in sibling contexts`() = checkByText("""
main =
    [ let
        ( a, _ ) = ( "", "" )
      in
      a
    , let
        ( a, _ ) = ( (), () )
      in
      <error descr="Type mismatch.Required: StringFound: ()">a</error>
    ]
""")

    fun `test if-else with mismatched condition`() = checkByText("""
main = if <error descr="Type mismatch.Required: BoolFound: Float">1.0</error> then 1 else 2
""")

    fun `test if-else with mismatched else`() = checkByText("""
main = if True then 1.0 else <error descr="Type mismatch.Required: FloatFound: String">"foo"</error>
""")

    fun `test value with mismatched if-else `() = checkByText("""
main : ()
main = <error descr="Type mismatch.Required: ()Found: Float">if True then 1.0 else 2.0</error>
""")

    fun `test if-else with mismatched branches`() = checkByText("""
main = if True then 1.0 else if True then <error descr="Type mismatch.Required: FloatFound: String">"foo"</error> else ()
""")

    fun `test mismatched elements`() = checkByText("""
main = ["", <error descr="Type mismatch.Required: StringFound: Float">1.0</error>, ()]
""")

    fun `test matched lambda type with closure`() = checkByText("""
type Foo = Bar
main : Foo -> (Foo -> Foo)
main a = (\_ -> a)
""")

    fun `test returning lambda`() = checkByText("""
main : () -> ()
main = (\a -> a)
""")

    fun `test function with arguments returning lambda`() = checkByText("""
foo a = \b -> b

main : ()
main = <error descr="Type mismatch.Required: ()Found: String">foo "" ""</error>
""")

    fun `test lambda returning lambda`() = checkByText("""
foo = \a -> (\b -> b)

main : ()
main = <error descr="Type mismatch.Required: ()Found: String">foo "" ""</error>
""")

    fun `test lambda returning lambda returning lambda`() = checkByText("""
foo = \a -> (\b -> (\c -> c))

main : ()
main = <error descr="Type mismatch.Required: ()Found: String">foo "" "" ""</error>
""")

    fun `test record update in lambda`() = checkByText("""
type alias R = {x : ()}
main : R -> R
main = (\r -> { r | x = () })
""")

    fun `test matched lambda type with closure pattern matching`() = checkByText("""
type Foo = Bar
main : (Foo, Foo) -> ((Foo, Foo) -> Foo)
main a = (\(_, b) -> b)
""")

    fun `test mismatched lambda type with closure`() = checkByText("""
type Foo = Bar
main : () -> Foo -> Foo
main a = <error descr="Type mismatch.Required: Foo → FooFound: Foo → ()">(\_ -> a)</error>
""")

    fun `test mismatched tuple pattern in parameter`() = checkByText("""
type Foo = Foo () ()
main : (Foo, Foo) -> ()
main (a, b) = <error descr="Type mismatch.Required: ()Found: Foo">a</error>
""")

    fun `test matched union pattern in parameter`() = checkByText("""
type Foo = Foo ()
main : Foo -> ()
main (Foo foo) = foo
""")

    fun `test mismatched union pattern in parameter`() = checkByText("""
type Foo = Foo ()
main : Foo -> Foo
main (Foo foo) = <error descr="Type mismatch.Required: FooFound: ()">foo</error>
""")

    fun `test union pattern in parameter with too many args`() = checkByText("""
type Foo = Foo ()
main : Foo -> ()
main (<error descr="The type expects 1 argument, but it got 2 instead.">Foo foo bar</error>) = foo
""")

    fun `test union pattern in parameter with too few args`() = checkByText("""
type Foo = Foo () ()
main : Foo -> ()
main (<error descr="The type expects 2 arguments, but it got 1 instead.">Foo foo</error>) = foo
""")

    fun `test union pattern in parameter with too many args for non-constructor`() = checkByText("""
type Foo = Foo
main : Foo -> ()
main (<error descr="The type expects 0 arguments, but it got 1 instead.">Foo foo</error>) = foo
""")
}
