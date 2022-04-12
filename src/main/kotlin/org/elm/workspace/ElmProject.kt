package org.elm.workspace

import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.indexing.LightDirectoryIndex
import org.elm.openapiext.pathAsPath
import java.nio.file.Path
import java.nio.file.Paths


/**
 * The logical representation of an Elm project. An Elm project can be an application
 * or a package, and it specifies its dependencies.
 *
 * @param manifestPath The location of the manifest file (e.g. `elm.json`). Uniquely identifies a project.
 * @param dependencies Additional Elm packages that this project depends on directly
 * @param testDependencies Additional Elm packages that this project's **tests** depends on directly
 * @param sourceDirectories The relative paths to one-or-more directories containing Elm source files belonging to this project.
 * @param testsRelativeDirPath The path to the directory containing unit tests, relative to the [projectDirPath].
 * Typically this will be "tests": see [testsDirPath] for more info.
 */
sealed class ElmProject(
        val manifestPath: Path,
        val dependencies: List<ElmPackageProject>,
        val testDependencies: List<ElmPackageProject>,
        val sourceDirectories: List<Path>,
        testsRelativeDirPath: String
) : UserDataHolderBase() {

    /**
     * The path to the directory containing the Elm project JSON file.
     */
    val projectDirPath: Path = manifestPath.parent

    /**
     * The path to the directory containing unit tests, relative to the [projectDirPath]. Typically this will be "tests":
     * see [testsDirPath] for more info.
     *
     * Note that this path is normalized (see [Path.normalize]) so can safely be compared to [DEFAULT_TESTS_DIR_NAME].
     * For example, if the user specifies a value such as `"./foo/"` in some config file, this property will return `"foo"`.
     */
    val testsRelativeDirPath = Paths.get(testsRelativeDirPath).normalize().toString()

    /**
     * The path to the directory containing unit tests.
     *
     * For packages this will be a directory called "tests", as elm-test requires packages to have tests in a top-level
     * "tests" directory. For applications the default behaviour is the same as for packages, but optionally tests can
     * be put in some other directory, as long as when elm-test is called, the path to those tests is specified as a
     * cmd-line argument.
     */
    val testsDirPath: Path = projectDirPath.resolve(this.testsRelativeDirPath)

    /**
     * A flag indicating whether this project use a custom folder for unit tests (i.e. where the tests are any folder other
     * than the default `"tests"`).
     */
    val isCustomTestsDir = this.testsRelativeDirPath != DEFAULT_TESTS_DIR_NAME

    /**
     * A name which can be shown in the UI. Note that while Elm packages have user-assigned
     * names, applications do not. Thus, in order to cover both cases, we use the name
     * of the parent directory.
     */
    val presentableName: String =
            projectDirPath.fileName?.toString() ?: "UNKNOWN"

    /**
     * Returns the absolute paths for each source directory.
     *
     * @see sourceDirectories
     */
    val absoluteSourceDirectories: List<Path> =
            sourceDirectories.map { projectDirPath.resolve(it).normalize() }

    /**
     * Returns all the source directories, i.e. the [absoluteSourceDirectories] and the [testsDirPath].
     */
    val allSourceDirs: Sequence<Path> =
            absoluteSourceDirectories.asSequence() + sequenceOf(testsDirPath)

    /**
     * Returns all packages which this project depends on directly, whether it be for normal,
     * production code or for tests.
     */
    val allDirectDeps: Sequence<ElmPackageProject> =
            sequenceOf(dependencies, testDependencies).flatten()

    /**
     * Returns the direct and indirect dependencies of the receiver, recursively.
     */
    fun deepDeps(): List<ElmPackageProject> {
        val stack = allDirectDeps.toMutableList()
        val acc = mutableListOf<ElmPackageProject>()
        while (stack.isNotEmpty()) {
            val p = stack.removeAt(0)
            acc.add(p)
            stack.addAll(p.allDirectDeps)
        }
        return acc.distinctBy { it.manifestPath }
    }

    /**
     * Returns true if this project is compatible with Elm compiler [version].
     *
     * This is a looser form of a version check that allows for Elm compiler versions that include
     * alpha/beta/rc suffixes. e.g. "0.19.1-alpha-4"
     */
    fun isCompatibleWith(version: Version) =
            when (this) {
                is ElmApplicationProject -> elmVersion.xyz == version.xyz
                is LamderaApplicationProject -> elmVersion.xyz == version.xyz
                is ElmPackageProject -> elmVersion.contains(version.xyz)
                is ElmReviewProject -> elmVersion.xyz == version.xyz
            }

    /**
     * Return `true` iff this package is the core package for the current version of Elm.
     */
    open fun isCore(): Boolean = false

    fun rootDirContaining(virtualFile: VirtualFile): Path? {
        val dirPath = virtualFile.pathAsPath
        return (absoluteSourceDirectories + listOf(testsDirPath))
            .find { dirPath.startsWith(it) }
    }
}


class ElmReviewProject(
    manifestPath: Path,
    val elmVersion: Version,
    dependencies: List<ElmPackageProject>,
    testDependencies: List<ElmPackageProject>,
    sourceDirectories: List<Path>,
    testsRelativeDirPath: String = DEFAULT_TESTS_DIR_NAME
) : ElmProject(manifestPath, dependencies, testDependencies, sourceDirectories, testsRelativeDirPath)

/**
 * Represents an Elm application
 */
class ElmApplicationProject(
        manifestPath: Path,
        val elmVersion: Version,
        dependencies: List<ElmPackageProject>,
        testDependencies: List<ElmPackageProject>,
        sourceDirectories: List<Path>,
        testsRelativeDirPath: String = DEFAULT_TESTS_DIR_NAME
) : ElmProject(manifestPath, dependencies, testDependencies, sourceDirectories, testsRelativeDirPath)


/**
 * Represents a Lamdera application
 */
class LamderaApplicationProject(
        manifestPath: Path,
        val elmVersion: Version,
        dependencies: List<ElmPackageProject>,
        testDependencies: List<ElmPackageProject>,
        sourceDirectories: List<Path>,
        testsRelativeDirPath: String = DEFAULT_TESTS_DIR_NAME
) : ElmProject(manifestPath, dependencies, testDependencies, sourceDirectories, testsRelativeDirPath)


// TODO ?
// class ElmLamderaProgramTest: ElmProject()


/**
 * Represents an Elm package/library
 */
class ElmPackageProject(
        manifestPath: Path,
        val elmVersion: Constraint,
        dependencies: List<ElmPackageProject>,
        testDependencies: List<ElmPackageProject>,
        sourceDirectories: List<Path>,
        val name: String,
        val version: Version,
        val exposedModules: List<String>
) : ElmProject(
        manifestPath = manifestPath,
        dependencies = dependencies,
        testDependencies = testDependencies,
        sourceDirectories = sourceDirectories,
        testsRelativeDirPath = DEFAULT_TESTS_DIR_NAME
) {
    override fun isCore(): Boolean =
            name == "elm/core"
}

/**
 * A dummy sentinel value because [LightDirectoryIndex] needs it.
 */
val noProjectSentinel = ElmApplicationProject(
        manifestPath = Paths.get("/elm.json"),
        elmVersion = Version(0, 0, 0),
        dependencies = emptyList(),
        testDependencies = emptyList(),
        sourceDirectories = emptyList()
)


/**
 * The default name of the directory which contains unit tests.
 */
const val DEFAULT_TESTS_DIR_NAME = "tests"