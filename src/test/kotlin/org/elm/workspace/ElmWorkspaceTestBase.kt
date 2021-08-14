/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 *
 * Originally from intellij-rust
 */

package org.elm.workspace

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.builders.ModuleFixtureBuilder
import com.intellij.testFramework.fixtures.CodeInsightFixtureTestCase
import com.intellij.util.ThrowableRunnable
import org.elm.FileTree
import org.elm.FileTreeBuilder
import org.elm.TestProject
import org.elm.fileTree
import java.util.concurrent.CompletableFuture

/**
 * Base class for "heavy" integration tests such as those that depend on the Elm toolchain
 * or read Elm project files in order to create a "workspace".
 *
 * For normal tests, see [org.elm.lang.ElmTestBase]
 */
abstract class ElmWorkspaceTestBase : CodeInsightFixtureTestCase<ModuleFixtureBuilder<*>>() {


    protected var toolchain = ElmToolchain.BLANK
    private var originalToolchain = ElmToolchain.BLANK


    protected val elmWorkspaceDirectory: VirtualFile
        get() = myFixture.findFileInTempDir(".")

    protected fun FileTree.asyncCreateWithAutoDiscover(): CompletableFuture<TestProject> {
        val testProject = create(project, elmWorkspaceDirectory)
        return project.elmWorkspace.asyncDiscoverAndRefresh().thenApply { testProject }
    }

    override fun setUp() {
        super.setUp()
        originalToolchain = project.elmToolchain
        toolchain = ElmToolchain.suggest(project)
        project.elmWorkspace.useToolchain(toolchain)
    }


    override fun runTestRunnable(testRunnable: ThrowableRunnable<Throwable>) {
        if (!toolchain.looksLikeValidToolchain()) {
            System.err.println("SKIP $name: no Elm toolchain found")
            return
        }
        super.runTestRunnable(testRunnable)
    }


    override fun tearDown() {
        project.elmWorkspace.useToolchain(originalToolchain)
        super.tearDown()
    }


    fun checkEquals(expected: Any, actual: Any) {
        if (expected != actual)
            failure(expected.toString(), actual.toString())
    }


    fun failure(expected: String, actual: String): AssertionError {
        // IntelliJ will handle this output specially by showing a diff.
        throw AssertionError("\nExpected: $expected\n     but: was $actual")
    }

    fun buildProject(builder: FileTreeBuilder.() -> Unit): TestProject {
        val result = fileTree(builder).asyncCreateWithAutoDiscover().get()
        require(project.elmWorkspace.allProjects.isNotEmpty()) { "no Elm project was loaded" }
        return result
    }
}