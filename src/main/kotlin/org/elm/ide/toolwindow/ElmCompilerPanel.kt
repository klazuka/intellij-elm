package org.elm.ide.toolwindow

import com.intellij.icons.AllIcons
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
import com.intellij.ui.JBSplitter
import com.intellij.ui.SimpleTextAttributes.REGULAR_ATTRIBUTES
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentManager
import org.elm.openapiext.checkIsEventDispatchThread
import org.elm.openapiext.findFileByPath
import org.elm.workspace.compiler.*
import java.awt.Color
import java.nio.file.Paths
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JTextPane
import javax.swing.ListSelectionModel


class ElmCompilerPanel(private val project: Project, private val contentManager: ContentManager) : SimpleToolWindowPanel(true, false) {

    private val actionIdForward: String = "Elm.MessageForward"

    private val actionIdBack: String = "Elm.MessageBack"

    private val errorListUI = JBList<String>(emptyList()).apply {
        emptyText.text = ""
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = object : ColoredListCellRenderer<String>() {
            override fun customizeCellRenderer(list: JList<out String>, value: String, index: Int, selected: Boolean, hasFocus: Boolean) {
                icon = AllIcons.General.Error
                append(value, REGULAR_ATTRIBUTES)
            }
        }
        object : AutoScrollToSourceHandler() {
            override fun isAutoScrollMode() = true
            override fun setAutoScrollMode(state: Boolean) {}
        }.install(this)
        addListSelectionListener {
            if (!it.valueIsAdjusting && selectedIndex >= 0) {
                index = selectedIndex
                canForward = index < compilerMessages.size - 1
                canBack = index > 0
                updateActions()
                messageUI.text = compilerMessages[index].messageWithRegion.message
            }
        }
    }

    private val messageUI = JTextPane().apply {
        contentType = "text/html"
        background = Color(34, 34, 34)
    }

    var index: Int = 0

    var compilerMessages: List<CompilerMessage> = emptyList()
        set(value) {
            checkIsEventDispatchThread()
            field = value
            if (compilerMessages.isEmpty()) {
                errorListUI.setListData(emptyArray())
                messageUI.text = ""
                canForward = false
                canBack = false
            } else {
                val titles = compilerMessages.map { it.name + prettyRegion(it.messageWithRegion.region) + "    " + it.messageWithRegion.title }
                errorListUI.setListData(titles.toTypedArray())
                messageUI.text = compilerMessages[0].messageWithRegion.message
                canForward = true
                canBack = false
                updateActions()
            }
        }

    private fun prettyRegion(region: Region): String {
        return " @ line ${region.start.line} column ${region.start.column}"
    }

    init {
        setToolbar(createToolbar())

        val splitPane = JBSplitter()
        splitPane.firstComponent = JBScrollPane(errorListUI)
        splitPane.secondComponent = messageUI
        setContent(splitPane)

        with(project.messageBus.connect()) {
            subscribe(ElmBuildAction.ERRORS_TOPIC, object : ElmBuildAction.ElmErrorsListener {
                override fun update(messages: List<CompilerMessage>) {
                    ApplicationManager.getApplication().invokeLater {
                        compilerMessages = messages
                        contentManager.getContent(0)?.displayName = "${compilerMessages.size} errors"
                        errorListUI.selectedIndex = 0
                        updateActions()
                    }
                }
            })
            subscribe(ElmForwardAction.ERRORS_FORWARD_TOPIC, object : ElmForwardAction.ElmErrorsForwardListener {
                override fun forward() {
                    if (!compilerMessages.isEmpty())
                        ApplicationManager.getApplication().invokeLater {
                            val maxIndex = compilerMessages.size - 1
                            if (index < maxIndex) {
                                canBack = true
                                index += 1
                                canForward = index < maxIndex
                            }
                            messageUI.text = compilerMessages[index].messageWithRegion.message
                            errorListUI.selectedIndex = index
                        }
                }
            })
            subscribe(ElmBackAction.ERRORS_BACK_TOPIC, object : ElmBackAction.ElmErrorsBackListener {
                override fun back() {
                    if (!compilerMessages.isEmpty())
                        ApplicationManager.getApplication().invokeLater {
                            if (index > 0) {
                                canForward = true
                                index -= 1
                                canBack = index > 0
                            }
                            messageUI.text = compilerMessages[index].messageWithRegion.message
                            errorListUI.selectedIndex = index
                        }
                }
            })
        }
    }

    private fun updateActions() {
        elmBackAction.enabled = !compilerMessages.isEmpty() && canBack
        elmForwardAction.enabled = !compilerMessages.isEmpty() && canForward
    }

    var canForward: Boolean = false
    var canBack: Boolean = false

    private lateinit var elmBackAction: ElmBackAction

    private lateinit var elmForwardAction: ElmForwardAction

    private fun createToolbar(): JComponent {
        val toolbar = with(ActionManager.getInstance()) {
            // TODO alternative management of actions ? test with closing the project, then reopen it!
            val defaultActionGroup = getAction("Elm.CompilerToolsGroup") as DefaultActionGroup
            val action = getAction(actionIdForward)
            if (action != null) {
                defaultActionGroup.remove(action)
                unregisterAction(actionIdForward)
                defaultActionGroup.remove(getAction(actionIdBack))
                unregisterAction(actionIdBack)
            }
            // TODO is this safe ?
            elmBackAction = ElmBackAction()
            registerAction(actionIdBack, elmBackAction)
            elmForwardAction = ElmForwardAction()
            registerAction(actionIdForward, elmForwardAction)

            defaultActionGroup.addSeparator()
            defaultActionGroup.add(elmBackAction)
            defaultActionGroup.add(elmForwardAction)

            createActionToolbar(
                    "Elm Compiler Toolbar", // the value here doesn't matter, as far as I can tell
                    getAction("Elm.CompilerToolsGroup") as DefaultActionGroup, // defined in plugin.xml
                    true // horizontal layout
            )
        }
        toolbar.setTargetComponent(this)
        return toolbar.component
    }

    override fun getData(dataId: String): Any? {
        return when {
            CommonDataKeys.NAVIGATABLE.`is`(dataId) -> {
                if (!compilerMessages.isEmpty()) {
                    var filePath = compilerMessages[index].path
                    if (!filePath.startsWith("/")) filePath = project.basePath + "/" + filePath
                    val file = LocalFileSystem.getInstance().findFileByPath(Paths.get(filePath)) // TODO differently ?
                    val start = compilerMessages[index].messageWithRegion.region.start
                    OpenFileDescriptor(project, file!!, start.line - 1, start.column - 1)
                } else {
                    super.getData(dataId)
                }
            }
            else ->
                super.getData(dataId)
        }
    }
}
