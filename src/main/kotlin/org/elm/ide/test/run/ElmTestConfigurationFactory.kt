package org.elm.ide.test.run

import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationType
import com.intellij.openapi.project.Project
import org.elm.ide.icons.ElmIcons

class ElmTestConfigurationFactory internal constructor(type: ConfigurationType) : ConfigurationFactory(type) {

    override fun createTemplateConfiguration(project: Project) =
            ElmTestRunConfiguration(project, this, "Elm Test")

    override fun getId() = name

    override fun getName() = "Elm Test configuration factory"

    override fun getIcon() = RUN_ICON

    companion object {
        val RUN_ICON = ElmIcons.COLORFUL
    }
}