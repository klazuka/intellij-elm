package org.elm.lang.core.completion

import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.lookup.LookupElementBuilder
import org.elm.ide.icons.ElmIcons
import org.elm.lang.core.psi.elements.ElmField
import org.elm.lang.core.psi.elements.ElmRecordExpr
import org.elm.lang.core.psi.outermostDeclaration
import org.elm.lang.core.types.Ty
import org.elm.lang.core.types.findInference
import org.elm.lang.core.types.renderedText

object ElmRecordExprSuggestor : Suggestor {
    override fun addCompletions(parameters: CompletionParameters, result: CompletionResultSet) {
        val pos = parameters.position
        val field = pos.parent as? ElmField ?: return
        val record = field.parent as? ElmRecordExpr ?: return
        val diff = record.findInference()?.recordDiffs?.get(record) ?: return

        // HACK: for some reason, subsequent completions can return stale results if you edit a
        // record after triggering completion. This forces the results to update. Note that even
        // without this, type errors and hints update as expected, which means the inference _is_
        // being invalidated correctly. The stale completion results probably have something to do
        // with the fact that completion runs on a shadow copy of the actual file.
        record.outermostDeclaration(strict = true)?.modificationTracker?.incModificationCount()

        val needsEquals = field.lowerCaseIdentifier.text.endsWith(EMPTY_RECORD_FIELD_DUMMY_IDENTIFIER)

        for ((f, t) in diff.missing) {
            result.add(needsEquals, f, t)
        }
    }
}

private fun CompletionResultSet.add(needsEquals: Boolean, str: String, field: Ty) {
    addElement(LookupElementBuilder.create(str)
            .withIcon(ElmIcons.VALUE)
            .withTypeText(field.renderedText())
            .withInsertHandler { context, _ ->
                if (needsEquals) {
                    val tailOffset = context.tailOffset
                    context.document.insertString(tailOffset, " = ")
                    context.editor.caretModel.moveToOffset(tailOffset + 3)
                }
            })
}
