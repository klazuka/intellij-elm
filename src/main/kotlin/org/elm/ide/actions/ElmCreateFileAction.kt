package org.elm.ide.actions

import com.intellij.ide.actions.CreateFileFromTemplateAction
import com.intellij.ide.actions.CreateFileFromTemplateDialog
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiFile
import org.elm.lang.core.ElmFileType
import org.elm.openapiext.pathAsPath
import org.elm.workspace.ElmProject
import org.elm.workspace.elmWorkspace
import java.nio.file.Path

class ElmCreateFileAction : CreateFileFromTemplateAction(CAPTION, "", ElmFileType.icon) {

    private val log = logger<ElmCreateFileAction>()

    override fun getActionName(directory: PsiDirectory?, newName: String, templateName: String?): String =
            CAPTION

    override fun buildDialog(project: Project, directory: PsiDirectory, builder: CreateFileFromTemplateDialog.Builder) {
        // TODO add additional "kinds" here (e.g. an `elm-test` skeleton module)
        builder.setTitle(CAPTION)
                .addKind("Module", ElmFileType.icon, ELM_MODULE_KIND)
    }

    override fun createFile(name: String?, templateName: String, dir: PsiDirectory): PsiFile? {
        if (name == null) return null
        val cleanedName = name.removeSuffix(".elm")
        val newFile = super.createFile(cleanedName, templateName, dir) ?: return null

        if (templateName == ELM_MODULE_KIND) {
            // HACK: I use an empty template to generate the file and then fill in the contents here
            // TODO ask around to find out what's the right way to do this
            newFile.viewProvider.document?.setText("module ${qualifyModuleName(dir, cleanedName)} exposing (..)")
        }

        return newFile
    }

    private fun qualifyModuleName(dir: PsiDirectory, name: String): String {
        log.debug("trying to determine fully-qualified module name prefix based on parent dir")
        val elmProject = dir.project.elmWorkspace.findProjectForFile(dir.virtualFile)
        if (elmProject == null) log.warn("failed to determine the Elm project that owns the dir")
        var rootDirPath = elmProject?.rootDirContaining(dir)
        if (rootDirPath == null) {
            log.warn("failed to determine root dir within the Elm project that owns the dir")
            rootDirPath = dir.virtualFile.pathAsPath
        }
        val qualifier = rootDirPath.relativize(dir.virtualFile.pathAsPath).joinToString(".")
        log.debug("using qualifier: '$qualifier'")
        return if (qualifier == "") name else "$qualifier.$name"
    }

    /** A helper utility intended only to be called from test code */
    fun testHelperCreateFile(name: String, dir: PsiDirectory): PsiFile? =
            createFile(name, ELM_MODULE_KIND, dir)

    override fun isAvailable(dataContext: DataContext): Boolean {
        if (!super.isAvailable(dataContext)) return false

        val file = CommonDataKeys.VIRTUAL_FILE.getData(dataContext) ?: return false
        val project = PlatformDataKeys.PROJECT.getData(dataContext)!!
        return project.elmWorkspace.findProjectForFile(file) != null
    }

    private companion object {
        private const val CAPTION = "Elm Module"
        private const val ELM_MODULE_KIND = "Elm Module" // must match name of internal template stored in JAR resources
    }
}

private fun ElmProject.rootDirContaining(dir: PsiDirectory): Path? {
    val dirPath = dir.virtualFile.pathAsPath
    return (absoluteSourceDirectories + listOf(testsDirPath))
            .find { dirPath.startsWith(it) }
}
