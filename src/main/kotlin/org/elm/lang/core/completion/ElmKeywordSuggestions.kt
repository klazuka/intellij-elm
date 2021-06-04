package org.elm.lang.core.completion

import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.editor.EditorModificationUtil
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil
import org.elm.ide.icons.ElmIcons
import org.elm.lang.core.psi.ElmTypes.*
import org.elm.lang.core.psi.elementType
import org.elm.lang.core.psi.elements.*
import org.elm.lang.core.psi.prevLeaves
import org.elm.lang.core.psi.withoutErrors

/**
 * Provide code completion for Elm's keywords
 */
object ElmKeywordSuggestor : Suggestor {

    override fun addCompletions(parameters: CompletionParameters, result: CompletionResultSet) {
        val pos = parameters.position
        val parent = pos.parent
        val grandParent = pos.parent?.parent

        // Don't suggest keywords for record expression field names
        if (grandParent is ElmRecordExpr) return

        if (pos.elementType == LOWER_CASE_IDENTIFIER) {
            // NOTE TO SELF:
            // PsiTreeUtil.prevVisibleLeaf() skips over PsiErrorElement, which is good,
            //                               but also skips the VIRTUAL_END_DECL tokens
            // PsiElement#getPrevSibling() includes VIRTUAL_END_DECL tokens,
            //                             but does not skip over PsiErrorElement, which is bad
            // TODO [kl] find a less brittle way to express this. Maybe using IntelliJ's pattern DSL.
            val prevVisibleLeaf = PsiTreeUtil.prevVisibleLeaf(pos)

            if (prevVisibleLeaf == null) {
                // at the beginning of the file
                result.add("module")
            }

            if (prevVisibleLeaf == null || pos.prevSibling?.text == "\n") {
                // at the beginning of the file or beginning of a line
                result.add("type")
                result.add("import")
            }

            if (prevVisibleLeaf?.elementType == TYPE) {
                result.add("alias")
            }

            // Import declarations
            val beforeParentType = PsiTreeUtil.skipWhitespacesAndCommentsBackward(parent)?.elementType
            if (prevVisibleLeaf?.elementType == UPPER_CASE_IDENTIFIER && beforeParentType == IMPORT_CLAUSE) {
                result.add("as")
                result.add("exposing")
            }

            // Module declaration
            if (prevVisibleLeaf?.elementType == UPPER_CASE_IDENTIFIER && parent.elementType == MODULE_DECLARATION) {
                result.add("exposing")
            }

            // keywords that can appear at the beginning of an expression
            if (prevVisibleLeaf?.elementType in listOf(EQ, ARROW, IN, COMMA, LEFT_SQUARE_BRACKET, LEFT_PARENTHESIS)) {
                result.add("if")
                result.add("case")
                result.add("let")
            }

            // keywords within the context of an 'if' expression
            if (PsiTreeUtil.getParentOfType(pos, ElmIfElseExpr::class.java) != null) {
                result.add("then")
                result.add("else")
            }

            // the 'of' in a 'case' expression
            val caseOfExpr = PsiTreeUtil.getParentOfType(pos, ElmCaseOfExpr::class.java)
            if (caseOfExpr != null) {
                // boy this is ugly. I either need to level-up my PsiTreeUtil-fu or learn how to use PsiPattern.
                val functionCall = PsiTreeUtil.getParentOfType(pos, ElmFunctionCallExpr::class.java)
                if (functionCall?.parent == caseOfExpr && grandParent?.parent == functionCall) {
                    result.add("of")
                }
            }

            // the 'in' in a 'let' expression
            val letInExpr = PsiTreeUtil.getParentOfType(pos, ElmLetInExpr::class.java)
            if (letInExpr != null) {
                // Check to see if we are immediately after a VIRTUAL_END_(DECL|SECTION).
                val leaves = pos.prevLeaves.withoutErrors.filter { it !is PsiWhiteSpace && it !is PsiComment }
                if (leaves.firstOrNull()?.elementType in listOf(VIRTUAL_END_SECTION, VIRTUAL_END_DECL)) {
                    result.add("in")
                }
            }
        }
    }

    private fun CompletionResultSet.add(keyword: String) {
        var builder = LookupElementBuilder.create(keyword).withIcon(ElmIcons.KEYWORD)
        builder = addInsertionHandler(keyword, builder)
        addElement(builder)
    }

    private val ALWAYS_NEEDS_SPACE = setOf("type", "alias", "module", "import", "as", "if", "else", "case")


    private fun addInsertionHandler(keyword: String, item: LookupElementBuilder): LookupElementBuilder {
        when (keyword) {
            in ALWAYS_NEEDS_SPACE ->
                return item.withInsertHandler { ctx, _ -> ctx.addSuffix(" ") }

            "exposing" ->
                return item.withInsertHandler { ctx, _ -> ctx.addSuffix(" ()", moveCursorLeft = 1) }

            else ->
                return item
        }
    }


    private fun InsertionContext.addSuffix(suffix: String, moveCursorLeft: Int = 0) {
        document.insertString(selectionEndOffset, suffix)
        EditorModificationUtil.moveCaretRelatively(editor, suffix.length - moveCursorLeft)
    }
}
