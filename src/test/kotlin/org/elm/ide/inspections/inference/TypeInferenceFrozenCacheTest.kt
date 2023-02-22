package org.elm.ide.inspections.inference

import org.elm.lang.ElmTestBase
import org.elm.lang.core.psi.ElmPsiElement
import org.elm.lang.core.psi.directChildrenOfType
import org.elm.lang.core.psi.elements.ElmPortAnnotation
import org.elm.lang.core.psi.elements.ElmTypeAliasDeclaration
import org.elm.lang.core.psi.elements.ElmTypeDeclaration
import org.elm.lang.core.psi.elements.ElmValueDeclaration
import org.elm.lang.core.types.*
import org.intellij.lang.annotations.Language

/**
 * Tests that all cached TyRecords are frozen and immutable. Variant inference results don't need to be
 * frozen, since they're never used in other inference.
 */
class TypeInferenceFrozenCacheTest : ElmTestBase() {
    fun `test port`() = doTest("""
port module Main exposing (..)
port p1 : () -> {f: ()}
type alias R = {f2 : ()}
port p2 : () -> R
""")

    fun `test type`() = doTest("""
type alias R = {f2 : ()}
type T a
    = V1 {f: a}
    | V2

main : T R
main = V2
""")

    fun `test parameterized alias`() = doTest("""
type alias R a = {a | f : ()}
type alias R2 = R { f2 : () }
""")

    fun `test annotation`() = doTest("""
type alias R a = {a | f : ()}
main : R { f2 : () } -> ()
main _ = ()
""")

    fun `test expression types`() = doTest("""
type alias R a = {a | f : ()}
foo : R { f2 : () } -> R { f2 : () }
foo a = a

main a b =
    let
        bar = foo b
        baz = a.a
    in
    (bar.f2, baz.b, b)
""")

    fun `test modifying extension record in lambda`() = doTest("""
type alias R a = {a | f : ()}
type alias S = { g: () }

main : R S -> R S
main r = 
    (\s -> { s | g = () }) r
""")

    private fun doTest(@Language("Elm") code: String) {
        InlineFile(code)
        myFixture.file.directChildrenOfType<ElmPsiElement>().forEach { elem ->
            when (elem) {
                is ElmValueDeclaration -> checkResult(elem.findInference()!!)
                is ElmTypeAliasDeclaration -> checkResult(elem.typeExpressionInference())
                is ElmPortAnnotation -> checkResult(elem.typeExpressionInference())
                is ElmTypeDeclaration -> checkResult(elem.typeExpressionInference())
            }
        }
    }

    private fun checkResult(r: InferenceResult) = checkResult(r.expressionTypes, r.diagnostics, r.ty)
    private fun checkResult(r: ParameterizedInferenceResult<out Ty>) = checkResult(null, r.diagnostics, r.value)
    private fun checkResult(expressionTypes: Map<ElmPsiElement, Ty>? = null, diagnostics: List<*>, ty: Ty) {
        assertEmpty(diagnostics)
        ty.assertFrozen()
        expressionTypes?.values?.forEach { it.assertFrozen() }
    }

    private fun Ty.assertFrozen() {
        when (this) {
            is TyTuple -> types.forEach { it.assertFrozen() }
            is TyRecord -> {
                fields.values.forEach { it.assertFrozen() }
                baseTy?.assertFrozen()
                assertTrue("not frozen: $this", fieldReferences.frozen)
            }
            is MutableTyRecord -> fail("MutableTyRecord found: $this")
            is TyUnion -> parameters.forEach { it.assertFrozen() }
            is TyFunction -> {
                ret.assertFrozen()
                parameters.forEach { it.assertFrozen() }
            }

            TyInProgressBinding -> TODO()
            is TyUnit -> TODO()
            is TyUnknown -> TODO()
            is TyVar -> TODO()
        }
        alias?.parameters?.forEach { it.assertFrozen() }
    }
}
