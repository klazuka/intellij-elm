/*
The MIT License (MIT)

Derived from intellij-rust
Copyright (c) 2015 Aleksey Kladov, Evgeny Kurbatsky, Alexey Kudinkin and contributors
Copyright (c) 2016 JetBrains

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
 */

package org.elm.lang

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.roots.ContentEntry
import com.intellij.openapi.roots.ModifiableRootModel
import com.intellij.openapi.util.io.StreamUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileFilter
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.impl.PsiManagerEx
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.LightPlatformCodeInsightFixture4TestCase
import junit.framework.AssertionFailedError
import org.elm.FileTree
import org.elm.TestProject
import org.elm.fileTreeFromText
import org.elm.lang.core.psi.parentOfType
import org.elm.workspace.ElmToolchain
import org.elm.workspace.ElmToolchain.Companion.ELM_JSON
import org.elm.workspace.EmptyElmStdlibVariant
import org.elm.workspace.MinimalElmStdlibVariant
import org.elm.workspace.elmWorkspace
import org.intellij.lang.annotations.Language
import java.util.*

private val log = logger<ElmTestBase>()

/**
 * Base class for basically all Elm tests *except* lexing, parsing and stuff that depends
 * on the Elm toolchain.
 *
 * Features:
 * - defines a project descriptor suitable for IntelliJ's integration test system
 * - test util functions checkBy{File,Directory,Text} that run and compare against expected output
 * - test util functions findElement...InEditor that identify a mark in the source input
 *   and make it easy to check facts about the PsiElement at the indicated position
 *
 * We don't use this base class for lexing and parsing because IntelliJ already
 * provides [LexerTestCase] and [ParsingTestCase] for that purpose.
 *
 * For "heavier" integration tests, see [org.elm.workspace.ElmWorkspaceTestBase]
 */
abstract class ElmTestBase : LightPlatformCodeInsightFixture4TestCase(), ElmTestCase {

    override fun getProjectDescriptor(): LightProjectDescriptor = ElmDefaultDescriptor

    override fun isWriteActionRequired(): Boolean = false

    open val dataPath: String = ""

    override fun getTestDataPath(): String = "${ElmTestCase.testResourcesPath}/$dataPath"

    protected val fileName: String
        get() = "$testName.elm"

    protected val testName: String
        get() = camelOrWordsToSnake(getTestName(true))

    protected fun checkByFile(ignoreTrailingWhitespace: Boolean = true, action: () -> Unit) {
        val (before, after) = (fileName to fileName.replace(".elm", "_after.elm"))
        myFixture.configureByFile(before)
        action()
        myFixture.checkResultByFile(after, ignoreTrailingWhitespace)
    }

    protected fun checkByDirectory(action: () -> Unit) {
        val (before, after) = ("$testName/before" to "$testName/after")

        val targetPath = ""
        val beforeDir = myFixture.copyDirectoryToProject(before, targetPath)

        action()

        val afterDir = getVirtualFileByName("$testDataPath/$after")
        PlatformTestUtil.assertDirectoriesEqual(afterDir!!, beforeDir)
    }

    protected fun checkByDirectory(@Language("Elm") before: String, @Language("Elm") after: String, action: () -> Unit) {
        fileTreeFromText(before).create()
        action()
        FileDocumentManager.getInstance().saveAllDocuments()
        fileTreeFromText(after).assertEquals(myFixture.findFileInTempDir("."))
    }

    protected fun checkByText(
            @Language("Elm") before: String,
            @Language("Elm") after: String,
            action: () -> Unit
    ) {
        InlineFile(before)
        action()
        myFixture.checkResult(replaceCaretMarker(after))
    }

    protected fun openFileInEditor(path: String) {
        myFixture.configureFromExistingVirtualFile(myFixture.findFileInTempDir(path))
    }

    protected fun getVirtualFileByName(path: String): VirtualFile? =
            LocalFileSystem.getInstance().findFileByPath(path)

    protected inline fun <reified X : Throwable> expect(f: () -> Unit) {
        try {
            f()
        } catch (e: Throwable) {
            if (e is X)
                return
            throw e
        }
        fail("No ${X::class.java} was thrown during the test")
    }

    inner class InlineFile(@Language("Elm") private val code: String, val name: String = "main.elm") {
        private val hasCaretMarker = "{-caret-}" in code

        init {
            myFixture.configureByText(name, replaceCaretMarker(code))
        }

        fun withCaret() {
            check(hasCaretMarker) {
                "Please add `{-caret-}` marker to\n$code"
            }
        }
    }

    protected inline fun <reified T : PsiElement> findElementInEditor(marker: String = "^"): T {
        val (element, data) = findElementWithDataAndOffsetInEditor<T>(marker)
        check(data.isEmpty()) { "Did not expect marker data" }
        return element
    }

    protected inline fun <reified T : PsiElement> findElementAndDataInEditor(marker: String = "^"): Pair<T, String> {
        val (element, data) = findElementWithDataAndOffsetInEditor<T>(marker)
        return element to data
    }

    protected inline fun <reified T : PsiElement> findElementWithDataAndOffsetInEditor(marker: String = "^"): Triple<T, String, Int> {
        val (data, offset) = findDataAndOffsetInEditor(marker)
        val elementAtMarker = myFixture.file.findElementAt(offset)!!
        val element = elementAtMarker.parentOfType<T>(strict = false)
                ?: error("No ${T::class.java.simpleName} at ${elementAtMarker.text}")
        return Triple(element, data, offset)
    }

    protected fun findReferenceWithDataInEditor(marker: String = "^"): Pair<PsiReference?, String> {
        val (data, offset) = findDataAndOffsetInEditor(marker)
        return Pair(myFixture.file.findReferenceAt(offset), data)
    }

    protected fun findDataAndOffsetInEditor(marker: String = "^"): Pair<String, Int> {
        val elmLineComment = "--"
        val caretMarker = "$elmLineComment$marker"
        return run {
            val text = myFixture.file.text
            val markerOffset = text.indexOf(caretMarker)
            check(markerOffset != -1) { "No `$marker` marker:\n$text" }
            check(text.indexOf(caretMarker, startIndex = markerOffset + 1) == -1) {
                "More than one `$marker` marker:\n$text"
            }

            val data = text.drop(markerOffset).removePrefix(caretMarker).takeWhile { !it.isWhitespace() }.trim()
            val markerPosition = myFixture.editor.offsetToLogicalPosition(markerOffset + caretMarker.length - 1)
            val previousLine = LogicalPosition(markerPosition.line - 1, markerPosition.column)
            val elementOffset = myFixture.editor.logicalPositionToOffset(previousLine)
            Pair(data, elementOffset)
        }
    }

    protected fun replaceCaretMarker(@Language("Elm") text: String) =
            text.replace("{-caret-}", "<caret>")

    protected fun applyQuickFix(name: String) {
        val action = myFixture.findSingleIntention(name)
        myFixture.launchAction(action)
    }

    protected open class ElmProjectDescriptorBase(val enableStdlib: Boolean) : LightProjectDescriptor() {
        open val skipTestReason: String? = null

        override fun configureModule(module: Module, model: ModifiableRootModel, contentEntry: ContentEntry) {
            super.configureModule(module, model, contentEntry)

            if (skipTestReason != null)
                return

            val toolchain = ElmToolchain.suggest(module.project)
            require(toolchain.looksLikeValidToolchain()) { "failed to find Elm toolchain: cannot setup workspace for tests" }

            val variant = if (enableStdlib) MinimalElmStdlibVariant else EmptyElmStdlibVariant
            variant.ensureElmStdlibInstalled(module.project, toolchain)
            val contentRoot = contentEntry.file!!
            val elmJsonFile = contentRoot.createChildData(this, ELM_JSON)
            VfsUtil.saveText(elmJsonFile, variant.jsonManifest)
            module.project.elmWorkspace.setupForTests(toolchain, elmJsonFile)
        }
    }

    protected object ElmWithStdlibDescriptor : ElmProjectDescriptorBase(enableStdlib = true)
    protected object ElmDefaultDescriptor : ElmProjectDescriptorBase(enableStdlib = false)

    protected fun checkAstNotLoaded(fileFilter: VirtualFileFilter) {
        PsiManagerEx.getInstanceEx(project).setAssertOnFileLoadingFilter(fileFilter, testRootDisposable)
    }

    protected open fun configureByText(text: String) {
        InlineFile(text.trimIndent())
    }

    protected open fun configureByFileTree(text: String) {
        fileTreeFromText(text).createAndOpenFileWithCaretMarker()
    }

    companion object {
        // XXX: hides `Assert.fail`
        fun fail(message: String): Nothing {
            throw AssertionFailedError(message)
        }

        @JvmStatic
        fun camelOrWordsToSnake(name: String): String {
            if (' ' in name) return name.replace(" ", "_")
            return name.split("(?=[A-Z])".toRegex()).joinToString("_") { it.lowercase(Locale.US) }
        }

        @JvmStatic
        fun checkHtmlStyle(html: String) {
            // Consistency check for the HTML documentation of intention actions
            // http://stackoverflow.com/a/1732454
            val re = "<body>(.*)</body>".toRegex(RegexOption.DOT_MATCHES_ALL)
            val body = (re.find(html)?.let { it.groups[1]!!.value } ?: html).trim()
            check(body[0].isUpperCase()) { "Please start description with the capital latter" }
            check(body.last() == '.') { "Please end description with a period" }
        }

        @JvmStatic
        fun getResourceAsString(path: String): String? {
            val stream = ElmTestBase::class.java.classLoader.getResourceAsStream(path)
                    ?: return null

            return stream.bufferedReader().use { it.readText() }
        }
    }

    protected fun FileTree.create(): TestProject =
            create(myFixture.project, myFixture.findFileInTempDir("."))

    protected fun FileTree.createAndOpenFileWithCaretMarker(): TestProject {
        val testProject = create()
        myFixture.configureFromTempProjectFile(testProject.fileWithCaret)
        return testProject
    }
}
