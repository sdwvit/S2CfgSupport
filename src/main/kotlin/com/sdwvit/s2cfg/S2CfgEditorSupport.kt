package com.sdwvit.s2cfg

import com.intellij.lang.BracePair
import com.intellij.lang.Commenter
import com.intellij.lang.PairedBraceMatcher
import com.intellij.psi.PsiElement
import com.intellij.psi.tree.IElementType

class S2CfgBraceMatcher : PairedBraceMatcher {
  override fun getPairs() = PAIRS
  override fun isPairedBracesAllowedBeforeType(lbraceType: IElementType, next: IElementType?) = true
  override fun getCodeConstructStart(file: com.intellij.psi.PsiFile?, openingBraceOffset: Int) = openingBraceOffset

  private companion object {
    val PAIRS = arrayOf(
      BracePair(S2CfgTypes.LBRACE, S2CfgTypes.RBRACE, false),
      BracePair(S2CfgTypes.LBRACKET, S2CfgTypes.RBRACKET, false),
    )
  }
}

class S2CfgCommenter : Commenter {
  override fun getLineCommentPrefix() = "//"
  override fun getBlockCommentPrefix(): String? = null
  override fun getBlockCommentSuffix(): String? = null
  override fun getCommentedBlockCommentPrefix(): String? = null
  override fun getCommentedBlockCommentSuffix(): String? = null
}
