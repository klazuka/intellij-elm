package org.elm.lang.core.resolve.scope

import com.intellij.openapi.util.Key
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider.Result
import com.intellij.psi.util.CachedValuesManager
import org.elm.lang.core.psi.ElmFile
import org.elm.lang.core.psi.ElmNamedElement
import org.elm.lang.core.psi.directChildren
import org.elm.lang.core.psi.elements.ElmImportClause
import org.elm.lang.core.psi.elements.ElmTypeDeclaration
import org.elm.lang.core.psi.modificationTracker

private val DECLARED_VALUES_KEY: Key<CachedValue<List<ElmNamedElement>>> = Key.create("DECLARED_VALUES_KEY")
private val VISIBLE_VALUES_KEY: Key<CachedValue<VisibleNames>> = Key.create("VISIBLE_VALUES_KEY")
private val REFRENCABLE_VALUES_KEY: Key<CachedValue<VisibleNames>> = Key.create("REFRENCABLE_VALUES_KEY")
private val DECLARED_TYPES_KEY: Key<CachedValue<List<ElmNamedElement>>> = Key.create("DECLARED_TYPES_KEY")
private val VISIBLE_TYPES_KEY: Key<CachedValue<VisibleNames>> = Key.create("VISIBLE_TYPES_KEY")
private val DECLARED_CONSTRUCTORS_KEY: Key<CachedValue<List<ElmNamedElement>>> = Key.create("DECLARED_CONSTRUCTORS_KEY")
private val VISIBLE_CONSTRUCTORS_KEY: Key<CachedValue<VisibleNames>> = Key.create("VISIBLE_CONSTRUCTORS_KEY")

data class VisibleNames(
        val global: List<ElmNamedElement>,
        val topLevel: List<ElmNamedElement>,
        val imported: List<ElmNamedElement>
) {
    /**
     * Returns the list of all visible names ordered by precedence.
     *
     * Elm resolves name ambiguity as follows:
     *
     * 1. check to see if it's defined within the current file
     * 2. if not found, check if it has been exposed by an import
     * 3. if you still haven't found it, check the implicit, global imports
     */
    val all: List<ElmNamedElement> get() = listOf(topLevel, imported, global).flatten()
}

/**
 * A scope that provides access to declarations within the Elm module/file as well as declarations
 * that have been imported into the module. You can think of it as the view of the module from the inside.
 *
 * @see ImportScope for the view of the module from the outside
 */
object ModuleScope {

    fun getImportDecls(elmFile: ElmFile) =
            elmFile.directChildren.filterIsInstance<ElmImportClause>().toList()

    fun getAliasDecls(elmFile: ElmFile) =
            getImportDecls(elmFile).mapNotNull { it.asClause }


    /**
     * Finds import declarations named by [qualifierPrefix].
     *
     * The [qualifierPrefix] may be either a module name or an import alias.
     *
     * In most cases, this should either one decl or zero. However, there is
     * an obscure feature of Elm where a user can import multiple modules
     * using the same alias. e.g.
     *
     * ```
     * import List
     * import List.Extra as List
     *
     * foo = List.<whatever> -- where <whatever> can come from either `List` or `List.Extra`
     * ```
     */
    fun importDeclsForQualifierPrefix(elmFile: ElmFile, qualifierPrefix: String) =
            getImportDecls(elmFile).filter {
                // If a module has an alias, then the alias hides the original module name. (issue #93)
                qualifierPrefix == it.asClause?.name ?: it.moduleQID.text
            }

    /**
     * Given a [name] in a [module], return the qualifier prefix necessary to reference the name in this scope.
     *
     * Note that this function does not ensure that [name] is actually declared in [module].
     *
     * e.g.
     *
     * ```
     * import M exposing(N)
     * import Q.U exposing(N)
     * import A exposing(..)
     * ```
     *
     * - `getQualifierForTypeName(M, N)` -> `""`
     * - `getQualifierForTypeName(M, O)` -> `"M."`
     * - `getQualifierForTypeName(Q.U, O)` -> `"Q.U."`
     * - `getQualifierForTypeName(A, B)` -> `""`
     * - `getQualifierForTypeName(X, Y)` -> `null`
     */
    fun getQualifierForName(elmFile: ElmFile, module: String, name: String): String? =
            getImportDecls(elmFile)
                    .find { it.moduleQID.text == module }
                    ?.let { importDecl ->
                        when {
                            getVisibleImportValues(importDecl).any { it.element.name == name } -> ""
                            getVisibleImportTypes(importDecl).any { it.name == name } -> ""
                            importDecl.asClause != null -> importDecl.asClause!!.name + "."
                            else -> importDecl.moduleQID.text + "."
                        }
                    }


    // VALUES

    fun getDeclaredValues(elmFile: ElmFile): List<ElmNamedElement> {
        return CachedValuesManager.getCachedValue(elmFile, DECLARED_VALUES_KEY) {
            val valueDecls = elmFile.getValueDeclarations().flatMap {
                it.declaredNames(includeParameters = false)
            }
            val values = listOf(valueDecls, elmFile.getPortAnnotations(), elmFile.getInfixDeclarations())
                    .flatten()
            Result.create(values, elmFile.project.modificationTracker)
        }
    }


    /** Get all names that can be referenced from [elmFile] without a qualifier */
    fun getVisibleValues(elmFile: ElmFile): VisibleNames {
        return CachedValuesManager.getCachedValue(elmFile, VISIBLE_VALUES_KEY) {
            val fromGlobal = GlobalScope.forElmFile(elmFile)?.getVisibleValues() ?: emptyList()
            val fromTopLevel = getDeclaredValues(elmFile)

            // Explicit imports shadow names from wildcard imports, so we need to sort the names
            val fromImports = elmFile.findChildrenByClass(ElmImportClause::class.java)
                    .flatMap { getVisibleImportValues(it) }
                    .sortedBy { it.fromWildcard }
                    .map { it.element }

            val visibleValues = VisibleNames(global = fromGlobal, topLevel = fromTopLevel, imported = fromImports)
            Result.create(visibleValues, elmFile.project.modificationTracker)
        }
    }

    /** Get all names that can be referenced from [elmFile], with or without a qualifier */
    fun getReferencableValues(elmFile: ElmFile): VisibleNames {
        return CachedValuesManager.getCachedValue(elmFile, REFRENCABLE_VALUES_KEY) {
            val fromGlobal = GlobalScope.forElmFile(elmFile)?.getVisibleValues() ?: emptyList()
            val fromTopLevel = getDeclaredValues(elmFile)

            // Explicit imports shadow names from wildcard imports, so we need to sort the names
            val fromImports = getImportDecls(elmFile)
                    .flatMap { getAllImportNames(it) }
                    .sortedBy { it.fromWildcard }
                    .map { it.element }

            val visibleValues = VisibleNames(global = fromGlobal, topLevel = fromTopLevel, imported = fromImports)
            Result.create(visibleValues, elmFile.project.modificationTracker)
        }
    }

    private data class ExposedElement(val fromWildcard: Boolean, val element: ElmNamedElement)

    private fun getVisibleImportValues(importClause: ElmImportClause): List<ExposedElement> {
        val allExposedValues = ImportScope.fromImportDecl(importClause)
                ?.getExposedValues()
                ?: return emptyList()

        if (importClause.exposesAll)
            return allExposedValues.map { ExposedElement(true, it) }

        // intersect the names exposed by the module with the names declared
        // in this import clause's exposing list.
        val exposedNames = mutableSetOf<String>()
        importClause.exposingList?.exposedValueList
                ?.mapTo(exposedNames) { it.lowerCaseIdentifier.text }

        importClause.exposingList?.exposedOperatorList
                ?.mapTo(exposedNames) { it.operatorIdentifier.text }

        return allExposedValues.filter {
            it.name in exposedNames
        }.map { ExposedElement(false, it) }
    }

    private fun getAllImportNames(importClause: ElmImportClause): List<ExposedElement> {
        return ImportScope.fromImportDecl(importClause)
                ?.getExposedValues()
                ?.map { ExposedElement(importClause.exposesAll, it) }
                ?: return emptyList()
    }


    // TYPES


    fun getDeclaredTypes(elmFile: ElmFile): List<ElmNamedElement> {
        return CachedValuesManager.getCachedValue(elmFile, DECLARED_TYPES_KEY) {
            val declaredTypes = (elmFile.getTypeDeclarations() as List<ElmNamedElement>) +
                    (elmFile.getTypeAliasDeclarations() as List<ElmNamedElement>)
            Result.create(declaredTypes, elmFile.project.modificationTracker)
        }
    }


    fun getVisibleTypes(elmFile: ElmFile): VisibleNames {
        return CachedValuesManager.getCachedValue(elmFile, VISIBLE_TYPES_KEY) {
            val fromGlobal = GlobalScope.forElmFile(elmFile)?.getVisibleTypes() ?: emptyList()
            val fromTopLevel = getDeclaredTypes(elmFile)
            val fromImports = elmFile.findChildrenByClass(ElmImportClause::class.java)
                    .flatMap { getVisibleImportTypes(it) }
            val names = VisibleNames(global = fromGlobal, topLevel = fromTopLevel, imported = fromImports)
            Result.create(names, elmFile.project.modificationTracker)
        }
    }


    private fun getVisibleImportTypes(importClause: ElmImportClause): List<ElmNamedElement> {
        val allExposedTypes = ImportScope.fromImportDecl(importClause)
                ?.getExposedTypes()
                ?: return emptyList()

        if (importClause.exposesAll)
            return allExposedTypes

        // intersect the names exposed by the module with the names declared
        // in this import clause's exposing list.
        val locallyExposedNames = importClause.exposingList?.exposedTypeList
                ?.mapTo(mutableSetOf<String>()) { it.upperCaseIdentifier.text }
                ?: return emptyList()
        return allExposedTypes.filter { locallyExposedNames.contains(it.name) }
    }


    // UNION CONSTRUCTORS AND RECORD CONSTRUCTORS


    fun getDeclaredConstructors(elmFile: ElmFile): List<ElmNamedElement> {
        return CachedValuesManager.getCachedValue(elmFile, DECLARED_CONSTRUCTORS_KEY) {
            val declaredConstructors = listOf(
                    elmFile.getTypeDeclarations().flatMap { it.unionVariantList },
                    elmFile.getTypeAliasDeclarations().filter { it.isRecordAlias }
            ).flatten()
            Result.create(declaredConstructors, elmFile.project.modificationTracker)
        }
    }


    fun getVisibleConstructors(elmFile: ElmFile): VisibleNames {
        return CachedValuesManager.getCachedValue(elmFile, VISIBLE_CONSTRUCTORS_KEY) {
            val fromGlobal = GlobalScope.forElmFile(elmFile)?.getVisibleConstructors() ?: emptyList()
            val fromTopLevel = getDeclaredConstructors(elmFile)
            val fromImports = elmFile.findChildrenByClass(ElmImportClause::class.java)
                    .flatMap { getVisibleImportConstructors(it) }
            val names = VisibleNames(global = fromGlobal, topLevel = fromTopLevel, imported = fromImports)
            Result.create(names, elmFile.project.modificationTracker)
        }

    }

    private fun getVisibleImportConstructors(importClause: ElmImportClause): List<ElmNamedElement> {
        val allExposedConstructors = ImportScope.fromImportDecl(importClause)
                ?.getExposedConstructors()
                ?: return emptyList()

        if (importClause.exposesAll)
            return allExposedConstructors

        val exposedTypes = importClause.exposingList?.exposedTypeList
                ?: return emptyList()

        // Intersect the names exposed by the module with the names declared
        // in this import clause's exposing list, making special consideration for
        // union types that expose all of their constructors.

        val locallyExposedUnionConstructorNames =
                exposedTypes.flatMap {
                    when {
                        it.exposesAll ->
                            (it.reference.resolve() as? ElmTypeDeclaration)
                                    ?.unionVariantList
                                    ?.map { it.name }
                                    ?: emptyList()

                        else ->
                            it.exposedUnionConstructors?.exposedUnionConstructors
                                    ?.map { it.upperCaseIdentifier.text }
                                    ?: emptyList()
                    }
                }

        val locallyExposedRecordConstructorNames = exposedTypes
                .mapNotNullTo(mutableSetOf()) {
                    if (it.exposedUnionConstructors == null)
                        it.upperCaseIdentifier.text
                    else
                        null
                }

        val allExposedNames = locallyExposedUnionConstructorNames.union(locallyExposedRecordConstructorNames)
        return allExposedConstructors.filter { allExposedNames.contains(it.name) }
    }
}
