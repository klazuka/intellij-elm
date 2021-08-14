package org.elm.workspace.compiler

import com.intellij.execution.ExecutionException
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.util.messages.Topic
import org.elm.ide.notifications.showBalloon
import org.elm.lang.core.ElmFileType
import org.elm.lang.core.lookup.ClientLocation
import org.elm.lang.core.lookup.ElmLookup
import org.elm.lang.core.psi.ElmFile
import org.elm.lang.core.psi.elements.ElmFunctionDeclarationLeft
import org.elm.lang.core.types.TyUnion
import org.elm.lang.core.types.TyUnknown
import org.elm.lang.core.types.findTy
import org.elm.openapiext.isUnitTestMode
import org.elm.openapiext.pathAsPath
import org.elm.openapiext.saveAllDocuments
import org.elm.workspace.ElmApplicationProject
import org.elm.workspace.ElmPackageProject
import org.elm.workspace.ElmProject
import org.elm.workspace.elmToolchain
import org.elm.workspace.elmWorkspace
import java.nio.file.Path

val ELM_BUILD_ACTION_ID = "Elm.Build"

class ElmBuildAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        saveAllDocuments()
        val project = e.project ?: return

        val elmCLI = project.elmToolchain.elmCLI
                ?: return showError(project, "Please set the path to the 'elm' binary", includeFixAction = true)

        val activeFile = findActiveFile(e, project)
                ?: return showError(project, "Could not determine active Elm file")

        if (ElmFile.fromVirtualFile(activeFile, project)?.isInTestsDirectory == true)
            return showError(project, "To check tests for compile errors, use the elm-test run configuration instead.")

        val elmProject = project.elmWorkspace.findProjectForFile(activeFile)
                ?: return showError(project, "Could not determine active Elm project")

        val projectDir = VfsUtil.findFile(elmProject.projectDirPath, true)
                ?: return showError(project, "Could not determine active Elm project's path")

        val (filePathToCompile, targetPath, offset) = when (elmProject) {
            is ElmApplicationProject -> {
                val mainEntryPoint = findMainEntryPoint(project, elmProject)
                mainEntryPoint?.containingFile?.virtualFile
                        ?.let {
                            Triple(
                                    it.pathAsPath,
                                    VfsUtilCore.getRelativePath(it, projectDir),
                                    mainEntryPoint.textOffset
                            )
                        }
                        ?: return showError(project, "Cannot find your Elm app's main entry point. Please make sure that it has a type annotation.")
            }

            is ElmPackageProject ->
                Triple(
                        activeFile.pathAsPath,
                        VfsUtilCore.getRelativePath(activeFile, projectDir),
                        0
                )
        }

        val json = try {
            elmCLI.make(project, elmProject.projectDirPath, filePathToCompile, jsonReport = true).stderr
        } catch (e: ExecutionException) {
            return showError(project, "Failed to run the 'elm' executable. Is the path correct?", includeFixAction = true)
        }

        val messages = if (json.isEmpty()) emptyList() else {
            elmJsonToCompilerMessages(json).sortedWith(
                    compareBy(
                            { it.location?.moduleName },
                            { it.location?.region?.start?.line },
                            { it.location?.region?.start?.column }
                    ))
        }

        fun postErrors() = project.messageBus.syncPublisher(ERRORS_TOPIC)
                .update(elmProject.projectDirPath, messages, targetPath!!, offset)

        when {
            isUnitTestMode -> postErrors()
            else -> ToolWindowManager.getInstance(project).getToolWindow("Elm Compiler")?.show {
                postErrors()
            }
        }
    }

    private fun findMainEntryPoint(project: Project, elmProject: ElmProject): ElmFunctionDeclarationLeft? =
            ElmLookup.findByName<ElmFunctionDeclarationLeft>("main", LookupClientLocation(project, elmProject))
                    .find { decl ->
                        val key = when (val ty = decl.findTy()) {
                            is TyUnion -> ty.module to ty.name
                            is TyUnknown -> ty.alias?.let { it.module to it.name }
                            else -> null
                        }
                        key != null && key in elmMainTypes && decl.isTopLevel
                    }

    private fun findActiveFile(e: AnActionEvent, project: Project): VirtualFile? =
            e.getData(CommonDataKeys.VIRTUAL_FILE)
                    ?: FileEditorManager.getInstance(project).selectedFiles.firstOrNull { it.fileType is ElmFileType }

    private fun showError(project: Project, message: String, includeFixAction: Boolean = false) {
        val actions = if (includeFixAction)
            arrayOf("Fix" to { project.elmWorkspace.showConfigureToolchainUI() })
        else
            emptyArray()
        project.showBalloon(message, NotificationType.ERROR, *actions)
    }

    interface ElmErrorsListener {
        fun update(baseDirPath: Path, messages: List<ElmError>, targetPath: String, offset: Int)
    }

    companion object {
        val ERRORS_TOPIC = Topic("Elm compiler-messages", ElmErrorsListener::class.java)
        private val elmMainTypes = setOf(
                "Platform" to "Program",
                "Html" to "Html",
                "VirtualDom" to "Node"
        )
    }

    data class LookupClientLocation(
            override val intellijProject: Project,
            override val elmProject: ElmProject?,
            override val isInTestsDirectory: Boolean = false
    ) : ClientLocation
}
