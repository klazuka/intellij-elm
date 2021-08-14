package org.elm.ide.lineMarkers

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProviderDescriptor
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.psi.PsiElement
import org.elm.lang.core.psi.ElmFile
import org.elm.lang.core.psi.parentOfType

class ElmLineMarkerProvider : LineMarkerProviderDescriptor() {
    companion object {
        private val optionProviders = mapOf(
                ElmExposureLineMarkerProvider.OPTION to { ElmExposureLineMarkerProvider() },
                ElmRecursiveCallLineMarkerProvider.OPTION to { ElmRecursiveCallLineMarkerProvider() }
        )
        private val OPTIONS = optionProviders.keys.toTypedArray()
    }

    override fun getName(): String = "Elm line markers"
    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? = null

    // This provides the options to show in Settings > Editor > General > Gutter Icons
    override fun getOptions(): Array<Option> = OPTIONS

    override fun collectSlowLineMarkers(
        elements: List<PsiElement>,
        result: MutableCollection<in LineMarkerInfo<*>>
    ) {
        val first = elements.firstOrNull() ?: return
        if (DumbService.getInstance(first.project).isDumb || first.parentOfType<ElmFile>()?.elmProject == null) return

        // Create the providers each time this function is called, since they may have internal state
        val providers = optionProviders.filter { it.key.isEnabled }.values.map { it() }

        if (providers.isEmpty()) return

        for (element in elements) {
            ProgressManager.checkCanceled()

            // See the docs of LineMarkerProvider for why we can only add markers to leaf nodes
            if (element.firstChild != null) continue

            providers.mapNotNullTo(result) { it.getLineMarkerInfo(element) }
        }
    }
}
