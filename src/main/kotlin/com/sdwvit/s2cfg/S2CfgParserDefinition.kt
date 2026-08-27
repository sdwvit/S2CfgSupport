package com.sdwvit.s2cfg

import com.intellij.lang.ASTNode
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiParser
import com.intellij.openapi.project.Project
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet

class S2CfgParserDefinition : ParserDefinition {
  override fun createLexer(project: Project?) = S2CfgLexer()
  override fun createParser(project: Project?): PsiParser = S2CfgParser()
  override fun getFileNodeType() = FILE
  override fun getCommentTokens(): TokenSet = S2CfgTypes.COMMENTS
  override fun getStringLiteralElements(): TokenSet = S2CfgTypes.STRINGS
  override fun getWhitespaceTokens(): TokenSet = TokenSet.create(TokenType.WHITE_SPACE)
  override fun createElement(node: ASTNode): PsiElement = S2CfgPsiFactory.create(node)
  override fun createFile(viewProvider: FileViewProvider): PsiFile = S2CfgFile(viewProvider)

  companion object {
    val FILE = IFileElementType(S2CfgLanguage)
  }
}
