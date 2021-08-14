/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 *
 * Originally from intellij-rust
 */

package org.elm.workspace

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.common.annotations.VisibleForTesting
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.components.*
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.ex.ProjectRootManagerEx
import com.intellij.openapi.startup.StartupManager
import com.intellij.openapi.util.EmptyRunnable
import com.intellij.openapi.util.SimpleModificationTracker
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.impl.source.resolve.ResolveCache
import com.intellij.util.Consumer
import com.intellij.util.io.exists
import com.intellij.util.io.systemIndependentPath
import com.intellij.util.messages.Topic
import org.elm.lang.core.psi.modificationTracker
import org.elm.openapiext.*
import org.elm.utils.MyDirectoryIndex
import org.elm.utils.joinAll
import org.elm.utils.runAsyncTask
import org.elm.workspace.ElmToolchain.Companion.DEFAULT_FORMAT_ON_SAVE
import org.elm.workspace.ElmToolchain.Companion.ELM_JSON
import org.elm.workspace.commandLineTools.ElmCLI
import org.elm.workspace.ui.ElmWorkspaceConfigurable
import org.jdom.Element
import java.io.FileNotFoundException
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference


private val log = logger<ElmWorkspaceService>()


/**
 * The workspace can hold multiple [ElmProject]s. Most of the time there will only be one
 * [ElmProject], but you might have multiple if you are working on multiple Elm apps in
 * the same IntelliJ project.
 *
 * There is only one workspace for the entire IntelliJ [Project].
 *
 * The state includes user-specific paths so it is persisted to IntelliJ's workspace file
 * (which is _not_ placed in version control).
 */
@State(name = "ElmWorkspace", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class ElmWorkspaceService(
        val intellijProject: Project
) : PersistentStateComponent<Element> {


    init {
        with(intellijProject.messageBus.connect()) {
            subscribe(VirtualFileManager.VFS_CHANGES, ElmProjectWatcher {
                asyncRefreshAllProjects()
            })
        }
    }

    /**
     * Increments whenever the workspace and/or settings change.
     *
     * This is provided as an alternative to listening to the message bus for changes.
     * You should use either one or the other, depending on your situation.
     */
    val changeTracker = SimpleModificationTracker()


    // SETTINGS AND TOOLCHAIN


    /* A nice view of the settings to the outside world */
    data class Settings(val toolchain: ElmToolchain)

    /* Representation of settings suitable for editor UI and serialization */
    data class RawSettings(
            val elmCompilerPath: String = "",
            val elmFormatPath: String = "",
            val elmTestPath: String = "",
            val isElmFormatOnSaveEnabled: Boolean = DEFAULT_FORMAT_ON_SAVE
    )


    val settings: Settings
        get() {
            val raw = rawSettingsRef.get()
            val toolchain = ElmToolchain(
                    elmCompilerPath = raw.elmCompilerPath,
                    elmFormatPath = raw.elmFormatPath,
                    elmTestPath = raw.elmTestPath,
                    isElmFormatOnSaveEnabled = raw.isElmFormatOnSaveEnabled)
            return Settings(toolchain = toolchain)
        }


    val rawSettings get() = rawSettingsRef.get()


    private val rawSettingsRef = AtomicReference(RawSettings())


    /**
     * The core, internal function updating the workspace settings. All updates must ultimately
     * go through here to make sure that the appropriate notifications are triggered.
     */
    fun modifySettings(notify: Boolean = true, f: (RawSettings) -> RawSettings): RawSettings {
        return rawSettingsRef.getAndUpdate(f)
                .also { if (notify) notifyDidChangeWorkspace() }
    }


    fun useToolchain(toolchain: ElmToolchain) {
        if (settings.toolchain == toolchain) {
            return
        }
        modifySettings {
            it.copy(elmCompilerPath = toolchain.elmCompilerPath?.toString() ?: "",
                    elmFormatPath = toolchain.elmFormatPath?.toString() ?: "",
                    elmTestPath = toolchain.elmTestPath?.toString() ?: "",
                    isElmFormatOnSaveEnabled = toolchain.isElmFormatOnSaveEnabled)
        }
    }


    fun showConfigureToolchainUI() {
        ShowSettingsUtil.getInstance()
                .showSettingsDialog(intellijProject, ElmWorkspaceConfigurable::class.java)
    }


    // ELM PROJECTS


    /**
     * The INTERNAL list of Elm projects in the workspace. Project truth lives here.
     *
     * IMPORTANT: must ensure thread-safe access and that the ElmProject objects themselves are immutable values.
     */
    private val projectsRef = AtomicReference(emptyList<ElmProject>())


    /**
     * The list of Elm projects in the workspace, suitable for use by the rest of the plugin.
     */
    val allProjects: List<ElmProject>
        get() = projectsRef.get()


    /**
     * The core, internal function for updating the list of Elm projects in the workspace.
     * All updates must ultimately go through here to make sure that dependent data-structures
     * are updated and notifications are triggered.
     */
    private fun modifyProjects(f: (List<ElmProject>) -> List<ElmProject>): List<ElmProject> {
        projectsRef.getAndUpdate(f)
        log.info("Resetting the directoryIndex for project lookup")
        directoryIndex.resetIndex()
        notifyDidChangeWorkspace()
        return allProjects
    }


    /**
     * Add [elmProject] to the workspace. If the project has already been registered, it will be replaced
     * with the newer one.
     */
    private fun upsertProject(elmProject: ElmProject): List<ElmProject> =
            modifyProjects { projects ->
                val otherProjects = projects.filter { it.manifestPath != elmProject.manifestPath }
                otherProjects + elmProject
            }


    /**
     * Asynchronously load an Elm project described by a manifest file (e.g. `elm.json`).
     */
    private fun asyncLoadProject(manifestPath: Path, installDeps: Boolean = false): CompletableFuture<ElmProject> =
            runAsyncTask(intellijProject, "Loading Elm project '$manifestPath'") {
                val elmCLI = settings.toolchain.elmCLI
                        ?: throw ProjectLoadException("Must specify a valid path to Elm binary in Settings")

                val compilerVersion = elmCLI.queryVersion().orNull()
                        ?: throw ProjectLoadException("Could not determine version of the Elm compiler")

                if (installDeps) {
                    installProjectDeps(manifestPath, elmCLI)
                }

                // not thread-safe; do not reuse across threads!
                val repo = ElmPackageRepository(compilerVersion)

                // External files may have been created/modified by the Elm compiler. Refresh.
                findFileByPathTestAware(Paths.get(repo.elmHomePath))?.also {
                    fullyRefreshDirectory(it)
                }

                // Load the project
                ElmProjectLoader.topLevelLoad(manifestPath, repo)
            }.whenComplete { _, error ->
                // log the result
                if (error == null) {
                    log.info("Successfully loaded Elm project $manifestPath")
                } else {
                    when (error) {
                        is ProjectLoadException -> log.warn("Failed to load $manifestPath: ${error.message}")
                        else -> log.error("Unexpected error when loading $manifestPath", error)
                    }
                }
            }

    private fun installProjectDeps(manifestPath: Path, elmCLI: ElmCLI): Boolean {
        // The only way to install an Elm project's dependencies is to compile
        // the project. But the project may not be in a compilable state when
        // we try to load it. So we will copy the `elm.json` into a temp dir
        // and run the compiler there.

        // Create temp dir to hold everything
        val dir = FileUtil.createTempDirectory("elm_deps_hack", null)

        // Re-write the `elm.json` with sane source-directories
        val mapper = ObjectMapper()
        val dto = try {
            mapper.readTree(manifestPath.toFile()) as ObjectNode
        } catch (e: FileNotFoundException) {
            log.error("Failed to install deps: the elm.json file does not exist", e)
            FileUtil.delete(dir)
            return false
        }

        if (dto.has("source-directories")) {
            dto.putArray("source-directories").add("src")
        }
        val tempManifest = dir.toPath().resolve(ELM_JSON).toFile()
        mapper.writeValue(tempManifest, dto)

        // Synthesize a dummy Elm file to make the compiler happy
        val tempMain = dir.toPath().resolve("src/Main.elm").toFile()
        FileUtil.writeToFile(tempMain, """
                                module Main exposing (..)
                                dummyValue = 0
                            """.trimIndent())

        // Before we run the Elm compiler, make sure that the `elm.json` file is valid
        // because some inputs can hang the compiler.
        if (!ElmProjectLoader.isValid(tempManifest.toPath())) {
            log.error("Failed to install deps: the elm.json file is invalid")
            FileUtil.delete(dir)
            return false
        }

        // Run the Elm compiler to install the dependencies
        val output = elmCLI.make(intellijProject, workDir = dir.toPath(), path = tempMain.toPath())

        // Cleanup
        FileUtil.delete(dir)

        if (!output.isSuccess) {
            log.error("Failed to install deps: Elm compiler failed: ${output.stderr}")
            return false
        }
        return true
    }


    // WORKSPACE ACTIONS


    fun asyncAttachElmProject(manifestPath: Path): CompletableFuture<List<ElmProject>> =
            asyncLoadProject(manifestPath, installDeps = true)
                    .thenApply {
                        upsertProject(it)
                    }


    fun detachElmProject(manifestPath: Path) {
        modifyProjects { oldProjects ->
            oldProjects.filter { it.manifestPath != manifestPath }
        }
    }


    fun asyncRefreshAllProjects(installDeps: Boolean = false): CompletableFuture<List<ElmProject>> =
            allProjects.map { elmProject ->
                asyncLoadProject(elmProject.manifestPath, installDeps = installDeps)
                        .exceptionally { null } // TODO [kl] capture info about projects that failed to load and show to user
            }.joinAll()
                    .thenApply { rawProjects ->
                        val freshProjects = rawProjects.filterNotNull().associateBy { it.manifestPath }
                        modifyProjects { currentProjects ->
                            // replace existing projects with the fresh ones, if possible
                            currentProjects.map {
                                freshProjects[it.manifestPath] ?: it
                            }
                        }
                    }


    fun asyncDiscoverAndRefresh(): CompletableFuture<List<ElmProject>> {
        if (hasAtLeastOneValidProject())
            return CompletableFuture.completedFuture(allProjects)

        val guessManifest = intellijProject.modules
                .asSequence()
                .flatMap { ModuleRootManager.getInstance(it).contentRoots.asSequence() }
                .mapNotNull { dir -> dir.findFileBreadthFirst(maxDepth = 3) { it.name == ELM_JSON } }
                .firstOrNull()
                ?: return CompletableFuture.completedFuture(allProjects)

        return asyncAttachElmProject(guessManifest.pathAsPath)
                .exceptionally { emptyList() }
    }


    fun hasAtLeastOneValidProject() =
            allProjects.any { it.manifestPath.exists() }


    // PROJECT LOOKUP


    fun findProjectForFile(file: VirtualFile): ElmProject? =
            directoryIndex.getInfoForFile(file).takeIf { it !== noProjectSentinel }


    private val directoryIndex: MyDirectoryIndex<ElmProject> =
            MyDirectoryIndex(intellijProject, noProjectSentinel, Consumer { index ->
                fun put(path: Path?, elmProject: ElmProject) {
                    if (path == null) return
                    val file = findFileByPathTestAware(path) ?: return
                    val existingElmProject = findProjectForFile(file)
                    if (existingElmProject == null) {
                        index.putInfo(file, elmProject)
                    } else {
                        /*
                        Conflict: There is already an Elm project associated with this directory.

                        Elm's source directories can be shared between Elm projects.
                        The "right" thing to do would be to model this fully, allowing an Elm file
                        to belong to multiple Elm projects. But that would complicate things everywhere,
                        and so I have chosen to instead keep things simple by associating each Elm file
                        with a SINGLE Elm project only.

                        The conflict will be resolved by always associating an Elm file with
                        the Elm project that is nearest in the file system hierarchy. This is by no
                        means perfect, but it should be good enough in nearly all cases.

                        In the future we may want to re-visit this decision.
                        */
                        val oldDistance = existingElmProject.projectDirPath.relativize(path.normalize()).toList().size
                        val newDistance = elmProject.projectDirPath.relativize(path.normalize()).toList().size
                        if (newDistance < oldDistance) {
                            log.debug("Resolved conflict by by re-associating $file with $elmProject")
                            index.putInfo(file, elmProject)
                        } else {
                            log.debug("Resolved conflict by keeping the existing association of $file with $existingElmProject")
                        }
                    }
                }

                for (project in allProjects) {
                    for (sourceDir in project.absoluteSourceDirectories) {
                        log.debug("Registering source directory $sourceDir for $project")
                        put(sourceDir, project)
                    }
                    for (pkg in project.deepDeps()) {
                        log.debug("Registering dependency directory ${pkg.projectDirPath} for $pkg")
                        put(pkg.projectDirPath, pkg)
                    }

                    log.debug("Registering tests directory ${project.testsDirPath} for $project")
                    put(project.testsDirPath, project)
                }
            })


    // INTEGRATION TEST SUPPORT


    /// Configures the workspace for the Elm project described by [manifestFile]
    fun setupForTests(toolchain: ElmToolchain, manifestFile: VirtualFile) {
        useToolchain(toolchain)
        asyncLoadProject(manifestFile.pathAsPath)
                .get(5, TimeUnit.SECONDS)
                .run { upsertProject(this) }
    }


    // PERSISTENT STATE


    override fun getState(): Element {
        val state = Element("state")

        val projectsElement = Element("elmProjects")
        state.addContent(projectsElement)
        for (project in allProjects) {
            val elem = Element("project").setAttribute("path", project.manifestPath.systemIndependentPath)
            projectsElement.addContent(elem)
        }

        val settingsElement = Element("settings")
        state.addContent(settingsElement)
        val raw = rawSettingsRef.get()
        settingsElement.setAttribute("elmCompilerPath", raw.elmCompilerPath)
        settingsElement.setAttribute("elmFormatPath", raw.elmFormatPath)
        settingsElement.setAttribute("elmTestPath", raw.elmTestPath)
        settingsElement.setAttribute("isElmFormatOnSaveEnabled", raw.isElmFormatOnSaveEnabled.toString())

        return state
    }

    override fun loadState(state: Element) {
        asyncLoadState(state)
    }

    @VisibleForTesting
    fun asyncLoadState(state: Element): CompletableFuture<Unit> {
        // Must load the Settings before the Elm Projects in order to have an ElmToolchain ready
        val settingsElement = state.getChild("settings")
        val elmCompilerPath = settingsElement.getAttributeValue("elmCompilerPath") ?: ""
        val elmFormatPath = settingsElement.getAttributeValue("elmFormatPath") ?: ""
        val elmTestPath = settingsElement.getAttributeValue("elmTestPath") ?: ""
        val isElmFormatOnSaveEnabled = settingsElement
                .getAttributeValue("isElmFormatOnSaveEnabled")
                .takeIf { it != null && it.isNotBlank() }?.toBoolean()
                ?: ElmToolchain.DEFAULT_FORMAT_ON_SAVE

        modifySettings(notify = false) {
            RawSettings(
                    elmCompilerPath = elmCompilerPath,
                    elmFormatPath = elmFormatPath,
                    elmTestPath = elmTestPath,
                    isElmFormatOnSaveEnabled = isElmFormatOnSaveEnabled
            )
        }

        return state.getChild("elmProjects")
                .getChildren("project")
                .mapNotNull { it.getAttributeValue("path") }
                .mapNotNull { Paths.get(it) }
                .map { path ->
                    asyncLoadProject(path)
                            .exceptionally { null } // TODO [kl] capture info about projects that failed to load and show to user
                }.joinAll()
                .thenApply { rawProjects ->
                    if (rawProjects.isNotEmpty()) {
                        // Exclude `elm-stuff` directories to prevent pollution of open-by-filename, etc.
                        ApplicationManager.getApplication().invokeLater {
                            // for (module in intellijProject.modules.asSequence()) {
                            //     ModuleRootModificationUtil.updateModel(module) { model ->
                            //         model.contentEntries.forEach {
                            //             if ("elm-stuff" !in it.excludePatterns)
                            //                 it.addExcludePattern("elm-stuff")
                            //         }
                            //     }
                            // }
                        }
                    }

                    modifyProjects { _ -> rawProjects.filterNotNull() }
                    Unit
                }
    }

    override fun noStateLoaded() {
        // The workspace is being opened for the first time. As soon as IntelliJ has
        // fully loaded the project, we will attempt to auto-discover the Elm toolchain
        // and the `elm.json` file.
        StartupManager.getInstance(intellijProject).runWhenProjectIsInitialized {
            asyncAutoDiscoverWorkspace(intellijProject)
        }
    }

    // NOTIFICATIONS


    private fun notifyDidChangeWorkspace() {
        if (intellijProject.isDisposed) return
        ApplicationManager.getApplication().invokeAndWait {
            runWriteAction {
                // Invalidate caches
                ResolveCache.getInstance(intellijProject).clearCache(true) // PsiReference resolve
                intellijProject.modificationTracker.incModificationCount() // CachedValuesManager: Elm Psi content
                changeTracker.incModificationCount()                       // CachedValuesManager: Elm workspace/settings

                // Refresh library roots
                ProjectRootManagerEx.getInstanceEx(intellijProject)
                        .makeRootsChange(EmptyRunnable.getInstance(), false, true)
            }
        }
        intellijProject.messageBus.syncPublisher(WORKSPACE_TOPIC)
                .didUpdate()
    }


    interface ElmWorkspaceListener {
        fun didUpdate()
    }


    companion object {
        // This topic covers any changes to the projects contained within the Elm workspace as
        // well as changes to the workspace settings.
        val WORKSPACE_TOPIC = Topic("Elm workspace changes", ElmWorkspaceListener::class.java)
    }
}


class ProjectLoadException(msg: String, cause: Exception? = null) : RuntimeException(msg, cause)


// AUTO-DISCOVER


fun asyncAutoDiscoverWorkspace(project: Project, explicitRequest: Boolean = false): CompletableFuture<Unit> {
    if (!explicitRequest) {
        val alreadyTried = run {
            val key = "org.elm.workspace.PROJECT_DISCOVERY"
            with(PropertiesComponent.getInstance(project)) {
                getBoolean(key).also { setValue(key, true) }
            }
        }
        if (alreadyTried) return CompletableFuture.completedFuture(Unit)
    }

    val toolchain = project.elmToolchain
    val suggestedToolchain = toolchain.autoDiscoverAll(project)
    if (suggestedToolchain != toolchain) {
        project.elmWorkspace.useToolchain(suggestedToolchain)
    }

    return project.elmWorkspace.asyncDiscoverAndRefresh()
            .thenApply { Unit }
}


// CONVENIENCE EXTENSIONS


val Project.elmWorkspace
    get() = service<ElmWorkspaceService>()


val Project.elmSettings
    get() = elmWorkspace.settings


val Project.elmToolchain: ElmToolchain
    get() = elmSettings.toolchain
