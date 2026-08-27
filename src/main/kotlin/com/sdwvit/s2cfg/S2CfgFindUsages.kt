package com.sdwvit.s2cfg

import com.intellij.lang.HelpID
import com.intellij.lang.cacheBuilder.DefaultWordsScanner
import com.intellij.lang.cacheBuilder.WordsScanner
import com.intellij.lang.findUsages.FindUsagesProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.tree.TokenSet

class S2CfgFindUsagesProvider : FindUsagesProvider {
  override fun getWordsScanner(): WordsScanner = DefaultWordsScanner(
    S2CfgLexer(),
    TokenSet.create(S2CfgTypes.IDENT, S2CfgTypes.TEXT),
    S2CfgTypes.COMMENTS,
    TokenSet.EMPTY,
  )

  override fun canFindUsagesFor(element: PsiElement) =
    element is S2CfgStruct && S2CfgDeclarations.isDeclaration(element)

  override fun getHelpId(element: PsiElement): String = HelpID.FIND_OTHER_USAGES
  override fun getType(element: PsiElement) = "struct"
  override fun getDescriptiveName(element: PsiElement) = (element as? S2CfgStruct)?.presentableName ?: ""
  override fun getNodeText(element: PsiElement, useFullName: Boolean) = getDescriptiveName(element)
}
