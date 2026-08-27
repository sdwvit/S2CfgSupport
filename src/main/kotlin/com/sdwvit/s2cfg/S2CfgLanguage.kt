package com.sdwvit.s2cfg

import com.intellij.lang.Language
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

object S2CfgLanguage : Language("S2Cfg")

object S2CfgIcons {
  val FILE: Icon = IconLoader.getIcon("/icons/s2cfg.svg", S2CfgIcons::class.java)
}

object S2CfgFileType : LanguageFileType(S2CfgLanguage) {
  override fun getName() = "STALKER 2 Cfg"
  override fun getDescription() = "STALKER 2 game data config"
  override fun getDefaultExtension() = "cfg"
  override fun getIcon() = S2CfgIcons.FILE
}
