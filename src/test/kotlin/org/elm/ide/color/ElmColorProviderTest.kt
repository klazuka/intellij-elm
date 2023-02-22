package org.elm.ide.color

import com.github.ajalt.colormath.Color
import com.github.ajalt.colormath.model.HSL
import com.github.ajalt.colormath.model.RGB
import com.intellij.openapi.application.runWriteAction
import com.intellij.util.ui.ColorIcon
import org.elm.lang.ElmTestBase
import org.intellij.lang.annotations.Language


class ElmColorProviderTest : ElmTestBase() {
    //<editor-fold desc="css">
    fun `test value with other string content`() = doGutterTest(1, """
main = ("border", "1px solid #aabbcc")
""")

    // format test cases from https://developer.mozilla.org/en-US/docs/Web/CSS/color_value

    fun `test #f09`() = doFormatTest("#f09")
    fun `test #F09`() = doFormatTest("#F09")
    fun `test #ff0099`() = doFormatTest("#ff0099")
    fun `test #FF0099`() = doFormatTest("#FF0099")
    fun `test rgb(255,0,153)`() = doFormatTest("rgb(255,0,153)")
    fun `test rgb(255, 0, 153)`() = doFormatTest("rgb(255, 0, 153)")
    fun `test rgb(255, 0, 153_0)`() = doFormatTest("rgb(255, 0, 153.0)")
    fun `test rgb(100%,0%,60%)`() = doFormatTest("rgb(100%,0%,60%)")
    fun `test rgb(100%, 0%, 60%)`() = doFormatTest("rgb(100%, 0%, 60%)")
    fun `test rgb(255 0 153)`() = doFormatTest("rgb(255 0 153)")
    fun `test #f09f`() = doFormatTest("#f09f")
    fun `test #F09F`() = doFormatTest("#F09F")
    fun `test #ff0099ff`() = doFormatTest("#ff0099ff")
    fun `test #FF0099FF`() = doFormatTest("#FF0099FF")
    fun `test rgb(255, 0, 153, 1)`() = doFormatTest("rgb(255, 0, 153, 1)")
    fun `test rgb(255, 0, 153, 100%)`() = doFormatTest("rgb(255, 0, 153, 100%)")
    fun `test rgb(255 0 153 _ 1)`() = doFormatTest("rgb(255 0 153 / 1)")
    fun `test rgb(255 0 153 _ 100%)`() = doFormatTest("rgb(255 0 153 / 100%)")
    fun `test rgb(255, 0, 153_6, 1)`() = doFormatTest("rgb(255, 0, 153.6, 1)")
    fun `test rgb(1e2, _5e1, _5e0, +_25e2%)`() = doFormatTest("rgb(1e2, .5e1, .5e0, +.25e2%)")
    fun `test hsl(270,60%,70%)`() = doFormatTest("hsl(270,60%,70%)")
    fun `test hsl(270, 60%, 70%)`() = doFormatTest("hsl(270, 60%, 70%)")
    fun `test hsl(270 60% 70%)`() = doFormatTest("hsl(270 60% 70%)")
    fun `test hsl(270deg, 60%, 70%)`() = doFormatTest("hsl(270deg, 60%, 70%)")
    fun `test hsl(4_71239rad, 60%, 70%)`() = doFormatTest("hsl(4.71239rad, 60%, 70%)")
    fun `test hsl(_75turn, 60%, 70%)`() = doFormatTest("hsl(.75turn, 60%, 70%)")
    fun `test hsl(270, 60%, 50%, _15)`() = doFormatTest("hsl(270, 60%, 50%, .15)")
    fun `test hsl(270, 60%, 50%, 15%)`() = doFormatTest("hsl(270, 60%, 50%, 15%)")
    fun `test hsl(270 60% 50% _ _15)`() = doFormatTest("hsl(270 60% 50% / .15)")
    fun `test hsl(270 60% 50% _ 15%)`() = doFormatTest("hsl(270 60% 50% / 15%)")

    fun `test write #f09`() = doCssWriteTest("#f09", "#7b2d43")
    fun `test write #ff0099`() = doCssWriteTest("#ff0099", "#7b2d43")
    fun `test write rgb(255,0,153)`() = doCssWriteTest("rgb(255,0,153)", "rgb(123, 45, 67)")
    fun `test write rgb(255, 0, 153)`() = doCssWriteTest("rgb(255, 0, 153)", "rgb(123, 45, 67)")
    fun `test write rgb(255, 0, 153_0)`() = doCssWriteTest("rgb(255, 0, 153.0)", "rgb(123, 45, 67)")
    fun `test write rgb(100%,0%,60%)`() = doCssWriteTest("rgb(100%,0%,60%)", "rgb(48%, 18%, 26%)")
    fun `test write rgb(100%, 0%, 60%)`() = doCssWriteTest("rgb(100%, 0%, 60%)", "rgb(48%, 18%, 26%)")
    fun `test write rgb(255 0 153)`() = doCssWriteTest("rgb(255 0 153)", "rgb(123 45 67)")
    fun `test write #f090`() = doCssWriteTest("#f090", "#7b2d4300", RGB(123, 45, 67, 0f))
    fun `test write #ff009900`() = doCssWriteTest("#ff00990", "#7b2d4300", RGB(123, 45, 67, 0f))
    fun `test write rgba(255, 0, 153, 1)`() = doCssWriteTest("rgba(255, 0, 153, 1)", "rgba(123, 45, 67)")
    fun `test write rgb(255, 0, 153, 100%)`() = doCssWriteTest("rgb(255, 0, 153, 100%)", "rgb(123, 45, 67, 50%)", RGB(123, 45, 67, .5f))
    fun `test write rgb(255 0 153 _ 1)`() = doCssWriteTest("rgb(255 0 153 / 1)", "rgb(123 45 67 / .2)", RGB(123, 45, 67, .2f))
    fun `test write rgb(255 0 153 _ 100%)`() = doCssWriteTest("rgb(255 0 153 / 100%)", "rgb(123 45 67 / 50%)", RGB(123, 45, 67, .5f))
    fun `test write hsl(270,60%,70%)`() = doCssWriteTest("hsl(270,60%,70%)", "hsl(123, 45%, 67%, .2)", HSL(123, 45, 67, .2f))
    fun `test write hsl(270, 60%, 70%)`() = doCssWriteTest("hsl(270, 60%, 70%)", "hsl(123, 45%, 67%)", HSL(123, 45, 67))
    fun `test write hsl(270 60% 70%)`() = doCssWriteTest("hsl(270 60% 70%)", "hsl(123 45% 67%)", HSL(123, 45, 67))
    fun `test write hsl(270, 60%, 50%, _15)`() = doCssWriteTest("hsl(270, 60%, 50%, .15)", "hsl(123, 45%, 67%, .2)", HSL(123, 45, 67, .2f))
    fun `test write hsl(270, 60%, 50%, 15%)`() = doCssWriteTest("hsl(270, 60%, 50%, 15%)", "hsl(123, 45%, 67%, 20%)", HSL(123, 45, 67, .2f))
    fun `test write hsl(270 60% 50% _ _15)`() = doCssWriteTest("hsl(270 60% 50% / .15)", "hsl(123 45% 67% / .2)", HSL(123, 45, 67, .2f))
    fun `test write hsl(270 60% 50% _ 15%)`() = doCssWriteTest("hsl(270 60% 50% / 15%)", "hsl(123 45% 67% / 20%)", HSL(123, 45, 67, .2f))
    fun `test write hsl(270grad,60%,70%)`() = doCssWriteTest("hsl(270grad,60%,70%)", "hsl(136.6666grad, 45%, 67%, .2)", HSL(123, 45, 67, .2f))
    fun `test write hsl(270rad,60%,70%)`() = doCssWriteTest("hsl(270rad,60%,70%)", "hsl(2.1467rad, 45%, 67%, .2)", HSL(123, 45, 67, .2f))
    fun `test write hsl(270turn,60%,70%)`() = doCssWriteTest("hsl(270turn,60%,70%)", "hsl(.3416turn, 45%, 67%, .2)", HSL(123, 45, 67, .2f))
    //</editor-fold>

    //<editor-fold desc="functions">

    fun `test rgb int read`() = doGutterTest(2, """
type Color = Color
rgb : Int -> Int -> Int -> Color
rgb r g b = Color

main =
    [ rgb 0 0 0
    , rgb 255 255 255
    , rgb 0 0 -- partial
    , rgb 0 0 300 -- out of bounds
    , rgb 0 0 -2 -- out of bounds
    ]
""")

    fun `test rgba int read`() = doGutterTest(3, """
type Color = Color
rgba : Int -> Int -> Int -> Float -> Color
rgba r g b a = Color

main =
    [ rgba 0 0 0 0
    , rgba 255 255 255 1
    , rgba 0 0 0 0.5
    , rgba 0 0 0 -- partial
    ]
""")

    fun `test rgb255 int read`() = doGutterTest(2, """
type Color = Color
rgb255 : Int -> Int -> Int -> Color
rgb255 r g b = Color

main =
    [ rgb255 0 0 0
    , rgb255 255 255 255
    ]
""")

    fun `test rgb float read`() = doGutterTest(3, """
type Color = Color
rgb : Float -> Float -> Float -> Color
rgb r g b = Color

main =
    [ rgb 0 0 0
    , rgb 1 1 1
    , rgb 0 0.5 1
    , rgb 0 0 -- partial
    ]
""")

    fun `test rgba float read`() = doGutterTest(3, """
type Color = Color
rgba : Float -> Float -> Float -> Float -> Color
rgba r g b a = Color

main =
    [ rgba 0 0 0 0
    , rgba 1 1 1 1
    , rgba 0.5 0.5 0.5 0.5
    , rgba 0 0 -- partial
    ]
""")

    fun `test hsl read`() = doGutterTest(3, """
type Color = Color
hsl : Float -> Float -> Float -> Color
rgb h s l = Color

main =
    [ hsl 0 0 0
    , hsl 1 1 1
    , hsl 0 0.5 1
    , hsl 0 0 2 -- out of bounds
    , hsl 0 0 -- partial
    ]
""")

    fun `test hsla read`() = doGutterTest(3, """
type Color = Color
hsla : Float -> Float -> Float -> Float -> Color
hsla h s l a = Color

main =
    [ hsla 0 0 0 0
    , hsla 1 1 1 1
    , hsla 0 0.5 1 0.5
    , hsla 0 0 0 -- partial
    ]
""")

    fun `test write rgb 0 0 0`() = doFuncWriteTest("rgb", "0 0 0", "123 45 67")

    fun `test write rgb 255 255 255`() = doFuncWriteTest("rgb", "255 255 255", "123 45 67")
    fun `test write rgb 0 0 0 with alpha`() = doFuncWriteTest("rgb", "0 0 0", "123 45 67", RGB(123, 45, 67, .5f))

    fun `test write rgb255 0 0 0`() = doFuncWriteTest("rgb255", "0 0 0", "123 45 67")

    fun `test write rgba 0 0 0 0`() = doFuncWriteTest("rgba", "0 0 0 0", "123 45 67 1")
    fun `test write rgba 255 255 255 1`() = doFuncWriteTest("rgba", "255 255 255 1", "123 45 67 0.2", RGB(123, 45, 67, .2f))

    fun `test write rgb float 1 1 1`() = doFuncWriteTest("rgb", "1 1 1", "0.4824 0.1765 0.2627")
    fun `test write rgb float 0_5 0_5 0_5`() = doFuncWriteTest("rgb", "0.5 0.5 05", "0.4824 0.1765 0.2627")

    fun `test write rgba 1 1 1 1`() = doFuncWriteTest("rgba", "1 1 1 1", "0.4824 0.1765 0.2627 1")
    fun `test write rgba float`() = doFuncWriteTest("rgba", "0.5 0.5 05 0.5", "0.4824 0.1765 0.2627 1")

    fun `test write hsl 0 0 0`() = doFuncWriteTest("hsl", "0 0 0", "0 0 0", HSL(0, 0, 0))
    fun `test write hsl 360 1 1`() = doFuncWriteTest("hsl", "360 1 1", "180 0.5 0.5", HSL(180, 50, 50))

    fun `test write hsla 0 0 0 0`() = doFuncWriteTest("hsla", "0 0 0 0", "0 0 0 1", HSL(0, 0, 0))
    fun `test write hsla 360 1 1 1`() = doFuncWriteTest("hsla", "360 1 1 1", "180 0.5 0.5 0.2", HSL(180, 50, 50, .2f))

    fun `test write rgb with linebreaks and comments`() = doWriteTest(RGB(123, 45, 67), """
main = rgb{-caret-}
    -- red
    2 --
     3 {--}
      {--}4
""", """
main = rgb
    -- red
    123 --
     45 {--}
      {--}67
""")

    //</editor-fold>

    private fun doFormatTest(color: String) {
        doGutterTest(1, """
        import Html.Attributes exposing (style)
        main = style "color" "$color"
        """.trimIndent())
    }

    private fun doGutterTest(expected: Int, @Language("Elm") code: String) {
        InlineFile(code)
        val actual = myFixture.findAllGutters().count { it.icon is ColorIcon }
        assertEquals(expected, actual)
    }

    private fun doCssWriteTest(before: String, after: String, color: Color = RGB(123, 45, 67)) {
        doWriteTest(color, "main = \". $before {-caret-}.\"", "main = \". $after .\"")
    }

    private fun doFuncWriteTest(func: String, before: String, after: String, color: Color = RGB(123, 45, 67)) {
        doWriteTest(color, "main = $func{-caret-} $before", "main = $func $after")
    }

    private fun doWriteTest(color: Color, @Language("Elm") before: String, @Language("Elm") after: String) {
        InlineFile(before)
        val element = myFixture.file.findElementAt(myFixture.caretOffset - 1)
        requireNotNull(element)
        val awtColor = color.toAwtColor()
        runWriteAction {
            ElmColorProvider().setColorTo(element, awtColor)
        }
        myFixture.checkResult(after)
    }
}
