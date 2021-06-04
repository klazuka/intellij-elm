package org.elm.workspace

import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.AdditionalLibraryRootsProvider
import com.intellij.openapi.roots.SyntheticLibrary
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import org.elm.ide.icons.ElmIcons
import org.elm.openapiext.findFileByPath
import javax.swing.Icon


class ElmAdditionalLibraryRootsProvider : AdditionalLibraryRootsProvider() {

    override fun getAdditionalProjectLibraries(project: Project): Collection<ElmLibrary> =
            project.elmWorkspace.allProjects
                    .flatMap { it.deepDeps() }
                    .mapNotNull { ElmLibrary.fromPackage(it) }
                    .toList()

    override fun getRootsToWatch(project: Project) =
            getAdditionalProjectLibraries(project).map { it.root }
}


class ElmLibrary(
        val root: VirtualFile,
        private val name: String
) : SyntheticLibrary(), ItemPresentation {

    override fun equals(other: Any?): Boolean =
            other is ElmLibrary && other.root == root

    override fun hashCode(): Int = root.hashCode()

    override fun getSourceRoots(): Collection<VirtualFile> =
            listOf(root)

    override fun getLocationString(): String? =
            null

    override fun getIcon(unused: Boolean): Icon? =
            ElmIcons.PACKAGE

    override fun getPresentableText(): String? =
            name

    companion object {
        fun fromPackage(pkg: ElmPackageProject): ElmLibrary? {
            val root = LocalFileSystem.getInstance().findFileByPath(pkg.projectDirPath) ?: return null
            if (!root.exists()) return null
            return ElmLibrary(root = root, name = pkg.name)
        }
    }
}
