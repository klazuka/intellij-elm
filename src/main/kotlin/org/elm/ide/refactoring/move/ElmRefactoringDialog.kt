package org.elm.ide.refactoring.move

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.refactoring.ui.RefactoringDialog
import com.intellij.ui.components.JBTextField
import com.intellij.ui.layout.panel
import com.intellij.util.IncorrectOperationException
import org.elm.lang.core.psi.ElmFile
import org.elm.openapiext.toPsiFile
import java.awt.Dimension
import java.io.File
import javax.swing.JComponent

class ElmMoveTopLevelItemsDialog(
    project: Project,
    vsFile: VirtualFile,
    private val itemsToMove: Array<out PsiElement>
) : RefactoringDialog(project, false) {

    private val sourceFilePath: String = vsFile.path
    private val sourceFileField: JBTextField = JBTextField(sourceFilePath).apply { isEnabled = false }
    private val targetFileChooser: TextFieldWithBrowseButton = createTargetFileChooser(project)

    init {
        super.init()
        title = "Move Module Items"
    }

    private fun createTargetFileChooser(project: Project): TextFieldWithBrowseButton {
        return TextFieldWithBrowseButton()
            .also {
                it.text = sourceFilePath
                it.textField.caretPosition = sourceFilePath.removeSuffix(".elm").length
                it.textField.moveCaretPosition(sourceFilePath.lastIndexOf('/') + 1)
            }
    }

    override fun createCenterPanel(): JComponent? {
        return panel {
            row("From:") {
                sourceFileField(growX)
            }
            row("To:") {
                targetFileChooser(growX)
            }
        }.also { it.preferredSize = Dimension(600, 100) }
    }

    override fun doAction() {
        val targetFilePath = targetFileChooser.text

        val targetMod = findTargetMod(targetFilePath)
        if (targetMod == null) {
            val message = "Target file must be an Elm file"
//            CommonRefactoringUtil.showErrorMessage(message("error.title"), message, null, project)
            return
        }

        try {
            val processor = ElmMoveTopLevelItemsProcessor(project, itemsToMove, targetMod, true)
            invokeRefactoring(processor)
        } catch (e: IncorrectOperationException) {
//            CommonRefactoringUtil.showErrorMessage(message("error.title"), e.message, null, project)
        }
    }


    private fun findTargetMod(targetFilePath: String): ElmFile? {
        val targetFile = LocalFileSystem.getInstance().findFileByIoFile(File(targetFilePath))
        return targetFile?.toPsiFile(project) as? ElmFile
    }

}
