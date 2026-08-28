package com.sdwvit.s2cfg

import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class S2CfgNavigationTest : BasePlatformTestCase() {

  fun testScanDeclarations() {
    val declarations = S2CfgSidIndex.scanDeclarations(
      """
      Root : struct.begin
         SID = Root_Sid
         Launchers : struct.begin
            [0] : struct.begin
               SID = SomeOtherRecord
            struct.end
         struct.end
      struct.end
      Second : struct.begin {bpatch}
         Conditions : removenode
         SID = Second
      struct.end
      """.trimIndent()
    )
    // the nested SID is a reference to another record, so it must not be indexed here
    assertEquals(setOf("Root", "Root_Sid", "Second"), declarations)
  }

  fun testNestedSidResolvesAcrossFiles() {
    myFixture.addFileToProject(
      "Quests/Targets.cfg",
      """
      ANCQ27_Start : struct.begin
         SID = ANCQ27_Start
      struct.end
      """.trimIndent(),
    )
    val file = myFixture.configureByText(
      "Quest.cfg",
      """
      ANCQ27_Spawn : struct.begin
         SID = ANCQ27_Spawn
         Launchers : struct.begin
            [0] : struct.begin
               SID = ANCQ27_St<caret>art
            struct.end
         struct.end
      struct.end
      """.trimIndent(),
    )
    val target = resolveAtCaret(file)
    assertEquals("ANCQ27_Start", (target as S2CfgStruct).name0)
    assertEquals("Targets.cfg", target.containingFile.name)
  }

  fun testOwnSidIsADeclarationNotAReference() {
    val file = myFixture.configureByText(
      "Quest.cfg",
      "Foo : struct.begin\n   SID = F<caret>oo\nstruct.end",
    )
    val value = PsiTreeUtil.findElementOfClassAtOffset(
      file, myFixture.caretOffset, S2CfgValue::class.java, false,
    )!!
    assertEmpty(value.references.toList())
  }

  fun testRefurlResolvesToFile() {
    myFixture.addFileToProject("Base/Armor.cfg", "Battle_Varta_Armor : struct.begin\n   A = 1\nstruct.end")
    // the patch lives in a sibling directory, so the real-world `../` form is exercised
    val patch = myFixture.addFileToProject(
      "Mods/Patch.cfg",
      "Mine : struct.begin {refurl=../Base/Armor.cfg; refkey=Battle_Varta_Armor}\nstruct.end",
    )
    myFixture.configureFromExistingVirtualFile(patch.virtualFile)
    val refs = PsiTreeUtil.findChildrenOfType(patch, S2CfgRef::class.java).associateBy { it.keyword }

    assertEquals("Armor.cfg", (refs["refurl"]!!.references.single().resolve() as PsiFile).name)

    val base = refs["refkey"]!!.references.single().resolve() as S2CfgStruct
    assertEquals("Battle_Varta_Armor", base.name0)
    assertEquals("Armor.cfg", base.containingFile.name)
  }

  fun testRefkeyResolvesInSameFileWhenNoRefurl() {
    val file = myFixture.configureByText(
      "Same.cfg",
      """
      Base : struct.begin
         A = 1
      struct.end
      Derived : struct.begin {refkey=Ba<caret>se}
         A = 2
      struct.end
      """.trimIndent(),
    )
    assertEquals("Base", (resolveAtCaret(file) as S2CfgStruct).name0)
  }

  fun testRefkeyByIndexHasNoReference() {
    val file = myFixture.configureByText("Idx.cfg", "D : struct.begin {refkey=[<caret>0]}\nstruct.end")
    val ref = PsiTreeUtil.findChildrenOfType(file, S2CfgRef::class.java).single()
    assertEmpty(ref.references.toList())
  }

  fun testRenameUpdatesSidAndReferences() {
    val referencing = myFixture.addFileToProject(
      "Other.cfg",
      """
      Caller : struct.begin
         SID = Caller
         Launchers : struct.begin
            [0] : struct.begin
               SID = OldName
            struct.end
         struct.end
      struct.end
      """.trimIndent(),
    )
    myFixture.configureByText(
      "Decl.cfg",
      "Old<caret>Name : struct.begin\n   SID = OldName\n   A = 1\nstruct.end",
    )
    myFixture.renameElementAtCaret("NewName")

    myFixture.checkResult("NewName : struct.begin\n   SID = NewName\n   A = 1\nstruct.end")
    assertTrue(
      "reference was not updated:\n${referencing.text}",
      referencing.text.contains("SID = NewName"),
    )
  }

  private fun resolveAtCaret(file: PsiFile) =
    file.findReferenceAt(myFixture.caretOffset)!!.resolve()

  /** `FittingWeaponsSIDs/[0] = GunPM_HG` navigates: the array's name is what makes it a reference. */
  fun testArrayElementResolvesThroughEnclosingArrayName() {
    myFixture.addFileToProject(
      "Weapons/Guns.cfg",
      """
      GunPM_HG : struct.begin
         SID = GunPM_HG
      struct.end
      """.trimIndent(),
    )
    val file = myFixture.configureByText(
      "Attachment.cfg",
      """
      Scope : struct.begin
         SID = Scope
         FittingWeaponsSIDs : struct.begin
            [0] = GunPM_HG
            [1] = GunAPB_HG
         struct.end
      struct.end
      """.trimIndent(),
    )
    val target = resolveValueNamed(file, "GunPM_HG")
    assertNotNull("[0] = GunPM_HG should resolve", target)
    assertEquals("Guns.cfg", target!!.containingFile.name)
  }

  /** Plural and `IDs` spellings carry references too; `Name` does not. */
  fun testReferenceKeyShapes() {
    for (key in listOf("SID", "QuestSID", "UpgradePrototypeSIDs", "RequiredUpgradeIDs", "BlockingUpgradeIds")) {
      assertTrue(key, S2CfgDeclarations.isReferenceKey(key))
    }
    // `Guid` keys point at records too, and so do the keys that do not say so in their name
    for (key in listOf("PlaceholderActorGuid", "TargetQuestGuids", "BlockingBodyMeshes", "AvailableDialogs", "Faction")) {
      assertTrue(key, S2CfgDeclarations.isReferenceKey(key))
    }
    // these pass a naive "value looks like a record name" test but are not references
    for (key in listOf("Name", "MaxDurability", "ItemType", "DLC", "PresetName", "MaterialGroup", "VariableValue")) {
      assertFalse(key, S2CfgDeclarations.isReferenceKey(key))
    }
  }

  private fun resolveValueNamed(file: PsiFile, text: String) =
    PsiTreeUtil.findChildrenOfType(file, S2CfgValue::class.java)
      .firstOrNull { it.text.trim() == text }
      ?.reference
      ?.resolve()

  fun testUpgradePrototypeSidsArrayResolves() {
    myFixture.addFileToProject(
      "Upgrades/Zorya.cfg",
      """
      Zorya_Neutral_Armor_MaxDurability_Left_1_1 : struct.begin
         SID = Zorya_Neutral_Armor_MaxDurability_Left_1_1
      struct.end
      """.trimIndent(),
    )
    val file = myFixture.configureByText(
      "Armor.cfg",
      """
      Zorya_Neutral_Armor : struct.begin
         SID = Zorya_Neutral_Armor
         UpgradePrototypeSIDs : struct.begin
            [0] = Zorya_Neutral_Armor_MaxDurability_Left_1_1
         struct.end
      struct.end
      """.trimIndent(),
    )
    val target = resolveValueNamed(file, "Zorya_Neutral_Armor_MaxDurability_Left_1_1")
    assertNotNull("[0] = ... should resolve", target)
  }

  /** A list-valued reference key makes every name in it clickable, not just the first. */
  fun testListValueResolvesEachName() {
    myFixture.addFileToProject(
      "Upgrades/Up.cfg",
      """
      Up_01 : struct.begin
         SID = Up_01
      struct.end
      Up_02 : struct.begin
         SID = Up_02
      struct.end
      """.trimIndent(),
    )
    val file = myFixture.configureByText(
      "Armor.cfg",
      "Mine : struct.begin\n   SID = Mine\n   RequiredUpgradeIDs = Up_01, Up_02\nstruct.end",
    )
    val value = PsiTreeUtil.findChildrenOfType(file, S2CfgValue::class.java)
      .first { it.text.contains("Up_01") }
    val resolved = value.references.mapNotNull { (it as com.intellij.psi.PsiPolyVariantReference).resolve() }
    assertEquals(2, value.references.size)
    assertEquals(listOf("Up_01", "Up_02"), resolved.map { (it as S2CfgStruct).name0 })
  }

  /** `EItemType::Armor` and numbers are not record names, so they contribute no reference. */
  fun testEnumAndNumericValuesHaveNoReference() {
    val file = myFixture.configureByText(
      "Armor.cfg",
      "Mine : struct.begin\n   SID = Mine\n   UpgradeSID = EItemType::Armor\n   OtherID = 12\nstruct.end",
    )
    for (text in listOf("EItemType::Armor", "12")) {
      val value = PsiTreeUtil.findChildrenOfType(file, S2CfgValue::class.java)
        .first { it.text.trim() == text }
      assertEmpty(text, value.references.toList())
    }
  }

  /** The same name is declared in two kinds of file; the key says which one is meant. */
  fun testKeyNameNarrowsAmbiguousTarget() {
    myFixture.addFileToProject(
      "DialogPrototypes/D.cfg",
      "Shared_Name : struct.begin\n   SID = Shared_Name\nstruct.end",
    )
    myFixture.addFileToProject(
      "UpgradePrototypes.cfg",
      "Shared_Name : struct.begin\n   SID = Shared_Name\nstruct.end",
    )
    val file = myFixture.configureByText(
      "Quest.cfg",
      """
      Node : struct.begin
         SID = Node
         LastPhraseSID = Shared_Name
         UpgradeSID = Shared_Name
      struct.end
      """.trimIndent(),
    )
    val byKey = PsiTreeUtil.findChildrenOfType(file, S2CfgEntry::class.java)
      .filter { it.valueText == "Shared_Name" }
      .associate { it.keyName to it.valueElement!!.reference!!.resolve() }
    assertEquals("D.cfg", (byKey["LastPhraseSID"] as S2CfgStruct).containingFile.name)
    assertEquals("UpgradePrototypes.cfg", (byKey["UpgradeSID"] as S2CfgStruct).containingFile.name)
  }

  /** A project that does not mirror the GameData layout still resolves: the hint is dropped. */
  fun testHintIsIgnoredWhenNothingMatchesIt() {
    myFixture.addFileToProject(
      "MyMod/Phrases.cfg",
      "Some_Phrase : struct.begin\n   SID = Some_Phrase\nstruct.end",
    )
    val file = myFixture.configureByText(
      "Quest.cfg",
      "Node : struct.begin\n   SID = Node\n   LastPhraseSID = Some_Phrase\nstruct.end",
    )
    val target = resolveValueNamed(file, "Some_Phrase")
    assertEquals("Phrases.cfg", (target as? S2CfgStruct)?.containingFile?.name)
  }

  /** `PlayerOnlyEffects/[*] = X` names effect records, whatever the enclosing effect block is. */
  fun testPlayerOnlyEffectsResolve() {
    myFixture.addFileToProject(
      "GameData/EffectPrototypes.cfg",
      """
      Binoculars_01_AimingPP : struct.begin
         SID = Binoculars_01_AimingPP
      struct.end
      """.trimIndent(),
    )
    val file = myFixture.configureByText(
      "Binoculars.cfg",
      """
      Binoculars_01 : struct.begin
         SID = Binoculars_01
         AimingEffects : struct.begin
            PlayerOnlyEffects : struct.begin
               [*] = Binoculars_01_AimingPP
            struct.end
         struct.end
      struct.end
      """.trimIndent(),
    )
    val target = resolveValueNamed(file, "Binoculars_01_AimingPP")
    assertNotNull("[*] = Binoculars_01_AimingPP should resolve", target)
    assertEquals("EffectPrototypes.cfg", target!!.containingFile.name)
  }
}
