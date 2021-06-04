package org.elm.lang.core.completion

import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.psi.PsiReference
import com.intellij.util.ProcessingContext

interface Suggestor {
    fun addCompletions(parameters: CompletionParameters, result: CompletionResultSet)
}

/**
 * Most completions are provided by implementing [PsiReference.getVariants],
 * but there are some things that cannot be expressed that way (keywords)
 * or are difficult to express (qualified names).
 *
 * This class supplements the completions provided by the reference system.
 */
class ElmCompletionProvider : CompletionProvider<CompletionParameters>() {

    val suggestors = listOf(ElmQualifiableRefSuggestor, ElmRecordFieldSuggestor, ElmRecordExprSuggestor, ElmKeywordSuggestor)

    override fun addCompletions(parameters: CompletionParameters, context: ProcessingContext, result: CompletionResultSet) {
        suggestors.forEach { it.addCompletions(parameters, result.caseInsensitive()) }
    }
}
