package org.elm.ide.intentions

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import org.elm.lang.core.psi.*
import org.elm.lang.core.psi.elements.*
import org.elm.utils.getIndent

/**
 * This intention is built to easily allow users to add log statements on values.
 * It is currently restricted from working fully with pipelines and function composition.
 * For pipelined operations, you can only log the complete statement (so logging the final output, in other words),
 * and function composition is ignored entirely
 * (since a composition produces a function, which can't be sensibly logged).
 *
 * Ideally, we would support inserting Debug.log statements inside a pipeline/composition,
 * to allow users to print the data getting piped along.
 */
class InlineDebugIntention : ElmAtCaretIntentionActionBase<InlineDebugIntention.Context>() {
    data class Context(val valueToDebug: ElmPsiElement)

    override fun getFamilyName() = text

    private sealed class MessageContext {
        object DebugValue : MessageContext()
        object DebugFunctionCall : MessageContext()
        object DebugParentFunctionCall : MessageContext()
        object DebugBinaryOpt : MessageContext()
        object DebugPipeline : MessageContext()
        object DebugCase : MessageContext()
        object DebugExpression : MessageContext()
    }

    override fun findApplicableContext(project: Project, editor: Editor, element: PsiElement): Context? {
        val contexts = getContext(project, editor, element)

        if (contexts != null) {
            val firstPartOfDescription = "Debug.log "
            val (context, messageContext) = contexts
            when (messageContext) {
                is MessageContext.DebugValue -> {
                    super.setText(firstPartOfDescription + "this value")
                }
                is MessageContext.DebugFunctionCall -> {
                    super.setText(firstPartOfDescription + "output of function")
                }
                is MessageContext.DebugParentFunctionCall -> {
                    super.setText(firstPartOfDescription + "output of parent function")
                }
                is MessageContext.DebugBinaryOpt -> {
                    super.setText(firstPartOfDescription + "expression")
                }
                MessageContext.DebugPipeline -> {
                    super.setText(firstPartOfDescription + "output of pipeline")
                }
                is MessageContext.DebugCase -> {
                    super.setText(firstPartOfDescription + "output of case statement")
                }
                is MessageContext.DebugExpression -> {
                    super.setText(firstPartOfDescription + "expression")
                }
            }

            return context
        } else {
            return null
        }
    }


    private fun getContext(project: Project, editor: Editor, element: PsiElement): Pair<Context, MessageContext>? {
        element.ancestors.forEach { currentExpr ->
            when (currentExpr) {
                is ElmConstantTag -> {
                    // A constant is unlikely to be interesting to debug.
                    // It is more likely the context the constant is used in, that is interesting.
                    currentExpr.parentOfType<ElmBinOpExpr>()
                            ?.let { parentBinOpExpr -> return Pair(Context(parentBinOpExpr), MessageContext.DebugBinaryOpt) }
                    return null
                }
                is ElmParenthesizedExpr -> {
                    val parentCallExpr = element.parentOfType<ElmFunctionCallExpr>()

                    return if (parentCallExpr != null && isDebugCall(parentCallExpr) && parentCallExpr.arguments.contains(currentExpr)) {
                        null
                    } else {
                        Pair(Context(currentExpr), MessageContext.DebugExpression)
                    }
                }
                is ElmValueExpr -> {
                    val parentCallExpr = element.parentOfType<ElmFunctionCallExpr>()

                    // Avoid showing the intention when a value is already used for debugging.
                    // It is very likely the intention would just be noise for the user in this case.
                    if (parentCallExpr != null) {
                        if (isDebugCall(parentCallExpr) && (parentCallExpr.target == currentExpr || parentCallExpr.argumentsWithoutParens.contains(currentExpr))) {
                            return null
                        }
                        val grandParentCallExpr = parentCallExpr.parentOfType<ElmFunctionCallExpr>()

                        if (grandParentCallExpr != null &&
                                isDebugCall(grandParentCallExpr) &&
                                grandParentCallExpr.argumentsWithoutParens.any { arg -> arg is ElmFunctionCallExpr && arg.target == currentExpr }) {
                            return null
                        } else if (parentCallExpr.target == currentExpr) {
                            // If the intention is invoked on a non-debug function, it is likely the output of that function,
                            // that the user is interested in.
                            return Pair(Context(parentCallExpr), MessageContext.DebugFunctionCall)
                        }
                    }

                    val binOpParent = currentExpr.parentOfType<ElmBinOpExpr>()
                    return if (binOpParent != null && binOpParent.parts.any { it == currentExpr }) {
                        when {
                            // Function composition produces new functions, which do not show any meaningful data when logged.
                            // A separate intention is intended to allow users to insert log statements inside the composition.
                            isFunComposition(binOpParent) -> {
                                null
                            }
                            // A separate intention is intended to allow users to insert log statements inside the pipeline.
                            binOpParent.asPipeline() != null -> {
                                Pair(Context(binOpParent), MessageContext.DebugPipeline)
                            }
                            else -> {
                                Pair(Context(currentExpr), MessageContext.DebugValue)
                            }
                        }
                    } else {
                        Pair(Context(currentExpr), MessageContext.DebugValue)
                    }
                }
                is ElmCaseOfExpr -> {
                    return Pair(Context(currentExpr), MessageContext.DebugCase)
                }
                is ElmAtomTag -> {
                    return Pair(Context(currentExpr), MessageContext.DebugValue)
                }
                is ElmBinOpExpr -> {
                    return when {
                        isFunComposition(currentExpr) -> {
                            null
                        }
                        currentExpr.asPipeline() != null -> {
                            Pair(Context(currentExpr), MessageContext.DebugPipeline)
                        }
                        else -> {
                            Pair(Context(currentExpr), MessageContext.DebugBinaryOpt)
                        }
                    }
                }
            }
        }

        return null
    }


    private fun isDebugCall(call: ElmFunctionCallExpr): Boolean {
        val text = call.target.text
        return text == "Debug.log" || text == "Debug.todo"
    }

    private fun isFunComposition(opExpr: ElmBinOpExpr): Boolean =
            opExpr.parts
                    .filterIsInstance<ElmOperator>()
                    .map { it.referenceName }
                    .any { it == "<<" || it == ">>" }


    override fun invoke(project: Project, editor: Editor, context: Context) {
        val factory = ElmPsiFactory(project)
        val valueExpr = context.valueToDebug
        val itemsText = valueExpr.text
        val textLines = itemsText.lines()

        val debuggedText =
                if (textLines.size > 1) {
                    val baseIndent = editor.getIndent(valueExpr.startOffset)
                    val indentation = "    "
                    val indentedLines = textLines.mapIndexed { index, line -> baseIndent + indentation.repeat(index) + line }
                    """Debug.log
$baseIndent    "${textLines.first().escapeDoubleQuotes()} ..."
$baseIndent    (
$baseIndent    ${indentedLines.joinToString("\n")}
$baseIndent    )"""
                } else {
                    """Debug.log "${itemsText.escapeDoubleQuotes()}" ($itemsText)"""
                }

        valueExpr.replace(factory.createParens(debuggedText))
    }
}

private fun String.escapeDoubleQuotes(): String = replace("\"", "\\\"")
