package com.sdwvit.s2cfg

import com.intellij.openapi.util.TextRange
import com.intellij.psi.*
import com.intellij.psi.impl.source.resolve.ResolveCache
import com.intellij.psi.search.GlobalSearchScope

/** `SID = ANCQ27_Start` (nested) / `UpgradeSID = Up_01` -> the struct declaring that name. */
class S2CfgSidReference(element: PsiElement, range: TextRange, private val name: String) :
  PsiReferenceBase.Poly<PsiElement>(element, range, /* soft = */ true) {

  override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> =
    ResolveCache.getInstance(element.project).resolveWithCaching(this, Resolver, false, incompleteCode)

  private fun resolveUncached(): Array<ResolveResult> =
    S2CfgDeclarations.findByName(element.project, name, GlobalSearchScope.projectScope(element.project))
      .map { PsiElementResolveResult(it) }
      .toTypedArray()

  /** Completion is handled by [S2CfgCompletionContributor], which can filter by prefix and cap. */
  override fun getVariants(): Array<Any> = emptyArray()

  private object Resolver : ResolveCache.PolyVariantResolver<S2CfgSidReference> {
    override fun resolve(ref: S2CfgSidReference, incompleteCode: Boolean) = ref.resolveUncached()
  }
}

/** `{refurl=../DialogPoolPrototypes/Brodyaga.cfg}` -> that file. */
class S2CfgRefurlReference(element: PsiElement, range: TextRange, private val path: String) :
  PsiReferenceBase<PsiElement>(element, range, /* soft = */ true) {

  override fun resolve(): PsiElement? {
    val dir = element.containingFile?.originalFile?.virtualFile?.parent ?: return null
    val target = dir.findFileByRelativePath(path) ?: return null
    return PsiManager.getInstance(element.project).findFile(target)
  }
}

/** `{refkey=Battle_Varta_Armor}` -> the base struct, in the refurl file or in this one. */
class S2CfgRefkeyReference(element: PsiElement, range: TextRange, private val name: String) :
  PsiReferenceBase.Poly<PsiElement>(element, range, /* soft = */ true) {

  override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> =
    ResolveCache.getInstance(element.project).resolveWithCaching(this, Resolver, false, incompleteCode)

  private fun resolveUncached(): Array<ResolveResult> {
    val refs = element.parent as? S2CfgRefs
    val inRefurlFile = refs?.value("refurl")?.let { url ->
      val dir = element.containingFile?.originalFile?.virtualFile?.parent
      val target = dir?.findFileByRelativePath(url)
      (target?.let { PsiManager.getInstance(element.project).findFile(it) } as? S2CfgFile)
        ?.structs?.filter { name in S2CfgDeclarations.namesDeclaredBy(it) }
    }
    val targets = inRefurlFile?.takeIf { it.isNotEmpty() }
      ?: (element.containingFile as? S2CfgFile)?.structs
        ?.filter { name in S2CfgDeclarations.namesDeclaredBy(it) }
        ?.takeIf { it.isNotEmpty() }
      ?: S2CfgDeclarations.findByName(element.project, name, GlobalSearchScope.projectScope(element.project))
    return targets.map { PsiElementResolveResult(it) }.toTypedArray()
  }

  /** Completion is handled by [S2CfgCompletionContributor], which can filter by prefix and cap. */
  override fun getVariants(): Array<Any> = emptyArray()

  private object Resolver : ResolveCache.PolyVariantResolver<S2CfgRefkeyReference> {
    override fun resolve(ref: S2CfgRefkeyReference, incompleteCode: Boolean) = ref.resolveUncached()
  }
}

/**
 * References are attached by the PSI elements themselves rather than through a
 * `psi.referenceContributor`: contributed references are only consulted for
 * [com.intellij.psi.ContributedReferenceHost] elements, which custom-language PSI is not.
 */
object S2CfgReferenceFactory {

  /** `SID = ANCQ27_Start`, `UpgradeSID = Up_01`, ... */
  fun forValue(value: S2CfgValue): Array<PsiReference> {
    val entry = value.parent as? S2CfgEntry ?: return PsiReference.EMPTY_ARRAY
    val key = entry.keyName ?: return PsiReference.EMPTY_ARRAY
    if (!S2CfgDeclarations.isReferenceKey(key)) return PsiReference.EMPTY_ARRAY
    val text = value.text.trim()
    if (text.isEmpty() || text.contains(' ')) return PsiReference.EMPTY_ARRAY
    // the record's own `SID = X` is the declaration, not a reference to itself
    val owner = entry.parent as? S2CfgStruct
    if (key == "SID" && owner != null && S2CfgDeclarations.isDeclaration(owner)) {
      return PsiReference.EMPTY_ARRAY
    }
    val offset = value.text.indexOf(text)
    return arrayOf(S2CfgSidReference(value, TextRange(offset, offset + text.length), text))
  }

  /** `{refurl=../Base/Armor.cfg}` and `{refkey=Battle_Varta_Armor}` */
  fun forRef(ref: S2CfgRef): Array<PsiReference> {
    val value = ref.value?.trim()?.takeIf { it.isNotEmpty() } ?: return PsiReference.EMPTY_ARRAY
    val valueNode = ref.node.findChildByType(S2CfgTypes.TEXT) ?: return PsiReference.EMPTY_ARRAY
    val start = valueNode.startOffset - ref.textRange.startOffset
    val range = TextRange(start, start + valueNode.textLength)
    return when (ref.keyword) {
      "refurl" -> arrayOf(S2CfgRefurlReference(ref, range, value))
      // `refkey=[0]` points at an array index, which has no name to resolve
      "refkey" -> if (value.startsWith("[")) PsiReference.EMPTY_ARRAY
      else arrayOf(S2CfgRefkeyReference(ref, range, value))
      else -> PsiReference.EMPTY_ARRAY
    }
  }
}
