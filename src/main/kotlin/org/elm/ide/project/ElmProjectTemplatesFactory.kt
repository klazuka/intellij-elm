package org.elm.ide.project

import com.intellij.ide.util.projectWizard.WizardContext
import com.intellij.platform.ProjectTemplate
import com.intellij.platform.ProjectTemplatesFactory
import org.elm.ide.icons.ElmIcons
import javax.swing.Icon

class ElmProjectTemplatesFactory : ProjectTemplatesFactory() {

    private val elmGroupName = "Elm"

    override fun getGroups(): Array<String> = arrayOf(elmGroupName)

    override fun getGroupIcon(group: String?): Icon =
            when (group) {
                elmGroupName -> ElmIcons.LOGO
                else -> super.getGroupIcon(group)
            }

    override fun createTemplates(group: String?, context: WizardContext?): Array<ProjectTemplate> =
            arrayOf(ElmWebProjectTemplate())
}