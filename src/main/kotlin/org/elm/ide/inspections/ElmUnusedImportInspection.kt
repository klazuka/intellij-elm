package org.elm.ide.inspections

import com.intellij.codeInsight.actions.OptimizeImportsProcessor
import com.intellij.codeInspection.*
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import org.elm.lang.core.psi.ElmExposedItemTag
import org.elm.lang.core.psi.ElmFile
import org.elm.lang.core.psi.elements.ElmAsClause
import org.elm.lang.core.psi.elements.ElmImportClause
import org.elm.lang.core.psi.parentOfType
import org.elm.lang.core.resolve.ElmReferenceElement
import org.elm.lang.core.resolve.reference.LexicalValueReference
import org.elm.lang.core.resolve.reference.QualifiedReference
import org.elm.lang.core.resolve.scope.ModuleScope
import java.util.concurrent.ConcurrentHashMap

/**
 * Find unused imports
 */
class ElmUnusedImportInspection : LocalInspectionTool() {

    private val visitorKey = Key.create<ImportVisitor>("")

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean, session: LocalInspectionToolSession): PsiElementVisitor {
        val file = session.file as? ElmFile
                ?: return super.buildVisitor(holder, isOnTheFly, session)
        val visitor = ImportVisitor(ModuleScope.getImportDecls(file))
        session.putUserData(visitorKey, visitor)
        return visitor
    }

    override fun inspectionFinished(session: LocalInspectionToolSession, problemsHolder: ProblemsHolder) {
        val visitor = session.getUserData(visitorKey) ?: return

        for (import in visitor.unusedImports) {
            problemsHolder.markUnused(import, "Unused import")
        }

        for (item in visitor.unusedExposedItems) {
            problemsHolder.markUnused(item, "'${item.text}' is exposed but unused")
        }

        for (alias in visitor.unusedModuleAliases) {
            problemsHolder.markUnused(alias, "Unused alias")
        }
    }
}


private fun ProblemsHolder.markUnused(elem: PsiElement, message: String) {
    registerProblem(elem, message, ProblemHighlightType.LIKE_UNUSED_SYMBOL, OptimizeImportsFix())
}

private class OptimizeImportsFix : NamedQuickFix("Optimize imports") {
    override fun applyFix(element: PsiElement, project: Project) {
        val file = element.containingFile as? ElmFile ?: return
        OptimizeImportsProcessor(project, file).run()
    }
}

class ImportVisitor(initialImports: List<ElmImportClause>) : PsiElementVisitor() {

    // IMPORTANT! IntelliJ's LocalInspectionTool requires that visitor implementations be thread-safe.
    private val imports = initialImports.associateByTo(ConcurrentHashMap()) { it.moduleQID.text }
    private val exposing = initialImports.mapNotNull { it.exposingList }
            .flatMap { it.exposedValueList }
            .associateByTo(ConcurrentHashMap()) { it.text }
    private val moduleAliases = initialImports.mapNotNull { it.asClause }
            .associateByTo(ConcurrentHashMap()) { it.upperCaseIdentifier.text }

    /** Returns the list of unused imports. IMPORTANT: only valid *after* the visitor completes its traversal. */
    val unusedImports: List<ElmImportClause>
        get() = imports.values.filter { !it.safeToIgnore }

    /** Returns the list of unused exposed items. IMPORTANT: only valid *after* the visitor completes its traversal. */
    val unusedExposedItems: List<ElmExposedItemTag>
        get() = exposing.values.filter { notChildOfAlreadyReportedStatement(it) }

    /** Returns the list of unused module aliases. IMPORTANT: only valid *after* the visitor completes its traversal. */
    val unusedModuleAliases: List<ElmAsClause>
        get() = moduleAliases.values.filter { notChildOfAlreadyReportedStatement(it) }

    private fun notChildOfAlreadyReportedStatement(it: PsiElement) : Boolean {
        val import = it.parentOfType<ElmImportClause>() ?: return false
        return import !in unusedImports && !import.safeToIgnore
    }

    override fun visitElement(element: PsiElement) {
        super.visitElement(element)
        if (element is ElmReferenceElement && element !is ElmImportClause && element !is ElmExposedItemTag) {
            val reference = element.reference
            val resolved = reference.resolve() ?: return
            val resolvedModule = resolved.elmFile.getModuleDecl() ?: return
            val resolvedModuleName = resolvedModule.name
            imports.remove(resolvedModuleName)

            // For now we are just going to mark exposed values/functions which are unused
            // TODO expand this to types, union variant constructors, and operators
            if (reference is LexicalValueReference) {
                exposing.remove(element.referenceName)
            }

            if (reference is QualifiedReference) {
                moduleAliases.remove(reference.qualifierPrefix)
            }
        }
    }
}


// Elm's Kernel modules are defined in JS, and our PsiReference system does not (currently)
// cross the boundary from Elm to JS. So we must ignore any warnings for "kernel" imports.
private val ElmImportClause.safeToIgnore: Boolean
    get() = moduleQID.isKernelModule
