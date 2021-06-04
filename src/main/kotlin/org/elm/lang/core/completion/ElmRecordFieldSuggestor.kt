package org.elm.lang.core.completion

import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.lookup.LookupElementBuilder
import org.elm.ide.icons.ElmIcons
import org.elm.lang.core.psi.ELM_IDENTIFIERS
import org.elm.lang.core.psi.ElmFieldAccessTargetTag
import org.elm.lang.core.psi.elementType
import org.elm.lang.core.psi.elements.ElmFieldAccessExpr
import org.elm.lang.core.psi.elements.ElmValueExpr
import org.elm.lang.core.types.Ty
import org.elm.lang.core.types.TyRecord
import org.elm.lang.core.types.findTy
import org.elm.lang.core.types.renderedText

object ElmRecordFieldSuggestor : Suggestor {

    override fun addCompletions(parameters: CompletionParameters, result: CompletionResultSet) {
        val pos = parameters.position
        val parent = pos.parent

        if (pos.elementType in ELM_IDENTIFIERS && parent is ElmFieldAccessExpr) {
            // Infer the type of the record whose fields are being accessed
            // and suggest that record's fields as completion results.

            val targetExpr = parent.targetExpr
            val ty = targetExpr.findTy() as? TyRecord ?: resolveTarget(targetExpr) ?: return

            // provide each field as a completion result
            ty.fields.forEach { (fieldName, fieldTy) ->
                result.add(fieldName, fieldTy)
            }
        }
    }

    /**
     * In partial programs, a function body might not be inferred. But it's common to complete
     * fields on parameters, and in many cases the function's parameters are still inferred even if
     * the body isn't.
     */
    private fun resolveTarget(targetExpr: ElmFieldAccessTargetTag): TyRecord? {
        return when (targetExpr) {
            is ElmValueExpr -> {
                val ref = targetExpr.reference.resolve() ?: return null
                ref.findTy() as? TyRecord
            }
            is ElmFieldAccessExpr -> {
                val field = targetExpr.lowerCaseIdentifier.text
                val base = resolveTarget(targetExpr.targetExpr) ?: return null
                base.fields[field] as? TyRecord
            }
            else -> null
        }
    }
}

private fun CompletionResultSet.add(str: String, field: Ty) {
    addElement(LookupElementBuilder.create(str)
            .withIcon(ElmIcons.VALUE)
            .withTypeText(field.renderedText()))
}
