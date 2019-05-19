package org.frawa.elmtest.core

import com.intellij.psi.PsiElement
import com.intellij.testFramework.ParsingTestCase
import org.elm.ide.test.core.LabelUtils
import org.elm.lang.core.parser.ElmParserDefinition
import org.frawa.elmtest.core.ElmPluginHelper.getPsiElement
import java.util.*

class ElmPluginHelperTest : ParsingTestCase("elmPluginHelper", "elm", ElmParserDefinition()) {

    override fun getTestDataPath(): String {
        return "src/test/resources"
    }

    override// see file resources/elmPluginHelper/Navigation
    fun getTestName(lowercaseFirstLetter: Boolean): String {
        return "Navigation"
    }

    fun testTopLevelSuite() {
        doTest(false)
        assertSuite(27, "suite1")
    }

    fun testTestInSuite() {
        doTest(false)
        assertTest(55, "suite1", "test1")
    }

    fun testTopLevelTest() {
        doTest(false)
        assertTest(137, "test1")
    }

    fun testNestedSuitesAndTests() {
        doTest(false)
        assertSuite(207, "suite2")
        assertTest(235, "suite2", "test1")
        assertSuite(291, "suite2", "nested1")
        assertTest(324, "suite2", "nested1", "test1")
    }

    fun testMissingTopLevelSuite() {
        doTest(false)
        assertMissing("suiteMissing")
    }

    fun testMissingTopLevelTest() {
        doTest(false)
        assertMissing("testMissing")
    }

    fun testMissingNestedSuitesAndTests() {
        doTest(false)
        assertSuite(207, "suite2")
        assertMissing("suite2", "testMissing")
        assertFallback("suite2", "suite2", "nestedMissing")
        assertFallback("nested1", "suite2", "nested1", "testMissing")
    }

    private fun assertSuite(offset: Int, vararg labels: String) {
        val path = LabelUtils.toPath(Arrays.asList(*labels))
        val element = getPsiElement(true, path.toString(), myFile)

        val expected = String.format("describe \"%s\"", labels[labels.size - 1])
        assertEquals(expected, firstLine(text(element)))
        assertEquals(offset, element.node.startOffset)
    }

    private fun assertTest(offset: Int, vararg labels: String) {
        val path = LabelUtils.toPath(Arrays.asList(*labels))
        val element = getPsiElement(false, path.toString(), myFile)

        val expected = String.format("test \"%s\"", labels[labels.size - 1])
        assertEquals(expected, text(element))
        assertEquals(offset, element.node.startOffset)
    }

    private fun assertMissing(vararg labels: String) {
        val path = LabelUtils.toPath(Arrays.asList(*labels))
        val element = getPsiElement(false, path.toString(), myFile)
        assertSame(myFile, element)
    }

    private fun assertFallback(fallback: String, vararg labels: String) {
        val path = LabelUtils.toPath(Arrays.asList(*labels))
        val element = getPsiElement(true, path.toString(), myFile)

        val expected = String.format("describe \"%s\"", fallback)
        assertEquals(expected, firstLine(text(element)))
    }

    private fun text(element: PsiElement): String {
        return element.text.trim { it <= ' ' }
    }

    private fun firstLine(text: String): String {
        return text.split("\n".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()[0]
    }

}