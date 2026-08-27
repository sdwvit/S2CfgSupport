package com.sdwvit.s2cfg

import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.ParsingTestCase
import java.io.File

class S2CfgParsingTest : ParsingTestCase("", "cfg", S2CfgParserDefinition()) {
  override fun getTestDataPath() = "src/test/testData"
  override fun skipSpaces() = false
  override fun includeRanges() = false

  private fun parseNoErrors(text: String): S2CfgFile {
    val file = createPsiFile("test", text) as S2CfgFile
    val errors = PsiTreeUtil.findChildrenOfType(file, PsiErrorElement::class.java)
      .map { "${it.errorDescription} at '${it.text}'" }
    assertEquals("unexpected parse errors in:\n$text\n$errors", emptyList<String>(), errors)
    return file
  }

  fun testSimpleStruct() {
    val file = parseNoErrors(
      """
      MyArmor : struct.begin
         SID = MyArmor
         MaxDurability = 1500.0
         IsQuestItem = false
         ItemType = EItemType::Armor
         Name =
      struct.end
      """.trimIndent()
    )
    val struct = file.structs.single()
    assertEquals("MyArmor", struct.name0)
    assertEquals("MyArmor", struct.sid)
    assertEquals(5, struct.entries.size)
    assertNull(struct.entries.last().valueText) // empty value stays empty
  }

  fun testRefsAndNesting() {
    val file = parseNoErrors(
      """
      Patched : struct.begin {refurl=../Base/Armor.cfg; refkey=Battle_Varta_Armor;bpatch}
         Upgrades : struct.begin
            [0] : struct.begin
               UpgradeSID = Up_01
            struct.end
            [*] : struct.begin
               UpgradeSID = Up_02
            struct.end
         struct.end
      struct.end
      """.trimIndent()
    )
    val root = file.structs.single()
    assertEquals("Battle_Varta_Armor", root.refkey)
    assertEquals("../Base/Armor.cfg", root.refurl)
    val upgrades = root.childStructs.single()
    assertEquals("Upgrades", upgrades.name0)
    assertEquals(listOf("[0]", "[*]"), upgrades.childStructs.map { it.name0 })
  }

  fun testValueLevelModifierAndComments() {
    parseNoErrors(
      """
      # leading comment
      // another comment
      Root : struct.begin
         InfotopicDialogChain = SomeChain{bskipref}
         Removed = empty{bpatch}
      struct.end
      """.trimIndent()
    )
  }

  fun testRemoveNode() {
    val file = parseNoErrors(
      """
      Node : struct.begin {bpatch}
         Conditions : removenode
         StartDelay = 1
      struct.end
      """.trimIndent()
    )
    val removed = file.structs.single().childStructs.single()
    assertEquals("Conditions", removed.name0)
    assertTrue(removed.isRemoveNode)
  }

  fun testByteOrderMarkIsTolerated() {
    parseNoErrors("\uFEFF// header\nFoo : struct.begin\n\tSID = Foo\nstruct.end")
  }

  fun testUnbalancedStructEndIsAnError() {
    val file = createPsiFile("bad", "Foo : struct.begin\n   A = 1\n")
    assertNotEmpty(PsiTreeUtil.findChildrenOfType(file, PsiErrorElement::class.java).toList())
  }

  /** The real corpus: every cfg in the sibling mods repo must parse cleanly. */
  fun testRepoCorpusParses() {
    val root = File(System.getProperty("user.home"), "IdeaProjects/S2Mods/Mods")
    if (!root.isDirectory) return
    val cfgs = root.walkTopDown().filter { it.isFile && it.extension == "cfg" }.toList()
    if (cfgs.isEmpty()) return
    val failures = mutableListOf<String>()
    for (cfg in cfgs) {
      val file = createPsiFile(cfg.nameWithoutExtension, cfg.readText())
      val errors = PsiTreeUtil.findChildrenOfType(file, PsiErrorElement::class.java)
      if (errors.isNotEmpty()) {
        failures += "${cfg.path}: ${errors.first().errorDescription} at '${errors.first().text.take(40)}'"
      }
    }
    assertEquals("cfgs failed to parse:\n" + failures.joinToString("\n"), 0, failures.size)
  }

  /** PhysicsInteractionPrototypes.cfg comments with `;` on one line and `//` on the next. */
  fun testSemicolonStartsAComment() {
    val file = createPsiFile(
      "semicolon",
      """
      PhysicsInteraction : struct.begin
         WaterImpulseReduction = 3.0
         ;Max impulse to apply to the object when pushed by player
         PlayerPushImpulse = 1000.0
         // Default distance to cut all sounds is 10m
      struct.end
      """.trimIndent(),
    )
    assertEmpty(PsiTreeUtil.findChildrenOfType(file, PsiErrorElement::class.java).toList())
    val struct = PsiTreeUtil.findChildOfType(file, S2CfgStruct::class.java)!!
    // the comment must not become an entry of its own
    assertEquals(
      listOf("WaterImpulseReduction", "PlayerPushImpulse"),
      struct.entries.map { it.keyName },
    )
  }
}
