package org.elm.workspace

import com.intellij.openapi.project.Project
import org.elm.workspace.commandLineTools.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

const val elmCompilerTool = "elm"
const val lamderaCompilerTool = "lamdera"
const val elmFormatTool = "elm-format"
const val elmTestTool = "elm-test"
const val elmReviewTool = "elm-review"
val elmTools = listOf(elmCompilerTool, lamderaCompilerTool, elmFormatTool, elmTestTool, elmReviewTool)

data class ElmToolchain(
        val elmCompilerPath: Path?,
        val lamderaCompilerPath: Path?,
        val elmFormatPath: Path?,
        val elmTestPath: Path?,
        val elmReviewPath: Path?,
        val isElmFormatOnSaveEnabled: Boolean
) {
    constructor(elmCompilerPath: String, lamderaCompilerPath: String, elmFormatPath: String, elmTestPath: String, elmReviewPath: String, isElmFormatOnSaveEnabled: Boolean) :
            this(
                    if (elmCompilerPath.isNotBlank()) Paths.get(elmCompilerPath) else null,
                    if (lamderaCompilerPath.isNotBlank()) Paths.get(lamderaCompilerPath) else null,
                    if (elmFormatPath.isNotBlank()) Paths.get(elmFormatPath) else null,
                    if (elmTestPath.isNotBlank()) Paths.get(elmTestPath) else null,
                    if (elmReviewPath.isNotBlank()) Paths.get(elmReviewPath) else null,
                    isElmFormatOnSaveEnabled
            )

    val elmCLI: ElmCLI? = elmCompilerPath?.let { ElmCLI(it) }

    val lamderaCLI: LamderaCLI? = lamderaCompilerPath?.let { LamderaCLI(it) }

    val elmFormatCLI: ElmFormatCLI? = elmFormatPath?.let { ElmFormatCLI(it) }

    val elmTestCLI: ElmTestCLI? = elmTestPath?.let { ElmTestCLI(it) }

    val elmReviewCLI: ElmReviewCLI? = elmReviewPath?.let { ElmReviewCLI(it) }

    val presentableLocation: String =
            elmCompilerPath?.toString() ?: "unknown location"

    /**
     * Checks the currently configured elm compiler path. If a bare `elm` command is provided we check that it is on the
     * path.
     * This performs file I/O.
     */
    fun looksLikeValidToolchain(overridePathSearch: Sequence<Path> = emptySequence()): Boolean {
        return if (elmCompilerPath.toString() == "elm") {
            ElmSuggest.compilerIsOnPath(overridePathSearch)
        } else {
            elmCompilerPath != null && Files.isExecutable(elmCompilerPath)
        }
    }

    /**
     * Attempts to locate Elm tool paths for all tools which are un-configured.
     * Returns a copy of the receiver. Performs file I/O.
     */
    fun autoDiscoverAll(project: Project): ElmToolchain {
        val suggestions = ElmSuggest.suggestTools(project)
        return copy(
                elmCompilerPath = elmCompilerPath ?: suggestions[elmCompilerTool],
                lamderaCompilerPath = lamderaCompilerPath ?: suggestions[lamderaCompilerTool],
                elmFormatPath = elmFormatPath ?: suggestions[elmFormatTool],
                elmTestPath = elmTestPath ?: suggestions[elmTestTool],
                elmReviewPath = elmReviewPath ?: suggestions[elmReviewTool]
        )
    }

    companion object {
        const val ELM_JSON = "elm.json"

        /**
         * The name of the file that contains information specific to an Elm project, but which is _not_ in `elm.json`.
         * Here we put extra information which isn't in the normal `elm.json`, but which this plugin requires, such as
         * a custom path to the directory containing tests.
         *
         * The `elm.json` file is referred to elsewhere as the _manifest_. This `elm.intellij.json` file is referred to
         * as the _sidecar manifest_.
         */
        const val SIDECAR_FILENAME = "elm.intellij.json"

        const val DEFAULT_FORMAT_ON_SAVE = true

        /**
         * A blank, default [ElmToolchain].
         */
        val BLANK = ElmToolchain(
                elmCompilerPath = null,
                lamderaCompilerPath = null,
                elmFormatPath = null,
                elmTestPath = null,
                elmReviewPath = null,
                isElmFormatOnSaveEnabled = ElmToolchain.DEFAULT_FORMAT_ON_SAVE
        )

        val MIN_SUPPORTED_COMPILER_VERSION = Version(0, 19, 0)
        val MIN_SUPPORTED_LAMDERA_COMPILER_VERSION = Version(0, 19, 1) // TODO ? 0.19.1-1.0.1

        /**
         * Suggest a default toolchain based on common locations where Elm tools are frequently installed.
         * This performs file I/O.
         */
        fun suggest(project: Project): ElmToolchain =
                BLANK.autoDiscoverAll(project)
    }
}
