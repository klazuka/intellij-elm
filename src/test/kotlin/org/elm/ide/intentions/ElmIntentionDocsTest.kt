package org.elm.ide.intentions

import com.intellij.codeInsight.intention.impl.config.IntentionManagerImpl
import org.elm.lang.ElmTestBase

class ElmIntentionDocsTest : ElmTestBase() {

    fun `test intentions has documentation`() {
        IntentionManagerImpl.EP_INTENTION_ACTIONS
                .extensions
                .filter { it.category?.startsWith("Elm") == true }
                .forEach {
                    val simpleName = it.className.substringAfterLast(".")
                    val directory = "intentionDescriptions/$simpleName"
                    val files = listOf("before.elm.template", "after.elm.template", "description.html")
                    for (file in files) {
                        val text = getResourceAsString("$directory/$file")
                                ?: fail("No HTML docs found for ${it.className}.\n" +
                                        "Add ${files.joinToString()} to src/main/resources/$directory")

                        if (file.endsWith(".html")) {
                            checkHtmlStyle(text)
                        }
                    }
                }
    }
}
