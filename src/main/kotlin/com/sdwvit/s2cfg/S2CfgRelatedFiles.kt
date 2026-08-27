package com.sdwvit.s2cfg

import com.intellij.navigation.GotoRelatedItem
import com.intellij.navigation.GotoRelatedProvider
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope

/**
 * Navigate | Related Symbol from `Foo_patch_MyMod.cfg` to the `Foo.cfg` it patches, and back.
 *
 * Mods in this repo name their overrides `<Base>_patch_<ModName>.cfg` and mirror the game's
 * `Content/GameLite/GameData/...` layout, so the match is made on the GameData-relative path and
 * falls back to the plain file name.
 */
class S2CfgRelatedProvider : GotoRelatedProvider() {

  override fun getItems(psiElement: PsiElement): List<GotoRelatedItem> {
    val file = psiElement.containingFile as? S2CfgFile ?: return emptyList()
    val virtualFile = file.originalFile.virtualFile ?: return emptyList()
    val project = psiElement.project

    val counterparts = when {
      PATCH_NAME.matches(virtualFile.nameWithoutExtension) -> {
        val base = PATCH_NAME.find(virtualFile.nameWithoutExtension)!!.groupValues[1]
        findByName(project, "$base.cfg", virtualFile)
      }
      else -> findPatchesOf(project, virtualFile)
    }

    return counterparts.mapNotNull { PsiManager.getInstance(project).findFile(it) }
      .map { GotoRelatedItem(it, "STALKER 2 Cfg") }
  }

  private fun findByName(project: com.intellij.openapi.project.Project, name: String, from: VirtualFile) =
    FilenameIndex.getVirtualFilesByName(name, GlobalSearchScope.allScope(project))
      .filter { it != from }
      .sortedByDescending { sharedGameDataPath(it, from) }

  private fun findPatchesOf(project: com.intellij.openapi.project.Project, base: VirtualFile) =
    FilenameIndex.getAllFilenames(project)
      .filter { it.startsWith(base.nameWithoutExtension + "_patch_") && it.endsWith(".cfg") }
      .flatMap { FilenameIndex.getVirtualFilesByName(it, GlobalSearchScope.allScope(project)) }
      .filter { it != base }
      .sortedByDescending { sharedGameDataPath(it, base) }

  /** How much of the `GameData/...` tail two files share — the tie-breaker between same-named cfgs. */
  private fun sharedGameDataPath(a: VirtualFile, b: VirtualFile): Int {
    val left = a.path.substringAfter("GameData/", "").split('/').dropLast(1)
    val right = b.path.substringAfter("GameData/", "").split('/').dropLast(1)
    return left.zip(right).takeWhile { (l, r) -> l == r }.size
  }

  private companion object {
    val PATCH_NAME = Regex("^(.+)_patch_[^_]+$")
  }
}
