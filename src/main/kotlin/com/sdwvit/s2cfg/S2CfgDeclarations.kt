package com.sdwvit.s2cfg

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.Processor
import com.intellij.util.indexing.FileBasedIndex

/**
 * What counts as declaring a name in cfg data:
 *  - the name of a top-level struct (`ANCQ27_Spawn_911 : struct.begin`)
 *  - a `SID = X` entry *directly inside* a top-level struct
 *
 * A `SID = X` nested any deeper (e.g. inside `Launchers/[0]/Connections/[0]`) is a *reference* to
 * another record, not a declaration — that distinction is the whole point of this file.
 *
 * Every lookup here is bounded. GameData holds hundreds of thousands of records, so an unbounded
 * "all names" or "all files declaring this name" walk is a UI freeze rather than a slow answer.
 */
object S2CfgDeclarations {

  /** Cap on records materialised for one lookup: enough to navigate, small enough to stay instant. */
  const val MAX_RESULTS = 50

  /** Names this struct declares, if it is a top-level struct. */
  fun namesDeclaredBy(struct: S2CfgStruct): List<String> {
    if (struct.parent !is S2CfgFile) return emptyList()
    return listOfNotNull(struct.name0?.takeIf { it.isNotBracketed() }, struct.sid).distinct()
  }

  fun isDeclaration(struct: S2CfgStruct) = namesDeclaredBy(struct).isNotEmpty()

  /**
   * Top-level structs in the project declaring [name], at most [limit] of them.
   *
   * Each candidate file has to be parsed into PSI, so the limit matters: a name that appears in a
   * thousand files would otherwise mean a thousand full parses inside a single resolve.
   */
  fun findByName(
    project: Project,
    name: String,
    scope: GlobalSearchScope,
    limit: Int = MAX_RESULTS,
  ): List<S2CfgStruct> {
    if (name.isEmpty()) return emptyList()
    // resolving during indexing would throw IndexNotReadyException; an empty result degrades better
    if (DumbService.isDumb(project)) return emptyList()

    return S2CfgLog.timed(what = { "findByName('$name')" }) {
      val manager = PsiManager.getInstance(project)
      val files = FileBasedIndex.getInstance().getContainingFiles(S2CfgSidIndex.NAME, name, scope)
      val found = ArrayList<S2CfgStruct>()
      for (file in files) {
        ProgressManager.checkCanceled()
        val psi = manager.findFile(file) as? S2CfgFile ?: continue
        for (struct in psi.structs) {
          if (name in namesDeclaredBy(struct)) found += struct
          if (found.size >= limit) {
            S2CfgLog.LOG.debug("findByName('$name') truncated at $limit of ${files.size} file(s)")
            return@timed found
          }
        }
      }
      found
    }
  }

  /**
   * Feed every declared name in the project to [processor], stopping early when it returns false.
   *
   * This streams index keys instead of materialising the whole key set, which is what makes
   * prefix-filtered completion affordable.
   */
  fun processNames(project: Project, processor: Processor<in String>): Boolean {
    if (DumbService.isDumb(project)) return true
    return FileBasedIndex.getInstance().processAllKeys(S2CfgSidIndex.NAME, processor, project)
  }

  /**
   * Keys whose values point at another record: `SID`, `QuestSID`, `UpgradeSID`, and the plural
   * array forms (`UpgradePrototypeSIDs`, `RequiredUpgradeIDs`, `BlockingUpgradeIds`) — together
   * some six thousand entries across the corpus.
   *
   * Being generous is safe: these references are soft, so a value naming no record simply does not
   * navigate, and the inspection that would flag it is off by default.
   */
  fun isReferenceKey(key: String): Boolean {
    val singular = key.removeSuffix("s")
    return singular.endsWith("SID") || singular.endsWith("ID") || singular.endsWith("Id")
  }

  /**
   * The key that decides what an entry's value *means*.
   *
   * For `[0] = GunPM_HG` that is not `[0]` but the name of the array holding it
   * (`FittingWeaponsSIDs`), so array elements navigate like the scalar form does.
   */
  fun effectiveKey(entry: S2CfgEntry): String? {
    var key = entry.keyName ?: return null
    var owner = entry.parent as? S2CfgStruct
    while (key.startsWith("[")) {
      key = owner?.name0 ?: return null
      owner = owner.parent as? S2CfgStruct
    }
    return key
  }

  private fun String.isNotBracketed() = !startsWith("[")
}
