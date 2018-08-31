package org.elm.lang.core.psi

import com.intellij.lang.ASTNode
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiBuilder
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.TokenType.WHITE_SPACE
import com.intellij.psi.tree.IElementType
import org.elm.lang.core.ElmFileType
import org.elm.lang.core.psi.ElmTypes.*
import org.elm.lang.core.psi.elements.*


class ElmPsiFactory(private val project: Project) {
    companion object {
        /**
         * WARNING: this should only be called from the [ParserDefinition] hook
         * which takes [ASTNode]s from the [PsiBuilder] and emits [PsiElement].
         *
         * IMPORTANT: Must be kept in-sync with the BNF. The grammar rules are
         * written in CamelCase, but the IElementType constants that they correspond
         * to are generated by GrammarKit in ALL_CAPS. So if you have add a rule
         * named `FooBar` to the BNF, then you need to add a line here like:
         * `FOO_BAR -> return ElmFooBar(node)`. Don't forget to create the `ElmFooBar`
         * class.
         */
        fun createElement(node: ASTNode): PsiElement =
                when (node.elementType) {
                    ANONYMOUS_FUNCTION -> ElmAnonymousFunction(node)
                    ANYTHING_PATTERN -> ElmAnythingPattern(node)
                    AS_CLAUSE -> ElmAsClause(node)
                    CASE_OF -> ElmCaseOf(node)
                    CASE_OF_BRANCH -> ElmCaseOfBranch(node)
                    CONS_PATTERN -> ElmConsPattern(node)
                    EXPOSED_OPERATOR -> ElmExposedOperator(node)
                    EXPOSED_TYPE -> ElmExposedType(node)
                    EXPOSED_VALUE -> ElmExposedValue(node)
                    EXPOSED_UNION_CONSTRUCTORS -> ElmExposedUnionConstructors(node)
                    EXPOSED_UNION_CONSTRUCTOR -> ElmExposedUnionConstructor(node)
                    EXPOSING_LIST -> ElmExposingList(node)
                    EXPRESSION -> ElmExpression(node)
                    FIELD -> ElmField(node)
                    FIELD_TYPE -> ElmFieldType(node)
                    FUNCTION_CALL -> ElmFunctionCall(node)
                    FUNCTION_DECLARATION_LEFT -> ElmFunctionDeclarationLeft(node)
                    OPERATOR_DECLARATION_LEFT -> ElmOperatorDeclarationLeft(node) // TODO [drop 0.18] remove this line
                    OPERATOR_CONFIG -> ElmOperatorConfig(node) // TODO [drop 0.18] remove this line
                    GLSL_CODE -> ElmGlslCode(node)
                    IF_ELSE -> ElmIfElse(node)
                    IMPORT_CLAUSE -> ElmImportClause(node)
                    INFIX_DECLARATION -> ElmInfixDeclaration(node)
                    LET_IN -> ElmLetIn(node)
                    LIST -> ElmList(node)
                    LIST_PATTERN -> ElmListPattern(node)
                    LITERAL -> ElmLiteral(node)
                    LOWER_PATTERN -> ElmLowerPattern(node)
                    LOWER_TYPE_NAME -> ElmLowerTypeName(node)
                    MODULE_DECLARATION -> ElmModuleDeclaration(node)
                    NEGATE_EXPRESSION -> ElmNegateExpresssion(node)
                    NON_EMPTY_TUPLE -> ElmNonEmptyTuple(node)
                    OPERATOR -> ElmOperator(node)
                    OPERATOR_AS_FUNCTION -> ElmOperatorAsFunction(node)
                    PARAMETRIC_TYPE_REF -> ElmParametricTypeRef(node)
                    PATTERN -> ElmPattern(node)
                    PATTERN_AS -> ElmPatternAs(node)
                    PORT_ANNOTATION -> ElmPortAnnotation(node)
                    RECORD -> ElmRecord(node)
                    RECORD_BASE_IDENTIFIER -> ElmRecordBaseIdentifier(node)
                    RECORD_PATTERN -> ElmRecordPattern(node)
                    RECORD_TYPE -> ElmRecordType(node)
                    TUPLE_CONSTRUCTOR -> ElmTupleConstructor(node) // TODO [drop 0.18] remove this line
                    TUPLE_PATTERN -> ElmTuplePattern(node)
                    TUPLE_TYPE -> ElmTupleType(node)
                    TYPE_ALIAS_DECLARATION -> ElmTypeAliasDeclaration(node)
                    TYPE_ANNOTATION -> ElmTypeAnnotation(node)
                    TYPE_DECLARATION -> ElmTypeDeclaration(node)
                    TYPE_REF -> ElmTypeRef(node)
                    TYPE_VARIABLE_REF -> ElmTypeVariableRef(node)
                    UNION_MEMBER -> ElmUnionMember(node)
                    UNION_PATTERN -> ElmUnionPattern(node)
                    UNIT -> ElmUnit(node)
                    UPPER_PATH_TYPE_REF -> ElmUpperPathTypeRef(node)
                    VALUE_DECLARATION -> ElmValueDeclaration(node)
                    VALUE_EXPR -> ElmValueExpr(node)
                    else -> throw AssertionError("Unknown element type: " + node.elementType)
                }
    }

    fun createLowerCaseIdentifier(text: String): PsiElement =
            createFromText("$text = 42", LOWER_CASE_IDENTIFIER)
                    ?: error("Failed to create lower-case identifier: `$text`")

    fun createUpperCaseIdentifier(text: String): PsiElement =
            createFromText<ElmTypeAliasDeclaration>("type alias $text = Int")
                    ?.upperCaseIdentifier
                    ?: error("Failed to create upper-case identifier: `$text`")

    fun createUpperCaseQID(text: String): ElmUpperCaseQID =
            createFromText<ElmModuleDeclaration>("module $text exposing (..)")
                    ?.upperCaseQID
                    ?: error("Failed to create upper-case QID: `$text`")

    fun createValueQID(text: String): ElmValueQID =
            createFromText<ElmValueDeclaration>("f = $text")
                    ?.expression
                    ?.childOfType()
                    ?: error("Failed to create value QID: `$text`")

    fun createOperatorIdentifier(text: String): PsiElement =
            createFromText("foo = x $text y", OPERATOR_IDENTIFIER)
                    ?: error("Failed to create operator identifier: `$text`")

    fun createImport(moduleName: String) =
            "import $moduleName"
                    .let { createFromText<ElmImportClause>(it) }
                    ?: error("Failed to create import of $moduleName")

    fun createImportExposing(moduleName: String, exposedNames: List<String>) =
            "import $moduleName exposing (${exposedNames.joinToString(", ")})"
                    .let { createFromText<ElmImportClause>(it) }
                    ?: error("Failed to create import of $moduleName exposing $exposedNames")

    fun createValueDeclaration(name: String, argNames: List<String>): ElmValueDeclaration {
        val s = if (argNames.isEmpty())
            "$name = "
        else
            "$name ${argNames.joinToString(" ")} = "
        return createFromText(s)
                ?: error("Failed to create value declaration named $name")
    }

    fun createFreshLine() =
    // TODO [kl] make this more specific by actually find a token which contains
    // newline, not just any whitespace
            PsiFileFactory.getInstance(project)
                    .createFileFromText("DUMMY.elm", ElmFileType, "\n")
                    .descendantOfType(WHITE_SPACE)
                    ?: error("failed to create fresh line: should never happen")

    private inline fun <reified T : PsiElement> createFromText(code: String): T? =
            PsiFileFactory.getInstance(project)
                    .createFileFromText("DUMMY.elm", ElmFileType, code)
                    .childOfType()

    private fun createFromText(code: String, elementType: IElementType): PsiElement? =
            PsiFileFactory.getInstance(project)
                    .createFileFromText("DUMMY.elm", ElmFileType, code)
                    .descendantOfType(elementType)
}
