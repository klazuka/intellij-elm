package org.elm.ide.intentions

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import org.elm.lang.core.psi.*
import org.elm.lang.core.psi.elements.*
import org.elm.workspace.commandLineTools.ElmFormatCLI
import org.elm.workspace.elmSettings
import org.elm.workspace.elmToolchain

/**
 * An intention action that transforms a series of function applications to/from a pipeline.
 */
class RemovePipelineIntention : ElmAtCaretIntentionActionBase<RemovePipelineIntention.Context>() {

    sealed class Context {
        data class HasRightPipes(val functionCall: ElmFunctionCallExpr) : Context()
    }

    override fun getText() = "Remove Pipes"
    override fun getFamilyName() = text

    private fun findPipeline(element: PsiElement): ElmBinOpExpr? {
        val firstOrNull = element.ancestors.filterIsInstance<ElmBinOpExpr>().firstOrNull()
        return firstOrNull
    }

    private fun normalizePipeline(originalPipeline: List<ElmPsiElement>, project: Project): ElmPsiElement {
        var soFar: ElmParenthesizedExpr? = null
        var unprocessed = originalPipeline
        while (true)  {
            val takeWhile = unprocessed.takeWhile { !(it is ElmOperator && it.referenceName == "|>") }
            unprocessed = unprocessed.drop(takeWhile.size + 1)
            if (soFar == null) {
                soFar = ElmPsiFactory(project).createParens(
                        takeWhile
                                .map { it.text }
                                .toList()
                                .joinToString(separator = " ")
                )
            } else {
                soFar = ElmPsiFactory(project).callFunctionWithArgument(
                        takeWhile
                                .map { it.text }
                                .toList()
                                .joinToString(separator = " ")
                        , soFar
                )

            }

            if (takeWhile.isEmpty() || unprocessed.isEmpty()) {
                return soFar!!
            }

        }
    }

    private fun findAndNormalize(element: PsiElement, project: Project): ElmPsiElement? {
        val parts = findPipeline(element)?.parts
        return if (parts != null) {
            normalizePipeline(parts.toList(), project)
        } else {
            null
        }
    }

    override fun findApplicableContext(project: Project, editor: Editor, element: PsiElement): Context? {
        return when (val functionCall = element.ancestors.filterIsInstance<ElmFunctionCallExpr>().firstOrNull()) {
            is ElmFunctionCallExpr -> {

                if (functionCall.prevSiblings.withoutWsOrComments.toList().size >= 2) {
                    val (prev1, argument) = functionCall.prevSiblings.withoutWsOrComments.toList()
                    if (prev1 is ElmOperator && prev1.referenceName.equals("|>")) {
                        Context.HasRightPipes(functionCall)
                    } else {
                        null
                    }
                } else if (functionCall.nextSiblings.withoutWsOrComments.toList().size >= 2) {
                    val (prev1, argument) = functionCall.nextSiblings.withoutWsOrComments.toList()
                    if (prev1 is ElmOperator && prev1.referenceName.equals("|>")) {
                        Context.HasRightPipes(functionCall)
                    } else {
                        null
                    }
                } else {
                    null
                }
            }
            else -> {
                null
            }
        }
    }

    override fun invoke(project: Project, editor: Editor, context: Context) {
        WriteCommandAction.writeCommandAction(project).run<Throwable> {
            when (context) {
                is Context.HasRightPipes -> {
                    context.functionCall.parent.replace(findAndNormalize(context.functionCall, project)?.originalElement!!)
                }
            }

            if (project.elmSettings.toolchain.isElmFormatOnSaveEnabled) {
                tryElmFormat(project, editor)
            }
        }
    }

    private fun tryElmFormat(project: Project, editor: Editor) {
        PsiDocumentManager.getInstance(project).doPostponedOperationsAndUnblockDocument(editor.document)

        try {
            val vFile = FileDocumentManager.getInstance().getFile(editor.document)
            val elmVersion = ElmFormatCLI.getElmVersion(project, vFile!!)
            val elmFormat = project.elmToolchain.elmFormatCLI
            elmFormat!!.formatDocumentAndSetText(project, editor.document, elmVersion!!, addToUndoStack = false)
        } catch (e: Throwable) {
        }
    }
}

