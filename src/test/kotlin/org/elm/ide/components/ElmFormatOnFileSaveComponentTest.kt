package org.elm.ide.components

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.util.ThrowableRunnable
import junit.framework.TestCase
import org.elm.workspace.ElmWorkspaceTestBase
import org.elm.workspace.elmWorkspace
import org.intellij.lang.annotations.Language

class ElmFormatOnFileSaveComponentTest : ElmWorkspaceTestBase() {

    override fun runTestRunnable(testRunnable: ThrowableRunnable<Throwable>) {
        if (toolchain.elmFormatCLI == null) {
            // TODO in the future maybe we should install elm-format in the CI build environment
            System.err.println("SKIP $name: elm-format not found")
            return
        }
        super.runTestRunnable(testRunnable)
    }

    val unformatted = """
                    module Main exposing (f)


                    f x = x

                """.trimIndent()

    val expectedFormatted = """
                    module Main exposing (f)


                    f x =
                        x

                """.trimIndent()

    fun `test ElmFormatOnFileSaveComponent should work with elm 19`() {

        buildProject {
            project("elm.json", manifestElm19)
            dir("src") {
                elm("Main.elm", unformatted)
            }
        }

        testCorrectFormatting("src/Main.elm", unformatted, expectedFormatted)
    }

    fun `test ElmFormatOnFileSaveComponent should not add to the undo stack`() {

        buildProject {
            project("elm.json", manifestElm19)
            dir("src") {
                elm("Main.elm", unformatted)
            }
        }

        val inputPath = "src/Main.elm"
        testCorrectFormatting(inputPath, unformatted, expectedFormatted)
        val file = myFixture.configureFromTempProjectFile(inputPath).virtualFile
        val fileEditor = FileEditorManager.getInstance(project).getSelectedEditor(file)

        val undoManager = UndoManager.getInstance(project)
        TestCase.assertFalse(undoManager.isUndoAvailable(fileEditor))
    }

    fun `test ElmFormatOnFileSaveComponent should not touch a file with the wrong ending like 'scala'`() {
        buildProject {
            project("elm.json", manifestElm19)
            dir("src") {
                elm("Main.elm")
                file("Main.scala", "blah")
            }
        }

        testCorrectFormatting("src/Main.scala", unformatted, expected = unformatted)
    }

    fun `test ElmFormatOnFileSaveComponent should not touch a file if the save-hook is deactivated`() {
        buildProject {
            project("elm.json", manifestElm19)
            dir("src") {
                elm("Main.elm", """
                    module Main exposing (f)


                    f x = x

                """.trimIndent())
            }
        }

        testCorrectFormatting(
                "src/Main.elm",
                unformatted,
                expected = unformatted,
                activateOnSaveHook = false
        )
    }

    private fun testCorrectFormatting(fileWithCaret: String, unformatted: String, expected: String, activateOnSaveHook: Boolean = true) {

        project.elmWorkspace.useToolchain(toolchain.copy(isElmFormatOnSaveEnabled = activateOnSaveHook))

        val file = myFixture.configureFromTempProjectFile(fileWithCaret).virtualFile
        val fileDocumentManager = FileDocumentManager.getInstance()
        val document = fileDocumentManager.getDocument(file)!!

        // set text to mark file as unsaved
        // (can't be the unmodified document.text since this won't trigger the beforeDocumentSaving callback)
        ApplicationManager.getApplication().runWriteAction {
            document.setText(unformatted)
        }

        fileDocumentManager.saveDocument(document)

        TestCase.assertEquals(expected, document.text)
    }
}


@Language("JSON")
private val manifestElm19 = """
        {
            "type": "application",
            "source-directories": [
                "src"
            ],
            "elm-version": "0.19.1",
            "dependencies": {
                "direct": {
                    "elm/core": "1.0.0",
                    "elm/json": "1.0.0"
                },
                "indirect": {}
            },
            "test-dependencies": {
                "direct": {},
                "indirect": {}
            }
        }
        """.trimIndent()
