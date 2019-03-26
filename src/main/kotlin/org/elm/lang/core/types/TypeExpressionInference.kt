package org.elm.lang.core.types

import com.intellij.openapi.util.Key
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import org.elm.lang.core.diagnostics.BadRecursionError
import org.elm.lang.core.diagnostics.ElmDiagnostic
import org.elm.lang.core.diagnostics.TypeArgumentCountError
import org.elm.lang.core.psi.*
import org.elm.lang.core.psi.elements.*


// Changes to type expressions always invalidate the whole project, since they influence inferred
// value types (e.g. removing a field from a record causes usages of that field everywhere to be invalid.)

/** A map of variant names to variant parameter tys, in declaration order */
typealias VariantParameters = Map<String, List<Ty>>

private val TY_UNION_CACHE_KEY: Key<CachedValue<ParameterizedInferenceResult<TyUnion>>> = Key.create("TY_UNION_INFERENCE")
private val TY_CACHE_KEY: Key<CachedValue<ParameterizedInferenceResult<Ty>>> = Key.create("TY_INFERENCE")
private val TY_VARIANT_CACHE_KEY: Key<CachedValue<ParameterizedInferenceResult<VariantParameters>>> = Key.create("TY_VARIANT_INFERENCE")

fun ElmTypeDeclaration.typeExpressionInference(): ParameterizedInferenceResult<TyUnion> {
    val cachedValue = CachedValuesManager.getCachedValue(this, TY_UNION_CACHE_KEY) {
        val inferenceResult = TypeExpression(this).beginTypeDeclarationInference(this)
        CachedValueProvider.Result.create(inferenceResult, project.modificationTracker)
    }
    return cachedValue.copy(value = TypeReplacement.freshenVars(cachedValue.value) as TyUnion)
}

fun ElmTypeAliasDeclaration.typeExpressionInference(): ParameterizedInferenceResult<Ty> {
    val cachedValue = CachedValuesManager.getCachedValue(this, TY_CACHE_KEY) {
        val inferenceResult = TypeExpression(this).beginTypeAliasDeclarationInference(this)
        CachedValueProvider.Result.create(inferenceResult, project.modificationTracker)
    }
    return cachedValue.copy(value = TypeReplacement.freshenVars(cachedValue.value))
}


fun ElmPortAnnotation.typeExpressionInference(): ParameterizedInferenceResult<Ty> {
    val cachedValue = CachedValuesManager.getCachedValue(this, TY_CACHE_KEY) {
        val inferenceResult = TypeExpression(this).beginPortAnnotationInference(this)
        CachedValueProvider.Result.create(inferenceResult, project.modificationTracker)
    }
    return cachedValue.copy(value = TypeReplacement.freshenVars(cachedValue.value))

}

fun ElmUnionVariant.typeExpressionInference(): ParameterizedInferenceResult<Ty> {
    val cachedValue = CachedValuesManager.getCachedValue(this, TY_CACHE_KEY) {
        val inferenceResult = TypeExpression(this).beginUnionConstructorInference(this)
        CachedValueProvider.Result.create(inferenceResult, project.modificationTracker)
    }
    return cachedValue.copy(value = TypeReplacement.freshenVars(cachedValue.value))
}


/** Get the type of the expression in this annotation, or null if the program is incomplete and no expression exists */
fun ElmTypeAnnotation.typeExpressionInference(): ParameterizedInferenceResult<Ty>? {
    val typeRef = typeExpression ?: return null

    // PSI changes inside a value declaration only invalidate the modification tracker for the
    // outer-most declaration, not the entire project. If this is a nested annotation, we need to
    // find that tracker.
    val parentModificationTracker = outermostDeclaration(strict = true)?.modificationTracker

    val cachedValue = CachedValuesManager.getCachedValue(typeRef, TY_CACHE_KEY) {
        val inferenceResult = TypeExpression(this).beginTypeRefInference(typeRef)

        val trackers = when (parentModificationTracker) {
            null -> arrayOf(project.modificationTracker)
            else -> arrayOf(project.modificationTracker, parentModificationTracker)
        }

        CachedValueProvider.Result.create(inferenceResult, *trackers)
    }
    return cachedValue.copy(value = TypeReplacement.freshenVars(cachedValue.value))
}

/** Get the names and parameter tys for all variants of this union */
fun ElmTypeDeclaration.variantInference(): ParameterizedInferenceResult<VariantParameters> =
        CachedValuesManager.getCachedValue(this, TY_VARIANT_CACHE_KEY) {
            val inferenceResult = TypeExpression(this).beginUnionVariantsInference(this)
            CachedValueProvider.Result.create(inferenceResult, project.modificationTracker)
        }

/**
 * Inference for type declarations and expressions like function annotations and constructor calls.
 *
 * For inference of value declarations and expressions, see [InferenceScope].
 *
 * ### Algorithm
 *
 * Inference for most type expressions is straight forward, but we have to keep track of type
 * variables in order to correctly infer parameterized type expressions.
 *
 * Vars are scoped to a single declaration or annotation. Within a scope, all vars with the same
 * name must refer to the same object.
 *
 * To infer the types of parameterized type expressions, we infer the referenced target type, which
 * will be either a union type or a type alias, and will have one type unique type variable for each
 * parameter. We then infer the types of the arguments and use [TypeReplacement] to replace the type
 * variables in the parameters with their arguments.
 *
 * This two step process is simpler than trying to pass arguments around while inferring
 * declarations, and opens the door to caching the [Ty]s for declarations and aliases.
 */
class TypeExpression(
        private val root: ElmPsiElement,
        private val varsByName: MutableMap<String, TyVar> = mutableMapOf(),
        private val diagnostics: MutableList<ElmDiagnostic> = mutableListOf(),
        private val activeAliases: MutableSet<ElmTypeAliasDeclaration> = mutableSetOf()
) {
    fun beginPortAnnotationInference(annotation: ElmPortAnnotation): ParameterizedInferenceResult<Ty> {
        val ty = annotation.typeExpression?.let { typeExpressionType(it) } ?: TyUnknown()
        return result(ty)
    }

    fun beginTypeRefInference(typeExpr: ElmTypeExpression): ParameterizedInferenceResult<Ty> {
        val ty = typeExpressionType(typeExpr)
        return result(ty)
    }

    fun beginTypeDeclarationInference(typeDeclaration: ElmTypeDeclaration): ParameterizedInferenceResult<TyUnion> {
        val ty = typeDeclarationType(typeDeclaration)
        return result(ty)
    }

    fun beginUnionVariantsInference(typeDeclaration: ElmTypeDeclaration): ParameterizedInferenceResult<VariantParameters> {
        val variants = typeDeclaration.unionVariantList.associate { it.name to unionVariantParamterTypes(it) }
        return result(variants)
    }

    fun beginUnionConstructorInference(variant: ElmUnionVariant): ParameterizedInferenceResult<Ty> {
        val decl = variant.parentOfType<ElmTypeDeclaration>()
                ?.let { typeDeclarationType(it) }
                ?: return result(TyUnknown())

        val params = variant.allParameters.map { typeSignatureDeclType(it) }.toList()

        val ty: Ty = if (params.isNotEmpty()) {
            // Constructors with parameters are functions returning the type.
            TyFunction(params, decl)
        } else {
            // Constructors without parameters are just instances of the type, since there are no nullary functions.
            decl
        }
        return result(ty)
    }

    fun beginTypeAliasDeclarationInference(decl: ElmTypeAliasDeclaration): ParameterizedInferenceResult<Ty> {
        val record = decl.aliasedRecord
        val params = decl.lowerTypeNameList.map { getTyVar(it.name) }.toList()

        if (decl in activeAliases) {
            diagnostics += BadRecursionError(decl)
            return result(TyUnknown())
        }

        activeAliases += decl

        val ty = if (record == null) {
            decl.typeExpression?.let { typeExpressionType(it) } ?: TyUnknown()
        } else {
            recordTypeDeclType(record)
        }

        val aliasInfo = AliasInfo(decl.moduleName, decl.upperCaseIdentifier.text, params)
        return result(ty.withAlias(aliasInfo))
    }

    private fun <T> result(value: T) = ParameterizedInferenceResult(diagnostics, value)


    /** Get the type for an entire type expression */
    private fun typeExpressionType(typeExpr: ElmTypeExpression): Ty {
        val segments = typeExpr.allSegments.map {
            typeSignatureDeclType(it)
        }.toList()
        val last = segments.last()
        return when {
            segments.size == 1 -> last
            else -> TyFunction(segments.dropLast(1), last).uncurry()
        }
    }

    /** Get the type for one segment of a type expression */
    private fun typeSignatureDeclType(decl: ElmTypeSignatureDeclarationTag): Ty {
        return when (decl) {
            is ElmTypeVariable -> getTyVar(decl.identifier.text)
            is ElmRecordType -> recordTypeDeclType(decl)
            is ElmTupleType -> if (decl.unitExpr != null) TyUnit() else TyTuple(decl.typeExpressionList.map { typeExpressionType(it) })
            is ElmTypeRef -> typeRefType(decl)
            is ElmTypeExpression -> typeExpressionType(decl)
            else -> error("unimplemented type $decl")
        }
    }

    private fun recordTypeDeclType(record: ElmRecordType): TyRecord {
        val declaredFields = record.fieldTypeList.associate { it.lowerCaseIdentifier.text to typeExpressionType(it.typeExpression) }
        val baseTy = record.baseTypeIdentifier?.referenceName?.let { getTyVar(it) }
        return TyRecord(declaredFields, baseTy)
    }

    private fun typeRefType(typeRef: ElmTypeRef): Ty {
        val argElements = typeRef.allArguments.toList()
        val args = argElements.map {
            typeSignatureDeclType(it)
        }
        val ref = typeRef.reference.resolve()

        val declaredTy = when (ref) {
            is ElmTypeAliasDeclaration -> when (root) {
                is ElmTypeAliasDeclaration -> {
                    val child = TypeExpression(ref, mutableMapOf(), diagnostics, activeAliases.toMutableSet())
                    child.beginTypeAliasDeclarationInference(ref).value
                }
                else -> ref.typeExpressionInference().value
            }
            is ElmTypeDeclaration -> {
                ref.typeExpressionInference().value
            }
            // In 0.19, unlike all other built-in types, Elm core doesn't define the List type anywhere, so the
            // reference won't resolve. So we check for a reference to that type here. Note that users can
            // create their own List types that shadow the built-in, so we only want to do this check if the
            // reference is null.
            // In 0.18, all the other basic types fail to resolve as well.
            // TODO [drop 0.18] remove the checks for everything except List
            null -> {
                when (typeRef.referenceName) {
                    "List" -> TyList(TyVar("a"))
                    "String" -> TyString
                    "Char" -> TyChar
                    "Bool" -> TyBool
                    "Int" -> TyInt
                    "Float" -> TyFloat
                    else -> TyUnknown()
                }
            }
            else -> error(typeRef, "Unexpected type reference")
        }

        // This cast is safe, since parameters of type declarations are always inferred as TyVar.
        // We know the parameters haven't been replaced yet, since we just created the ty ourselves.
        @Suppress("UNCHECKED_CAST")
        val params = when {
            declaredTy.alias != null -> declaredTy.alias!!.parameters
            declaredTy is TyUnion -> declaredTy.parameters
            else -> emptyList()
        } as List<TyVar>

        if (isInferable(declaredTy) && params.size != args.size) {
            diagnostics += TypeArgumentCountError(typeRef, args.size, params.size)
            return TyUnknown()
        }

        if (params.isEmpty()) {
            return declaredTy
        }

        return TypeReplacement.replace(declaredTy, params.zip(args).toMap())
    }

    private fun typeDeclarationType(declaration: ElmTypeDeclaration): TyUnion {
        val params = declaration.lowerTypeNameList.map { getTyVar(it.name) }
        return TyUnion(declaration.moduleName, declaration.name, params)
    }

    private fun unionVariantParamterTypes(variant: ElmUnionVariant): List<Ty> {
        return variant.allParameters.map { typeSignatureDeclType(it) }.toList()
    }

    private fun getTyVar(name: String) = varsByName.getOrPut(name) { TyVar(name) }
}

