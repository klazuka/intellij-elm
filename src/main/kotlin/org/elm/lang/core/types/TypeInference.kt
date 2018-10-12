package org.elm.lang.core.types

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.parentsOfType
import org.elm.lang.core.diagnostics.*
import org.elm.lang.core.psi.*
import org.elm.lang.core.psi.elements.*
import org.elm.lang.core.resolve.scope.ModuleScope

fun ElmValueDeclaration.inference(): InferenceResult = inference(emptySet()) // TODO cache
fun ElmValueDeclaration.inference(activeScopes: Set<ElmValueDeclaration>): InferenceResult {
    val visibleNames = HashSet<String>()

    // Add any visible names except imports.
    // For some reason, Elm lets you shadow imported names.
    parentsOfType<ElmFile>()
            .map { ModuleScope(it).getVisibleValues(includeImports = false) }
            .firstOrNull()
            ?.run { mapNotNullTo(visibleNames) { it.name } }

    // Add the function name itself name itself
    declaredNames(false).mapNotNullTo(visibleNames) { it.name }

    return InferenceScope(visibleNames, activeScopes.toMutableSet(), null).inferDeclaration(this)
}

/**
 * @property shadowableNames names of declared elements that will cause a shadowing error if redeclared
 * @property activeScopes scopes that are currently being inferred, to detect invalid recursion; copied from parent
 */
private class InferenceScope(
        private val shadowableNames: MutableSet<String>,
        private val activeScopes: MutableSet<ElmValueDeclaration>,
        parent: InferenceScope?
) {
    // cache for declared tys referenced in this scope; shared with parent
    private val resolvedDeclarations: MutableMap<ElmValueDeclaration, Ty> = parent?.resolvedDeclarations
            ?: mutableMapOf()
    // names declared in patterns; copied from parent since we can see parent's bindings, but ours
    // shouldn't be shared with other scopes that share a parent.
    private val bindings: MutableMap<ElmNamedElement, Ty> = parent?.bindings?.toMutableMap() ?: mutableMapOf()
    // errors encountered during inference
    private val diagnostics: MutableList<ElmDiagnostic> = mutableListOf()

    //<editor-fold desc="entry points">

    fun inferDeclaration(declaration: ElmValueDeclaration): InferenceResult {
        if (checkBadRecursion(declaration)) {
            return InferenceResult(emptyMap(), diagnostics, TyUnknown)
        }

        activeScopes += declaration

        val declaredTy = bindParameters(declaration)

        val expr = declaration.expression
        var bodyTy: Ty = TyUnknown
        if (expr != null) {
            bodyTy = inferExpressionType(expr)
            val expected = (declaredTy as? TyFunction)?.ret ?: declaredTy
            val parts = expr.parts.toList()
            // If the body is just a let expression, show the diagnostic on its expression rather than the whole body.
            val errorExpr = (parts.singleOrNull() as? ElmLetIn)?.expression ?: expr
            requireAssignable(errorExpr, bodyTy, expected)
        }

        val ty = if (declaredTy == TyUnknown) bodyTy else declaredTy
        return InferenceResult(bindings, diagnostics, ty)
    }

    private fun inferLambda(lambda: ElmAnonymousFunction): InferenceResult {
        // TODO [unification] infer param types
        lambda.patternList.forEach { bindPattern(it, TyUnknown, true) }
        val bodyTy = inferExpressionType(lambda.expression)
        val ty = TyFunction(lambda.patternList.map { TyUnknown }, bodyTy)
        return InferenceResult(bindings, diagnostics, ty)
    }

    private fun inferLetIn(letIn: ElmLetIn): InferenceResult {
        for (decl in letIn.valueDeclarationList) {
            val result = inferChild { inferDeclaration(decl) }
            resolvedDeclarations[decl] = result.ty

            val fdl = decl.functionDeclarationLeft
            if (fdl != null) {
                shadowableNames += fdl.name
            } else {
                val pattern = decl.pattern
                if (pattern != null) {
                    val patterns = PsiTreeUtil.collectElementsOfType(pattern, ElmLowerPattern::class.java)
                    for (p in patterns) {
                        bindings[p] = result.bindingType(p)
                        shadowableNames += p.name
                    }
                }
            }
        }

        val exprTy = inferExpressionType(letIn.expression)
        return InferenceResult(emptyMap(), diagnostics, exprTy)
    }

    private fun inferCaseOf(pattern: ElmPattern, caseTy: Ty, branchExpression: ElmExpression): InferenceResult {
        bindPattern(pattern, caseTy, false)
        val ty = inferExpressionType(branchExpression)
        return InferenceResult(bindings, diagnostics, ty)
    }

    private inline fun inferChild(block: InferenceScope.() -> InferenceResult): InferenceResult {
        val result = InferenceScope(shadowableNames.toMutableSet(), activeScopes.toMutableSet(), this).block()
        diagnostics += result.diagnostics
        return result
    }

    private fun checkBadRecursion(declaration: ElmValueDeclaration): Boolean {
        val isRecursive = declaration in activeScopes
        // Recursion is only allowed for functions with parameters
        val isBad = isRecursive &&
                declaration.functionDeclarationLeft.let { it == null || it.patterns.firstOrNull() == null }
        if (isBad) {
            diagnostics += BadRecursionError(declaration)
        }

        return isBad
    }

    //</editor-fold>
    //<editor-fold desc="inference">

    private fun inferExpressionType(expr: ElmExpression?): Ty {
        if (expr == null) return TyUnknown

        val parts1 = expr.parts.toList()
        val parts = parts1.map { part ->
            when (part) {
                is ElmOperator -> {
                    TyUnknown
                } // TODO operators
                is ElmOperandTag -> {
                    inferOperandType(part)
                }
                else -> TyUnknown
            }
        }

        // TODO operators
        return parts.singleOrNull() ?: TyUnknown
    }

    private fun inferOperandType(operand: ElmOperandTag): Ty {
        return when (operand) {
            is ElmAnonymousFunction -> inferChild { inferLambda(operand) }.ty
            is ElmCaseOf -> inferCaseType(operand)
            is ElmFieldAccess -> TyUnknown // TODO we need to get the record type from somewhere
            is ElmFunctionCall -> inferFunctionCallType(operand)
            is ElmIfElse -> inferIfElseType(operand)
            is ElmLetIn -> inferChild { inferLetIn(operand) }.ty
            is ElmList -> inferListType(operand)
            is ElmCharConstant -> TyChar
            is ElmStringConstant -> TyString
            is ElmNumberConstant -> {
                if (operand.isFloat) TyFloat
                else TyUnknown  // TODO int literals have type `numberN`, and we need to infer them
            }
            is ElmNegateExpression -> inferExpressionType(operand.expression)
            is ElmNonEmptyTuple -> TyTuple(operand.expressionList.map { inferExpressionType(it) })
            is ElmOperatorAsFunction -> inferOperatorAsFunctionType(operand)
            is ElmRecord -> inferRecordType(operand)
            is ElmValueExpr -> inferValueExprType(operand)
            is ElmUnit -> TyUnit
            is ElmExpression -> inferExpressionType(operand) // parenthesized expression
            is ElmGlslCode -> TyShader
            is ElmTupleConstructor -> TyUnknown // TODO [drop 0.18] remove this case
            else -> error("unexpected operand type $operand")
        }
    }

    private fun inferCaseType(caseOf: ElmCaseOf): Ty {
        // Currently, if the type of a case expression doesn't match the value it's assigned to, we issue a
        // diagnostic on the entire case expression. The elm compiler only issues the diagnostic on
        // the first branch expression.

        val caseOfExprTy = inferExpressionType(caseOf.expression)
        var ty: Ty = TyUnknown
        var errorEncountered = false

        // TODO: check patterns cover possibilities
        for (branch in caseOf.branches) {
            // The elm compiler stops issuing diagnostics for branches when it encounters most errors,
            // but will still issue errors within expressions if an earlier type error was encountered
            val pat = branch.pattern ?: break
            val branchExpression = branch.expression ?: break

            if (errorEncountered) {
                // just check for internal errors
                bindPattern(pat, TyUnknown, false)
                inferExpressionType(branchExpression)
                continue
            }

            val result = inferChild { inferCaseOf(pat, caseOfExprTy, branchExpression) }

            if (result.diagnostics.isNotEmpty()) {
                errorEncountered = true
                continue
            }

            if (assignable(result.ty, ty)) {
                ty = result.ty
            } else {
                diagnostics += TypeMismatchError(branchExpression, result.ty, ty)
                errorEncountered = true
            }
        }
        return ty
    }


    private fun inferRecordType(record: ElmRecord): Ty {
        return when {
            record.baseRecordIdentifier != null -> TyUnknown // TODO the type is the type of the base record
            else -> TyRecord(record.fieldList.associate { f ->
                f.lowerCaseIdentifier.text to inferExpressionType(f.expression)
            })
        }
    }

    private fun inferListType(expr: ElmList): Ty {
        val expressionList = expr.expressionList
        val expressionTys = expressionList.map { inferExpressionType(it) }

        for (i in 1..expressionList.lastIndex) {
            // Only issue an error on the first mismatched expression
            if (!requireAssignable(expressionList[i], expressionTys[i], expressionTys[0])) {
                break
            }
        }

        return TyList(expressionTys.firstOrNull() ?: TyUnknown)
    }

    private fun inferIfElseType(ifElse: ElmIfElse): Ty {
        val expressionList = ifElse.expressionList
        if (expressionList.size < 3 || expressionList.size % 2 == 0) return TyUnknown // incomplete program
        val expressionTys = expressionList.map { inferExpressionType(it) }

        // check all the conditions
        for (i in 0 until expressionList.lastIndex step 2) {
            requireAssignable(expressionList[i], expressionTys[i], TyBool)
        }

        // check that all branches match the first
        for (i in expressionList.indices) {
            if (i != expressionList.lastIndex && (i < 3 || i % 2 == 0)) continue

            if (!requireAssignable(expressionList[i], expressionTys[i], expressionTys[1])) {
                // Only issue an error on the first mismatched branch to avoid spam in the case the
                // only the first branch is different
                break
            }
        }

        return expressionTys[1]
    }

    private fun inferValueExprType(expr: ElmValueExpr): Ty {
        val ref = expr.reference.resolve() ?: return TyUnknown

        // If the value is a parameter, its type has already been added to bindings
        bindings[ref]?.let {
            if (it is TyInProgressBinding) {
                diagnostics += CyclicDefinitionError(expr)
                return TyUnknown
            }
            return it
        }

        return when (ref) {
            is ElmUnionMember -> ref.ty
            is ElmTypeAliasDeclaration -> ref.ty
            is ElmFunctionDeclarationLeft -> {
                val decl = ref.parentOfType<ElmValueDeclaration>() ?: return TyUnknown
                inferValueDeclType(decl)
            }
            is ElmPortAnnotation -> ref.ty
            // This should never happen, but it's worth checking for in case we miss a parameter binding
            else -> {
                error("Unexpected reference type ${ref.elementType}")
            }
        }
    }

    private fun inferFunctionCallType(call: ElmFunctionCall): Ty {
        val targetTy = inferOperandType(call.target)

        val arguments = call.arguments.toList()
        val paramTys = if (targetTy is TyFunction) targetTy.parameters else listOf()

        if (targetTy != TyUnknown && arguments.size > paramTys.size) {
            diagnostics.add(ArgumentCountError(call, arguments.size, paramTys.size))
        }

        if (targetTy !is TyFunction) return TyUnknown

        for ((arg, paramTy) in arguments.zip(paramTys)) {
            val argTy = inferOperandType(arg)
            requireAssignable(arg, argTy, paramTy)
        }

        return when {
            // partial application, return a function
            arguments.size < paramTys.size -> TyFunction(paramTys.drop(arguments.size), targetTy.ret)
            else -> targetTy.ret
        }
    }

    private fun inferOperatorAsFunctionType(op: ElmOperatorAsFunction): Ty {
        var ref = op.reference.resolve()
        // For operators, we need to resolve the infix declaration to the actual function
        if (ref is ElmInfixDeclaration) {
            ref = ref.valueExpr?.reference?.resolve()
        }
        val decl = ref?.parentOfType<ElmValueDeclaration>() ?: return TyUnknown
        return inferValueDeclType(decl)
    }

    private fun inferValueDeclType(decl: ElmValueDeclaration): Ty {
        if (checkBadRecursion(decl)) return TyUnknown

        // Currently, we don't use the inference if there's no annotation. It would be nice to do so,
        // but we would need to deal with mutual recursion.
        val existing = resolvedDeclarations[decl]
        if (existing != null) return existing
        // For performance, use the type annotation if there is one
        var ty = decl.typeAnnotation?.typeRef?.ty
        if (ty == null) {
            // Do a top level inference, since decl isn't a child of this scope
            val result = decl.inference(activeScopes)
            diagnostics += result.diagnostics
            ty = result.ty
        }
        resolvedDeclarations[decl] = ty
        return ty
    }

    //</editor-fold>
    //<editor-fold desc="binding">
    /** Cache the type for a pattern binding, or report an error if the name is shadowing something */
    fun setBinding(element: ElmNamedElement, ty: Ty) {
        val elementName = element.name
        if (elementName != null && !shadowableNames.add(elementName)) {
            diagnostics += RedefinitionError(element)
        }

        // Bind the element even if it's shadowing something so that later inference knows it's a parameter
        bindings[element] = ty
    }

    /** @return the entire declared type, or [TyUnknown] if no annotation exists */
    private fun bindParameters(valueDeclaration: ElmValueDeclaration): Ty {
        return when {
            valueDeclaration.functionDeclarationLeft != null -> {
                bindFunctionDeclarationParameters(valueDeclaration, valueDeclaration.functionDeclarationLeft!!)
            }
            valueDeclaration.pattern != null -> {
                bindPatternDeclarationParameters(valueDeclaration, valueDeclaration.pattern!!)
            }
            valueDeclaration.operatorDeclarationLeft != null -> {
                // TODO [drop 0.18] remove this case
                // this is 0.18 only, so we aren't going to bother implementing it
                bindings += PsiTreeUtil.collectElementsOfType(valueDeclaration.pattern, ElmLowerPattern::class.java)
                        .associate { it to TyUnknown }
                TyUnknown
            }
            else -> TyUnknown
        }
    }

    private fun bindFunctionDeclarationParameters(
            valueDeclaration: ElmValueDeclaration,
            decl: ElmFunctionDeclarationLeft
    ): Ty {
        val typeRef = valueDeclaration.typeAnnotation?.typeRef

        if (typeRef == null) {
            val patterns = decl.patterns.toList()
            patterns.forEach { pat -> bindPattern(pat, TyUnknown, true) }
            if (patterns.size > 1) {
                return TyFunction(patterns.map { TyUnknown }, TyUnknown)
            }
            return TyUnknown
        }

        decl.patterns.zip(typeRef.allParameters).forEach { (pat, type) -> bindPattern(pat, type.ty, true) }
        return typeRef.ty
    }

    private fun bindPatternDeclarationParameters(valueDeclaration: ElmValueDeclaration, pattern: ElmPattern): Ty {
        // We need to infer the branch expression before we can bind its parameters, but first we
        // add sentinels to `bindings` in case the expression contains references to any names
        // declared in the pattern, which is an error.
        val declaredNames = pattern.descendantsOfType<ElmLowerPattern>()
        declaredNames.associateTo(bindings) { it to TyInProgressBinding }
        val bodyTy = inferExpressionType(valueDeclaration.expression)
        // Now we can overwrite the sentinels we set earlier with the inferred type
        bindPattern(pattern, bodyTy, false)
        // If an error was encountered during binding, the sentinels might not have been
        // overwritten, so do a sanity check here.
        for (name in declaredNames) {
            if (bindings[name] == TyInProgressBinding) {
                error("failed to bind ${name.text}")
            }
        }
        return bodyTy
    }

    private fun bindPattern(pat: ElmFunctionParamOrPatternChildTag, ty: Ty, isParameter: Boolean) {
        when (pat) {
            is ElmAnythingPattern -> {
            }
            is ElmConsPattern -> {
                if (isParameter) diagnostics += PartialPatternError(pat)
            }
            is ElmListPattern -> {
                if (isParameter) diagnostics += PartialPatternError(pat)
            }
            is ElmConstantTag -> {
                if (isParameter) diagnostics += PartialPatternError(pat)
            }
            is ElmPattern -> {
                bindPattern(pat.child, ty, isParameter)
                bindPatternAs(pat.patternAs, ty)
            }
            is ElmLowerPattern -> setBinding(pat, ty)
            is ElmRecordPattern -> bindRecordPattern(pat, ty, isParameter)
            is ElmTuplePattern -> bindTuplePattern(pat, ty, isParameter)
            is ElmUnionPattern -> bindUnionPattern(pat, isParameter)
            is ElmUnit -> {
            }
            else -> error("unexpected type $pat")
        }
    }

    private fun bindPatternAs(pat: ElmPatternAs?, ty: Ty) {
        if (pat != null) setBinding(pat, ty)
    }

    private fun bindUnionPattern(pat: ElmUnionPattern, isParameter: Boolean) {
        // If the referenced union member isn't a constructor (e.g. `Nothing`), then there's nothing to bind
        val memberTy = (pat.reference.resolve() as? ElmUnionMember)?.ty ?: return
        val argumentPatterns = pat.argumentPatterns.toList()

        fun issueError(actual: Int, expected: Int) {
            diagnostics += ArgumentCountError(pat, actual, expected)
            pat.namedParameters.forEach { setBinding(it, TyUnknown) }
        }

        if (memberTy is TyFunction) {
            if (argumentPatterns.size != memberTy.parameters.size) {
                issueError(argumentPatterns.size, memberTy.parameters.size)
            } else {
                for ((p, t) in argumentPatterns.zip(memberTy.parameters)) {
                    // The other option is an UpperCaseQID, which doesn't bind anything
                    if (p is ElmFunctionParamOrPatternChildTag) bindPattern(p, t, isParameter)
                }
            }
        } else if (argumentPatterns.isNotEmpty()) {
            issueError(argumentPatterns.size, 0)
        }
    }

    private fun bindTuplePattern(pat: ElmTuplePattern, ty: Ty, isParameter: Boolean) {
        if (ty !is TyTuple) {
            pat.patternList.forEach { bindPattern(it, TyUnknown, isParameter) }
            // TODO [unification] handle binding vars
            if (ty !is TyVar) {
                val actualTy = TyTuple(uniqueVars(pat.patternList.size))
                diagnostics += TypeMismatchError(pat, actualTy, ty)
            }
            return
        }
        pat.patternList
                .zip(ty.types)
                .forEach { (pat, type) -> bindPattern(pat.child, type, isParameter) }
    }

    private fun bindRecordPattern(pat: ElmRecordPattern, ty: Ty, isParameter: Boolean) {
        val lowerPatternList = pat.lowerPatternList
        if (ty !is TyRecord || lowerPatternList.any { it.name !in ty.fields }) {
            val actualTyParams = lowerPatternList.map { it.name }.zip(uniqueVars(lowerPatternList.size))
            val actualTy = TyRecord(actualTyParams.toMap())

            // For pattern declarations, the elm compiler issues diagnostics on the expression
            // rather than the pattern, but it's easier for us to issue them on the pattern instead.
            diagnostics += TypeMismatchError(pat, actualTy, ty)

            for (p in lowerPatternList) {
                bindPattern(p, TyUnknown, isParameter)
            }

            return
        }

        for (id in lowerPatternList) {
            val fieldTy = ty.fields[id.name]!!
            bindPattern(id, fieldTy, isParameter)
        }
    }

    //</editor-fold>
    //<editor-fold desc="coercion">

    private fun requireAssignable(element: PsiElement, ty1: Ty, ty2: Ty): Boolean {
        val assignable = assignable(ty1, ty2)
        if (!assignable) {
            diagnostics.add(TypeMismatchError(element, ty1, ty2))
        }
        return assignable
    }

    /** Return `false` if [ty1] definitely cannot be assigned to [ty2] */
    private fun assignable(ty1: Ty, ty2: Ty): Boolean {
        // TODO inference for vars
        return ty1 === ty2 || ty2 is TyVar || ty2 is TyUnknown || when (ty1) {
            is TyVar -> true
            is TyTuple -> ty2 is TyTuple
                    && ty1.types.size == ty2.types.size
                    && allAssignable(ty1.types, ty2.types)
            is TyRecord -> ty2 is TyRecord
                    && ty1.fields.size == ty2.fields.size
                    && ty1.fields.all { (k, v) -> ty2.fields[k]?.let { assignable(v, it) } ?: false }
            is TyUnion -> ty2 is TyUnion
                    && ty1.name == ty2.name
                    && ty1.module == ty2.module
                    && allAssignable(ty1.parameters, ty2.parameters)
            is TyFunction -> ty2 is TyFunction
                    && allAssignable(ty1.allTys, ty2.allTys)
            // object tys are covered by the identity check above
            TyShader, TyUnit -> false
            TyUnknown -> true
            TyInProgressBinding -> error("should never try to assign TyInProgressBinding")
        }
    }

    private fun allAssignable(ty1: List<Ty>, ty2: List<Ty>) = ty1.zip(ty2).all { (l, r) -> assignable(l, r) }

    //</editor-fold>
}


/**
 * @property ty the return type of the function or expression being inferred
 */
data class InferenceResult(val bindings: Map<ElmNamedElement, Ty>,
                           val diagnostics: List<ElmDiagnostic>,
                           val ty: Ty) {
    fun bindingType(element: ElmNamedElement): Ty = bindings[element] ?: TyUnknown
}

/** Get the type for one part of a type ref */
private val ElmTypeSignatureDeclarationTag.ty: Ty
    get() = when (this) {
        is ElmUpperPathTypeRef -> ty
        is ElmTypeVariableRef -> TyVar(identifier.text) // TODO
        is ElmRecordType -> TyRecord(fieldTypeList.associate { it.lowerCaseIdentifier.text to it.typeRef.ty })
        is ElmTupleType -> if (unit != null) TyUnit else TyTuple(typeRefList.map { it.ty })
        is ElmParametricTypeRef -> ty
        is ElmTypeRef -> ty
        else -> error("unimplemented type $this")
    }

private val ElmParametricTypeRef.ty: TyUnion
    get() {
        val ref = reference.resolve()
        val parameters = allParameters.map { it.ty }.toList()
        val name = ref?.name ?: upperCaseQID.text
        val module = ref?.moduleName ?: builtInModule(name) ?: moduleName
        return TyUnion(module, name, parameters)
    }

private val ElmPsiElement.moduleName: String
    get() = elmFile.getModuleDecl()?.name ?: ""

private val ElmUpperPathTypeRef.ty: Ty
    get() {
        val ref = reference.resolve()

        return when (ref) {
            // If this is referencing an alias, use aliased type
            is ElmTypeAliasDeclaration -> ref.ty
            is ElmTypeDeclaration -> ref.ty
            else -> {
                val name = ref?.name ?: text
                val module = ref?.moduleName ?: builtInModule(name) ?: moduleName
                TyUnion(module, name, emptyList())
            }
        }
    }

private val ElmTypeAliasDeclaration.ty
    get() = typeRef?.ty ?: TyUnknown

private val ElmTypeRef.ty: Ty
    get() {
        val params = allParameters.toList()
        return when {
            params.size == 1 -> params[0].ty
            else -> TyFunction(params.dropLast(1).map { it.ty }, params.last().ty)
        }
    }

private val ElmTypeDeclaration.ty: Ty
    get() = TyUnion(moduleName, name, lowerTypeNameList.map { TyVar(it.name) })

private val ElmUnionMember.ty: Ty
    get() {
        val decl = parentOfType<ElmTypeDeclaration>()?.ty ?: return TyUnknown
        val params = allParameters.map { it.ty }.toList()

        return if (params.isNotEmpty()) {
            // Constructors with parameters are functions returning the type.
            TyFunction(params, decl)
        } else {
            // Constructors without parameters are just instances of the type, since there are no nullary functions.
            decl
        }
    }

private val ElmPortAnnotation.ty: Ty
    get() = typeRef?.ty ?: TyUnknown

/** Return the module name for built-in types, or null */
private fun builtInModule(name: String): String? {
    return when (name) {
        "Int", "Float", "Bool" -> "Basics"
        "String" -> "String"
        "Char" -> "Char"
        "List" -> "List"
        else -> null
    }
}

/** Return [count] [TyVar]s named a, b, ... z, a1, b1, ... */
private fun uniqueVars(count: Int): List<TyVar> {
    val s = "abcdefghijklmnopqrstuvwxyz"
    return (0 until count).map {
        TyVar(s[it % s.length] + if (it >= s.length) (it / s.length).toString() else "")
    }

}
