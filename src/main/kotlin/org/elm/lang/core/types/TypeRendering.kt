package org.elm.lang.core.types

import com.intellij.codeInsight.documentation.DocumentationManagerUtil
import com.intellij.openapi.util.text.StringUtil
import org.elm.lang.core.psi.ElmFile
import org.elm.lang.core.resolve.scope.ModuleScope
import org.elm.lang.core.toElmLowerId

/**
 * Render a [Ty] to a string that can be shown to the user.
 *
 * @param linkify If true, add hyperlinks to union names
 * @param withModule If true, qualify union names with their module
 * @param elmFile If given, [withModule] is ignored and this file will be used to determine module qualifiers
 */
fun Ty.renderedText(linkify: Boolean = false, withModule: Boolean = false, elmFile: ElmFile? = null): String {
    return TypeRenderer(linkify, withModule, elmFile).render(this)
}

private class TypeRenderer(
        private val linkify: Boolean,
        private val withModule: Boolean,
        private val elmFile: ElmFile?
) {
    private val varDisplayNames = mutableMapOf<TyVar, String>()
    private val possibleNames = varNames()

    fun render(ty: Ty): String {
        return ty.alias?.renderedText() ?: when (ty) {
            is TyFunction -> renderFunc(ty)
            is TyUnion -> renderUnion(ty)
            is TyRecord -> renderRecord(ty)
            is TyTuple -> renderTuple(ty)
            is TyVar -> renderVar(ty)
            is TyUnit -> "()"
            is TyUnknown, TyInProgressBinding -> "unknown"
            is MutableTyRecord -> renderRecord(ty.toRecord())
        }
    }

    private fun renderVar(ty: TyVar): String {
        if (ty in varDisplayNames) return varDisplayNames[ty]!!

        // If two different normal vars in this ty have the same name, show a unique name instead to
        // avoid confusion. This is common with multiple unannotated functions, where they'll each
        // have types inferred as `a`, `b`, etc.
        val takenNames = varDisplayNames.values
        if (ty.typeclassName() == null && ty.name in takenNames) {
            val displayName = possibleNames.first { it !in takenNames }
            varDisplayNames[ty] = displayName
            return displayName
        }

        varDisplayNames[ty] = ty.name
        return ty.name
    }

    private fun renderFunc(ty: TyFunction): String {
        return (ty.parameters + ty.ret).joinToString(" → ") {
            if (it is TyFunction) "(${renderFunc(it)})" else render(it)
        }
    }

    private fun renderUnion(ty: TyUnion): String {
        if (ty == TyShader) return "shader"

        val name = buildString { renderLink(ty.name, ty.name) }
        val type = when {
            ty.parameters.isEmpty() -> name
            else -> ty.parameters.joinToString(" ", prefix = "$name ") {
                if (it is TyFunction
                        || it is TyUnion && it.parameters.isNotEmpty()
                        || it.alias?.parameters?.isNotEmpty() == true) {
                    "(${render(it)})"
                } else {
                    render(it)
                }
            }
        }
        return when {
            elmFile != null -> "${ModuleScope.getQualifierForName(elmFile, ty.module, ty.name) ?: ""}$type"
            withModule && ty.module.isNotBlank() -> "${ty.module}.$type"
            else -> type
        }
    }

    private fun renderRecord(ty: TyRecord): String {
        val prefix = if (ty.baseTy != null) "{ ${render(ty.baseTy)} | " else "{ "
        return ty.fields.entries.joinToString(", ", prefix = prefix, postfix = " }") { (name, ty) ->
            "$name : ${render(ty)}"
        }
    }

    private fun renderTuple(ty: TyTuple): String {
        return ty.types.joinToString(", ", prefix = "( ", postfix = " )") { render(it) }
    }

    private fun AliasInfo.renderedText(): String {
        return renderUnion(TyUnion(module, name, parameters))
    }

    private fun StringBuilder.renderLink(refText: String, text: String) {
        if (linkify) DocumentationManagerUtil.createHyperlink(this, refText, text, true)
        else append(text)
    }
}

/** Render a name or destructuring pattern to use as a parameter for a function or case branch */
fun Ty.renderParam(): String {
    return alias?.name?.toElmLowerId() ?: when (this) {
        is TyFunction -> "function"
        TyShader -> "shader"
        is TyUnion -> renderParam()
        is TyRecord, is MutableTyRecord -> "record"
        is TyTuple -> renderParam()
        is TyVar -> name
        is TyUnit -> "()"
        is TyUnknown, TyInProgressBinding -> "unknown"
    }
}

fun TyUnion.renderParam(): String {
    val singleParam = when (val p = parameters.singleOrNull()) {
        is TyUnion -> p.name
        is TyRecord, is MutableTyRecord -> p.alias?.name
        else -> null
    }
    return when {
        isTyList -> singleParam?.let { StringUtil.pluralize(it) } ?: "list"
        module == "Maybe" && name == "Maybe" -> singleParam?.let { "maybe$it" } ?: "maybe"
        else -> name
    }.toElmLowerId()
}

fun TyTuple.renderParam(): String =
        types.joinToString(", ", prefix = "(", postfix = ")") { it.renderParam() }

/** An infinite sequence of possible type variable names: (a, b, ... z, a1, b1, ...) */
fun varNames(): Sequence<String> = (0..Int.MAX_VALUE).asSequence().map { nthVarName(it) }

/** return the [n]th item from [varNames], calculated directly */
fun nthVarName(n: Int): String {
    val letter = VAR_LETTERS[n % 26]
    return when {
        n < 26 -> letter.toString()
        else -> "$letter${n / 26}"
    }
}

private const val VAR_LETTERS = "abcdefghijklmnopqrstuvwxyz"

