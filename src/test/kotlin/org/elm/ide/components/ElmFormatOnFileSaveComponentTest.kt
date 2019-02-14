package org.elm.ide.components

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import junit.framework.TestCase
import org.elm.workspace.ElmWorkspaceTestBase
import org.elm.workspace.elmWorkspace

class ElmFormatOnFileSaveComponentTest : ElmWorkspaceTestBase() {

    override fun runTest() {
        if (toolchain?.elmFormat == null) {
            // TODO in the future maybe we should install elm-format in the CI build environment
            System.err.println("SKIP $name: elm-format not found")
            return
        }
        super.runTest()
    }

    val unformatted = """
                    module Main exposing (f)


                    f x = x{-caret-}

                """.trimIndent()

    val expectedFormatted = """
                    module Main exposing (f)


                    f x =
                        x

                """.trimIndent()


    // TODO [drop 0.18] remove this test
    fun `test ElmFormatOnFileSaveComponent should work with elm 18`() {

        val fileWithCaret: String = buildProject {
            project("elm.json", """
            {
                "type": "application",
                "source-directories": [
                    "src"
                ],
                "elm-version": "0.18.0",
                "dependencies": {
                    "direct": {},
                    "indirect": {}
                },
                "test-dependencies": {
                    "direct": {},
                    "indirect": {}
                }
            }
            """.trimIndent())
            dir("src") {
                elm("Main.elm", unformatted)
            }
        }.fileWithCaret

        testCorrectFormatting(fileWithCaret, unformatted, expectedFormatted)

    }

    fun `test ElmFormatOnFileSaveComponent should work with elm 19`() {

        val fileWithCaret: String = buildProject {
            project("elm.json", """
            {
                "type": "application",
                "source-directories": [
                    "src"
                ],
                "elm-version": "0.19.0",
                "dependencies": {
                    "direct": {},
                    "indirect": {}
                },
                "test-dependencies": {
                    "direct": {},
                    "indirect": {}
                }
            }
            """.trimIndent())
            dir("src") {
                elm("Main.elm", unformatted)
            }
        }.fileWithCaret

        testCorrectFormatting(fileWithCaret, unformatted, expectedFormatted)
    }

    fun `test ElmFormatOnFileSaveComponent should not touch a file with the wrong ending like 'scala'`() {
        val fileWithCaret = buildProject {
            project("elm.json", """
            {
                "type": "application",
                "source-directories": [
                    "src"
                ],
                "elm-version": "0.19.0",
                "dependencies": {
                    "direct": {},
                    "indirect": {}
                },
                "test-dependencies": {
                    "direct": {},
                    "indirect": {}
                }
            }
            """.trimIndent())
            dir("src") {
                elm("Main.scala", """
                    module Main exposing (f)


                    f x = x{-caret-}

                """.trimIndent())
            }
        }.fileWithCaret

        testCorrectFormatting(
                fileWithCaret,
                unformatted,
                expected = unformatted.replace("{-caret-}", "")
        )
    }

    fun `test ElmFormatOnFileSaveComponent should not touch a file if the save-hook is deactivated`() {
        val fileWithCaret = buildProject {
            project("elm.json", """
            {
                "type": "application",
                "source-directories": [
                    "src"
                ],
                "elm-version": "0.19.0",
                "dependencies": {
                    "direct": {},
                    "indirect": {}
                },
                "test-dependencies": {
                    "direct": {},
                    "indirect": {}
                }
            }
            """.trimIndent())
            dir("src") {
                elm("Main.elm", """
                    module Main exposing (f)


                    f x = x{-caret-}

                """.trimIndent())
            }
        }.fileWithCaret

        testCorrectFormatting(
                fileWithCaret,
                unformatted,
                expected = unformatted.replace("{-caret-}", ""),
                activateOnSaveHook = false
        )
    }

    private fun testCorrectFormatting(fileWithCaret: String, unformatted: String, expected: String, activateOnSaveHook: Boolean = true) {

        project.elmWorkspace.useToolchain(toolchain?.copy(isElmFormatOnSaveEnabled = activateOnSaveHook))

        val file = myFixture.configureFromTempProjectFile(fileWithCaret).virtualFile
        val fileDocumentManager = FileDocumentManager.getInstance()
        val document = fileDocumentManager.getDocument(file)!!

        // set text to mark file as unsaved
        // (can't be the unmodified document.text since this won't trigger the beforeDocumentSaving callback)
        val newContent = unformatted.replace("{-caret-}", "")
        ApplicationManager.getApplication().runWriteAction {
            document.setText(newContent)
        }

        fileDocumentManager.saveDocument(document)

        TestCase.assertEquals(expected, document.text)
    }
}
