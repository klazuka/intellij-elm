package org.elm.ide.toolwindow

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.AutoScrollToSourceHandler
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import org.elm.ide.icons.ElmIcons
import org.elm.openapiext.checkIsEventDispatchThread
import org.elm.openapiext.findFileByPath
import org.elm.workspace.ElmDetachProjectAction
import org.elm.workspace.ElmProject
import org.elm.workspace.ElmWorkspaceService
import org.elm.workspace.elmWorkspace
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.ListSelectionModel


/**
 * A panel within the Elm tool window which shows the contents of the workspace as well
 * as various actions for modifying the workspace.
 *
 * @see ElmWorkspaceService
 */
class ElmWorkspacePanel(private val project: Project) : SimpleToolWindowPanel(true, false) {


    private var elmProjects = emptyList<ElmProject>()
        set(value) {
            checkIsEventDispatchThread()
            field = value.sortedBy { it.manifestPath }
            projectListUI.setListData(field.toTypedArray())
        }


    private val projectListUI = JBList<ElmProject>(emptyList()).apply {
        emptyText.text = "No Elm projects have been attached yet"
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = object : ColoredListCellRenderer<ElmProject>() {
            override fun customizeCellRenderer(list: JList<out ElmProject>, value: ElmProject, index: Int,
                                               selected: Boolean, hasFocus: Boolean) {
                icon = ElmIcons.LOGO
                val attributes = SimpleTextAttributes.REGULAR_ATTRIBUTES
                append(value.manifestPath.toString(), attributes)
            }
        }
        object : AutoScrollToSourceHandler() {
            override fun isAutoScrollMode() = true
            override fun setAutoScrollMode(state: Boolean) {}
        }.install(this)
    }


    private val selectedProject: ElmProject?
        get() = projectListUI.selectedValue // JList can return null, but it's not annotated


    init {
        setToolbar(createToolbar())
        setContent(ScrollPaneFactory.createScrollPane(projectListUI, 0))

        // populate the initial workspace state
        ApplicationManager.getApplication().invokeLater {
            elmProjects = project.elmWorkspace.allProjects
        }

        // observe changes to the workspace
        with(project.messageBus.connect()) {
            subscribe(ElmWorkspaceService.WORKSPACE_TOPIC, object : ElmWorkspaceService.ElmWorkspaceListener {
                override fun didUpdate() {
                    ApplicationManager.getApplication().invokeLater {
                        elmProjects = project.elmWorkspace.allProjects
                    }
                }
            })
        }
    }


    private fun createToolbar(): JComponent {
        val toolbar = with(ActionManager.getInstance()) {
            createActionToolbar(
                    "Elm Toolbar", // the value here doesn't matter, as far as I can tell
                    getAction("Elm.WorkspaceToolsGroup") as DefaultActionGroup, // defined in plugin.xml
                    true // horizontal layout
            )
        }
        toolbar.setTargetComponent(this)
        return toolbar.component
    }

    override fun getData(dataId: String): Any? {
        return when {
            CommonDataKeys.NAVIGATABLE.`is`(dataId) ->
                selectedProject?.manifestPath
                        ?.let { LocalFileSystem.getInstance().findFileByPath(it) }
                        ?.let { OpenFileDescriptor(project, it) }
            ElmDetachProjectAction.DATA_KEY.`is`(dataId) ->
                selectedProject
            else ->
                super.getData(dataId)
        }
    }
}
