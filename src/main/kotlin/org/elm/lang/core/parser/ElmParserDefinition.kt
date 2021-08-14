package org.elm.lang.core.parser

import com.intellij.lang.ASTNode
import com.intellij.lang.ParserDefinition
import com.intellij.lang.ParserDefinition.SpaceRequirements.MAY
import com.intellij.lang.PsiBuilder
import com.intellij.lang.PsiParser
import com.intellij.openapi.project.Project
import com.intellij.psi.FileViewProvider
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet
import org.elm.lang.core.lexer.ElmIncrementalLexer
import org.elm.lang.core.lexer.ElmLayoutLexer
import org.elm.lang.core.psi.ELM_COMMENTS
import org.elm.lang.core.psi.ElmFile
import org.elm.lang.core.psi.ElmPsiFactory
import org.elm.lang.core.psi.ElmTypes
import org.elm.lang.core.stubs.ElmFileStub


class ElmParserDefinition : ParserDefinition {

    override fun createLexer(project: Project?) =
            ElmLayoutLexer(ElmIncrementalLexer())

    override fun getWhitespaceTokens() =
            TokenSet.create(TokenType.WHITE_SPACE)

    override fun getCommentTokens() =
            ELM_COMMENTS

    override fun getStringLiteralElements() =
            TokenSet.create(ElmTypes.REGULAR_STRING_PART)

    override fun createParser(project: Project?) =
    // TODO [kl] factor this out
            object : PsiParser {
                override fun parse(root: IElementType, builder: PsiBuilder): ASTNode {
                    builder.setTokenTypeRemapper { source, _, _, _ ->
                        if (source == ElmTypes.NEWLINE || source == ElmTypes.TAB)
                            TokenType.WHITE_SPACE
                        else
                            source
                    }
                    return ElmParser().parse(root, builder)
                }
            }

    override fun getFileNodeType() =
            ElmFileStub.Type

    override fun createFile(viewProvider: FileViewProvider) =
            ElmFile(viewProvider)

    override fun spaceExistanceTypeBetweenTokens(left: ASTNode?, right: ASTNode?) =
            MAY

    override fun createElement(node: ASTNode) = ElmPsiFactory.createElement(node)
}
