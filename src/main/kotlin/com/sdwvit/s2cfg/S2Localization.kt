package com.sdwvit.s2cfg

/** One `LocalizedTexts` element: a record SID and its per-language strings. */
data class S2LocalizedText(val sid: String, val languages: Map<String, String>)

/** Raised when the JSON in the editor is well-formed but not a localization document. */
class S2LocalizationException(message: String, val offset: Int = -1) : Exception(message)

/**
 * The bridge between a `*-localization.uasset` package and the JSON document the editor shows.
 *
 * The JSON shape is deliberately the same one the S2Mods `src/localization/uasset.mts` dump prints —
 * `{"LocalizedTexts": [{"SID": …, "LanguagesToLocalizedStrings": {…}}]}` — so a document can be
 * copied between the two tools.
 */
object S2Localization {

  fun toJson(asset: S2Uasset): String = toJson(read(asset))

  fun toJson(entries: List<S2LocalizedText>): String = S2Json.write(
    S2JsonValue.Obj(
      listOf(
        S2UassetFormat.LOCALIZED_TEXTS to S2JsonValue.Arr(
          entries.map { entry ->
            S2JsonValue.Obj(
              listOf(
                S2UassetFormat.SID to S2JsonValue.Str(entry.sid),
                S2UassetFormat.LANGUAGES to S2JsonValue.Obj(
                  entry.languages.map { (k, v) -> k to S2JsonValue.Str(v) }
                ),
              )
            )
          }
        )
      )
    )
  ) + "\n"

  /** Pulls the entries out of the package's [S2UassetFormat.LOCALIZATION_CLASS] export. */
  fun read(asset: S2Uasset): List<S2LocalizedText> {
    val export = asset.exports.firstOrNull { it.className == S2UassetFormat.LOCALIZATION_CLASS }
      ?: throw S2UassetException("no ${S2UassetFormat.LOCALIZATION_CLASS} export in this package")
    val texts = export.properties?.get(S2UassetFormat.LOCALIZED_TEXTS)
    if (texts == null) return emptyList()
    if (texts !is S2PropertyValue.Items)
      throw S2UassetException("${S2UassetFormat.LOCALIZED_TEXTS} is not an array")
    return texts.items.map { item ->
      val fields = (item as? S2PropertyValue.Fields)?.fields
        ?: throw S2UassetException("${S2UassetFormat.LOCALIZED_TEXTS} element is not a struct")
      val sid = (fields[S2UassetFormat.SID] as? S2PropertyValue.Text)?.value ?: ""
      val languages = (fields[S2UassetFormat.LANGUAGES] as? S2PropertyValue.Fields)?.fields
        ?.mapValues { (_, v) -> (v as? S2PropertyValue.Text)?.value ?: "" }
        ?: emptyMap()
      S2LocalizedText(sid, languages)
    }
  }

  /**
   * Parses and validates the editor's text.
   *
   * Everything that would make the write fail is checked here, so the caller can refuse to save
   * with a message instead of leaving a half-written package behind: JSON syntax, the document
   * schema, and that every language key can be hashed into an FName. A language the package was
   * not saved with is fine — the writer rebuilds the name table around whatever the document
   * needs — but a non-ASCII one is not, because `FNameEntrySerialized`'s hashes are byte-wise.
   */
  fun parse(text: String): List<S2LocalizedText> {
    val root = S2Json.parse(text)
    if (root !is S2JsonValue.Obj)
      throw S2LocalizationException("the top-level value must be an object", 0)
    val texts = root[S2UassetFormat.LOCALIZED_TEXTS]
      ?: throw S2LocalizationException("missing \"${S2UassetFormat.LOCALIZED_TEXTS}\"", 0)
    if (texts !is S2JsonValue.Arr)
      throw S2LocalizationException("\"${S2UassetFormat.LOCALIZED_TEXTS}\" must be an array")

    val seen = HashSet<String>()
    return texts.items.mapIndexed { i, item ->
      val where = "${S2UassetFormat.LOCALIZED_TEXTS}[$i]"
      if (item !is S2JsonValue.Obj) throw S2LocalizationException("$where must be an object")
      val sid = item[S2UassetFormat.SID]
        ?: throw S2LocalizationException("$where has no \"${S2UassetFormat.SID}\"")
      if (sid !is S2JsonValue.Str)
        throw S2LocalizationException("$where.${S2UassetFormat.SID} must be a string")
      if (sid.value.isEmpty())
        throw S2LocalizationException("$where.${S2UassetFormat.SID} must not be empty")
      if (!seen.add(sid.value))
        throw S2LocalizationException("duplicate ${S2UassetFormat.SID} \"${sid.value}\" at $where")

      val languages = item[S2UassetFormat.LANGUAGES]
        ?: throw S2LocalizationException("$where has no \"${S2UassetFormat.LANGUAGES}\"")
      if (languages !is S2JsonValue.Obj)
        throw S2LocalizationException("$where.${S2UassetFormat.LANGUAGES} must be an object")
      val strings = LinkedHashMap<String, String>()
      for ((language, value) in languages.entries) {
        if (value !is S2JsonValue.Str)
          throw S2LocalizationException("$where.${S2UassetFormat.LANGUAGES}.$language must be a string")
        if (!language.all { it.code in 0..0x7f }) {
          throw S2LocalizationException(
            "$where.${S2UassetFormat.LANGUAGES} names \"$language\", which is not ASCII — " +
              "language keys become FNames and cannot be hashed"
          )
        }
        if (strings.put(language, value.value) != null)
          throw S2LocalizationException("duplicate language \"$language\" at $where")
      }
      S2LocalizedText(sid.value, strings)
    }
  }

  /** Applies the editor's [text] to [original] package bytes, or throws if [text] is invalid. */
  fun apply(original: ByteArray, text: String): ByteArray {
    return S2UassetFormat.withLocalizedTexts(original, parse(text))
  }
}
