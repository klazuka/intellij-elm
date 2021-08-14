package org.elm.ide.refactoring

import com.intellij.lang.ImportOptimizer
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import org.elm.ide.inspections.ImportVisitor
import org.elm.lang.core.psi.ElmFile
import org.elm.lang.core.psi.elements.ElmImportClause
import org.elm.lang.core.psi.elements.removeItem
import org.elm.lang.core.psi.parentOfType
import org.elm.lang.core.psi.prevSiblings
import org.elm.lang.core.resolve.scope.ModuleScope

class ElmImportOptimizer: ImportOptimizer {

    override fun supports(file: PsiFile) =
            file is ElmFile

    override fun processFile(file: PsiFile): Runnable {
        if (file !is ElmFile) error("expected an Elm File!")

        // Pre-compute the unused elements prior to performing the
        // actual edits in the Runnable on the UI thread.
        val visitor = ImportVisitor(ModuleScope.getImportDecls(file))
        file.accept(object : PsiRecursiveElementWalkingVisitor() {
            override fun visitElement(element: PsiElement) {
                element.accept(visitor)
                super.visitElement(element)
            }
        })

        return Runnable {
            val documentManager = PsiDocumentManager.getInstance(file.project)
            val document = documentManager.getDocument(file)
            if (document != null) {
                documentManager.commitDocument(document)
            }
            execute(visitor)
        }
    }

    private fun execute(visitor: ImportVisitor) {
        for (unusedImport in visitor.unusedImports) {
            val prevNewline = unusedImport.prevSiblings.firstOrNull { it.textContains('\n') }
            if (prevNewline == null) unusedImport.delete()
            else unusedImport.parent.deleteChildRange(prevNewline, unusedImport)
        }

        for (item in visitor.unusedExposedItems) {
            val exposingList = item.parentOfType<ElmImportClause>()?.exposingList ?: continue
            if (exposingList.allExposedItems.size <= 1) exposingList.delete()
            else exposingList.removeItem(item)
        }

        for (alias in visitor.unusedModuleAliases) {
            val parent = alias.parentOfType<ElmImportClause>() ?: continue
            // Delete the alias and the preceding whitespace
            parent.deleteChildRange(parent.moduleQID.nextSibling, alias)
        }
    }
}
