/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 *
 * From intellij-rust
 */

package org.elm.ide.intentions

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.testFramework.PlatformTestUtil
import junit.framework.ComparisonFailure
import org.elm.fileTreeFromText
import org.elm.lang.ElmTestBase
import org.intellij.lang.annotations.Language


abstract class ElmIntentionTestBase(val intention: IntentionAction) : ElmTestBase() {

    protected fun doAvailableTest(@Language("Elm") before: String, @Language("Elm") after: String, expectedIntentionText: String? = null) {
        InlineFile(before).withCaret()
        launchAction()
        myFixture.checkResult(replaceCaretMarker(after))
        if (expectedIntentionText != null && expectedIntentionText != intention.text) {
            throw ComparisonFailure("Intention text did not match", expectedIntentionText, intention.text)
        }
    }

    protected fun doAvailableTestWithFileTree(@Language("Elm") fileStructureBefore: String, @Language("Elm") openedFileAfter: String) {
        fileTreeFromText(fileStructureBefore).createAndOpenFileWithCaretMarker()
        launchAction()
        myFixture.checkResult(replaceCaretMarker(openedFileAfter.trimIndent()))
    }

    protected fun doUnavailableTest(@Language("Elm") before: String) {
        InlineFile(before).withCaret()
        check(intention.familyName !in myFixture.availableIntentions.mapNotNull { it.familyName }) {
            "\"$intention\" intention should not be available"
        }
    }

    protected fun doUnavailableTestWithFileTree(@Language("Elm") before: String) {
        fileTreeFromText(before).createAndOpenFileWithCaretMarker()
        check(intention.familyName !in myFixture.availableIntentions.mapNotNull { it.familyName }) {
            "\"$intention\" intention should not be available"
        }
    }

    private fun launchAction() {
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        myFixture.launchAction(intention)
    }
}
