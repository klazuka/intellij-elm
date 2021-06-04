package org.elm.lang.core.types

import com.intellij.injected.editor.VirtualFileWindow
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiElement
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.ParameterizedCachedValue
import com.intellij.psi.util.PsiModificationTracker
import org.elm.lang.core.diagnostics.*
import org.elm.lang.core.psi.*
import org.elm.lang.core.psi.OperatorAssociativity.NON
import org.elm.lang.core.psi.elements.*
import org.elm.lang.core.resolve.ElmReferenceElement
import org.elm.lang.core.resolve.reference.LexicalValueReference
import org.elm.lang.core.resolve.scope.ModuleScope
import org.elm.lang.core.types.TypeReplacement.Companion.freeze

private val TYPE_INFERENCE_KEY: Key<ParameterizedCachedValue<InferenceResult, Set<ElmValueDeclaration>>> =
        Key.create("TYPE_INFERENCE_KEY")

/** Find the inference result that contains the given element */
fun PsiElement.findInference(): InferenceResult? {
    // the call here is non-strict here so that we can return this element
    return outermostDeclaration(strict = false)?.inference(emptySet())
}

/** Find the type of a given element, if the element is a value expression or declaration */
fun ElmPsiElement.findTy(): Ty? {
    return when (this) {
        is ElmFunctionDeclarationLeft -> {
            val decl = parentOfType<ElmValueDeclaration>() ?: return null
            return findInference()?.let { it.expressionTypes[decl] ?: it.ty }
        }
        is ElmInfixDeclaration -> {
            return funcRef?.reference?.resolve()?.findTy()
        }
        is ElmValueDeclaration -> {
            findInference()?.let { it.expressionTypes[this] ?: it.ty }
        }
        else -> {
            findInference()?.expressionTypes?.get(this)
        }
    }
}

private fun ElmValueDeclaration.inference(activeScopes: Set<ElmValueDeclaration>): InferenceResult {
    return CachedValuesManager.getManager(project).getParameterizedCachedValue(this, TYPE_INFERENCE_KEY, { useActiveScopes ->
        // Elm lets you shadow imported names, including auto-imported names, so only count names
        // declared in this file as shadowable.
        val shadowableNames = ModuleScope.getVisibleValues(elmFile).topLevel.mapNotNullTo(mutableSetOf()) { it.name }
        val result = InferenceScope(shadowableNames, useActiveScopes.toMutableSet(), false, null).beginDeclarationInference(this, true)

        if (containingFile.virtualFile is VirtualFileWindow) {
            // See ElmPsiElement.globalModificationTracker
            CachedValueProvider.Result.create(result, PsiModificationTracker.MODIFICATION_COUNT)
        } else {
            CachedValueProvider.Result.create(result, project.modificationTracker, modificationTracker)
        }
    }, /*trackValue*/ false, /*parameter*/ activeScopes)
}

// Several element types (e.g. ElmField, ElmFieldAccessExpr, ElmFieldAccessorFunctionExpr) use
// inference results to implement their references, so we can't resolve those elements when
// doing inference.
/**
 * Inference for a single lexical scope (declaration, lambda, or case branch).
 *
 * You can infer a top level declaration by creating an instance of this class and passing the declaration
 * element to [beginDeclarationInference]. This will walk all the branches of the PSI tree, creating nested
 * scopes as needed.
 *
 * @property shadowableNames names of declared elements that will cause a shadowing error if redeclared
 * @property activeScopes scopes that are currently being inferred, to detect invalid recursion; copied from parent
 * @property recursionAllowed if true, diagnostics about self-recursion won't be reported
 */
private class InferenceScope(
        private val shadowableNames: MutableSet<String>,
        private val activeScopes: MutableSet<ElmValueDeclaration>,
        private val recursionAllowed: Boolean,
        private val parent: InferenceScope?
) {
    /**
     * Cache for declared tys referenced in this scope; shared with parent
     * This will cause some items to be cached that aren't visible to all ancestors, but that's fine
     * since you can't read from the cache without a reference to the element anyway, and this way
     * we can cache declarations for sibling elements and other relatives.
     */
    private val resolvedDeclarations: MutableMap<ElmValueDeclaration, Ty> = parent?.resolvedDeclarations
            ?: mutableMapOf()

    /** names declared in parameters and patterns */
    private val bindings: MutableMap<ElmNamedElement, Ty> = mutableMapOf()

    /** errors encountered during inference */
    private val diagnostics: MutableList<ElmDiagnostic> = mutableListOf()

    /** Record diffs for elements that have a type error */
    private val recordDiffs: MutableMap<ElmPsiElement, RecordDiff> = mutableMapOf()

    /**
     * If this scope is a let-in, this set contains declarations that are direct children of this scope.
     *
     * This is used when inferring references to nested declarations that follow the current declaration lexically
     * so that we can find which ancestor to start inference from.
     */
    private val childDeclarations: MutableSet<ElmValueDeclaration> = mutableSetOf()

    /** The inferred types of elements that should be exposed through documentation. */
    private val expressionTypes: MutableMap<ElmPsiElement, Ty> = mutableMapOf()

    /** The unification table used for unannotated parameters */
    private val replacements: DisjointSet = parent?.replacements ?: DisjointSet()

    /**
     * All [TyVar]s that occur in this scope's type annotation, if there is one.
     *
     * This is used when inferring function call target types. Nested functions can contain
     * variables that reference variables in annotations of outer scopes. These vars are rigid at
     * call sites, while all other vars are flexible at call sites.
     */
    private var annotationVars: List<TyVar> = emptyList()

    private val ancestors: Sequence<InferenceScope> get() = generateSequence(this) { it.parent }

    private fun getBinding(e: ElmNamedElement): Ty? = ancestors.mapNotNull { it.bindings[e] }.firstOrNull()

    //<editor-fold desc="entry points">
    /*
     * These functions begin inference for elements that contain lexical scopes. Only one
     * `begin` function should be called on a scope instance.
     */

    fun beginDeclarationInference(declaration: ElmValueDeclaration, replaceExpressionTypes: Boolean): InferenceResult {
        val assignee = declaration.assignee

        // If the assignee has any syntax errors, we don't run inference on it. Trying to resolve
        // references in bodies when the parameters contain syntax errors can result in infinite
        // recursion and surprising bugs.
        if (assignee == null || assignee.hasErrors) {
            return toTopLevelResult(TyUnknown())
        }

        activeScopes += declaration

        // We need to bind parameters before we infer the body since the body can reference the
        // params in functions
        val binding = bindParameters(declaration)

        // The body of pattern declarations is inferred as part of parameter binding, so there's no
        // more to do here.
        if (declaration.pattern != null) {
            return toTopLevelResult(TyUnknown())
        }

        // For function declarations, we need to infer the body and check that it matches the
        // annotation, if there is one.
        val expr = declaration.expression
        var bodyTy: Ty = TyUnknown()
        if (expr != null) {
            bodyTy = inferExpression(expr)

            if (binding is ParameterBindingResult.Annotated) {
                val expected = (binding.ty as? TyFunction)?.partiallyApply(binding.count) ?: binding.ty
                requireAssignable(expr, bodyTy, expected)
            } else {
                checkToplevelCaseBranches(expr, bodyTy)
            }
        }

        val ty = when (binding) {
            is ParameterBindingResult.Annotated -> binding.ty
            is ParameterBindingResult.Unannotated -> {
                if (binding.count == 0) bodyTy
                else TyFunction(binding.params, bodyTy).uncurry()
            }
            is ParameterBindingResult.Other -> bodyTy
        }
        return toTopLevelResult(ty, replaceExpressionTypes)
    }

    private fun beginLambdaInference(lambda: ElmAnonymousFunctionExpr): InferenceResult {
        val patternList = lambda.patternList
        val paramVars = uniqueVars(patternList.size)
        patternList.zip(paramVars) { p, t -> bindPattern(p, t, true) }

        val expr = lambda.expression
        val bodyTy = inferExpression(expr)
        checkToplevelCaseBranches(expr, bodyTy)

        return InferenceResult(expressionTypes, diagnostics, recordDiffs, TyFunction(paramVars, bodyTy).uncurry())
    }

    private fun beginLetInInference(letIn: ElmLetInExpr): InferenceResult {
        val valueDeclarationList = letIn.valueDeclarationList
        childDeclarations += valueDeclarationList

        for (decl in valueDeclarationList) {
            // If a declaration was referenced by a child defined earlier in this scope, it has
            // already been inferred.
            if (decl !in resolvedDeclarations) {
                inferChildDeclaration(decl)
            }
        }

        val expr = letIn.expression
        val exprTy = inferExpression(expr)
        checkToplevelCaseBranches(expr, exprTy)

        return InferenceResult(expressionTypes, diagnostics, recordDiffs, exprTy)
    }

    private fun beginCaseBranchInference(
            pattern: ElmPattern,
            caseTy: Ty,
            branchExpression: ElmExpressionTag
    ): InferenceResult {
        bindPattern(pattern, caseTy, false)
        val ty = inferExpression(branchExpression)
        return InferenceResult(expressionTypes, diagnostics, recordDiffs, ty)
    }

    private inline fun inferChild(
            activeScopes: MutableSet<ElmValueDeclaration> = this.activeScopes.toMutableSet(),
            recursionAllowed: Boolean = this.recursionAllowed,
            block: InferenceScope.() -> InferenceResult
    ): InferenceResult {
        val result = InferenceScope(shadowableNames.toMutableSet(), activeScopes, recursionAllowed, this).block()
        diagnostics += result.diagnostics
        recordDiffs += result.recordDiffs
        expressionTypes += result.expressionTypes
        return result
    }

    private fun inferChildDeclaration(
            decl: ElmValueDeclaration,
            activeScopes: Set<ElmValueDeclaration> = this.activeScopes
    ): InferenceResult {
        val result = inferChild(activeScopes = activeScopes.toMutableSet()) { beginDeclarationInference(decl, false) }
        resolvedDeclarations[decl] = result.ty
        expressionTypes[decl] = result.ty

        // We need to keep track of declared function names and bound patterns so that other
        // children have access to them.
        val functionName = decl.functionDeclarationLeft?.name
        if (functionName != null) {
            shadowableNames += functionName
        } else {
            val pattern = decl.pattern
            if (pattern != null) {
                val patterns = pattern.descendantsOfType<ElmLowerPattern>()
                for (p in patterns) {
                    setBinding(p, result.elementType(p))
                    shadowableNames += p.name
                }
            }
        }
        return result
    }

    /** if [expr] is a case expression, make sure all the branches match */
    private fun checkToplevelCaseBranches(expr: ElmExpressionTag?, exprTy: Ty) {
        if (expr is ElmCaseOfExpr) {
            requireBranchesAssignable(expr, exprTy, TyUnknown())
        }
    }

    private fun checkRecursion(declaration: ElmValueDeclaration): Boolean {
        val isRecursive = declaration in activeScopes
        // Recursion is a compile-time error if the function doesn't have any parameters
        val fdl = declaration.functionDeclarationLeft
        if (isRecursive && !recursionAllowed && (fdl == null || fdl.patterns.firstOrNull() == null)) {
            diagnostics += BadRecursionError(declaration)
        }

        return isRecursive
    }

    // We only replace the expression types for inference scopes that will eventually be cached.
    // There's no need to replace everything for child calls since they share our replacements
    // table, and replacing them now would prevent record field references from being tracked in
    // nested declarations. We always need to replace the return value, since that gets freshened,
    // and that would prevent us from properly replacing vars in the ty.
    private fun toTopLevelResult(ty: Ty, replaceExpressionTypes: Boolean = true): InferenceResult {
        val exprs = when {
            replaceExpressionTypes -> {
                expressionTypes.mapValues { (_, t) ->
                    TypeReplacement.replace(t, replacements).also { freeze(it) }
                }
            }
            else -> expressionTypes
        }
        val outerVars = ancestors.drop(1).flatMap { it.annotationVars.asSequence() }.toList()
        val ret = TypeReplacement.replace(ty, replacements, outerVars)
        if (replaceExpressionTypes) freeze(ret)
        return InferenceResult(exprs, diagnostics, recordDiffs, ret)
    }

    //</editor-fold>
    //<editor-fold desc="inference">
    /*
     * These functions take elements from expressions and return their Ty. If the Ty can't be
     * inferred due to program error or unimplemented functionality, TyUnknown is returned.
     * These functions recurse down into children elements, if any, and report diagnostics on them.
     */

    private fun inferExpression(expr: ElmExpressionTag?): Ty {
        if (expr == null) return TyUnknown()
        return when (expr) {
            is ElmBinOpExpr -> inferBinOpExpr(expr)
            is ElmFunctionCallExpr -> inferFunctionCall(expr)
            is ElmAtomTag -> inferAtom(expr)
            else -> error(expr, "unexpected expression type: $expr")
        }
    }

    private fun inferBinOpExpr(expr: ElmBinOpExpr): Ty {
        val parts: List<ElmBinOpPartTag> = expr.parts.toList()

        // Get the operator types and precedences. We don't have to worry about invalid
        // code like `1 + + 1`, since it won't parse as an expression.
        val operatorPrecedences = HashMap<ElmOperator, OperatorPrecedence>(parts.size / 2)
        val operatorTys = HashMap<ElmOperator, TyFunction>(parts.size / 2)
        var lastPrecedence: OperatorPrecedence? = null
        for (part in parts) {
            if (part is ElmOperator) {
                val (ty, precedence) = inferOperatorAndPrecedence(part)
                when {
                    precedence == null || ty !is TyFunction || ty.parameters.size < 2 -> return TyUnknown()
                    precedence.associativity == NON && lastPrecedence?.associativity == NON -> {
                        // Non-associative operators can't be chained directly with other non-associative
                        // operators.
                        diagnostics += NonAssociativeOperatorError(expr, part)
                        return TyUnknown()
                    }
                    else -> {
                        operatorPrecedences[part] = precedence
                        operatorTys[part] = ty
                    }
                }
                lastPrecedence = precedence
            }
        }

        // Parse the tree and walk it, validating all the operator calls.
        fun validateTree(tree: BinaryExprTree<ElmBinOpPartTag>): TyAndRange {
            return when (tree) {
                is BinaryExprTree.Operand -> {
                    val ty = inferOperand(tree.operand as ElmOperandTag)
                    TyAndRange(tree.operand, ty)
                }
                is BinaryExprTree.Binary -> {
                    val l = validateTree(tree.left)
                    val r = validateTree(tree.right)
                    val func = operatorTys[tree.operator]!!
                    val lAssignable = requireAssignable(l.start, l.ty, func.parameters[0], l.end)
                    val rAssignable = requireAssignable(r.start, r.ty, func.parameters[1], r.end)
                    val ty = when {
                        lAssignable && rAssignable -> {
                            TypeReplacement.replace(func.partiallyApply(2), replacements, keepRecordsMutable = true)
                        }
                        else -> TyUnknown()
                    }
                    TyAndRange(l.start, r.end, ty)
                }
            }
        }

        val result = validateTree(BinaryExprTree.parse(parts, operatorPrecedences))
        expressionTypes[expr] = result.ty
        return result.ty
    }

    private fun inferOperand(operand: ElmOperandTag): Ty =
            when (operand) {
                is ElmFunctionCallExpr -> inferFunctionCall(operand)
                is ElmAtomTag -> inferAtom(operand)
                else -> error(operand, "unexpected operand type $operand")
            }

    private fun inferFunctionCall(expr: ElmFunctionCallExpr): Ty {
        val target = expr.target
        val arguments = expr.arguments.toList()

        // always infer the target and arguments so that they're added to expressionTypes
        val targetTy = inferAtom(target)
        val argTys = arguments.map { inferAtom(it) }

        fun argCountError(element: PsiElement, endElement: PsiElement, actual: Int, expected: Int): TyUnknown {
            diagnostics += ArgumentCountError(element, endElement, actual, expected)
            return TyUnknown()
        }

        if (target !is ElmFunctionCallTargetTag) {
            return argCountError(target, target, expr.arguments.count(), 0)
        }

        if (targetTy is TyVar) {
            val ty = TyFunction(argTys, TyVar("a"))
            return when {
                requireAssignable(target, targetTy, ty) -> ty.ret
                else -> TyUnknown()
            }
        }

        if (!isInferable(targetTy)) return TyUnknown()
        if (targetTy !is TyFunction) return argCountError(expr, expr, arguments.size, 0)

        var ok = true
        for (i in 0..(minOf(arguments.lastIndex, targetTy.parameters.lastIndex))) {
            // don't short-circuit: we need to check all args
            ok = requireAssignable(arguments[i], argTys[i], targetTy.parameters[i]) && ok
        }

        if (ok && arguments.size > targetTy.parameters.size) {
            var appliedTy = TypeReplacement.replace(targetTy.ret, replacements)

            // friendly error for the common case
            if (appliedTy !is TyFunction) {
                return argCountError(expr, expr, arguments.size, targetTy.parameters.size)
            }

            // If any of the arguments contain a function, the partially applied expression may
            // uncurry into a function.
            for (i in targetTy.parameters.size..arguments.lastIndex) {
                if (appliedTy !is TyFunction) return argCountError(target, arguments[i], 1, 0)
                if (!requireAssignable(arguments[i], argTys[i], appliedTy.parameters.first())) return TyUnknown()
                appliedTy = TypeReplacement.replace(appliedTy.partiallyApply(1), replacements)
            }

            expressionTypes[expr] = appliedTy
            return appliedTy
        }

        val resultTy = if (ok) {
            TypeReplacement.replace(targetTy.partiallyApply(arguments.size), replacements, keepRecordsMutable = true)
        } else {
            TyUnknown()
        }
        expressionTypes[expr] = resultTy
        return resultTy
    }

    private fun inferAtom(atom: ElmAtomTag): Ty {
        // For most atoms, we don't try to infer them if they contain errors.
        val ty = when {
            atom is ElmLetInExpr -> inferChild { beginLetInInference(atom) }.ty
            atom is ElmCaseOfExpr -> inferCase(atom)
            atom is ElmParenthesizedExpr -> inferExpression(atom.expression)
            atom.hasErrors -> TyUnknown()
            else -> when (atom) {
                is ElmAnonymousFunctionExpr -> inferLambda(atom)
                is ElmCharConstantExpr -> TyChar
                is ElmFieldAccessExpr -> inferFieldAccess(atom)
                is ElmFieldAccessorFunctionExpr -> inferFieldAccessorFunction(atom)
                is ElmGlslCodeExpr -> TyShader
                is ElmIfElseExpr -> inferIfElse(atom)
                is ElmListExpr -> inferList(atom)
                is ElmNegateExpr -> inferNegateExpression(atom)
                is ElmTupleExpr -> TyTuple(atom.expressionList.map { inferExpression(it) })
                is ElmNumberConstantExpr -> if (atom.isFloat) TyFloat else TyVar("number")
                is ElmOperatorAsFunctionExpr -> inferOperatorAsFunction(atom)
                is ElmRecordExpr -> inferRecord(atom)
                is ElmStringConstantExpr -> TyString
                is ElmUnitExpr -> TyUnit()
                is ElmValueExpr -> inferReferenceElement(atom)
                else -> error(atom, "unexpected atom type $atom")
            }
        }
        expressionTypes[atom] = ty
        return ty
    }

    private fun inferOperatorAndPrecedence(operator: ElmOperator): Pair<Ty, OperatorPrecedence?> {
        val opDecl = operator.reference.resolve() as? ElmInfixDeclaration ?: return TyUnknown() to null
        val precedence = opDecl.precedence ?: return TyUnknown() to null
        val implDecl = opDecl.funcRef?.reference?.resolve() ?: return TyUnknown() to null
        val ty = inferReferencedValueDeclaration(implDecl.parentOfType())
        return ty to OperatorPrecedence(precedence, opDecl.associativity)
    }

    private fun inferFieldAccess(expr: ElmFieldAccessExpr): Ty {
        val target = expr.targetExpr
        val targetType = inferFieldAccessTarget(target)
        val targetTy = replacements[targetType]
        val fieldIdentifier = expr.lowerCaseIdentifier
        val fieldName = fieldIdentifier.text

        if (targetTy is TyVar) {
            if (targetTy.rigid) {
                diagnostics += RecordBaseIdError(target, targetTy)
                return TyUnknown()
            }
            val ty = TyVar("b")
            trackReplacement(targetTy, MutableTyRecord(mutableMapOf(fieldName to ty), TyVar("a")))
            expressionTypes[expr] = ty
            return ty
        }

        if (targetTy is MutableTyRecord) {
            val ty = targetTy.fields.getOrPut(fieldName) { TyVar(nthVarName(targetTy.fields.size)) }
            expressionTypes[expr] = ty
            return ty
        }

        if (targetTy !is TyRecord) {
            if (isInferable(targetTy)) {
                diagnostics += FieldAccessOnNonRecordError(target, targetTy)
            }
            return TyUnknown()
        }

        if (fieldName !in targetTy.fields) {
            if (!targetTy.isSubset) {
                diagnostics += RecordFieldError(fieldIdentifier, fieldName)
            }
            return TyUnknown()
        }

        val ty = targetTy.fields.getValue(fieldName)
        expressionTypes[expr] = ty
        return ty
    }

    private fun inferFieldAccessTarget(target: ElmFieldAccessTargetTag): Ty {
        val ty = when (target) {
            is ElmValueExpr -> inferReferenceElement(target)
            is ElmParenthesizedExpr -> inferExpression(target.expression)
            is ElmRecordExpr -> inferRecord(target)
            is ElmFieldAccessExpr -> inferFieldAccess(target)
            else -> error(target, "unexpected field access target expression")
        }

        expressionTypes[target] = ty
        return ty
    }

    private fun inferLambda(lambda: ElmAnonymousFunctionExpr): Ty {
        // Self-recursion is allowed inside lambdas, so don't copy the active scopes when inferring them
        return inferChild(recursionAllowed = true) { beginLambdaInference(lambda) }.ty
    }

    private fun inferCase(caseOf: ElmCaseOfExpr): Ty {
        val caseOfExprTy = inferExpression(caseOf.expression)
        var ty: Ty? = null

        // TODO: check patterns cover possibilities
        for (branch in caseOf.branches) {
            if (branch.hasErrors) break

            val pat = branch.pattern
            val branchExpression = branch.expression ?: break
            val result = inferChild { beginCaseBranchInference(pat, caseOfExprTy, branchExpression) }

            // We check assignability of branches as a special-case of requireAssignable. We don't
            // want to do it now since we don't know what the case is being assigned to.
            if (ty == null) ty = result.ty
        }

        return ty ?: TyUnknown()
    }

    private fun inferRecord(record: ElmRecordExpr): Ty {
        val fields = record.fieldList.associate { f ->
            f.lowerCaseIdentifier to inferExpression(f.expression)
        }

        // If there's no base id, then the record is just the type of the fields.
        val recordIdentifier = record.baseRecordIdentifier
                ?: return TyRecord(fields.mapKeys { (k, _) -> k.text })

        // If there is a base id, we need to combine it with the fields
        val baseTy = inferReferenceElement(recordIdentifier)

        if (!isInferable(baseTy)) return TyUnknown()

        if (baseTy is TyVar) {
            val extRecord = TyRecord(fields.mapKeys { (k, _) -> k.text }, TyVar(baseTy.name))
            return if (requireAssignable(recordIdentifier, baseTy, extRecord)) {
                extRecord.copy(baseTy = baseTy)
            } else {
                TyUnknown()
            }
        }

        val baseFields = when (baseTy) {
            is TyRecord -> baseTy.fields
            is MutableTyRecord -> baseTy.fields
            else -> {
                diagnostics += RecordBaseIdError(recordIdentifier, baseTy)
                return TyUnknown()
            }
        }

        for ((name, ty) in fields) {
            val expected = baseFields[name.text]
            if (expected == null) {
                if (baseTy is TyRecord) {
                    if (!baseTy.isSubset) {
                        diagnostics += RecordFieldError(name, name.text)
                        recordDiffs[record] = calcRecordDiff(TyRecord(fields.mapKeys { (k, _) -> k.text }), baseTy)
                    }
                } else if (baseTy is MutableTyRecord) {
                    baseTy.fields[name.text] = ty
                }
            } else {
                requireAssignable(name, ty, expected)
            }
        }

        return baseTy
    }

    private fun inferList(expr: ElmListExpr): Ty {
        val expressionList = expr.expressionList
        val expressionTys = expressionList.map { inferExpression(it) }

        for (i in 1..expressionList.lastIndex) {
            if (requireAssignable(expressionList[i], expressionTys[i], expressionTys[0])) {
                // hack: if the list items are records, we will only end up setting the field
                // references on the first element, since that's what we return from this function.
                // So we set the expression type of all items to the ty of the first item so that we
                // can look up the fields for any of them. This is safe when all items are
                // assignable to each other.
                expressionTypes[expressionList[i]] = expressionTys[0]
            } else {
                // Only issue an error on the first mismatched expression
                break
            }
        }

        return TyList(expressionTys.firstOrNull() ?: TyVar("a"))
    }

    private fun inferIfElse(ifElse: ElmIfElseExpr): Ty {
        val expressionList = ifElse.expressionList
        if (expressionList.size < 3 || expressionList.size % 2 == 0) return TyUnknown() // incomplete program
        val expressionTys = expressionList.map { inferExpression(it) }

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

    private fun inferReferenceElement(expr: ElmReferenceElement): Ty {
        // Resolve lexical references shallowly. If they're a reference to a record pattern, we can
        // get the parameter binding rather than resolving to the type annotation.
        val ref = when (val exprRef = expr.reference) {
            is LexicalValueReference -> exprRef.resolveShallow()
            else -> exprRef.resolve()
        } ?: return TyUnknown()

        // If the value is a parameter, its type has already been added to bindings
        getBinding(ref)?.let {
            return when (it) {
                is TyInProgressBinding -> {
                    diagnostics += CyclicDefinitionError(expr)
                    TyUnknown()
                }
                else -> it
            }
        }

        return when (ref) {
            is ElmUnionVariant -> ref.typeExpressionInference().value
            is ElmTypeAliasDeclaration -> {
                val ty = ref.typeExpressionInference().value
                // Record aliases in expressions are constructor functions
                if (ty is TyRecord && ty.fields.isNotEmpty()) {
                    TyFunction(ty.fields.values.toList(), ty)
                } else {
                    ty
                }
            }
            is ElmFunctionDeclarationLeft -> inferReferencedValueDeclaration(ref.parentOfType())
            is ElmPortAnnotation -> ref.typeExpressionInference().value
            is ElmLowerPattern -> {
                // Pattern declarations might not have been inferred yet
                val parentPatternDecl = parentPatternDecl(ref)
                if (parentPatternDecl != null) {
                    inferReferencedValueDeclaration(parentPatternDecl)
                    // Now that the pattern's declaration has been inferred, the pattern has been
                    // added to bindings
                    return getBinding(ref)
                            ?: error(expr, "failed to destructure pattern")
                }

                // All patterns should now be bound
                error(expr, "failed to bind pattern")
            }
            is ElmFieldType -> {
                return (ref.parentOfType<ElmTypeAliasDeclaration>()
                        ?.typeExpressionInference()
                        ?.value as? TyRecord)
                        ?.fields?.get(ref.name)
                        ?: TyUnknown()
            }
            else -> error(ref, "Unexpected reference type")
        }
    }

    private fun inferFieldAccessorFunction(function: ElmFieldAccessorFunctionExpr): Ty {
        val field = function.identifier.text
        val tyVar = TyVar("b")
        return TyFunction(listOf(MutableTyRecord(mutableMapOf(field to tyVar), baseTy = TyVar("a"))), tyVar)
    }

    private fun inferNegateExpression(expr: ElmNegateExpr): Ty {
        val subExpr = expr.expression ?: return TyUnknown()
        val subTy = inferExpression(subExpr)
        return when {
            requireAssignable(subExpr, subTy, TyVar("number")) -> subTy
            else -> TyUnknown()
        }
    }

    private fun inferOperatorAsFunction(op: ElmOperatorAsFunctionExpr): Ty {
        var ref = op.reference.resolve()
        // For operators, we need to resolve the infix declaration to the actual function
        if (ref is ElmInfixDeclaration) {
            ref = ref.funcRef?.reference?.resolve()
        }
        return inferReferencedValueDeclaration(ref?.parentOfType())
    }

    private fun inferReferencedValueDeclaration(decl: ElmValueDeclaration?): Ty {
        if (decl == null) return TyUnknown()
        // Check for bad recursion before we do any early returns in case it needs to generate diagnostics.
        val recursive = checkRecursion(decl)
        val existing = resolvedDeclarations[decl]
        if (existing != null) return TypeReplacement.freshenVars(existing)
        // Use the type annotation if there is one
        var ty = decl.typeAnnotation?.typeExpressionInference(rigid = false)?.ty
        // If there's no annotation, do full inference on the function.
        if (ty == null) {
            // Trying to infer unannotated recursive funcitons would cause an infinite loop
            if (recursive) return TyUnknown()
            // First we have to find the parent of the declaration so that it has access to the
            // correct binding/declaration visibility. If there's no parent, we do a cached top-level inference.
            val declParentScope = ancestors.firstOrNull { decl in it.childDeclarations }
            ty = when (declParentScope) {
                null -> decl.inference(activeScopes).ty
                else -> declParentScope.inferChildDeclaration(decl, activeScopes).ty
            }
        }
        resolvedDeclarations[decl] = ty
        return ty
    }

    //</editor-fold>
    //<editor-fold desc="binding">

    /*
     * These functions take an element in a pattern and the Ty that it's binding to, and store the
     * bound names in `bindings`. We can then look up the bound Tys when we infer expressions later
     * in the scope. Note that every name defined in a pattern _must_ be bound to a Ty, even if just
     * to TyUnknown. This is still true in partial programs and other error states. Otherwise,
     * lookups will fail later in the inference.
     */

    /** Cache the type for a pattern binding, and report an error if the name is shadowing something */
    fun setBinding(element: ElmNamedElement, ty: Ty) {
        val elementName = element.name
        if (elementName != null && !shadowableNames.add(elementName)) {
            diagnostics += RedefinitionError(element)
        }

        // Bind the element even if it's shadowing something so that later inference knows it's a parameter
        bindings[element] = ty
        expressionTypes[element] = ty
    }

    /**
     * Bind all names created in a value declaration, either in function parameters or in a pattern
     * declaration.
     *
     * @return a pair of the entire declared type (or [TyUnknown] if no annotation exists), and the
     *   number of parameters in the declaration
     */
    private fun bindParameters(valueDeclaration: ElmValueDeclaration): ParameterBindingResult {
        return when {
            valueDeclaration.functionDeclarationLeft != null -> {
                bindFunctionDeclarationParameters(valueDeclaration, valueDeclaration.functionDeclarationLeft!!)
            }
            valueDeclaration.pattern != null -> {
                bindPatternDeclarationParameters(valueDeclaration, valueDeclaration.pattern!!)
                ParameterBindingResult.Other(0)
            }
            else -> ParameterBindingResult.Other(0)
        }
    }

    private fun bindFunctionDeclarationParameters(
            valueDeclaration: ElmValueDeclaration,
            decl: ElmFunctionDeclarationLeft
    ): ParameterBindingResult {
        val typeRefTy = valueDeclaration.typeAnnotation
                ?.typeExpressionInference(rigid = true)?.ty
        val patterns = decl.patterns.toList()

        if (typeRefTy == null) {
            val params = uniqueVars(patterns.size)
            patterns.zip(params) { pat, param -> bindPattern(pat, param, true) }
            return ParameterBindingResult.Unannotated(params, params.size)
        }
        val maxParams = (typeRefTy as? TyFunction)?.parameters?.size ?: 0
        if (patterns.size > maxParams) {
            diagnostics += ParameterCountError(patterns.first(), patterns.last(), patterns.size, maxParams)
            patterns.forEach { pat -> bindPattern(pat, TyUnknown(), true) }
            return ParameterBindingResult.Other(maxParams)
        }

        if (typeRefTy is TyFunction) {
            patterns.zip(typeRefTy.parameters) { pat, ty -> bindPattern(pat, ty, true) }
        }
        annotationVars = typeRefTy.allVars()
        return ParameterBindingResult.Annotated(typeRefTy, patterns.size)
    }

    private fun bindPatternDeclarationParameters(valueDeclaration: ElmValueDeclaration, pattern: ElmPattern) {
        // For case branches and pattern declarations like `(x,y) = (1,2)`, we need to finish
        // inferring the expression before we can bind the parameters. In these cases, it's an error
        // to use a name from the pattern in its expression (e.g. `{x} = {x=x}`). We need to know
        // about the declared names during inference in order to detect this, but we don't know the
        // type until inference is complete. Since we can't bind the names to a type, we instead
        // bind all the names to sentinel values, then infer the expression and check for cyclic
        // references, then finally overwrite the sentinels with the proper inferred type.
        val declaredNames = pattern.descendantsOfType<ElmLowerPattern>()
        declaredNames.associateWithTo(bindings) { TyInProgressBinding }
        val bodyTy = inferExpression(valueDeclaration.expression)
        // Now we can overwrite the sentinels we set earlier with the inferred type
        bindPattern(pattern, bodyTy, false)
        // If we made a mistake during binding, the sentinels might not have been
        // overwritten, so do a sanity check here.
        for (name in declaredNames) {
            if (getBinding(name) == TyInProgressBinding) {
                error(name, "failed to bind parameter")
            }
        }
    }

    private fun bindPattern(pat: ElmFunctionParamOrPatternChildTag, type: Ty, isParameter: Boolean) {
        val ty = replacements[type]
        when (pat) {
            is ElmAnythingPattern -> {
            }
            is ElmConsPattern -> {
                if (isParameter) {
                    diagnostics += PartialPatternError(pat)
                    bindConsPattern(pat, TyUnknown())
                } else {
                    bindConsPattern(pat, ty)
                }
            }
            is ElmListPattern -> {
                if (isParameter) {
                    diagnostics += PartialPatternError(pat)
                    bindListPattern(pat, TyUnknown())
                } else {
                    bindListPattern(pat, ty)
                }
            }
            is ElmConstantTag -> {
                if (isParameter) diagnostics += PartialPatternError(pat)
            }
            is ElmPattern -> {
                bindPattern(pat.child, ty, isParameter)
                pat.patternAs?.let { bindPattern(it, ty, isParameter) }
            }
            is ElmLowerPattern -> setBinding(pat, ty)
            is ElmRecordPattern -> bindRecordPattern(pat, ty, isParameter)
            is ElmTuplePattern -> bindTuplePattern(pat, ty, isParameter)
            is ElmUnionPattern -> bindUnionPattern(pat, ty, isParameter)
            is ElmUnitExpr -> requireAssignable(pat, ty, TyUnit(), patternBinding = true)
            else -> error(pat, "unexpected pattern type")
        }
    }

    private fun bindConsPattern(pat: ElmConsPattern, ty: Ty) {
        bindListPatternParts(pat, pat.parts.toList(), ty, true)
    }

    private fun bindListPattern(pat: ElmListPattern, ty: Ty) {
        bindListPatternParts(pat, pat.parts.toList(), ty, false)
    }

    private fun bindListPatternParts(pat: ElmPatternChildTag, parts: List<ElmPatternChildTag>, type: Ty, isCons: Boolean) {
        val ty = bindIfVar(pat, type) { TyList(TyVar("a")) }

        if (!isInferable(ty) || ty !is TyUnion || !ty.isTyList) {
            if (isInferable(ty)) {
                diagnostics += TypeMismatchError(pat, TyList(TyVar("a")), ty, patternBinding = true)
            }
            parts.forEach { bindPattern(it, TyUnknown(), false) }
            return
        }

        val innerTy = ty.parameters[0] // lists only have one parameter

        for (part in parts.dropLast(1)) {
            val t = if (part is ElmListPattern) ty else innerTy
            bindPattern(part, t, false)
        }

        if (parts.isNotEmpty()) {
            // The last part of a cons pattern binds to the entire list type, while in list patterns
            // it binds to the element type.
            bindPattern(parts.last(), if (isCons) ty else innerTy, false)
        }
    }

    private fun bindUnionPattern(pat: ElmUnionPattern, type: Ty, isParameter: Boolean) {
        val variant = pat.reference.resolve() as? ElmUnionVariant
        val variantTy = variant?.typeExpressionInference()?.value

        if (variantTy == null || !isInferable(variantTy)) {
            pat.namedParameters.forEach { setBinding(it, TyUnknown()) }
            return
        }

        fun issueError(actual: Int, expected: Int) {
            diagnostics += ArgumentCountError(pat, pat, actual, expected, true)
            pat.namedParameters.forEach { setBinding(it, TyUnknown()) }
        }

        val argumentPatterns = pat.argumentPatterns.toList()

        if (variantTy is TyFunction) {
            val ty = bindIfVar(pat, type) { variantTy.ret }
            if (requireAssignable(pat, ty, variantTy.ret)) {
                if (argumentPatterns.size != variantTy.parameters.size) {
                    issueError(argumentPatterns.size, variantTy.parameters.size)
                } else {
                    for ((p, t) in argumentPatterns.zip(variantTy.parameters)) {
                        // The other option is an ElmNullaryConstructorArgumentPattern, which doesn't bind anything
                        if (p is ElmFunctionParamOrPatternChildTag) bindPattern(p, t, isParameter)
                    }
                }
            } else {
                pat.namedParameters.forEach { setBinding(it, TyUnknown()) }
            }
        } else {
            val ty = bindIfVar(pat, type) { variantTy }
            if (requireAssignable(pat, ty, variantTy) && argumentPatterns.isNotEmpty()) {
                issueError(argumentPatterns.size, 0)
            } else {
                pat.namedParameters.forEach { setBinding(it, TyUnknown()) }
            }
        }
    }

    private fun bindTuplePattern(pat: ElmTuplePattern, type: Ty, isParameter: Boolean) {
        val patternList = pat.patternList
        val ty = bindIfVar(pat, type) { TyTuple(uniqueVars(patternList.size)) }

        if (ty !is TyTuple || ty.types.size != patternList.size) {
            patternList.forEach { bindPattern(it, TyUnknown(), isParameter) }
            if (isInferable(ty)) {
                val actualTy = TyTuple(uniqueVars(patternList.size))
                diagnostics += TypeMismatchError(pat, actualTy, ty, patternBinding = true)
            }
            return
        }

        patternList.zip(ty.types) { p, t ->
            bindPattern(p, t, isParameter)
        }
    }

    private fun bindRecordPattern(pat: ElmRecordPattern, type: Ty, isParameter: Boolean) {
        val fields = pat.lowerPatternList

        val ty = bindIfVar(pat, type) {
            MutableTyRecord(fields = mutableMapOf(), baseTy = TyVar("a"))
        }

        if (ty is MutableTyRecord) {
            fields.zip(uniqueVars(fields.size)).forEach { (field, v) ->
                ty.fields.getOrPut(field.name) { v }
            }
        } else if (ty !is TyRecord || fields.any { it.name !in ty.fields }) {
            if (isInferable(ty)) {
                val actualTyParams = fields.zip(uniqueVars(fields.size)) { f, v -> f.name to v }
                val actualTy = TyRecord(actualTyParams.toMap())
                val recordDiff = if (ty is TyRecord) calcRecordDiff(actualTy, ty).copy(missing = emptyMap()) else null

                // For pattern declarations, the elm compiler issues diagnostics on the expression
                // rather than the pattern, but it's easier for us to issue them on the pattern instead.
                diagnostics += TypeMismatchError(pat, actualTy, ty, patternBinding = true, recordDiff = recordDiff)
                recordDiff?.let { recordDiffs[pat] = it }
            }

            for (f in fields) {
                bindPattern(f, TyUnknown(), isParameter)
            }

            return
        }

        val tyFields = (ty as? MutableTyRecord)?.fields ?: (ty as TyRecord).fields
        for (f in fields) {
            bindPattern(f, tyFields.getValue(f.name), isParameter)
        }

        expressionTypes[pat] = type
    }

    //</editor-fold>
    //<editor-fold desc="unification">

    /*
     * These functions test that a Ty can be assigned to another Ty. The tests are lenient, so no
     * diagnostic will be reported if either type is TyUnknown.
     */

    private fun requireAssignable(
            element: PsiElement,
            ty1: Ty,
            ty2: Ty,
            endElement: ElmPsiElement? = null,
            patternBinding: Boolean = false
    ): Boolean {
        if (element is ElmCaseOfExpr) {
            return requireBranchesAssignable(element, ty1, ty2)
        }
        val assignable = try {
            assignable(ty1, ty2)
        } catch (e: InfiniteTypeException) {
            diagnostics += InfiniteTypeError(element)
            return false
        }
        if (!assignable) {
            val t1 = TypeReplacement.replace(ty1, replacements)
            val t2 = TypeReplacement.replace(ty2, replacements)

            if (generateTupleTypeMismatchError(element, ty1, ty2)) {
                return false
            }

            val diff = if (t1 is TyRecord && t2 is TyRecord) calcRecordDiff(t1, t2) else null
            // For let expressions, show the diagnostic on its value expression
            // rather than the whole thing.
            val errorElement = (element as? ElmLetInExpr)?.expression ?: element
            diagnostics += TypeMismatchError(errorElement, t1, t2, endElement, patternBinding, diff)
            if (diff != null && element is ElmRecordExpr) {
                recordDiffs[element] = diff
            }
        }
        return assignable
    }

    /**
     * Generate type errors for specific tuple elements that mismatch rather than the entire tuple
     * @return true if this function handled generating errors for the [element]
     */
    private fun generateTupleTypeMismatchError(element: PsiElement, ty1: Ty, ty2: Ty): Boolean {
        if (element !is ElmTupleExpr || ty1 !is TyTuple || ty2 !is TyTuple || ty1.types.size != ty2.types.size) {
            return false
        }
        val expressionList = element.expressionList
        for (i in expressionList.indices) {
            requireAssignable(expressionList[i], ty1.types[i], ty2.types[i])
        }
        return true
    }

    /**
     * Require that all branches of a case [expr] can be assigned to the type the case expr is
     * assigned to ([ty2]), or if [ty2] is a variable, to the type of the first branch ([ty1])
     */
    private fun requireBranchesAssignable(
            expr: ElmCaseOfExpr,
            ty1: Ty,
            ty2: Ty
    ): Boolean {
        // if we don't have a concrete type that the case is assigned to, then all branches need to
        // match the first
        val t2 = if (isInferable(ty2) && ty2 !is TyVar) ty2 else ty1
        // don't short-circuit
        return expr.branches.mapNotNull { branch ->
            val e = branch.expression ?: return@mapNotNull null
            val t1 = expressionTypes[e] ?: return@mapNotNull null
            requireAssignable(e, t1, t2)
        }.all { it }
    }

    /** Return `false` if [type1] definitely cannot be assigned to [type2] */
    private fun assignable(type1: Ty, type2: Ty): Boolean {
        val ty1 = replacements[type1]
        val ty2 = replacements[type2]

        val result = ty1 === ty2 || ty1 is TyUnknown || ty2 is TyUnknown || if (ty1 !is TyVar && ty2 is TyVar) {
            nonVarAssignableToVar(ty1, ty2)
        } else when (ty1) {
            is TyVar -> if (ty2 is TyVar) varsAssignable(ty1, ty2) else nonVarAssignableToVar(ty2, ty1)
            is TyTuple -> ty2 is TyTuple
                    && ty1.types.size == ty2.types.size
                    && allAssignable(ty1.types, ty2.types)
            is TyRecord -> {
                ty2 is TyRecord && recordAssignable(ty1, ty2) ||
                        ty2 is MutableTyRecord && mutableRecordAssignable(ty2, ty1)
            }
            is MutableTyRecord -> {
                ty2 is TyRecord && mutableRecordAssignable(ty1, ty2) ||
                        ty2 is MutableTyRecord && mutableRecordAssignable(ty1, ty2.asRecord())
            }
            is TyUnion -> ty2 is TyUnion
                    && ty1.name == ty2.name
                    && ty1.module == ty2.module
                    && allAssignable(ty1.parameters, ty2.parameters)
            is TyFunction -> ty2 is TyFunction && funcsAssignable(ty1, ty2)
            is TyUnit -> ty2 is TyUnit
            is TyUnknown -> true
            TyInProgressBinding -> error("should never try to assign $ty1")
        }

        if (result) trackReplacement(ty1, ty2)
        return result
    }

    private fun mutableRecordAssignable(ty1: MutableTyRecord, ty2: TyRecord): Boolean {
        if (!recordAssignable(ty1.asRecord(), ty2)) return false
        ty1.fields += ty2.fields
        return true
    }

    private fun recordAssignable(ty1: TyRecord, ty2: TyRecord): Boolean {
        val result = calcRecordDiff(ty1, ty2).isEmpty()
        if (result) {
            // Subset record tys are created from extension record declarations or field accessor
            // functions.
            // If we're assigning a concrete record to a subset, set the type of the extension base
            // var to the concrete record.
            if (!ty1.isSubset && ty2.baseTy is TyVar) {
                trackReplacement(ty1, ty2.baseTy)
            }
            // Do the same in the inverse case
            if (ty1.baseTy is TyVar && !ty2.isSubset) {
                trackReplacement(ty1.baseTy, ty2)
            }
            // If we assign a record value expression, we know what its field references resolve to
            ty1.fieldReferences.addAll(ty2.fieldReferences)
            ty2.fieldReferences.addAll(ty1.fieldReferences)
        }

        return result
    }

    private fun calcRecordDiff(actual: TyRecord, expected: TyRecord) = RecordDiff(
            extra = if (expected.isSubset) emptyMap() else {
                actual.fields.filterKeys { it !in expected.fields }
            },
            missing = if (actual.isSubset) emptyMap() else {
                expected.fields.filterKeys { it !in actual.fields }
            },
            mismatched = actual.fields.mapNotNull { (k, v) ->
                if (expected.fields[k]?.let { assignable(v, it) } == false) {
                    k to (v to expected.fields.getValue(k))
                } else {
                    null
                }
            }.toMap()
    )

    private fun allAssignable(ty1: List<Ty>, ty2: List<Ty>): Boolean {
        // don't short circuit so that all types get applied
        return ty1.size == ty2.size && ty1.zip(ty2) { l, r -> assignable(l, r) }.all { it }
    }

    private fun funcsAssignable(ty1: TyFunction, ty2: TyFunction): Boolean {
        // We need to handle currying. If ty1 has fewer parameters than ty2, it's an error. If ty1
        // has more parameters than ty2, all extra parameters are curried into a function that is
        // assigned to the last parameter of ty2.
        val tys1 = ty1.allTys
        val tys2 = ty2.allTys

        fun makeFunc(tys: List<Ty>) = when {
            tys.size == 1 -> tys.single()
            else -> TyFunction(tys.dropLast(1), tys.last())
        }

        val sharedSize = minOf(tys1.size, tys2.size) - 1
        val sharedAssignable = allAssignable(tys1.take(sharedSize), tys2.take(sharedSize))
        val tailAssignable = assignable(makeFunc(tys1.drop(sharedSize)), makeFunc(tys2.drop(sharedSize)))

        return sharedAssignable && tailAssignable
    }

    /**
     * Check if a [ty2] can that compares unequal to a [ty1] can be unified with it
     */
    private fun varsAssignable(ty1: TyVar, ty2: TyVar): Boolean {
        val tc1 = ty1.typeclassName()
        val tc2 = ty2.typeclassName()

        return when {
            !ty1.rigid && tc1 == null -> true
            !ty1.rigid && tc1 != null -> {
                typeclassesCompatable(tc1, tc2, unconstrainedAllowed = !ty2.rigid) ||
                        !ty2.rigid && typeclassesConstrainToCompappend(tc1, tc2)
            }
            ty1.rigid && tc1 == null -> !ty2.rigid && tc2 == null
            ty1.rigid && tc1 != null && ty2.rigid -> tc1 == tc2
            ty1.rigid && tc1 != null && !ty2.rigid -> typeclassesCompatable(tc1, tc2, unconstrainedAllowed = !ty2.rigid)
            else -> error("impossible")
        }
    }

    private fun nonVarAssignableToVar(ty: Ty, tyVar: TyVar): Boolean {
        // Vars with certain names are treated as typeclasses that only unify with a limited set of
        // types.
        //
        //  From the elm guide:
        //
        //  - `number` permits `Int` and `Float`
        //  - `appendable` permits `String` and `List a`
        //  - `comparable` permits `Int`, `Float`, `Char`, `String`, and lists/tuples of `comparable` values
        //  - `compappend` permits `String` and `List comparable`
        //
        //  Not listed in the elm guide:
        //
        //  - `number`     permits `comparable`
        //  - `comparable` permits `compappend`, `number` and lists/tuples of `number`
        //  - `appendable` permits `compappend`
        //  - `compappend` permits `List compappend`

        fun List<Ty>.allAssignableTo(typeclass: String): Boolean = all { assignable(it, TyVar(typeclass)) }

        return when {
            tyVar.name.startsWith("number") -> when (ty) {
                is TyUnion -> ty.isTyFloat || ty.isTyInt
                else -> false
            }
            tyVar.name.startsWith("appendable") -> when (ty) {
                is TyUnion -> ty.isTyString || ty.isTyList
                else -> false
            }
            tyVar.name.startsWith("comparable") -> when (ty) {
                is TyTuple -> ty.types.allAssignableTo("comparable")
                is TyUnion -> ty.isTyFloat
                        || ty.isTyInt
                        || ty.isTyChar
                        || ty.isTyString
                        || ty.isTyList && ty.parameters.run {
                    allAssignableTo("comparable") || allAssignableTo("number")
                }
                else -> false
            }
            tyVar.name.startsWith("compappend") -> when (ty) {
                is TyUnion -> ty.isTyString || ty.isTyList && ty.parameters.run {
                    allAssignableTo("comparable") || allAssignableTo("compappend")
                }
                else -> false
            }
            else -> !tyVar.rigid
        }
    }

    private fun typeclassesCompatable(name1: String, name2: String?, unconstrainedAllowed: Boolean = true): Boolean {
        return when {
            name2 == null -> unconstrainedAllowed
            name1 == name2 -> true
            name1 == "number" && name2 == "comparable" -> true
            name1 == "comparable" && name2 == "number" -> true
            name1 == "comparable" && (name2 == "number" || name2 == "compappend") -> true
            name1 == "compappend" && (name2 == "comparable" || name2 == "appendable") -> true
            else -> false
        }
    }

    private fun typeclassesConstrainToCompappend(tc1: String?, tc2: String?): Boolean {
        return when (tc1) {
            "comparable" -> tc2 == "appendable" || tc2 == "compappend"
            "appendable" -> tc2 == "comparable" || tc2 == "compappend"
            else -> false
        }
    }

    private fun trackReplacement(ty1: Ty, ty2: Ty) {
        if (ty1 === ty2) return

        fun assign(k: TyVar, v: Ty) {
            if (v.anyVar { it == k }) throw InfiniteTypeException()
            replacements[k] = v
        }

        // assigning anything to a variable constrains the type of that variable
        if (ty2 is TyVar && (ty2 !in replacements || ty1 !is TyVar && replacements[ty2] is TyVar)) {
            if (ty1 is TyVar) {
                val tc1 = ty1.typeclassName()
                val tc2 = ty2.typeclassName()
                if (tc1 == null && tc2 != null) {
                    // There's an edge case where an assignment like `a => number`
                    // should constrain `a` to be a `number`, rather than `number` to be an `a`.
                    assign(ty1, ty2)
                } else if (!ty1.rigid && !ty2.rigid && typeclassesConstrainToCompappend(tc1, tc2)) {
                    // There's another edge case where unifying flex `comparable` with flex
                    // `appendable` creates a new constraint with typeclass `compappend`.
                    // Assigning flex `comparable` or `appendable` to flex `compappend` will also
                    // constrain the arguments.
                    assign(ty1, if (tc2 == "compappend") ty2 else TyVar("compappend"))
                } else if (!ty1.rigid && !ty2.rigid && tc1 == "comparable" && tc2 == "number") {
                    // `comparable` can be constrained to `number`
                    assign(ty1, ty2)
                } else if (!ty1.rigid && ty2.rigid) {
                    // Assigning a flex var to a rigid var makes the flex var rigid
                    assign(ty1, ty2)
                } else {
                    assign(ty2, ty1)
                }
            } else {
                // Normally, you have assignments like `Int => number` which constrains `number` to
                // be an `Int`.
                assign(ty2, ty1)
            }
        }
        // unification: assigning a var to a type also constrains the var, but only if not
        // assigning it to another var, since we just handled that case above.
        if (ty1 is TyVar && ty2 !is TyVar && ty1 !in replacements) {
            // If the var is being assigned to an extension record, make the constraint mutable,
            // since other field constraints could be added later.
            if (ty2 is TyRecord && ty2.isSubset) {
                assign(ty1, MutableTyRecord(ty2.fields.toMutableMap(), ty2.baseTy))
            } else {
                assign(ty1, ty2)
            }
        }
    }

    /**
     * If the [ty] corresponding to [elem] is a [TyVar], bind [ty] to [default]; otherwise return [ty].
     *
     * This is used to constrain unannotated parameters during binding. Note that this binds via
     * [requireAssignable], so if [ty] is rigid, this will take care of generating a diagnostic
     * rather than constraining the variable.
     */
    private inline fun bindIfVar(elem: ElmPsiElement, ty: Ty, default: () -> Ty): Ty {
        return when (ty) {
            is TyVar -> default().also { requireAssignable(elem, ty, it, patternBinding = true) }
            else -> ty
        }
    }

    //</editor-fold>
}

/**
 * @property expressionTypes the types for any psi elements inferred that should be available to inspections
 * @property diagnostics any errors encountered during inference
 * @property recordDiffs record diffs for elements that have a type error
 * @property ty the return type of the function or expression being inferred
 */
data class InferenceResult(
        val expressionTypes: Map<ElmPsiElement, Ty>,
        val diagnostics: List<ElmDiagnostic>,
        val recordDiffs: Map<ElmPsiElement, RecordDiff>,
        val ty: Ty
) {
    fun elementType(element: ElmPsiElement): Ty = expressionTypes[element] ?: TyUnknown()
}

data class ParameterizedInferenceResult<T>(
        val diagnostics: List<ElmDiagnostic>,
        val value: T
)

/** Return [count] [TyVar]s named a, b, ... z, a1, b1, ... */
private fun uniqueVars(count: Int): List<TyVar> {
    return varNames().take(count).map { TyVar(it) }.toList()
}

/** Return the nearest [ElmValueDeclaration] if it declares a pattern, or `null` otherwise */
private fun parentPatternDecl(element: ElmPsiElement): ElmValueDeclaration? {
    val decl = element.parentOfType<ElmValueDeclaration>()
    return if (decl?.pattern == null) null else decl
}

/** A [ty] and the [start] and [end] elements of the expression that created it */
private data class TyAndRange(val start: ElmPsiElement, val end: ElmPsiElement, val ty: Ty) {
    constructor(element: ElmPsiElement, ty: Ty) : this(element, element, ty)
}

fun isInferable(ty: Ty): Boolean = ty !is TyUnknown

/** Throw an [IllegalStateException] with [message] augmented with information about [element] */
fun error(element: ElmPsiElement, message: String): Nothing {
    val file = element.containingFile
    val location = if (file == null) "" else {
        val fileText = file.text
        val textRange = element.textRange
        val startOffset = textRange.startOffset
        val lineNum = fileText.asSequence().take(startOffset).count { it == '\n' } + 1
        val lineStart = fileText.lastIndexOf('\n', startIndex = startOffset)
        val start = startOffset - lineStart
        val end = textRange.endOffset - lineStart
        " [${file.name}:$lineNum:$start-$end]"
    }

    val text = element.text.let { if (it.length > 25) "${it.take(25)}…" else it }.replace("\n", "↵")
    error("$message$location <${element.elementType}: '$text'>")
}

data class RecordDiff(
        val extra: Map<String, Ty>,
        val missing: Map<String, Ty>,
        val mismatched: Map<String, Pair<Ty, Ty>>
) {
    fun isEmpty() = extra.isEmpty() && missing.isEmpty() && mismatched.isEmpty()
    fun isNotEmpty() = extra.isNotEmpty() && missing.isNotEmpty() && mismatched.isNotEmpty()
}

private sealed class ParameterBindingResult {
    abstract val count: Int

    data class Annotated(val ty: Ty, override val count: Int) : ParameterBindingResult()
    data class Unannotated(val params: List<Ty>, override val count: Int) : ParameterBindingResult()
    data class Other(override val count: Int) : ParameterBindingResult()
}

/** dangerous shallow copy of a mutable record for performance, use `toRecord` if the result isn't discarded. */
private fun MutableTyRecord.asRecord(): TyRecord = TyRecord(fields, baseTy, fieldReferences = fieldReferences)

private class InfiniteTypeException : Exception()
