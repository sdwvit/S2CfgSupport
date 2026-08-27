package com.sdwvit.s2cfg

import com.intellij.navigation.ChooseByNameContributorEx
import com.intellij.navigation.NavigationItem
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.Processor
import com.intellij.util.indexing.FindSymbolParameters
import com.intellij.util.indexing.IdFilter

/** Makes every record reachable from Navigate | Symbol (Ctrl+Alt+Shift+N) by its SID. */
class S2CfgGotoSymbolContributor : ChooseByNameContributorEx {

  override fun processNames(processor: Processor<in String>, scope: GlobalSearchScope, filter: IdFilter?) {
    val project = scope.project ?: return
    S2CfgDeclarations.allNames(project).forEach { if (!processor.process(it)) return }
  }

  override fun processElementsWithName(
    name: String,
    processor: Processor<in NavigationItem>,
    parameters: FindSymbolParameters,
  ) {
    S2CfgDeclarations.findByName(parameters.project, name, parameters.searchScope)
      .forEach { if (!processor.process(it)) return }
  }
}
