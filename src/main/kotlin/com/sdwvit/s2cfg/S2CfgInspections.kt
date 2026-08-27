package com.sdwvit.s2cfg

import com.intellij.codeInspection.*
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor

/** `{refurl=../Base/Armor.cfg}` pointing at a file that isn't there. */
class S2CfgMissingRefurlInspection : LocalInspectionTool() {
  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean) = object : PsiElementVisitor() {
    override fun visitElement(element: PsiElement) {
      val ref = element as? S2CfgRef ?: return
      if (ref.keyword != "refurl") return
      val path = ref.value?.takeIf { it.isNotEmpty() } ?: return
      val reference = ref.references.firstOrNull() ?: return
      if (reference.resolve() == null) {
        holder.registerProblem(ref, reference.rangeInElement, "Cannot resolve cfg file '$path'")
      }
    }
  }
}

/**
 * Two entries with the same key inside one struct. The game keeps whichever it reads last and
 * s2cfgtojson renames the loser to `<key>_dupe_<line>`, so this is nearly always a copy-paste bug.
 */
class S2CfgDuplicateKeyInspection : LocalInspectionTool() {
  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean) = object : PsiElementVisitor() {
    override fun visitElement(element: PsiElement) {
      val struct = element as? S2CfgStruct ?: return
      val seen = HashSet<String>()
      for (entry in struct.entries) {
        val key = entry.keyName ?: continue
        if (!seen.add(key)) {
          holder.registerProblem(
            entry.key ?: entry,
            "Duplicate key '$key' in this struct",
            ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
          )
        }
      }
    }
  }
}

/**
 * A `*SID` value that matches no record anywhere in the project.
 *
 * Off by default on purpose: a mod normally references base-game records whose cfgs are not part of
 * the project, and every one of those would light up.
 */
class S2CfgUnresolvedSidInspection : LocalInspectionTool() {
  override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean) = object : PsiElementVisitor() {
    override fun visitElement(element: PsiElement) {
      val value = element as? S2CfgValue ?: return
      val reference = value.references.firstOrNull() as? S2CfgSidReference ?: return
      if (reference.multiResolve(false).isEmpty()) {
        holder.registerProblem(value, reference.rangeInElement, "Cannot resolve record '${value.text.trim()}'")
      }
    }
  }
}
