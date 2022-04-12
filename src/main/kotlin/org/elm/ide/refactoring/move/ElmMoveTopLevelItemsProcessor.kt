package org.elm.ide.refactoring.move

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.refactoring.BaseRefactoringProcessor
import com.intellij.refactoring.move.MoveMultipleElementsViewDescriptor
import com.intellij.usageView.UsageInfo
import com.intellij.usageView.UsageViewDescriptor
import org.elm.lang.core.imports.ImportAdder
import org.elm.lang.core.psi.*
import org.elm.lang.core.psi.elements.*
import org.elm.lang.core.resolve.ElmReferenceElement

class ElmMoveTopLevelItemsProcessor(
    private val project: Project,
    private val itemsToMove: Array<out PsiElement>,
    private val targetMod: ElmFile,
    private val searchForReferences: Boolean
) : BaseRefactoringProcessor(project) {

    override fun createUsageViewDescriptor(usages: Array<out UsageInfo>): UsageViewDescriptor {
        return MoveMultipleElementsViewDescriptor(emptyArray(), "")
    }

    override fun findUsages(): Array<UsageInfo> {
        if (!searchForReferences) return UsageInfo.EMPTY_ARRAY
        return itemsToMove
            .mapNotNull {
                when (it) {
                    is ElmValueDeclaration -> it.functionDeclarationLeft
                    else -> it
                }
            }
            .flatMap { ReferencesSearch.search(it, GlobalSearchScope.projectScope(project)) }
            .filterNotNull()
            .map { UsageInfo(it) }
            .toTypedArray()
    }

    override fun performRefactoring(usages: Array<out UsageInfo>) {
        val factory = ElmPsiFactory(project)
        val newline = factory.createNewline()

        targetMod.add(newline)

        for (item in itemsToMove) {
            val space = item.nextSibling as? PsiWhiteSpace

            targetMod.add(newline)
            // have to call `copy` because of rare suspicious `PsiInvalidElementAccessException`
            targetMod.add(item.copy())
            if (space != null) targetMod.add(space.copy())

            modifyExposeList(item)
            modifyDependants(item, usages)
            modifyImports(item)

            space?.delete()
            item.delete()
        }

    }

    private fun modifyImports(item: PsiElement) {
        val qid: ElmUpperCaseQID = targetMod.getModuleDecl()?.upperCaseQID?: return

        item
            .descendantsOfType<ElmReferenceElement>()
            .forEach {
                val resolve: ElmNamedElement = it.reference.resolve()?: return
                val candidate = ImportAdder.Import(resolve.moduleName, null, resolve.name?: return)

                ImportAdder.addImport(candidate, targetMod, false)
            }
    }

    private fun modifyDependants(item: PsiElement, usages: Array<out UsageInfo>) {
        val qid: ElmUpperCaseQID = targetMod.getModuleDecl()?.upperCaseQID?: return
        usages
            .map { it.element to it.file }
            .filter { it.first is ElmExposedValue }
            .forEach {
                val value = it.first as ElmExposedValue
                val exposingList = value.parent as ElmExposingList
                val candidate = ImportAdder.Import(qid.fullName, null, value.lowerCaseIdentifier.text)

                ImportAdder.addImport(candidate, it.second as ElmFile, false)

                if (exposingList.allExposedItems.size == 1) {
                    exposingList.parent.delete()
                } else {
                    exposingList.removeItem(value)
                }
            }
    }

    private fun modifyExposeList(item: PsiElement) {
        val element: PsiElement = when (item) {
            is ElmValueDeclaration -> item.functionDeclarationLeft?.lowerCaseIdentifier
            is ElmTypeDeclaration -> item.nameIdentifier
            is ElmTypeAliasDeclaration -> item.upperCaseIdentifier
            else -> null
        } ?: return

        modifyTargetExposeList(element)
        modifySourceExposeList(element)
    }

    private fun modifyTargetExposeList(element: PsiElement) {
        val moduleDecl: ElmModuleDeclaration? = targetMod.getModuleDecl()
        val exposingList: ElmExposingList? = moduleDecl?.exposingList

        if (moduleDecl == null || moduleDecl.exposesAll || exposingList == null) return

        exposingList.addItem(element.text)
    }

    private fun modifySourceExposeList(element: PsiElement) {
        val file: ElmFile = element.containingFile as ElmFile
        val moduleDecl: ElmModuleDeclaration? = file.getModuleDecl()
        val exposingList: ElmExposingList? = moduleDecl?.exposingList

        if (moduleDecl == null || moduleDecl.exposesAll || exposingList == null) return

        exposingList.allExposedItems.find { it.text == element.text }?.let { exposingList.removeItem(it) }
    }

    private fun getImportClauseIndex(
        file: ElmFile,
        moduleDeclaration: ElmModuleDeclaration
    ) = file
        .getImportClauses()
        .map(ElmImportClause::referenceName)
        .indexOf(moduleDeclaration.moduleName)

    override fun getCommandName(): String {
        return "Move Items"
    }

}
