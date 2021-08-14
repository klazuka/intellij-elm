package org.elm.ide.notifications

import com.intellij.execution.process.ProcessIOExecutorService
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotifications
import org.elm.lang.core.psi.isElmFile
import org.elm.openapiext.findFileByPath
import org.elm.workspace.*

sealed class VersionCheck {
    object NotChecked : VersionCheck()
    object Checking : VersionCheck()
    class Checked(val version: Version?) : VersionCheck()
}

private val log = logger<ElmNeedsConfigNotificationProvider>()

/**
 * Presents actionable notifications at the top of an Elm file whenever the Elm plugin
 * needs configuration (e.g. the path to the Elm compiler).
 */
class ElmNeedsConfigNotificationProvider(
        private val project: Project
) : EditorNotifications.Provider<EditorNotificationPanel>() {

    private val notifications = EditorNotifications.getInstance(project)

    private val lock = Any()
    private var versionCheck: VersionCheck = VersionCheck.NotChecked

    init {
        project.messageBus.connect(project).apply {
            subscribe(ElmWorkspaceService.WORKSPACE_TOPIC,
                    object : ElmWorkspaceService.ElmWorkspaceListener {
                        override fun didUpdate() {
                            log.debug("Workspace did change; invalidating cache and refreshing UI")
//                            synchronized(lock) {
//                                versionCheck = VersionCheck.NotChecked // Elm toolchain may have changed
//                            }
//                            notifications.updateAllNotifications()
                        }
                    })
        }
    }


    override fun getKey(): Key<EditorNotificationPanel> = PROVIDER_KEY


    override fun createNotificationPanel(file: VirtualFile, fileEditor: FileEditor): EditorNotificationPanel? {
        if (!file.isElmFile)
            return null

        val toolchain = project.elmToolchain
        if (!toolchain.looksLikeValidToolchain()) {
            return badToolchainPanel("You must specify a path to the Elm compiler")
        }

        val workspace = project.elmWorkspace
        if (!workspace.hasAtLeastOneValidProject()) {
            return noElmProjectPanel("No Elm projects found")
        }

        val elmProject = project.elmWorkspace.findProjectForFile(file)
                ?: return noElmProjectPanel("Could not find Elm project for this file")

        // Check that the toolchain path to the Elm binary corresponds to a version of the compiler
        // that is compatible with the Elm project. We have to do this async because this function
        // was called by IntelliJ while holding a Read Action. And we are forbidden from invoking
        // an external process while holding a Read Action.
        synchronized(lock) {
            when (val vc = versionCheck) {
                VersionCheck.NotChecked -> {
                    log.debug("Querying the version")
                    versionCheck = VersionCheck.Checking
                    asyncQueryElmCompilerVersion(toolchain)
                    return null
                }
                VersionCheck.Checking -> {
                    log.debug("Skipping version check")
                    return null
                }
                is VersionCheck.Checked -> {
                    log.debug("Using cached version ${vc.version}")
                    val compilerVersion = vc.version
                            ?: return badToolchainPanel("Could not determine Elm compiler version")

                    if (!elmProject.isCompatibleWith(compilerVersion)) {
                        return versionConflictPanel(project, elmProject, compilerVersion)
                    }

                    return null
                }
            }
        }
    }

    private fun asyncQueryElmCompilerVersion(toolchain: ElmToolchain) {
        ProcessIOExecutorService.INSTANCE.submit {
            val v = toolchain.elmCLI?.queryVersion()?.orNull()
            synchronized(lock) {
                versionCheck = VersionCheck.Checked(v)
            }
            // refresh the UI
            ApplicationManager.getApplication().invokeLater {
//                notifications.updateAllNotifications()
            }
        }
    }

    private fun badToolchainPanel(message: String) =
            EditorNotificationPanel().apply {
                setText(message)
                createActionLabel("Setup toolchain") {
                    project.elmWorkspace.showConfigureToolchainUI()
                }
            }


    private fun noElmProjectPanel(message: String) =
            EditorNotificationPanel().apply {
                setText(message)
                createActionLabel("Attach elm.json", "Elm.AttachElmProject")
            }


    private fun versionConflictPanel(project: Project, elmProject: ElmProject, compilerVersion: Version): EditorNotificationPanel {
        val expectedVersionText = when (elmProject) {
            is ElmApplicationProject -> elmProject.elmVersion.toString()
            is ElmPackageProject -> elmProject.elmVersion.toString()
        }
        val manifestFileName = elmProject.manifestPath.fileName.toString()
        return EditorNotificationPanel().apply {
            setText("Your $manifestFileName file requires Elm $expectedVersionText but your Elm compiler is $compilerVersion")
            createActionLabel("Open $manifestFileName") {
                val didNavigate = LocalFileSystem.getInstance().findFileByPath(elmProject.manifestPath)
                        ?.let { OpenFileDescriptor(project, it) }
                        ?.navigateInEditor(project, true)
                if (didNavigate != true)
                    project.showBalloon("Cannot open $manifestFileName", NotificationType.ERROR)
            }
            createActionLabel("Setup toolchain") {
                project.elmWorkspace.showConfigureToolchainUI()
            }
        }
    }


    companion object {
        private val PROVIDER_KEY: Key<EditorNotificationPanel> = Key.create("Setup Elm toolchain")
    }

}
