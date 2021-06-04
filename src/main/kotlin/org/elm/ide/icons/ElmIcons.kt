package org.elm.ide.icons

import com.intellij.icons.AllIcons
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.LayeredIcon
import javax.swing.Icon

object ElmIcons {


    // Basic icons
    val LOGO = getIcon("elm-logo.svg")
    val FILE = getIcon("elm-file.svg")
    val PACKAGE = getIcon("elm-package.svg")


    // Tool window icons
    val TOOL_WINDOW = getIcon("elm-toolwindow.svg")


    // Gutter icons
    val EXPOSED_GUTTER = getIcon("elm-exposure.svg")
    val RECURSIVE_CALL = AllIcons.Gutter.RecursiveMethod!!


    // Element icons
    val MODULE = AllIcons.FileTypes.Any_type!!
    val KEYWORD = TOOL_WINDOW
    val FUNCTION = AllIcons.Nodes.Function!!
    val VALUE = AllIcons.Nodes.Variable!!
    val UNION_TYPE = AllIcons.Nodes.Type!!
    val UNION_VARIANT = AllIcons.Nodes.Class!!
    val TYPE_ALIAS = LayeredIcon(AllIcons.Nodes.Type!!, AllIcons.Nodes.Shared)


    private fun getIcon(path: String): Icon {
        return IconLoader.getIcon("/icons/$path")
    }
}
