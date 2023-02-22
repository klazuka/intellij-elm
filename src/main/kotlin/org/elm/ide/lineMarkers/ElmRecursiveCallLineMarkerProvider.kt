package org.elm.ide.lineMarkers

import com.intellij.codeInsight.daemon.GutterIconDescriptor
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.util.FunctionUtil
import org.elm.ide.icons.ElmIcons
import org.elm.lang.core.psi.ElmTypes.LOWER_CASE_IDENTIFIER
import org.elm.lang.core.psi.ancestorsStrict
import org.elm.lang.core.psi.elementType
import org.elm.lang.core.psi.elements.ElmFunctionCallExpr
import org.elm.lang.core.psi.elements.ElmValueDeclaration
import org.elm.lang.core.psi.elements.ElmValueExpr
import org.elm.lang.core.psi.elements.ElmValueQID

/**
 * Put an icon in the gutter for top-level declarations (types, functions, values)
 * that are exposed by the containing module.
 */
class ElmRecursiveCallLineMarkerProvider : LineMarkerProvider {
    companion object {
        val OPTION = GutterIconDescriptor.Option("elm.recursive", "Recursive call", ElmIcons.RECURSIVE_CALL)
    }

    private val lines = mutableSetOf<Int>() // Only one marker per line

    override fun getLineMarkerInfo(el: PsiElement): LineMarkerInfo<*>? {
        // We only call this from ElmLineMarkerProvider.collectSlowLineMarkers, so it's ok to resolve references
        if (el.elementType != LOWER_CASE_IDENTIFIER) return null
        val qid = el.parent as? ElmValueQID ?: return null
        if (qid.isQualified) return null
        val valueExpr = qid.parent as? ElmValueExpr ?: return null
        val functionCall = valueExpr.parent as? ElmFunctionCallExpr ?: return null
        if (functionCall.target != valueExpr) return null

        val ref = valueExpr.reference.resolve()
        val nearestFunc = functionCall.ancestorsStrict.filterIsInstance<ElmValueDeclaration>()
                .firstOrNull()?.functionDeclarationLeft
        if (nearestFunc != ref) return null // not recursive

        val doc = PsiDocumentManager.getInstance(el.project).getDocument(el.containingFile) ?: return null
        val lineNumber = doc.getLineNumber(el.textOffset)
        if (lines.add(lineNumber)) {
            return LineMarkerInfo(
                    el,
                    el.textRange,
                    ElmIcons.RECURSIVE_CALL,
                    FunctionUtil.constant("Recursive call"),
                    null,
                    GutterIconRenderer.Alignment.RIGHT
            ) { "Recursive call" }
        }
        return null
    }
}
