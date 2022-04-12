package org.elm.ide.inspections

import com.intellij.psi.PsiDocumentManager
import org.elm.lang.core.lookup.ElmLookup
import org.elm.lang.core.psi.ElmPsiFactory
import org.elm.lang.core.psi.elements.*
import org.elm.lang.core.psi.indentStyle
import org.elm.lang.core.psi.oneLevelOfIndentation
import org.elm.lang.core.psi.startOffset
import org.elm.lang.core.resolve.scope.ModuleScope
import org.elm.lang.core.types.*
import org.elm.utils.getIndent

/**
 * This class can detect missing branches for case expressions and insert them into the PSI in place.
 */
class MissingCaseBranchAdder(val element: ElmCaseOfExpr) {
    sealed class Result {
        /** There are missing union variant branches */
        data class MissingVariants(val variants: VariantParameters) : Result() {
            init {
                require(variants.isNotEmpty()) { "MissingVariants may not be empty" }
            }
        }

        /** There are missing branches, but we can't offer suggestions */
        object NoSuggestions : Result()

        /** There are no missing branches that we can detect */
        object NoMissing : Result()
    }

    private val document = element.containingFile
            ?.let { PsiDocumentManager.getInstance(element.project).getDocument(it) }

    val result: Result by lazy { if (document == null) Result.NoMissing else calcMissingBranches() }

    fun addMissingBranches() {
        val result = this.result as? Result.MissingVariants ?: return

        val patterns = result.variants.map { (name, params) ->
            (listOf(name) + params.map { it.renderParam() }).joinToString(" ")
        }

        addPatterns(patterns)
    }

    fun addWildcardBranch() {
        if (result is Result.NoMissing) return
        addPatterns(listOf("_"))
    }

    private fun addPatterns(patterns: List<String>) {
        val indent = element.indentStyle.oneLevelOfIndentation
        val factory = ElmPsiFactory(element.project)
        val existingBranches = element.branches
        val existingIndent = document!!.getIndent(element.startOffset)
        val elements = factory.createCaseOfBranches(existingIndent, indent, patterns)
        // Add the two or first generated branch, which are the indent
        // and newline
        var start = elements.first().prevSibling.prevSibling

        // Add the virtual open token if there isn't one already
        if (existingBranches.isNotEmpty() && !element.text.endsWith('\n')) {
            start = start.prevSibling
        }

        element.addRange(start, elements.last())

        val parent = element.parent

        // Without a value in the last branch, the virtual end token ends up before the trailing
        // whitespace, so we can't grab it from the factory element and need to add it separately.
        if (parent is ElmParenthesizedExpr) {
            val trailingWs = factory.createElements("\n$indent$indent")
            element.addRange(trailingWs.first(), trailingWs.last())

            parent.replace(factory.createParens(element.text, existingIndent))
        } else {
            val trailingWs = factory.createElements("\n$existingIndent$indent$indent")
            element.addRange(trailingWs.first(), trailingWs.last())
        }
    }

    private fun calcMissingBranches(): Result {
        val defaultResult = if (element.branches.isEmpty()) Result.NoSuggestions else Result.NoMissing

        val inference = element.findInference() ?: return defaultResult

        // This only works on case expressions with a union type for now
        val exprTy = element.expression?.let { inference.elementType(it) } as? TyUnion
                ?: return defaultResult

        val declaration: ElmTypeDeclaration = ElmLookup.findFirstByNameAndModule(exprTy.name, exprTy.module, element.elmFile)
                ?: return defaultResult

        val allBranches = declaration.variantInference().value
        val missingBranches = allBranches.toMutableMap()

        for (branch in element.branches) {
            when (val pat = branch.pattern.child) {
                is ElmAnythingPattern -> return Result.NoMissing // covers all cases
                is ElmUnionPattern -> {
                    missingBranches.remove(pat.referenceName)
                }
                else -> return Result.NoMissing // invalid pattern
            }
        }

        if (missingBranches.isEmpty()) {
            return Result.NoMissing
        }

        val qualifierPrefix = ModuleScope.getQualifierForName(element.elmFile, exprTy.module, exprTy.name)
                ?: ""

        return Result.MissingVariants(missingBranches.mapKeys { (k, _) -> qualifierPrefix + k })
    }
}
