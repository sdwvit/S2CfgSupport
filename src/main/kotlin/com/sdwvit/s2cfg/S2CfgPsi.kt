package com.sdwvit.s2cfg

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.extapi.psi.PsiFileBase
import com.intellij.lang.ASTNode
import com.intellij.navigation.ItemPresentation
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiReference
import com.intellij.psi.util.PsiTreeUtil

class S2CfgFile(viewProvider: FileViewProvider) : PsiFileBase(viewProvider, S2CfgLanguage) {
  override fun getFileType() = S2CfgFileType
  override fun toString() = "STALKER 2 Cfg File"

  val structs: List<S2CfgStruct> get() = PsiTreeUtil.getChildrenOfTypeAsList(this, S2CfgStruct::class.java)
}

class S2CfgKey(node: ASTNode) : ASTWrapperPsiElement(node) {
  /** `[0]` and `[*]` keep their brackets; plain names are returned as-is. */
  val text0: String get() = text.trim()
}

class S2CfgValue(node: ASTNode) : ASTWrapperPsiElement(node) {
  override fun getReferences(): Array<PsiReference> = S2CfgReferenceFactory.forValue(this)
  override fun getReference(): PsiReference? = references.firstOrNull()
}

class S2CfgRef(node: ASTNode) : ASTWrapperPsiElement(node) {
  override fun getReferences(): Array<PsiReference> = S2CfgReferenceFactory.forRef(this)
  override fun getReference(): PsiReference? = references.firstOrNull()

  val keyword: String? get() = node.findChildByType(S2CfgTypes.REF_KEYWORD)?.text
  val value: String? get() = node.findChildByType(S2CfgTypes.TEXT)?.text
}

class S2CfgRefs(node: ASTNode) : ASTWrapperPsiElement(node) {
  val refs: List<S2CfgRef> get() = PsiTreeUtil.getChildrenOfTypeAsList(this, S2CfgRef::class.java)
  fun value(keyword: String): String? = refs.firstOrNull { it.keyword == keyword }?.value
}

class S2CfgStructHead(node: ASTNode) : ASTWrapperPsiElement(node) {
  val key: S2CfgKey? get() = PsiTreeUtil.getChildOfType(this, S2CfgKey::class.java)
  val refs: S2CfgRefs? get() = PsiTreeUtil.getChildOfType(this, S2CfgRefs::class.java)
}

class S2CfgEntry(node: ASTNode) : ASTWrapperPsiElement(node) {
  val key: S2CfgKey? get() = PsiTreeUtil.getChildOfType(this, S2CfgKey::class.java)
  val valueElement: S2CfgValue? get() = PsiTreeUtil.getChildOfType(this, S2CfgValue::class.java)
  val keyName: String? get() = key?.text0
  val valueText: String? get() = valueElement?.text?.trim()?.takeIf { it.isNotEmpty() }
}

class S2CfgStruct(node: ASTNode) : ASTWrapperPsiElement(node), PsiNameIdentifierOwner {
  val head: S2CfgStructHead? get() = PsiTreeUtil.getChildOfType(this, S2CfgStructHead::class.java)

  /** The declared name (`Foo` in `Foo : struct.begin`), or the array index (`[0]`), or null. */
  val name0: String? get() = head?.key?.text0

  val entries: List<S2CfgEntry> get() = PsiTreeUtil.getChildrenOfTypeAsList(this, S2CfgEntry::class.java)
  val childStructs: List<S2CfgStruct> get() = PsiTreeUtil.getChildrenOfTypeAsList(this, S2CfgStruct::class.java)

  /** `SID = X` inside this struct, the de facto identity of most game-data records. */
  val sid: String? get() = entries.firstOrNull { it.keyName == "SID" }?.valueText

  /** `Key : removenode` — a bodyless struct that deletes the inherited one. */
  val isRemoveNode: Boolean
    get() = head?.node?.findChildByType(S2CfgTypes.REMOVE_NODE) != null

  val refkey: String? get() = head?.refs?.value("refkey")
  val refurl: String? get() = head?.refs?.value("refurl")

  /** What the structure view and folding placeholder show. */
  val presentableName: String
    get() = name0?.takeIf { it.isNotEmpty() } ?: sid ?: "struct"

  // --- declaration support (rename / find usages) ---

  /** Only top-level structs declare a name; see [S2CfgDeclarations]. */
  override fun getName(): String? = if (parent is S2CfgFile) name0 ?: sid else null

  /**
   * The struct name and its `SID` are two spellings of the same identity in this data, so renaming
   * one rewrites the other when they agree.
   */
  override fun setName(name: String): PsiElement {
    val oldName = getName()
    head?.key?.let { key ->
      S2CfgElementFactory.createStruct(project, name).head?.key?.let { key.replace(it) }
    }
    val sidEntry = entries.firstOrNull { it.keyName == "SID" }
    if (sidEntry != null && sidEntry.valueText == oldName) {
      sidEntry.valueElement?.let { value ->
        S2CfgElementFactory.createEntry(project, "SID", name).valueElement?.let { value.replace(it) }
      }
    }
    return this
  }

  override fun getNameIdentifier(): PsiElement? = head?.key ?: entries.firstOrNull { it.keyName == "SID" }?.valueElement

  override fun getTextOffset(): Int = nameIdentifier?.textOffset ?: super.getTextOffset()

  override fun getPresentation(): ItemPresentation? = S2CfgStructPresentation(this)
}

class S2CfgStructPresentation(private val struct: S2CfgStruct) : ItemPresentation {
  override fun getPresentableText() = struct.presentableName
  override fun getLocationString() = struct.containingFile?.name
  override fun getIcon(unused: Boolean) = S2CfgIcons.FILE
}

object S2CfgPsiFactory {
  fun create(node: ASTNode): PsiElement = when (node.elementType) {
    S2CfgTypes.STRUCT -> S2CfgStruct(node)
    S2CfgTypes.STRUCT_HEAD -> S2CfgStructHead(node)
    S2CfgTypes.ENTRY -> S2CfgEntry(node)
    S2CfgTypes.KEY -> S2CfgKey(node)
    S2CfgTypes.VALUE -> S2CfgValue(node)
    S2CfgTypes.REFS -> S2CfgRefs(node)
    S2CfgTypes.REF -> S2CfgRef(node)
    else -> ASTWrapperPsiElement(node)
  }
}
