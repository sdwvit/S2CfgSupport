package com.sdwvit.s2cfg

import com.intellij.ide.projectView.PresentationData
import com.intellij.ide.structureView.*
import com.intellij.ide.util.treeView.smartTree.SortableTreeElement
import com.intellij.ide.util.treeView.smartTree.Sorter
import com.intellij.ide.util.treeView.smartTree.TreeElement
import com.intellij.lang.PsiStructureViewFactory
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil

class S2CfgStructureViewFactory : PsiStructureViewFactory {
  override fun getStructureViewBuilder(psiFile: PsiFile): StructureViewBuilder =
    object : TreeBasedStructureViewBuilder() {
      override fun createStructureViewModel(editor: Editor?) = S2CfgStructureViewModel(psiFile)
      override fun isRootNodeShown() = false
    }
}

class S2CfgStructureViewModel(psiFile: PsiFile) :
  StructureViewModelBase(psiFile, S2CfgStructureElement(psiFile)),
  StructureViewModel.ElementInfoProvider {

  init { withSuitableClasses(S2CfgStruct::class.java, S2CfgEntry::class.java) }

  override fun getSorters() = arrayOf(Sorter.ALPHA_SORTER)
  override fun isAlwaysShowsPlus(element: StructureViewTreeElement) = false
  override fun isAlwaysLeaf(element: StructureViewTreeElement) = element.value is S2CfgEntry
}

class S2CfgStructureElement(private val element: com.intellij.psi.PsiElement) :
  StructureViewTreeElement, SortableTreeElement {

  override fun getValue() = element
  override fun navigate(requestFocus: Boolean) = (element as? com.intellij.psi.NavigatablePsiElement)?.navigate(requestFocus) ?: Unit
  override fun canNavigate() = (element as? com.intellij.psi.NavigatablePsiElement)?.canNavigate() ?: false
  override fun canNavigateToSource() = canNavigate()
  override fun getAlphaSortKey() = presentationText()

  override fun getPresentation(): ItemPresentation = when (element) {
    is S2CfgStruct -> PresentationData(presentationText(), structHint(element), S2CfgIcons.FILE, null)
    is S2CfgEntry -> PresentationData(presentationText(), element.valueText, null, null)
    else -> PresentationData(element.containingFile?.name, null, S2CfgIcons.FILE, null)
  }

  override fun getChildren(): Array<TreeElement> {
    val children = when (element) {
      is S2CfgFile -> element.structs
      is S2CfgStruct -> element.entries.filter { it.valueElement != null } + element.childStructs
      else -> emptyList()
    }
    return children.map { S2CfgStructureElement(it) }.toTypedArray<TreeElement>()
  }

  private fun presentationText(): String = when (element) {
    is S2CfgStruct -> element.presentableName
    is S2CfgEntry -> element.keyName ?: "?"
    else -> element.containingFile?.name ?: "file"
  }

  /** Show what the struct inherits from, since refkey chains are the hard part to follow. */
  private fun structHint(struct: S2CfgStruct): String? {
    if (struct.isRemoveNode) return "removed"
    val refkey = struct.refkey ?: return null
    val refurl = struct.refurl
    return if (refurl != null) "→ $refurl:$refkey" else "→ $refkey"
  }
}

/** Utility used by other features that need every struct in a file. */
fun PsiFile.allS2CfgStructs(): Collection<S2CfgStruct> =
  PsiTreeUtil.findChildrenOfType(this, S2CfgStruct::class.java)
