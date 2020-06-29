package org.elm.ide.intentions

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import org.elm.lang.core.psi.*
import org.elm.lang.core.psi.elements.*

/**
 * An intention action that adds a function/type to a module's `exposing` list.
 */
class PipelineIntention : ElmAtCaretIntentionActionBase<PipelineIntention.Context>() {

    abstract class Context()

    data class NoPipes(val functionCall: ElmFunctionCallExpr) : Context()

    data class HasRightPipes(val functionCall: ElmFunctionCallExpr, val target: ElmFunctionCallTargetTag, val arguments : Sequence<PsiElement>) : Context()

    override fun getText() = "Pipeline"
    override fun getFamilyName() = text

    override fun findApplicableContext(project: Project, editor: Editor, element: PsiElement): Context? {
        return when (val functionCall = element.ancestors.filterIsInstance<ElmFunctionCallExpr>().firstOrNull()) {
            is ElmFunctionCallExpr -> {
                if (functionCall.prevSiblings.withoutWsOrComments.toList().size >= 2) {

                    val (prev1, argument) = functionCall.prevSiblings.withoutWsOrComments.toList()
                    if (prev1 is ElmOperator && prev1.referenceName.equals("|>")) {
                        HasRightPipes(functionCall, functionCall.target as ElmFunctionCallTargetTag, functionCall.arguments.plus(argument))
                    } else {
                        NoPipes(functionCall)
                    }
                } else {
                    NoPipes(functionCall)
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
                is NoPipes -> {
                    if (context.functionCall.descendantsOfType<ElmFunctionCallExpr>().isEmpty()) {
                        val last = context.functionCall.arguments.last()
                        last.delete()
                        val thing = ElmPsiFactory(project).createPipe(last.text, context.functionCall.text)
                        context.functionCall.replace(thing)
                    } else {
                        val nestedFunctionCall = context.functionCall.arguments.last().children.first() as ElmFunctionCallExpr
                        val funcCallChildren = nestedFunctionCall.arguments.first().children

                        if (funcCallChildren.size == 1 && funcCallChildren[0] is ElmFunctionCallExpr) {
                            val thing2 = funcCallChildren[0] as ElmFunctionCallExpr
                            thing2.arguments.first().text
                            thing2.target.text
                            val newThing = nestedFunctionCall.target.text

                            val topLevelFunctionTarget = context.functionCall.target.text
                            val thing = ElmPsiFactory(project).createPipeChain(arrayOf(thing2.arguments.first().text, thing2.target.text, newThing, topLevelFunctionTarget))
                            context.functionCall.replace(thing)
                        } else {
                            val initialValue = nestedFunctionCall.arguments.first().text
                            val chain1 = nestedFunctionCall.target.text
                            val topLevelFunctionTarget = context.functionCall.target.text
                            val thing = ElmPsiFactory(project).createPipeChain(arrayOf(initialValue, chain1, topLevelFunctionTarget))
                            context.functionCall.replace(thing)
                        }
                    }
                }

                is HasRightPipes -> {
                    val functionCallWithNoPipes = ElmPsiFactory(project)
                            .createParens(sequenceOf(context.functionCall.target).plus(context.arguments)
                            .map { it.text }.joinToString(separator = " "))
                    context.functionCall.parent.replace(functionCallWithNoPipes)
                }

            }
        }
    }
}
