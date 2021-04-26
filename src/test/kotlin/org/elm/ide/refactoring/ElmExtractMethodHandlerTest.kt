package org.elm.ide.refactoring

import com.intellij.application.options.CodeStyle
import junit.framework.TestCase
import org.elm.lang.ElmTestBase
import org.elm.lang.core.psi.ElmExpressionTag
import org.elm.lang.core.psi.indentStyle
import org.intellij.lang.annotations.Language

class ElmExtractMethodHandlerTest : ElmTestBase() {

    override fun getProjectDescriptor() = ElmWithStdlibDescriptor


    // BASICS


    fun `test creates method from base expression`() = doTest("""
f = 4 + {-caret-}3
""", listOf("3", "4 + 3"), 0, """
f = 4 + number

number =
   3
""")

    fun `test uses references from base method`() = doTest("""
f =
   let a = 4
   in 4 + {-caret-}a
""", listOf("a", "4 + a"), 1, """
f =
   let a = 4
   in number a

number a =
   4 + a
""")


    fun `test can select alternate expression`() = doTest("""
f =
    4 + {-caret-}3
""", listOf("3", "4 + 3"), 1, """
f =
    number

number =
   4 + 3
""")

    fun `test does not duplicate parameters`() = doTest("""
f =
    let x = 4
    in <selection>x + x</selection>
""", emptyList(), 0, """
f =
    let x = 4
    in number x

number x =
   x + x
""")

    fun `test passes locally declared parameters referenced inside parens`() = doTest("""
f =
    let x = 4
    in <selection>(x + x)</selection>
""", emptyList(), 0, """
f =
    let x = 4
    in number x

number x =
   (x + x)
""")

    fun `test does not pass parameters declared inside selection`() = doTest("""
f =
    let x = 4
    in <selection>let y = 5
        in x + y</selection>
""", emptyList(), 0, """
f =
    let x = 4
    in number x

number x =
   let y = 5
        in x + y
""")

    fun `test extracts nested expression correctly`() = doTest("""
f maybeFox = 
    case maybeFox of
        Nothing -> Nothing
        Just fox -> 
            let 
                age = <selection>fox.age</selection>
            in 
            Just age
""", emptyList(), 0, """
f maybeFox = 
    case maybeFox of
        Nothing -> Nothing
        Just fox -> 
            let 
                age = age1 fox
            in 
            Just age

age1 fox =
   fox.age
""")

    fun `test does not pass parameters declared outside top level value declaration`() = doTest("""
f a =
    <selection>add a 1</selection>

add x y =
    x + y
""", emptyList(), 0, """
f a =
    add1 a

add1 a =
   add a 1

add x y =
    x + y
""")


    // HELPERS


    private fun doTest(
            @Language("Elm") before: String,
            expressions: List<String>,
            target: Int,
            @Language("Elm") after: String
    ) {
        checkByText(before, after) {
            doExtractMethod(expressions, target)
        }
    }

    private fun doExtractMethod(expressions: List<String>, target: Int) {
        var shownTargetChooser = false
        withMockTargetExpressionChooser(object : ExtractExpressionUi {
            override fun chooseTarget(exprs: List<ElmExpressionTag>): ElmExpressionTag {
                shownTargetChooser = true
                TestCase.assertEquals(exprs.map { it.text }, expressions)
                return exprs[target]
            }
        }) {
            myFixture.performEditorAction("ExtractMethod")
            if (expressions.size > 1 && !shownTargetChooser) {
                error("Didn't show chooser")
            }
        }
    }
}
