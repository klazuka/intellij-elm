package org.elm.lang.core.resolve.scope

import com.intellij.openapi.util.Key
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.ParameterizedCachedValue
import org.elm.lang.core.ElmFileType
import org.elm.lang.core.lookup.ClientLocation
import org.elm.lang.core.psi.ElmFile
import org.elm.lang.core.psi.ElmNamedElement
import org.elm.lang.core.psi.descendantsOfType
import org.elm.lang.core.psi.elements.ElmImportClause
import org.elm.lang.core.psi.elements.ElmModuleDeclaration
import org.elm.lang.core.psi.globalModificationTracker
import org.elm.lang.core.stubs.index.ElmModulesIndex

private val VISIBLE_VALUES_KEY: Key<ParameterizedCachedValue<List<ElmNamedElement>, ElmFile>> = Key.create("VISIBLE_VALUES_KEY")
private val VISIBLE_TYPES_KEY: Key<ParameterizedCachedValue<List<ElmNamedElement>, ElmFile>> = Key.create("VISIBLE_TYPES_KEY")
private val VISIBLE_CTORS_KEY: Key<ParameterizedCachedValue<List<ElmNamedElement>, ElmFile>> = Key.create("VISIBLE_CTORS_KEY")

/**
 * The subset of implicitly exposed values, types and constructors provided by Elm's
 * standard library ("Core").
 */
class GlobalScope private constructor(private val clientFile: ElmFile) {

    companion object {

        fun forElmFile(elmFile: ElmFile): GlobalScope? {
            if (elmFile.elmProject?.isCore() != false) {
                // The `elm/core` standard library does not have an implicit global scope. It must explicitly
                // import modules like `List`, `String`, etc.
                return null
            }
            return GlobalScope(elmFile)
        }

        /**
         * Modules that the Elm compiler treats as being implicitly imported.
         */
        val defaultImports = listOf(
                "Basics",
                "Char",
                "List",
                "Maybe",
                "Result",
                "String",
                "Tuple",
                "Debug",
                "Platform",
                "Platform.Cmd",
                "Platform.Sub"
        )

        val defaultAliases = mapOf(
                "Cmd" to "Platform.Cmd",
                "Sub" to "Platform.Sub"
        )

        /**
         * Values and Types that are built-in to the Elm compiler. Any occurrences of
         * these symbols should be treated as always resolved.
         */
        val builtInValues = emptySet<String>()

        val builtInTypes = setOf("List")

        val allBuiltInSymbols = builtInValues.union(builtInTypes)

        fun implicitModulesMatching(name: String, clientFile: ElmFile): List<ElmModuleDeclaration> {
            val implicitModuleName = when (name) {
                in defaultImports -> name
                in defaultAliases.keys -> defaultAliases[name]
                else -> null
            } ?: return emptyList()

            return ElmModulesIndex.getAll(listOf(implicitModuleName), clientFile)
                    .filter { it.elmFile.isCore() }
        }


        private fun produceVisibleValues(clientFile: ClientLocation): List<ElmNamedElement> {
            fun helper(moduleName: String) =
                    ElmModulesIndex.get(moduleName, clientFile)
                            ?.let { ModuleScope.getDeclaredValues(it.elmFile) }

            val rest = mutableListOf<ElmNamedElement>()
            helper("Basics")?.list?.let { rest.addAll(it) }
            helper("List")?.get("::")?.let { rest.add(it) }
            return rest
        }


        // this function should also return List, but, unlike all the other global types, Elm doesn't
        // define it anywhere, so there's no element to return
        private fun produceVisibleTypes(location: ClientLocation): List<ElmNamedElement> {
            fun helper(moduleName: String) =
                    ElmModulesIndex.get(moduleName, location)
                            ?.let { ModuleScope.getDeclaredTypes(it.elmFile) }

            val rest = mutableListOf<ElmNamedElement>()
            helper("Basics")?.list?.let { rest.addAll(it) }
            helper("Char")?.get("Char")?.let { rest.add(it) }
            helper("Maybe")?.get("Maybe")?.let { rest.add(it) }
            helper("Result")?.get("Result")?.let { rest.add(it) }
            helper("String")?.get("String")?.let { rest.add(it) }
            helper("Platform")?.get("Program")?.let { rest.add(it) }
            helper("Platform.Cmd")?.get("Cmd")?.let { rest.add(it) }
            helper("Platform.Sub")?.get("Sub")?.let { rest.add(it) }
            return rest
        }

        private fun produceVisibleConstructors(location: ClientLocation): List<ElmNamedElement> {
            fun helper(moduleName: String) =
                    ElmModulesIndex.get(moduleName, location)
                            ?.let { ModuleScope.getDeclaredConstructors(it.elmFile) }

            val rest = mutableListOf<ElmNamedElement>()
            helper("Basics")?.list?.let { rest.addAll(it) }
            helper("Maybe")?.let { ctors ->
                ctors["Just"]?.let { rest.add(it) }
                ctors["Nothing"]?.let { rest.add(it) }
            }
            helper("Result")?.let { ctors ->
                ctors["Ok"]?.let { rest.add(it) }
                ctors["Err"]?.let { rest.add(it) }
            }
            return rest
        }
    }

    fun getVisibleValues(): List<ElmNamedElement> {
        val elmProject = clientFile.elmProject ?: return emptyList()
        return CachedValuesManager.getManager(clientFile.project).getParameterizedCachedValue(elmProject, VISIBLE_VALUES_KEY, {
            CachedValueProvider.Result.create(produceVisibleValues(it), it.globalModificationTracker)
        }, /*trackValue*/ false, /*parameter*/ clientFile)
    }


    fun getVisibleTypes(): List<ElmNamedElement> {
        val elmProject = clientFile.elmProject ?: return emptyList()
        return CachedValuesManager.getManager(clientFile.project).getParameterizedCachedValue(elmProject, VISIBLE_TYPES_KEY, {
            CachedValueProvider.Result.create(produceVisibleTypes(it), it.globalModificationTracker)
        }, /*trackValue*/ false, /*parameter*/ clientFile)
    }


    fun getVisibleConstructors(): List<ElmNamedElement> {
        val elmProject = clientFile.elmProject ?: return emptyList()
        return CachedValuesManager.getManager(clientFile.project).getParameterizedCachedValue(elmProject, VISIBLE_CTORS_KEY, {
            CachedValueProvider.Result.create(produceVisibleConstructors(it), it.globalModificationTracker)
        }, /*trackValue*/ false, /*parameter*/ clientFile)
    }

    fun getImportClauses(): List<ElmImportClause> {
        val fromString = { code: String ->
            (PsiFileFactory.getInstance(clientFile.project)
                .createFileFromText(code, clientFile.originalFile)
                ?.descendantsOfType<ElmImportClause>()
                ?.toList() ?: emptyList())
                .map {
                    it.elmFile.originalFile = clientFile.originalFile
                    it
                }
        }

        // https://package.elm-lang.org/packages/elm/core/latest/
        return fromString(
            "import Basics exposing (..)\n"
                    + "import List exposing (List, (::))\n"
                    + "import Maybe exposing (Maybe(..))\n"
                    + "import Result exposing (Result(..))\n"
                    + "import String exposing (String)\n"
                    + "import Char exposing (Char)\n"
                    + "import Tuple\n"
                    + "import Debug\n"
                    + "import Platform exposing (Program)\n"
                    + "import Platform.Cmd as Cmd exposing (Cmd)\n"
                    + "import Platform.Sub as Sub exposing (Sub)"
        )
    }
}
