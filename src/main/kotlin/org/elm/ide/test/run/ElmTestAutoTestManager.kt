package org.elm.ide.test.run

import com.intellij.execution.testframework.autotest.AbstractAutoTestManager
import com.intellij.execution.testframework.autotest.DelayedDocumentWatcher
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import org.elm.lang.core.ElmFileType
import java.util.function.IntConsumer
import java.util.function.Predicate

@State(
        name = "ElmTestAutoTestManager",
        storages = [Storage(StoragePathMacros.WORKSPACE_FILE)]
)
class ElmTestAutoTestManager internal constructor(
        project: Project
) : AbstractAutoTestManager(project) {

    override fun createWatcher(project: Project) =
            DelayedDocumentWatcher(project,
                    myDelayMillis,
                    IntConsumer { restartAllAutoTests(it) },
                    Predicate { it.fileType == ElmFileType && FileEditorManager.getInstance(project).isFileOpen(it) }
            )
}

val Project.elmAutoTestManager
    get() = service<ElmTestAutoTestManager>()
