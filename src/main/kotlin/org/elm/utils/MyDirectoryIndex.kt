/*
 * Copyright 2000-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Ported from IntelliJ's LightDirectoryIndex.java
 *
 * I ported this initially because I ran into a weird issue with
 * LightDirectoryIndex where lookup was failing in IntelliJ Ultimate,
 * but not when building & running the plugin as a developer. So I
 * copied it over to add some debug logging, and it magically started
 * working. I have no clue why this appears to have fixed it, but such
 * is life...
 */
package org.elm.utils

import com.intellij.concurrency.ConcurrentCollectionFactory.createConcurrentIntObjectMap
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileTypes.FileTypeEvent
import com.intellij.openapi.fileTypes.FileTypeListener
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.VirtualFileWithId
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.util.Consumer

private val log = logger<MyDirectoryIndex<*>>()

class MyDirectoryIndex<T>(parentDisposable: Disposable,
                          private val myDefValue: T,
                          private val myInitializer: Consumer<MyDirectoryIndex<T>>) {

    private val myInfoCache = createConcurrentIntObjectMap<T>()

    init {
        resetIndex()
        val connection = ApplicationManager.getApplication().messageBus.connect(parentDisposable)
        connection.subscribe(FileTypeManager.TOPIC, object : FileTypeListener {
            override fun fileTypesChanged(event: FileTypeEvent) {
                resetIndex()
            }
        })

        connection.subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                for (event in events) {
                    val file = event.file
                    if (file == null || file.isDirectory) {
                        resetIndex()
                        break
                    }
                }
            }
        })
    }

    fun resetIndex() {
        myInfoCache.clear()
        myInitializer.consume(this)
    }

    fun putInfo(file: VirtualFile?, value: T) {
        if (file !is VirtualFileWithId) return
        cacheInfo(file, value)
    }

    fun getInfoForFile(file: VirtualFile?): T {
        if (file !is VirtualFileWithId) return myDefValue

        val dir: VirtualFile
        dir = if (!file.isDirectory) {
            val info = getCachedInfo(file)
            if (info != null) {
                return info
            }
            file.parent
        } else {
            file
        }

        var count = 0
        var root: VirtualFile? = dir
        while (root != null) {
            if (++count > 1000) {
                throw IllegalStateException("Possible loop in tree, started at " + dir.name)
            }
            val info = getCachedInfo(root)
            if (info != null) {
                if (dir != root) {
                    cacheInfos(dir, root, info)
                }
                return info
            }
            root = root.parent
        }

        return cacheInfos(dir, null, myDefValue)
    }

    private fun cacheInfos(virtualFile: VirtualFile?, stopAt: VirtualFile?, info: T): T {
        var dir = virtualFile
        while (dir != null) {
            cacheInfo(dir, info)
            if (dir == stopAt) {
                break
            }
            dir = dir.parent
        }
        return info
    }

    private fun cacheInfo(file: VirtualFile, info: T) {
        val id = (file as VirtualFileWithId).id
        if (log.isDebugEnabled) {
            val thing = if (info == myDefValue) "sentinel" else info?.toString() ?: "Null"
            log.debug("Putting $thing for $file using id $id")
        }
        if (info != null)
            myInfoCache.put(id, info)
    }

    private fun getCachedInfo(file: VirtualFile): T? {
        val id = (file as VirtualFileWithId).id
        val info = myInfoCache.get(id)
        if (log.isDebugEnabled) {
            val thing = if (info == myDefValue) "sentinel" else info?.toString() ?: "Null"
            log.debug("Got $thing for $file using id $id")
        }
        return info
    }

}
