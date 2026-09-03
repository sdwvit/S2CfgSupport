package com.sdwvit.s2cfg

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.ex.FileEditorProviderManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

/** Exercises the editor end to end in a real IDE: open a package, edit the JSON, save, re-read. */
class S2LocalizationEditorTest : BasePlatformTestCase() {

  private val bytes by lazy {
    File("src/test/testData/FactionPatches-localization.uasset").readBytes()
  }

  private fun addPackage(name: String, content: ByteArray = bytes): VirtualFile =
    WriteAction.compute<VirtualFile, Exception> {
      val file = myFixture.tempDirFixture.findOrCreateDir(".").createChildData(this, name)
      file.setBinaryContent(content)
      file
    }

  fun testAcceptsOnlyLocalizationPackages() {
    val provider = S2LocalizationEditorProvider()
    assertTrue(provider.accept(project, addPackage("Mod-localization.uasset")))
    // The header decides, not the name.
    assertTrue(provider.accept(project, addPackage("SomethingElse.uasset")))
    assertFalse(provider.accept(project, addPackage("Other.uasset", ByteArray(2048))))
    assertFalse(provider.accept(project, myFixture.addFileToProject("a.cfg", "").virtualFile))
  }

  fun testEditingAndSavingWritesThePackage() {
    val file = addPackage("Mod-localization.uasset")
    val editor = S2LocalizationEditor(project, file)
    try {
      assertFalse(editor.isModified)
      val document = editor.document
      WriteCommandAction.runWriteCommandAction(project) {
        document.setText(document.text.replace("\"Faction Patch\"", "\"Patch\""))
      }
      assertTrue(editor.isModified)
      assertTrue(editor.save(interactive = false))
      assertFalse(editor.isModified)

      val entries = S2Localization.read(S2UassetFormat.parse(file.contentsToByteArray()))
      assertEquals(
        "Patch",
        entries.first { it.sid == "sid_items_FactionPatch_name" }
          .languages["ELocalizationLanguage::English"],
      )
    } finally {
      editor.dispose()
    }
  }

  fun testInvalidJsonIsNotSaved() {
    val file = addPackage("Mod-localization.uasset")
    val before = file.contentsToByteArray()
    val editor = S2LocalizationEditor(project, file)
    try {
      WriteCommandAction.runWriteCommandAction(project) { editor.document.setText("{oops") }
      assertFalse(editor.save(interactive = false))
      assertTrue(before.contentEquals(file.contentsToByteArray()))

      // A document that is valid JSON but not a localization document is refused just the same.
      WriteCommandAction.runWriteCommandAction(project) { editor.document.setText("""{"a": 1}""") }
      assertFalse(editor.save(interactive = false))
      assertTrue(before.contentEquals(file.contentsToByteArray()))
    } finally {
      editor.dispose()
    }
  }

  /**
   * plugin.xml is the other half of this feature: the platform has to hand a localization package
   * to our provider, and hide the binary viewer while doing it. (The headless editor manager only
   * opens text editors, so the registration is checked rather than an actual open.)
   */
  fun testProviderIsRegisteredForThePackage() {
    val file = addPackage("Mod-localization.uasset")
    val providers = FileEditorProviderManager.getInstance().getProviderList(project, file)
    assertTrue(providers.any { it is S2LocalizationEditorProvider })
    assertEquals(S2UassetFileType, file.fileType)
    assertTrue(file.fileType.isBinary)
  }
}
