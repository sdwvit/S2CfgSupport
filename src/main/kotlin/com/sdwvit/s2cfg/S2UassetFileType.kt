package com.sdwvit.s2cfg

import com.intellij.openapi.fileTypes.FileType
import javax.swing.Icon

/**
 * `.uasset` packages are binary, so all this type does is keep the IDE from offering to open them
 * as text. What a particular package *is* — and whether this plugin can edit it — is decided from
 * its header, not its name: see [S2LocalizationEditorProvider].
 */
object S2UassetFileType : FileType {
  override fun getName() = "STALKER 2 Asset"
  override fun getDescription() = "Unreal Engine asset package"
  override fun getDefaultExtension() = "uasset"
  override fun getIcon(): Icon = S2CfgIcons.FILE
  override fun isBinary() = true
}
