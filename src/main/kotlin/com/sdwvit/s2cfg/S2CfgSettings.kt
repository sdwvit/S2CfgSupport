package com.sdwvit.s2cfg

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.util.xmlb.XmlSerializerUtil
import java.io.File
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Plugin-wide settings.
 *
 * Application-level rather than per-project: the SDK is installed once and several mod projects
 * are edited against the same copy of it.
 */
@Service(Service.Level.APP)
@State(name = "S2CfgSettings", storages = [Storage("s2cfg.xml")])
class S2CfgSettings : PersistentStateComponent<S2CfgSettings> {

  /**
   * The mod SDK's `Content` directory — the one holding a directory per mod. Empty when it has
   * not been set, which is the default and simply turns the checks that need it off.
   */
  var sdkContentRoot: String = ""

  override fun getState(): S2CfgSettings = this

  override fun loadState(state: S2CfgSettings) = XmlSerializerUtil.copyBean(state, this)

  companion object {
    fun getInstance(): S2CfgSettings =
      ApplicationManager.getApplication().getService(S2CfgSettings::class.java)

    /**
     * The package name the cooker will address a file at [path] by: `/<SdkModName>/<AssetName>`
     * for a package sitting at the root of a mod's content directory, with any further
     * subdirectories in between.
     *
     * `null` when [root] is unset or [path] is not under it — there is nothing to compare against
     * then, and guessing a package name from a path outside the SDK would be worse than silence.
     */
    fun packageNameFor(root: String, path: String): String? {
      if (root.isBlank()) return null
      val content = runCatching { File(root).canonicalFile }.getOrNull() ?: return null
      val file = runCatching { File(path).canonicalFile }.getOrNull() ?: return null
      val relative = file.toPath().let { p ->
        if (!p.startsWith(content.toPath())) return null
        content.toPath().relativize(p)
      }
      // `<ModName>/<...>/<Asset>.uasset` — a file directly in Content/ has no mod to belong to.
      if (relative.nameCount < 2) return null
      val segments = (0 until relative.nameCount).map { relative.getName(it).toString() }
      val name = segments.last().removeSuffix(".uasset")
      return "/" + (segments.dropLast(1) + name).joinToString("/")
    }
  }
}

/** The plugin's settings page, under Settings | Tools | STALKER 2 Cfg. */
class S2CfgConfigurable : Configurable {

  private val field = TextFieldWithBrowseButton().apply {
    addBrowseFolderListener(
      "Mod SDK Content Directory",
      "The SDK's Content directory — the one holding a directory per mod",
      null,
      FileChooserDescriptorFactory.createSingleFolderDescriptor(),
    )
  }

  private val panel: JPanel = com.intellij.ui.dsl.builder.panel {
    row("Mod SDK Content directory:") { cell(field).resizableColumn() }
    row {
      comment(
        "Lets the localization editor tell whether a package's own name matches where it sits " +
          "— an asset copied from another mod keeps that mod's name and its strings never load. " +
          "Leave empty to turn the check off."
      )
    }
  }

  override fun getDisplayName() = "STALKER 2 Cfg"

  override fun createComponent(): JComponent = panel

  override fun isModified() = field.text != S2CfgSettings.getInstance().sdkContentRoot

  override fun apply() {
    S2CfgSettings.getInstance().sdkContentRoot = field.text.trim()
  }

  override fun reset() {
    field.text = S2CfgSettings.getInstance().sdkContentRoot
  }
}
