package com.sdwvit.s2cfg

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class S2CfgCompletionTest : BasePlatformTestCase() {

  fun testEnumCompletionOffersLiteralsSeenForTheSameKey() {
    myFixture.addFileToProject(
      "Base/Items.cfg",
      """
      Knife : struct.begin
         SID = Knife
         ItemType = EItemType::Weapon
         Rarity = ERarity::Common
      struct.end
      Vest : struct.begin
         SID = Vest
         ItemType = EItemType::Armor
      struct.end
      """.trimIndent(),
    )
    myFixture.configureByText("Mine.cfg", "Mine : struct.begin\n   ItemType = EItemType::<caret>\nstruct.end")
    val suggestions = myFixture.completeBasic().map { it.lookupString }
    assertContainsElements(suggestions, "EItemType::Weapon", "EItemType::Armor")
    // an enum only ever seen on another key must not be offered here
    assertDoesntContain(suggestions, "ERarity::Common")
  }

  fun testSidCompletionOffersRecordNames() {
    myFixture.addFileToProject("Base/Nodes.cfg", "ANCQ27_Start : struct.begin\n   SID = ANCQ27_Start\nstruct.end")
    myFixture.configureByText(
      "Quest.cfg",
      "Q : struct.begin\n   SID = Q\n   Launchers : struct.begin\n      SID = ANCQ<caret>\n   struct.end\nstruct.end",
    )
    assertContainsElements(myFixture.completeBasic().map { it.lookupString }, "ANCQ27_Start")
  }

  fun testRefkeyCompletionPrefersLocalStructs() {
    myFixture.configureByText(
      "Same.cfg",
      "Base_Armor : struct.begin\n   A = 1\nstruct.end\nD : struct.begin {refkey=Base<caret>}\nstruct.end",
    )
    assertContainsElements(myFixture.completeBasic().map { it.lookupString }, "Base_Armor")
  }

  fun testMissingRefurlIsReported() {
    myFixture.enableInspections(S2CfgMissingRefurlInspection())
    myFixture.configureByText(
      "Patch.cfg",
      "M : struct.begin {refurl=../Nope/Missing.cfg; refkey=X}\nstruct.end",
    )
    val problems = myFixture.doHighlighting().filter { it.description != null }
    assertTrue(
      "expected a missing-refurl warning, got: ${problems.map { it.description }}",
      problems.any { it.description!!.contains("Cannot resolve cfg file") },
    )
  }

  fun testExistingRefurlIsNotReported() {
    myFixture.enableInspections(S2CfgMissingRefurlInspection())
    myFixture.addFileToProject("Base/Armor.cfg", "X : struct.begin\n   A = 1\nstruct.end")
    val patch = myFixture.addFileToProject(
      "Mods/Patch.cfg",
      "M : struct.begin {refurl=../Base/Armor.cfg; refkey=X}\nstruct.end",
    )
    myFixture.configureFromExistingVirtualFile(patch.virtualFile)
    assertEmpty(myFixture.doHighlighting().mapNotNull { it.description })
  }

  fun testDuplicateKeyIsReported() {
    myFixture.enableInspections(S2CfgDuplicateKeyInspection())
    myFixture.configureByText("Dup.cfg", "S : struct.begin\n   A = 1\n   A = 2\nstruct.end")
    assertTrue(
      myFixture.doHighlighting().any { it.description?.contains("Duplicate key 'A'") == true },
    )
  }

  fun testRelatedFileFindsThePatchedBaseCfg() {
    val base = myFixture.addFileToProject(
      "Game/GameData/ItemPrototypes/Armor.cfg",
      "X : struct.begin\n   A = 1\nstruct.end",
    )
    val patch = myFixture.addFileToProject(
      "Mods/Mine/GameData/ItemPrototypes/Armor_patch_Mine.cfg",
      "X : struct.begin {bpatch}\n   A = 2\nstruct.end",
    )
    val toBase = S2CfgRelatedProvider().getItems(patch)
    assertEquals(listOf("Armor.cfg"), toBase.map { it.element?.containingFile?.name })
    assertEquals(base.virtualFile, toBase.single().element?.containingFile?.virtualFile)

    // and the reverse direction, from the base cfg to its patches
    val toPatch = S2CfgRelatedProvider().getItems(base)
    assertEquals(listOf("Armor_patch_Mine.cfg"), toPatch.map { it.element?.containingFile?.name })
  }

  fun testDocumentationShowsFieldsAndInheritance() {
    val file = myFixture.configureByText(
      "Doc.cfg",
      "Mine : struct.begin {refurl=../Base/Armor.cfg; refkey=Battle_Varta_Armor}\n" +
        "   SID = Mine\n   MaxDurability = 1500.0\n   Upgrades : struct.begin\n   struct.end\nstruct.end",
    )
    val struct = (file as S2CfgFile).structs.single()
    val doc = S2CfgDocumentationProvider().generateDoc(struct, null)!!
    assertTrue(doc, doc.contains("Mine"))
    assertTrue(doc, doc.contains("MaxDurability"))
    assertTrue(doc, doc.contains("Battle_Varta_Armor"))
    assertTrue(doc, doc.contains("Upgrades"))
  }
}
