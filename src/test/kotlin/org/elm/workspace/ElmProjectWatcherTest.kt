/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 *
 * Originally from intellij-rust
 */

package org.elm.workspace

import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import org.elm.lang.ElmTestBase


class ElmProjectWatcherTest : ElmTestBase() {


    private var counter = 0
    private lateinit var watcher: ElmProjectWatcher


    override fun setUp() {
        super.setUp()
        watcher = ElmProjectWatcher { counter++ }
    }


    fun `test detects a change involving elm json project manifest file`() =
            testVFileWatching(ElmToolchain.ELM_JSON)


    fun `test detects a change involving elm intellij json project manifest file`() =
            testVFileWatching(ElmToolchain.SIDECAR_FILENAME)


    fun `test ignores files other than elm json`() =
            testVFileWatching("foo.json", false)


    /**
     * Tests that events related to the file with the passed in `fileName` are/aren't triggered, as defined by `expectTriggered`.
     */
    private fun testVFileWatching(fileName: String, expectTriggered: Boolean = true) {
        // Define the function we'll call to check whether the event has/hasn't been triggered as expected (checkTriggered
        // or checkNotTriggered).
        // Need to create lambdas here as can't directly reference the checkTriggered/checkNotTriggered extension methods
        // as they aren't top-level functions (as they reference `counter` so need to be class members). See fourth comment
        // here: https://stackoverflow.com/a/46562791/10326373
        val watchingFn =
                if (expectTriggered) { event: VFileEvent -> watcher.checkTriggered(event) }
                else { event: VFileEvent -> watcher.checkNotTriggered(event) }

        val vFile = newVirtualFile(fileName)
        watchingFn(newCreateEvent(vFile))
        watchingFn(newChangeEvent(vFile))
        watchingFn(newDeleteEvent(vFile))
        runWriteAction { vFile.delete(this) }
    }


    private fun ElmProjectWatcher.checkTriggered(event: VFileEvent) {
        val old = counter
        after(listOf(event))
        check(counter == old + 1) {
            "Watcher ignored $event"
        }
    }


    private fun ElmProjectWatcher.checkNotTriggered(event: VFileEvent) {
        val old = counter
        after(listOf(event))
        check(counter == old) {
            "Watcher should have ignored $event"
        }
    }


    private fun newCreateEvent(vFile: VirtualFile) =
            VFileCreateEvent(null, vFile.parent, vFile.name, false, null, null, true, emptyArray())


    private fun newChangeEvent(vFile: VirtualFile) =
            VFileContentChangeEvent(null, vFile, vFile.modificationStamp - 1, vFile.modificationStamp, true)


    private fun newDeleteEvent(vFile: VirtualFile) =
            VFileDeleteEvent(null, vFile, true)


    private fun newVirtualFile(name: String) =
            myFixture.tempDirFixture.createFile("proj-watcher/$name")
}