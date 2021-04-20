package org.elm.ide.refactoring

import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.refactoring.move.moveFilesOrDirectories.MoveFileHandler
import com.intellij.usageView.UsageInfo
import org.elm.lang.core.psi.ElmFile
import org.elm.lang.core.psi.ElmPsiFactory
import org.elm.lang.core.psi.descendantsOfType
import org.elm.lang.core.psi.elements.ElmImportClause
import org.elm.lang.core.psi.elements.ElmModuleDeclaration
import org.elm.lang.core.psi.elements.ElmTypeRef
import org.elm.lang.core.psi.elements.ElmValueExpr
import org.elm.openapiext.pathAsPath
import org.elm.workspace.ElmProject
import java.nio.file.Path

class ElmMoveFileHandler : MoveFileHandler() {

    override fun canProcessElement(element: PsiFile?): Boolean {
        return ((element as? ElmFile?) != null)
    }

    override fun prepareMovedFile(
        file: PsiFile?,
        moveDestination: PsiDirectory?,
        oldToNewMap: MutableMap<PsiElement, PsiElement>?
    ) {
        if (oldToNewMap == null) return

        val elmFile: ElmFile = file as? ElmFile?: return
        val moduleDecl: ElmModuleDeclaration = elmFile.getModuleDecl()?: return
        val project: ElmProject = elmFile.elmProject?: return
        val path: Path = moveDestination?.virtualFile?.pathAsPath?: return
        val relativePath =
            project.rootDirContaining(elmFile.virtualFile)?.relativize(path)?.joinToString(".")?: return

        val moduleName = if (relativePath != "") "$relativePath." else ""
        val elmPsiFactory = ElmPsiFactory(elmFile.project)
        val newModuleDeclaration: ElmModuleDeclaration = elmPsiFactory
            .createElements("module ${moduleName}${elmFile.virtualFile.nameWithoutExtension} exposing (..)")
            .first() as ElmModuleDeclaration

        oldToNewMap[moduleDecl.upperCaseQID] = newModuleDeclaration.upperCaseQID

        ReferencesSearch
            .search(moduleDecl)
            .findAll()
            .forEach {
                when (val element = it.element) {
                    is ElmImportClause -> oldToNewMap[element.moduleQID] = newModuleDeclaration.upperCaseQID
                    is ElmTypeRef ->
                        if (element.upperCaseQID.isQualified) {
                            elmPsiFactory.createTypeRef(
                                newModuleDeclaration.upperCaseQID.text,
                            newModuleDeclaration.upperCaseQID.text + '.' +  element.upperCaseQID.refName
                            )?.let { valueExpr ->
                                oldToNewMap[element.upperCaseQID] = valueExpr
                                valueExpr
                                    .descendantsOfType<ElmTypeRef>()
                                    .forEach { elem -> elem.reference.resolve() }
                            }
                        }
                    is ElmValueExpr -> {
                        val valueQID = element.valueQID
                        if (valueQID != null && valueQID.isQualified) {
                            elmPsiFactory.createTypeRef(
                                newModuleDeclaration.upperCaseQID.text,
                            newModuleDeclaration.upperCaseQID.text + '.' +  valueQID.lowerCaseIdentifier.text
                            )?.let { valueExpr -> oldToNewMap[valueQID] = valueExpr }
                        }
                    }
                }
            }
    }

    override fun findUsages(
        psiFile: PsiFile?,
        newParent: PsiDirectory?,
        searchInComments: Boolean,
        searchInNonElmFiles: Boolean
    ): MutableList<UsageInfo> {
        return emptyList<UsageInfo>().toMutableList()
    }

    override fun retargetUsages(usageInfos: MutableList<UsageInfo>?, oldToNewMap: MutableMap<PsiElement, PsiElement>?) {
            oldToNewMap?.forEach { (old, new) -> old.replace(new) }
    }

    override fun updateMovedFile(file: PsiFile?) {
    }

}
