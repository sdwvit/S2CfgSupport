package com.sdwvit.s2cfg

import junit.framework.TestCase
import java.io.File

/**
 * The localization editor writes binary packages the game loads, so the properties that matter are
 * that a package survives a no-op round trip byte for byte, and that an edited one still parses.
 */
class S2LocalizationTest : TestCase() {

  private val bytes by lazy {
    File("src/test/testData/FactionPatches-localization.uasset").readBytes()
  }

  fun testReadsEntries() {
    val entries = S2Localization.read(S2UassetFormat.parse(bytes))
    assertEquals(28, entries.size)
    val name = entries.first { it.sid == "sid_items_FactionPatch_name" }
    assertEquals("Faction Patch", name.languages["ELocalizationLanguage::English"])
    assertEquals("Шеврон фракції", name.languages["ELocalizationLanguage::Ukrainian"])
    // Every entry carries the full language set the SDK saved, and every value is a string.
    assertEquals(18, name.languages.size)
    assertTrue(entries.all { it.languages.keys == name.languages.keys })
  }

  fun testJsonIsWhatTheEditorParsesBack() {
    val asset = S2UassetFormat.parse(bytes)
    val json = S2Localization.toJson(asset)
    assertEquals(S2Localization.read(asset), S2Localization.parse(json))
  }

  /** A save that changes nothing must not change a single byte of the package. */
  fun testNoOpRoundTripIsByteIdentical() {
    val json = S2Localization.toJson(S2UassetFormat.parse(bytes))
    assertTrue(bytes.contentEquals(S2Localization.apply(bytes, json)))
  }

  fun testEditedTextSurvivesTheRoundTrip() {
    val asset = S2UassetFormat.parse(bytes)
    val edited = S2Localization.toJson(asset)
      .replace("\"Faction Patch\"", "\"Faction Patch — довша назва\"")
    val out = S2Localization.apply(bytes, edited)
    val reparsed = S2Localization.read(S2UassetFormat.parse(out))
    assertEquals(
      "Faction Patch — довша назва",
      reparsed.first { it.sid == "sid_items_FactionPatch_name" }
        .languages["ELocalizationLanguage::English"],
    )
    // Dropping an entry shortens the export, which moves every later offset the other way.
    val fewer = S2Localization.read(S2UassetFormat.parse(out)).drop(1)
    val shrunk = S2UassetFormat.withLocalizedTexts(out, fewer)
    assertEquals(fewer, S2Localization.read(S2UassetFormat.parse(shrunk)))
  }

  /**
   * Rewriting what the SDK's editor wrote must reproduce its bytes. This is the whole contract for
   * the name-table rebuild: the header is no longer copied through, so every offset behind the
   * table — including the asset registry section's absolute pointer to its own dependency data,
   * which nothing in the summary points at — is only right if the arithmetic is.
   */
  fun testRewritingTheEditorsOwnBytesReproducesThem() {
    val original = File("src/test/testData/authored-localization.uasset").readBytes()
    val entries = S2Localization.read(S2UassetFormat.parse(original))
    assertEquals(3, entries.size)
    // Every language the enum offers, keyed the way the editor keys them.
    assertTrue(entries[0].languages.keys.contains("ELocalizationLanguage::Ukrainian"))

    val rewritten = S2UassetFormat.withLocalizedTexts(original, entries)
    assertTrue(original.contentEquals(rewritten))
  }

  /** The same, for a package with a different entry count — the name table lands elsewhere. */
  fun testRewritingTheSecondFixtureReproducesItToo() {
    val original = File("src/test/testData/authored2-localization.uasset").readBytes()
    val entries = S2Localization.read(S2UassetFormat.parse(original))
    assertTrue(original.contentEquals(S2UassetFormat.withLocalizedTexts(original, entries)))
  }

  /** The prize for rebuilding the name table: a language the package was never saved with. */
  fun testALanguageTheNameTableLacksCanBeAdded() {
    val entries = S2Localization.read(S2UassetFormat.parse(bytes))
    assertFalse("fixture already knows Klingon", "Klingon" in S2UassetFormat.parse(bytes).names)
    val withNew = entries.map { it.copy(languages = it.languages + ("Klingon" to "tlhIngan")) }
    val out = S2UassetFormat.withLocalizedTexts(bytes, withNew)
    assertEquals(withNew, S2Localization.read(S2UassetFormat.parse(out)))
    assertTrue("Klingon" in S2UassetFormat.parse(out).names)
  }

  /** An empty asset the SDK hands out has none of the payload's names; the writer adds them all. */
  fun testAnEmptyAssetCanBeFilled() {
    val empty = File("src/test/testData/empty-localization.uasset").readBytes()
    assertEquals(emptyList<S2LocalizedText>(), S2Localization.read(S2UassetFormat.parse(empty)))
    val entries = listOf(
      S2LocalizedText("sid_test_a", linkedMapOf("English" to "Alpha", "Ukrainian" to "Альфа")),
      S2LocalizedText("sid_test_b", linkedMapOf("English" to "Beta")),
    )
    val out = S2UassetFormat.withLocalizedTexts(empty, entries)
    val after = S2UassetFormat.parse(out)
    assertEquals(entries, S2Localization.read(after))
    // The export data starts where the header ends, so the header grew by exactly the right amount.
    assertEquals(after.summary.totalHeaderSize.toLong(), after.exports[0].serialOffset)
  }

  /**
   * The output is a function of the package's identity and the entries, nothing else. Without
   * dropping the old name-table prefix, writing over an asset that held *more* text would leave
   * that text's names behind and the bytes would drift with edit history.
   */
  fun testTheBytesDoNotDependOnWhatTheAssetHeldBefore() {
    val template = File("src/test/testData/authored2-localization.uasset").readBytes()
    val entries = listOf(
      S2LocalizedText("sid_test_a", linkedMapOf("English" to "Alpha")),
      S2LocalizedText("sid_test_b", linkedMapOf("English" to "Beta")),
    )
    val fresh = S2UassetFormat.withLocalizedTexts(template, entries)
    val detour = S2UassetFormat.withLocalizedTexts(
      template,
      entries + S2LocalizedText("sid_test_c", linkedMapOf("English" to "Gamma", "Polish" to "Gamma")),
    )
    assertTrue(fresh.contentEquals(S2UassetFormat.withLocalizedTexts(detour, entries)))
  }

  fun testMalformedJsonIsRejectedWithAnOffset() {
    val e = try {
      S2Localization.parse("{\"LocalizedTexts\": [ }")
      null
    } catch (e: S2JsonException) {
      e
    }
    assertNotNull(e)
    assertTrue(e!!.offset > 0)
  }

  fun testSchemaViolationsAreRejected() {
    for (bad in listOf(
      """[]""",
      """{}""",
      """{"LocalizedTexts": {}}""",
      """{"LocalizedTexts": [{"SID": "a"}]}""",
      """{"LocalizedTexts": [{"SID": 1, "LanguagesToLocalizedStrings": {}}]}""",
      """{"LocalizedTexts": [{"SID": "", "LanguagesToLocalizedStrings": {}}]}""",
      """{"LocalizedTexts": [{"SID": "a", "LanguagesToLocalizedStrings": {"x": 1}}]}""",
      """{"LocalizedTexts": [{"SID": "a", "LanguagesToLocalizedStrings": {}},
         {"SID": "a", "LanguagesToLocalizedStrings": {}}]}""",
    )) {
      try {
        S2Localization.parse(bad)
        fail("accepted $bad")
      } catch (_: S2LocalizationException) {
      }
    }
  }

  /** Language keys become FNames, and `FNameEntrySerialized`'s hashes are byte-wise. */
  fun testNonAsciiLanguageIsRejected() {
    val json = S2Localization.toJson(S2UassetFormat.parse(bytes))
      .replace("ELocalizationLanguage::English", "Клінгонська")
    try {
      S2Localization.parse(json)
      fail("accepted a non-ASCII language")
    } catch (e: S2LocalizationException) {
      assertTrue(e.message!!.contains("ASCII"))
    }
  }

  private val empty by lazy { File("src/test/testData/empty-localization.uasset").readBytes() }

  /**
   * The rename's correctness test: renaming away and back must land on the original bytes, so the
   * offsets it patched were the only difference. It exercises everything at once — FString splices
   * of different lengths, the re-sorted and re-hashed name table, and the offsets pushed through
   * all of it — and needs no fixture of its own.
   */
  fun testRenamingBackAndForthIsByteExact() {
    val was = S2UassetFormat.parse(empty).summary.packageName
    val away = S2UassetFormat.renameLocalizationPackage(empty, "/Other/Other-LocalizationWithALongName")
    val back = S2UassetFormat.renameLocalizationPackage(away, was)
    assertEquals(empty.size, back.size)
    // Except the localization id, which is re-derived rather than carried: it namespaces the
    // package's gathered text, so two mods minted from this fixture must not share one. The
    // fixture's own id is not an md5 of its name, so a reverse rename cannot reproduce it.
    val id = S2UassetFormat.parse(empty).summary.localizationId.toByteArray(Charsets.ISO_8859_1)
    val idAt = empty.indices.filter { at ->
      at + id.size <= empty.size && id.indices.all { empty[at + it] == id[it] }
    }
    assertEquals(2, idAt.size) // the summary, and PackageMetaData's localization namespace
    val differing = empty.indices.filter { empty[it] != back[it] }
    assertTrue("differs outside the localization id: $differing",
      differing.all { i -> idAt.any { i >= it && i < it + id.size } })
  }

  fun testRenamingSpellsTheNewNameEverywhere() {
    val to = "/AVeryLongModNameIndeed/AVeryLongModNameIndeed-Localization12"
    val out = S2UassetFormat.renameLocalizationPackage(empty, to)
    val after = S2UassetFormat.parse(out)
    assertEquals(to, after.summary.packageName)
    assertEquals("AVeryLongModNameIndeed-Localization12", after.exports[0].objectName)
    // The asset registry section and the summary spell it out too, so nothing may be left over —
    // a stale spelling there is what makes the editor list a package it cannot then load.
    assertFalse(String(out, Charsets.ISO_8859_1).contains("FactionPatches"))
    // Every length change has to have been pushed through every stored offset; the export data
    // starting exactly at the end of the header is the check the parser does not do for itself.
    assertEquals(after.summary.totalHeaderSize.toLong(), after.exports[0].serialOffset)
  }

  /** Two mods minted from one template must not share a text namespace. */
  fun testTheLocalizationIdIsDerivedFromTheNewName() {
    val a = S2UassetFormat.parse(S2UassetFormat.renameLocalizationPackage(empty, "/A/A-Localization"))
    val b = S2UassetFormat.parse(S2UassetFormat.renameLocalizationPackage(empty, "/B/B-Localization"))
    val again = S2UassetFormat.parse(S2UassetFormat.renameLocalizationPackage(empty, "/A/A-Localization"))
    assertFalse(a.summary.localizationId == b.summary.localizationId)
    assertEquals(a.summary.localizationId, again.summary.localizationId)
    assertEquals(
      java.security.MessageDigest.getInstance("MD5").digest("/A/A-Localization".toByteArray())
        .joinToString("") { "%02X".format(it) },
      a.summary.localizationId,
    )
  }

  /** Renaming to the name it already has is a no-op, not a rewrite. */
  fun testRenamingToTheSameNameChangesNothing() {
    val was = S2UassetFormat.parse(empty).summary.packageName
    assertTrue(empty.contentEquals(S2UassetFormat.renameLocalizationPackage(empty, was)))
  }

  /** The two writers have to compose: a renamed template is what a mod's first asset is made of. */
  fun testARenamedPackageStillAcceptsAnEdit() {
    val renamed = S2UassetFormat.renameLocalizationPackage(empty, "/NewMod/NewMod-Localization2")
    val entries = listOf(S2LocalizedText("sid_new", linkedMapOf("English" to "New")))
    val after = S2UassetFormat.parse(S2UassetFormat.withLocalizedTexts(renamed, entries))
    assertEquals(entries, S2Localization.read(after))
    assertEquals("/NewMod/NewMod-Localization2", after.summary.packageName)
  }

  fun testPackageNameIsDerivedFromThePathUnderTheContentRoot() {
    val root = "/sdk/Stalker2/Content"
    assertEquals(
      "/MyMod/FactionPatches-localization",
      S2CfgSettings.packageNameFor(root, "$root/MyMod/FactionPatches-localization.uasset"),
    )
    // Subdirectories between the mod and the asset are part of the mount path.
    assertEquals(
      "/MyMod/Text/Patches",
      S2CfgSettings.packageNameFor(root, "$root/MyMod/Text/Patches.uasset"),
    )
    // Nothing to compare against: no root set, outside the root, or no mod directory at all.
    assertNull(S2CfgSettings.packageNameFor("", "$root/MyMod/Foo.uasset"))
    assertNull(S2CfgSettings.packageNameFor(root, "/elsewhere/MyMod/Foo.uasset"))
    assertNull(S2CfgSettings.packageNameFor(root, "$root/Foo.uasset"))
  }

  fun testHeaderDetection() {
    assertTrue(S2UassetFormat.isLocalizationPackage(bytes))
    assertFalse(S2UassetFormat.isLocalizationPackage(ByteArray(64)))
    assertFalse(S2UassetFormat.isLocalizationPackage("Foo : struct.begin".toByteArray()))
  }
}
