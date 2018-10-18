package org.elm.lang.core.types

import org.elm.lang.core.psi.ElmNamedElement
import org.elm.lang.core.psi.ElmTypeSignatureDeclarationTag
import org.elm.lang.core.psi.elements.*
import org.elm.lang.core.psi.parentOfType

/*
 * These functions create a Ty from an element directly, without context.
 */

/** Get the type for one part of a type ref */
fun typeSignatureDeclType(decl: ElmTypeSignatureDeclarationTag): Ty {
    return when (decl) {
        is ElmUpperPathTypeRef -> upperPathTypeRefType(decl)
        is ElmTypeVariableRef -> TyVar(decl.identifier.text)
        is ElmRecordType -> recordTypeDeclType(decl, null)
        is ElmTupleType -> if (decl.unit != null) TyUnit else TyTuple(decl.typeRefList.map { typeRefType(it) })
        is ElmParametricTypeRef -> parametricTypeRefType(decl)
        is ElmTypeRef -> typeRefType(decl)
        else -> error("unimplemented type $decl")
    }
}

fun recordTypeDeclType(record: ElmRecordType, alias: TyUnion?): TyRecord {
    val fields = record.fieldTypeList.associate { it.lowerCaseIdentifier.text to typeRefType(it.typeRef) }
    val baseName = record.baseTypeIdentifier?.referenceName
    return TyRecord(fields, baseName, alias)
}

fun parametricTypeRefType(typeRef: ElmParametricTypeRef): Ty {
    return resolvedTypeRefType(typeRef.reference.resolve())
}

fun upperPathTypeRefType(typeRef: ElmUpperPathTypeRef): Ty {
    return resolvedTypeRefType(typeRef.reference.resolve())
}

fun resolvedTypeRefType(ref: ElmNamedElement?): Ty {
    return when (ref) {
        is ElmTypeAliasDeclaration -> typeAliasDeclarationType(ref)
        is ElmTypeDeclaration -> typeDeclarationType(ref)
        // We only get here if the reference doesn't resolve. We could create a TyUnion from the
        // ref name, but we don't know what module it's supposed to be defined in, so that would
        // lead to false positives.
        else -> TyUnknown
    }
}

fun typeAliasDeclarationType(decl: ElmTypeAliasDeclaration): Ty {
    val record = decl.aliasedRecord
    if (record != null) {
        val aliasParams = decl.lowerTypeNameList.map { TyVar(it.name) }
        val aliasTy = TyUnion(decl.moduleName, decl.upperCaseIdentifier.text, aliasParams)
        return recordTypeDeclType(record, aliasTy)
    }
    return decl.typeRef?.let { typeRefType(it) } ?: return TyUnknown
}

fun typeRefType(typeRef: ElmTypeRef): Ty {
    val params = typeRef.allParameters.map { typeSignatureDeclType(it) }.toList()
    val last = params.last()
    return when {
        params.size == 1 -> params[0]
        last is TyFunction -> TyFunction(params.dropLast(1) + last.parameters, last.ret)
        else -> TyFunction(params.dropLast(1), last)
    }
}

fun typeDeclarationType(declaration: ElmTypeDeclaration): Ty {
    return TyUnion(declaration.moduleName, declaration.name, declaration.lowerTypeNameList.map { TyVar(it.name) })
}

fun unionMemberType(member: ElmUnionMember): Ty {
    val decl = member.parentOfType<ElmTypeDeclaration>()?.let { typeDeclarationType(it) } ?: return TyUnknown
    val params = member.allParameters.map { typeSignatureDeclType(it) }.toList()

    return if (params.isNotEmpty()) {
        // Constructors with parameters are functions returning the type.
        TyFunction(params, decl)
    } else {
        // Constructors without parameters are just instances of the type, since there are no nullary functions.
        decl
    }
}

fun portAnnotationType(annotation: ElmPortAnnotation): Ty {
    return annotation.typeRef?.let { typeRefType(it) } ?: TyUnknown
}
