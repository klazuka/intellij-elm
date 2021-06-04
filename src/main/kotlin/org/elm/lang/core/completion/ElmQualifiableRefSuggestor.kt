package org.elm.lang.core.completion

import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import org.elm.ide.icons.ElmIcons
import org.elm.lang.core.psi.*
import org.elm.lang.core.psi.elements.*
import org.elm.lang.core.resolve.scope.ExpressionScope
import org.elm.lang.core.resolve.scope.GlobalScope
import org.elm.lang.core.resolve.scope.ImportScope
import org.elm.lang.core.resolve.scope.ModuleScope
import org.elm.lang.core.stubs.index.ElmModulesIndex
import org.elm.lang.core.types.findTy
import org.elm.lang.core.types.renderedText


/**
 * Most completions are provided by implementing [PsiReference.getVariants],
 * but the structure of our Psi tree made it hard to provide completions for
 * qualifiable references (e.g. `Html.Events.onClick`).
 *
 * This class supplements the completions provided by the reference system.
 */
object ElmQualifiableRefSuggestor : Suggestor {

    override fun addCompletions(parameters: CompletionParameters, result: CompletionResultSet) {
        val pos = parameters.position
        val parent = pos.parent
        val grandParent = pos.parent?.parent
        val file = pos.containingFile as ElmFile
        val fileModule = file.getModuleDecl() ?: return

        val prefix = parent.text.substring(0, parameters.offset - parent.textRange.startOffset)
        val prefixedResult = result.withPrefixMatcher(prefix)

        if (grandParent is ElmValueExpr && grandParent.prevSibling is ElmNumberConstantExpr) {
            // Ignore this case in order to prevent IntelliJ from suggesting completions
            // when the caret is immediately after a number.
            return
        }

        if (pos.elementType in ELM_IDENTIFIERS) {
            val fileImports =
                file.getImportClauses() +
                        (GlobalScope.forElmFile(file)?.getImportClauses() ?: emptyList())

            when (grandParent) {
                is ElmValueExpr -> {
                    ElmModulesIndex.getAll(file).plus(fileModule).forEach { module ->
                        prefixedResult.addModule(
                            pos,
                            module,
                            fileImports.firstOrNull { it.referenceName == module.name },
                            {
                                if (module.elmFile == file) (
                                        (ModuleScope.getDeclaredValues(module.elmFile).array
                                                + ModuleScope.getDeclaredConstructors(module.elmFile).array
                                                + ExpressionScope(parent).getVisibleValues(false).toList().toTypedArray()))
                                else
                                    (ImportScope(module.elmFile).getExposedValues().elements
                                            + ImportScope(module.elmFile).getExposedConstructors().elements)
                            },
                            { import: ElmImportClause ->
                                (ModuleScope.getVisibleImportValues(import).map { it.element }
                                        + ModuleScope.getVisibleImportConstructors(import))
                            }
                        )
                    }
                }
                is ElmPattern, is ElmUnionPattern -> {
                    ElmModulesIndex.getAll(file).plus(fileModule).forEach { module ->
                        prefixedResult.addModule(
                            pos,
                            module,
                            fileImports.firstOrNull { it.referenceName == module.name },
                            {
                                (if (module.elmFile == file) ModuleScope.getDeclaredConstructors(file).array
                                else (ImportScope(module.elmFile).getExposedConstructors().elements))
                                    .filterIsInstance<ElmUnionVariant>().toTypedArray()
                            },
                            { import: ElmImportClause ->
                                ModuleScope.getVisibleImportConstructors(import)
                            }
                        )
                    }
                }
                is ElmTypeRef, is ElmTypeExpression -> {
                    ElmModulesIndex.getAll(file).plus(fileModule).forEach { module ->
                        prefixedResult.addModule(
                            pos,
                            module,
                            fileImports.firstOrNull { it.referenceName == module.name },
                            {
                                if (module.elmFile == file) ModuleScope.getDeclaredTypes(file).array
                                else ImportScope(module.elmFile).getExposedTypes().elements
                            },
                            { import: ElmImportClause ->
                                ModuleScope.getVisibleImportTypes(import)
                            }
                        )
                    }

                    // https://github.com/elm/core/issues/1037
                    LookupElementBuilder
                        .create("List")
                        .withIcon(ElmIcons.UNION_TYPE)
                        .withTypeText("List")
                        .let {
                            PrioritizedLookupElement.withPriority(it, 1.0)
                        }.let {
                            prefixedResult.addElement(it)
                        }
                }
            }
        }
    }
}

private fun CompletionResultSet.addModule(
    position: PsiElement,
    module: ElmModuleDeclaration,
    moduleImport: ElmImportClause?,
    listModuleNames: (Unit) -> Array<ElmNamedElement>,
    getExposedNames: (ElmImportClause) -> List<ElmNamedElement>
) {
    when {
        // current module
        position.containingFile == module.elmFile -> {
            listModuleNames(Unit).forEach {
                this.addModuleElement(module, it, null)
            }
        }
        // not imported
        moduleImport == null -> {
            this.addModuleQualifier(module)
        }
        // imported
        else -> {
            val exposedNames = getExposedNames(moduleImport)

            listModuleNames(Unit).forEach {
                when {
                    // imported via expose all
                    moduleImport.exposesAll -> {
                        this.addModuleElement(module, it, null)
                    }
                    // imported via expose
                    exposedNames.contains(it) -> {
                        this.addModuleElement(module, it, null)
                    }
                    // imported via alias
                    moduleImport.asClause != null -> {
                        this.addModuleElement(module, it, moduleImport.asClause!!.name)
                    }
                    // imported
                    else -> {
                        this.addModuleElement(module, it, module.name)
                    }
                }
            }
        }
    }
}

private fun CompletionResultSet.addModuleQualifier(
    module: ElmModuleDeclaration
) {
    val importQualifier = module.name.split(".").last()

    LookupElementBuilder
        .createWithSmartPointer(importQualifier, module)
        .withIcon(module.getIcon(0))
        .withLookupString(module.name)
        .withPresentableText(module.name)
        .withInsertHandler { context, _ ->
            val offset = when {
                (context.file as ElmFile).getImportClauses().isEmpty() ->
                    (context.file.node.findChildByType(ELM_TOP_LEVEL_DECLARATIONS)!!).textRange.startOffset
                else ->
                    (context.file as ElmFile).getImportClauses().first().textRange.startOffset
            }
            val exposing =
                if (ImportScope(module.elmFile).getExposedTypes()[importQualifier] != null)
                    " exposing ($importQualifier)"
                else ""
            val import =
                if (importQualifier == module.name) "import ${module.name}$exposing\n"
                else ("import ${module.name} as $importQualifier$exposing\n")
            context.document.insertString(offset, import)
        }.let {
            PrioritizedLookupElement.withPriority(it, 0.0)
        }.let {
            addElement(it)
        }
}

private fun CompletionResultSet.addModuleElement(
    module: ElmModuleDeclaration,
    element: ElmNamedElement,
    importQualifier: String?
) {
    if (element.name == null) return

    val ty = element.findTy()

    LookupElementBuilder
        .createWithSmartPointer(
            if (importQualifier != null) importQualifier + "." + element.name!!
            else element.name!!,
            element
        )
        .withIcon(element.getIcon(0))
        .let {
            val annotation = ty?.renderedText(linkify = false, withModule = false, elmFile = null)
            if (annotation != null) it.withTailText(" : $annotation")
            else it
        }
        .withTypeText(module.name)
        .let {
            val operatorName = (element as? ElmInfixDeclaration)?.funcRef?.referenceName
            if (operatorName != null) it.withLookupString(operatorName)
            else it
        }
        .let {
            PrioritizedLookupElement.withPriority(it, 1.0)
        }.let {
            addElement(it)
        }
}
