package com.sdwvit.s2cfg

import com.intellij.lang.ASTNode
import com.intellij.lang.folding.FoldingBuilderEx
import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil

class S2CfgFoldingBuilder : FoldingBuilderEx() {
  override fun buildFoldRegions(root: PsiElement, document: Document, quick: Boolean): Array<FoldingDescriptor> =
    PsiTreeUtil.findChildrenOfType(root, S2CfgStruct::class.java)
      .mapNotNull { struct ->
        val range = foldRange(struct) ?: return@mapNotNull null
        FoldingDescriptor(struct.node, range, null, placeholder(struct))
      }
      .toTypedArray()

  /** Fold from the end of `struct.begin` (plus any `{...}`) to the end of `struct.end`. */
  private fun foldRange(struct: S2CfgStruct): TextRange? {
    val head = struct.head ?: return null
    val start = head.textRange.endOffset
    val end = struct.textRange.endOffset
    return if (end - start > 1) TextRange(start, end) else null
  }

  private fun placeholder(struct: S2CfgStruct): String {
    val children = struct.childStructs.size
    val entries = struct.entries.size
    val counts = buildList {
      if (entries > 0) add("$entries field${if (entries == 1) "" else "s"}")
      if (children > 0) add("$children struct${if (children == 1) "" else "s"}")
    }.joinToString(", ")
    return if (counts.isEmpty()) " ... " else " ... $counts "
  }

  override fun getPlaceholderText(node: ASTNode): String = " ... "
  override fun isCollapsedByDefault(node: ASTNode) = false
}
