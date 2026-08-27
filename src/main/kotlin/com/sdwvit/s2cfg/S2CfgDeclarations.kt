package com.sdwvit.s2cfg

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.indexing.FileBasedIndex

/**
 * What counts as declaring a name in cfg data:
 *  - the name of a top-level struct (`ANCQ27_Spawn_911 : struct.begin`)
 *  - a `SID = X` entry *directly inside* a top-level struct
 *
 * A `SID = X` nested any deeper (e.g. inside `Launchers/[0]/Connections/[0]`) is a *reference* to
 * another record, not a declaration — that distinction is the whole point of this file.
 */
object S2CfgDeclarations {

  /** Names this struct declares, if it is a top-level struct. */
  fun namesDeclaredBy(struct: S2CfgStruct): List<String> {
    if (struct.parent !is S2CfgFile) return emptyList()
    return listOfNotNull(struct.name0?.takeIf { it.isNotBracketed() }, struct.sid).distinct()
  }

  fun isDeclaration(struct: S2CfgStruct) = namesDeclaredBy(struct).isNotEmpty()

  /** Every top-level struct in the project declaring [name]. */
  fun findByName(project: Project, name: String, scope: GlobalSearchScope): List<S2CfgStruct> {
    if (name.isEmpty()) return emptyList()
    val manager = PsiManager.getInstance(project)
    return FileBasedIndex.getInstance()
      .getContainingFiles(S2CfgSidIndex.NAME, name, scope)
      .mapNotNull { manager.findFile(it) as? S2CfgFile }
      .flatMap { file -> file.structs.filter { name in namesDeclaredBy(it) } }
  }

  fun allNames(project: Project): Collection<String> =
    FileBasedIndex.getInstance().getAllKeys(S2CfgSidIndex.NAME, project)

  /** Keys whose values point at another record: `SID`, `QuestSID`, `UpgradeSID`, ... */
  fun isReferenceKey(key: String) = key == "SID" || key.endsWith("SID")

  private fun String.isNotBracketed() = !startsWith("[")
}
