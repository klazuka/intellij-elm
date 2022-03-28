package org.elm.ide.inspections

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemHighlightType.LIKE_UNKNOWN_SYMBOL
import com.intellij.codeInspection.ProblemHighlightType.WEAK_WARNING
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import org.elm.ide.inspections.import.AddImportFix
import org.elm.ide.inspections.import.AddQualifierFix
import org.elm.ide.inspections.import.MakeDeclarationFix
import org.elm.lang.core.psi.ElmFile
import org.elm.lang.core.psi.ElmPsiElement
import org.elm.lang.core.psi.elements.ElmImportClause
import org.elm.lang.core.psi.elements.ElmTypeAnnotation
import org.elm.lang.core.psi.elements.ElmTypeRef
import org.elm.lang.core.psi.elements.ElmValueExpr
import org.elm.lang.core.resolve.reference.*
import org.elm.lang.core.resolve.scope.GlobalScope
import org.elm.lang.core.resolve.scope.ImportScope
import org.elm.lang.core.resolve.scope.ModuleScope


class ElmUnresolvedReferenceInspection : ElmLocalInspection() {

    override fun visitElement(element: ElmPsiElement, holder: ProblemsHolder, isOnTheFly: Boolean) {
        val refs = element.references.toMutableList()

        // Pre-processing: ignore any qualified value/type refs where the module qualifier could not be resolved.
        // This is necessary because a single Psi element like ElmValueExpr can return multiple references:
        // one for the module name and the other for the value/type name. If the former reference cannot be resolved,
        // then the latter is guaranteed not to resolve. And we don't want to double-report the error, so we will
        // instead filter them out.
        if (refs.any { it is ModuleNameQualifierReference<*> && it.resolve() == null }) {
            refs.removeIf { it is QualifiedReference }
        }

        for (ref in refs.filter { it.resolve() == null }) {
            // Give each handler a chance to deal with the unresolved ref before falling back on an error
            if (handleTypeAnnotation(ref, element, holder)) continue
            if (handleSafeToIgnore(ref, element, holder)) continue
            if (handleModuleHiddenByAlias(ref, element, holder)) continue

            // Generic unresolved ref error
            //
            // Most of the time an ElmReferenceElement is not the ancestor of any other ElmReferenceElement.
            // And in these cases, it's ok to treat the error as spanning the entire reference element.
            // However, in cases like ElmTypeRef, its children can also be reference elements,
            // and so it is vital that we correctly mark the error only on the text range that
            // contributed the reference.
            val errorRange = (element as? ElmTypeRef)?.upperCaseQID?.textRangeInParent

            val fixes = mutableListOf<LocalQuickFix>()
            val qualifierContext = AddQualifierFix.findApplicableContext(element)
            val importContext = AddImportFix.findApplicableContext(element)

            if (importContext != null) {
                val t = importContext.candidates[0]
                fixes += NamedQuickFixHint(
                        element = element,
                        delegate = AddImportFix(),
                        hint = "${t.moduleName}.${t.nameToBeExposed}",
                        multiple = importContext.candidates.size > 1
                )
            }
            if (qualifierContext != null) {
                fixes += AddQualifierFix()
            }
            fixes += MakeDeclarationFromUsageFix()
            val description = "Unresolved reference '${ref.canonicalText}'"
            holder.registerProblem(element, description, LIKE_UNKNOWN_SYMBOL, errorRange, *fixes.toTypedArray())
        }
    }

    private fun handleTypeAnnotation(ref: PsiReference, element: PsiElement, holder: ProblemsHolder): Boolean {
        if (element !is ElmTypeAnnotation) return false

        val description = "'${ref.canonicalText}' does not exist"
        val fixes = when {
            MakeDeclarationFix(element).isAvailable -> arrayOf(MakeDeclarationFix(element))
            else -> emptyArray()
        }
        holder.registerProblem(element, description, WEAK_WARNING, *fixes)

        return true
    }

    private fun handleSafeToIgnore(ref: PsiReference, element: PsiElement, @Suppress("UNUSED_PARAMETER") holder: ProblemsHolder): Boolean {
        // Ignore refs to Kernel (JavaScript) modules
        when {
            element is ElmValueExpr && element.qid.isKernelModule -> return true
            element is ElmImportClause && element.moduleQID.isKernelModule -> return true
        }

        // Ignore soft refs
        if (ref.isSoft) {
            return true
        }

        // Ignore refs to built-in types and values
        if (GlobalScope.allBuiltInSymbols.contains(ref.canonicalText)) {
            return true
        }

        return false
    }

    // When a module is imported using an alias (e.g. `import Json.Decode as D`),
    // Elm prohibits the use of the original module name in qualified references.
    // So we will try to detect this condition and present a helpful error.
    private fun handleModuleHiddenByAlias(ref: PsiReference, element: PsiElement, holder: ProblemsHolder): Boolean {
        if (ref !is QualifiedReference) return false
        if (element !is ElmValueExpr && element !is ElmTypeRef) return false
        val elmFile = element.containingFile as? ElmFile ?: return false

        val importDecl = ModuleScope.getImportDecls(elmFile).find { it.moduleQID.text == ref.qualifierPrefix }
                ?: return false
        val aliasName = importDecl.asClause?.upperCaseIdentifier?.text ?: return false

        val importScope = ImportScope.fromImportDecl(importDecl) ?: return false

        val exposedNames = when (ref) {
            is QualifiedValueReference -> importScope.getExposedValues()
            is QualifiedConstructorReference -> importScope.getExposedConstructors()
            is QualifiedTypeReference -> importScope.getExposedTypes()
            else -> error("Unexpected qualified ref type: $ref")
        }

        if (exposedNames.elements.none { it.name == ref.nameWithoutQualifier })
            return false

        // Success! The reference would have succeeded were it not for the alias.
        val description = "Unresolved reference '${ref.nameWithoutQualifier}'. " +
                "Module '${ref.qualifierPrefix}' is imported as '$aliasName' and so you must use the alias here."
        holder.registerProblem(element, description, LIKE_UNKNOWN_SYMBOL)
        return true
    }
}
