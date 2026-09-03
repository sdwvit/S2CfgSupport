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
    assertEquals(S2Localization.read(asset), S2Localization.parse(json, asset.names))
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

  fun testMalformedJsonIsRejectedWithAnOffset() {
    val e = try {
      S2Localization.parse("{\"LocalizedTexts\": [ }", null)
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
        S2Localization.parse(bad, null)
        fail("accepted $bad")
      } catch (_: S2LocalizationException) {
      }
    }
  }

  /** The writer cannot grow the name table, so an unknown language has to fail before the write. */
  fun testUnknownLanguageIsRejected() {
    val asset = S2UassetFormat.parse(bytes)
    val json = S2Localization.toJson(asset).replace("ELocalizationLanguage::English", "Klingon")
    try {
      S2Localization.parse(json, asset.names)
      fail("accepted an unknown language")
    } catch (e: S2LocalizationException) {
      assertTrue(e.message!!.contains("Klingon"))
    }
  }

  fun testHeaderDetection() {
    assertTrue(S2UassetFormat.isLocalizationPackage(bytes))
    assertFalse(S2UassetFormat.isLocalizationPackage(ByteArray(64)))
    assertFalse(S2UassetFormat.isLocalizationPackage("Foo : struct.begin".toByteArray()))
  }
}
