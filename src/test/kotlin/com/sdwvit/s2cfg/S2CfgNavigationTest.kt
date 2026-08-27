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
}
