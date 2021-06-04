package org.elm.ide.project;

import com.intellij.ide.util.projectWizard.AbstractNewProjectStep
import com.intellij.ide.util.projectWizard.CustomStepProjectGenerator
import com.intellij.ide.util.projectWizard.ProjectSettingsStepBase
import com.intellij.ide.util.projectWizard.WebProjectTemplate
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.startup.StartupManager
import com.intellij.openapi.util.io.FileUtil.join
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.impl.welcomeScreen.AbstractActionWithPanel
import com.intellij.platform.DirectoryProjectGenerator
import org.elm.ide.icons.ElmIcons
import org.elm.openapiext.runWriteCommandAction
import org.elm.workspace.DEFAULT_TESTS_DIR_NAME
import org.elm.workspace.asyncAutoDiscoverWorkspace
import org.intellij.lang.annotations.Language

private val log = logger<ElmWebProjectTemplate>()

class ElmWebProjectTemplate : WebProjectTemplate<Any>(), CustomStepProjectGenerator<Any> {

    override fun getIcon() = ElmIcons.LOGO
    override fun getName() = "Elm Application"
    override fun getDescription() = "Create a basic Elm application project"

    override fun createStep(
            projectGenerator: DirectoryProjectGenerator<Any>?,
            callback: AbstractNewProjectStep.AbstractCallback<Any>?
    ): AbstractActionWithPanel {
        return ProjectSettingsStepBase(this, callback)
    }

    // TODO [kl] in the future we may want to instead show UI (via `createPeer()` override)
    //           to allow the user to explicitly configure the Elm paths, etc.

    override fun generateProject(project: Project, baseDir: VirtualFile, settings: Any, module: Module) {
        log.debug("Generating a new project")
        StartupManager.getInstance(project).runWhenProjectIsInitialized {
            val rootModel = ModuleRootManager.getInstance(module).modifiableModel
            val contentEntry = rootModel.contentEntries.single()
            val root = contentEntry.file ?: return@runWhenProjectIsInitialized

            project.runWriteCommandAction {
                // Generate the project skeleton
                VfsUtil.saveText(root.createChildData(this, "elm.json"), elmJson)
                val srcDir = root.createChildDirectory(this, "src")
                VfsUtil.saveText(srcDir.createChildData(this, "Main.elm"), elmMain)

                // Mark the source roots, etc.
                with(contentEntry) {
                    addSourceFolder(join(root.url, "src"), /* test = */ false)
                    addSourceFolder(join(root.url, DEFAULT_TESTS_DIR_NAME), /* test = */ true)
                    addExcludeFolder(join(root.url, "elm-stuff"))
                }

                rootModel.commit()
                project.save()
            }

            // attempt to auto-configure the Elm toolchain and attach `elm.json`.
            asyncAutoDiscoverWorkspace(project, explicitRequest = true).whenCompleteAsync { _, _ ->
                // open the Elm source file that we just created
                val vfile = VfsUtil.findRelativeFile(root, "src", "Main.elm") ?: return@whenCompleteAsync
                project.runWriteCommandAction {
                    FileEditorManager.getInstance(project).openFile(vfile, /*focusEditor*/ true)
                }
            }
        }
    }
}

// As-of 2020-08-25, this is the standard `elm.json` file created by `elm init`.
@Language("JSON")
private val elmJson = """
    {
      "type": "application",
      "source-directories": [
        "src"
      ],
      "elm-version": "0.19.1",
      "dependencies": {
        "direct": {
          "elm/browser": "1.0.2",
          "elm/core": "1.0.5",
          "elm/html": "1.0.0"
        },
        "indirect": {
          "elm/json": "1.1.3",
          "elm/time": "1.0.0",
          "elm/url": "1.0.0",
          "elm/virtual-dom": "1.0.2"
        }
      },
      "test-dependencies": {
        "direct": {},
        "indirect": {}
      }
    }
    """.trimIndent()


/**
 * An empty Elm application to get started with.
 */
private val elmMain = """
    module Main exposing (main)

    import Html exposing (text)

    main = text "hi"
    """.trimIndent()
