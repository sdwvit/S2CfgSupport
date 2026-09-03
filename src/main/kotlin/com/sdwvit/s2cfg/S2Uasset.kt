package com.sdwvit.s2cfg

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal reader for UE5 `.uasset` packages as written by the STALKER 2 Mod SDK
 * (`LegacyFileVersion -8`, `FileVersionUE4 522`, `FileVersionUE5 1013` — UE 5.4 era). Ported from
 * the S2Mods `localization-uasset.mts` reference reader.
 *
 * Two things differ from the older `.uasset` layouts most parsers implement:
 *
 * 1. The summary puts `SoftObjectPaths` count/offset between `NameOffset` and `LocalizationId`.
 * 2. Property tags carry UE 5.4's recursive `FPropertyTypeName` (an FName plus a parameter list)
 *    instead of the old `Type`/`InnerType`/`StructName` fields, and end with
 *    `Size:int32, Flags:uint8` — an `EPropertyTagFlags` bitfield. `ArrayIndex` and the property
 *    GUID are only present when the flags say so, a `bool`'s value *is* one of the flags, and
 *    natively-serialised structs (`Color`, `Guid`, …) are marked there rather than by name.
 *
 * Only as much of a package is decoded as the localization editor needs; anything undecodable is
 * kept as raw bytes rather than dropped, so a value we do not understand still round-trips when
 * the export it lives in is left alone.
 */

/** A parsed `FPropertyTypeName`: `MapProperty(EnumProperty(ELocalizationLanguage(…)),StrProperty)`. */
data class S2PropertyTypeName(val name: String, val params: List<S2PropertyTypeName> = emptyList()) {
  override fun toString() =
    if (params.isEmpty()) name else "$name(${params.joinToString(",")})"
}

/** Property values are the JSON-ish tree the tagged-property reader produces. */
sealed interface S2PropertyValue {
  data class Num(val value: Double) : S2PropertyValue
  data class Text(val value: String) : S2PropertyValue
  data class Flag(val value: Boolean) : S2PropertyValue
  data class Items(val items: List<S2PropertyValue>) : S2PropertyValue
  data class Fields(val fields: Map<String, S2PropertyValue>) : S2PropertyValue

  /** Natively serialised or undecodable bytes, kept verbatim so nothing is silently lost. */
  data class Raw(val hex: String) : S2PropertyValue
}

data class S2UassetImport(
  val classPackage: String,
  val className: String,
  val outerIndex: Int,
  val objectName: String,
  val packageName: String,
)

class S2UassetExport(
  val objectName: String,
  val className: String?,
  val serialOffset: Long,
  val serialSize: Long,
  /** `null` when the export's bytes are not tagged properties we could read. */
  var properties: Map<String, S2PropertyValue>? = null,
)

class S2UassetSummary(
  val legacyFileVersion: Int,
  val fileVersionUE5: Int,
  val packageName: String,
  val nameCount: Int,
  val nameOffset: Int,
  val exportCount: Int,
  val exportOffset: Int,
  val importCount: Int,
  val importOffset: Int,
  val dependsOffset: Int,
  /**
   * Byte positions *of* the two int64 summary fields that point past the export data, so the
   * writer can patch them without guessing. Their positions move with the length of every FString
   * ahead of them (the package name, the localization id, the engine-version branch names), so
   * they are recorded while walking rather than hardcoded. Both are `null` when the summary tail
   * did not decode to exactly `nameOffset` — alignment was lost and this package must not be
   * written to; `payloadTocOffsetAt` alone is `null` in packages predating that field.
   */
  val bulkDataStartOffsetAt: Int?,
  val payloadTocOffsetAt: Int?,
)

class S2Uasset(
  val summary: S2UassetSummary,
  /** Derived from the table bounds, not hardcoded — see `readExports`. */
  val exportStride: Int,
  val names: List<String>,
  val imports: List<S2UassetImport>,
  val exports: List<S2UassetExport>,
)

class S2UassetException(message: String) : Exception(message)

object S2UassetFormat {
  const val PACKAGE_FILE_TAG: Int = 0x9e2a83c1.toInt()

  /** The Mod SDK's per-mod text asset — the only export type this plugin can write back. */
  const val LOCALIZATION_CLASS = "LocalizationModTextToolAsset"
  const val LOCALIZED_TEXTS = "LocalizedTexts"
  const val SID = "SID"
  const val LANGUAGES = "LanguagesToLocalizedStrings"

  /**
   * `EUnrealEngineObjectUE5Version` values the summary layout and the property-tag layout hinge
   * on. The SDK writes 1013; older hand-made assets are 1008, which is *before* complete property
   * type names, so their exports use the legacy tag format this parser does not implement.
   */
  private const val UE5_NAMES_REFERENCED_FROM_EXPORT_DATA = 1001
  private const val UE5_PAYLOAD_TOC = 1002
  private const val UE5_ADD_SOFTOBJECTPATH_LIST = 1008
  private const val UE5_DATA_RESOURCES = 1009
  private const val UE5_PROPERTY_TAG_COMPLETE_TYPE_NAME = 1012

  /** `EPropertyTagFlags` (UE 5.4). */
  private const val TAG_HAS_ARRAY_INDEX = 1 shl 0
  private const val TAG_HAS_PROPERTY_GUID = 1 shl 1
  private const val TAG_HAS_PROPERTY_EXTENSIONS = 1 shl 2
  private const val TAG_HAS_BINARY_OR_NATIVE_SERIALIZE = 1 shl 3
  private const val TAG_BOOL_TRUE = 1 shl 4

  /** `EPropertyTagExtension::OverridableInformation`. */
  private const val TAG_EXT_OVERRIDABLE_INFORMATION = 1 shl 0

  /** Export classes whose bytes are not a tagged property list at all. */
  private val NATIVELY_SERIALISED_EXPORTS =
    setOf("MetaData", "AssetImportData", "InterchangeAssetImportData")

  private const val MAX_TYPE_PARAMS = 8

  /** `PKG_FilterEditorOnly` — set on cooked packages, and drops a handful of summary fields. */
  private const val PKG_FILTER_EDITOR_ONLY = 0x8000_0000L

  private const val EXPORT_SERIAL_SIZE_AT = 28
  private const val EXPORT_SERIAL_OFFSET_AT = 36

  /**
   * True when [bytes] look like a package holding a [LOCALIZATION_CLASS] export. Cheap enough to
   * run from a file editor provider: it reads the tag, then the name table, and stops there.
   */
  fun isLocalizationPackage(bytes: ByteArray): Boolean = runCatching {
    val r = Reader(bytes)
    val summary = readSummary(r)
    readNameTable(r, summary).contains(LOCALIZATION_CLASS)
  }.getOrDefault(false)

  fun parse(bytes: ByteArray): S2Uasset {
    val r = Reader(bytes)
    val summary = readSummary(r)
    val names = readNameTable(r, summary)
    val imports = readImports(r, summary, names)
    val stride =
      if (summary.exportCount == 0) 0
      else (summary.dependsOffset - summary.exportOffset) / summary.exportCount
    val exports = readExports(r, summary, stride, names, imports)
    // Tagged property values are only readable here in the UE 5.4 complete-type-name format; the
    // legacy Type/InnerType/StructName layout is a different parser and not implemented.
    if (summary.fileVersionUE5 < UE5_PROPERTY_TAG_COMPLETE_TYPE_NAME) {
      S2CfgLog.LOG.warn(
        "FileVersionUE5 ${summary.fileVersionUE5} predates complete property type names," +
          " reading header only"
      )
      return S2Uasset(summary, stride, names, imports, exports)
    }

    for (exp in exports) {
      if (exp.serialSize == 0L) continue
      // These serialise natively (MetaData is a TMap<FName, TMap<FName, FString>>), so attempting
      // a tagged-property read only produces a warning about garbage.
      if (exp.className in NATIVELY_SERIALISED_EXPORTS) continue
      r.pos = exp.serialOffset.toInt()
      r.u8() // __SerializationControlExtensions
      exp.properties = runCatching {
        readTaggedProperties(r, names, (exp.serialOffset + exp.serialSize).toInt())
      }.onFailure {
        S2CfgLog.LOG.warn("could not read properties of export ${exp.objectName}: ${it.message}")
      }.getOrNull()
    }
    return S2Uasset(summary, stride, names, imports, exports)
  }

  // ---------------------------------------------------------------- reading

  private class Reader(val buf: ByteArray, var pos: Int = 0) {
    private val bb: ByteBuffer = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)

    fun u8(): Int = buf[pos++].toInt() and 0xff
    fun i8(): Int = buf[pos++].toInt()
    fun i16(): Int = bb.getShort(pos).toInt().also { pos += 2 }
    fun u16(): Int = bb.getShort(pos).toInt().and(0xffff).also { pos += 2 }
    fun i32(): Int = bb.getInt(pos).also { pos += 4 }
    fun u32(): Long = bb.getInt(pos).toLong().and(0xffffffffL).also { pos += 4 }
    fun i64(): Long = bb.getLong(pos).also { pos += 8 }
    fun f32(): Float = bb.getFloat(pos).also { pos += 4 }
    fun f64(): Double = bb.getDouble(pos).also { pos += 8 }
    fun skip(n: Int) { pos += n }
    fun hex(from: Int, to: Int): String =
      buf.copyOfRange(from, to).joinToString("") { "%02x".format(it) }

    /** `FString`: positive length is UTF-8, negative length is UTF-16LE. Both are NUL-terminated. */
    fun fstring(): String {
      val len = i32()
      if (len == 0) return ""
      if (len < 0) {
        val bytes = -len * 2
        val s = String(buf, pos, bytes - 2, Charsets.UTF_16LE)
        pos += bytes
        return s
      }
      val s = String(buf, pos, len - 1, Charsets.UTF_8)
      pos += len
      return s
    }
  }

  /** `FName` is an index into the package name table plus a 1-based "number" suffix. */
  private fun readName(r: Reader, names: List<String>): String {
    val index = r.i32()
    val number = r.i32()
    // Out of range means we are no longer aligned to a real FName — bail loudly instead of
    // inventing a name and letting the caller wander off into the rest of the file.
    val base = names.getOrNull(index)
      ?: throw S2UassetException("name index $index out of range at offset ${r.pos - 8}")
    return if (number == 0) base else "${base}_${number - 1}"
  }

  private fun readTypeName(r: Reader, names: List<String>): S2PropertyTypeName {
    val name = readName(r, names)
    val paramCount = r.i32()
    if (paramCount < 0 || paramCount > MAX_TYPE_PARAMS)
      throw S2UassetException("implausible type parameter count $paramCount")
    return S2PropertyTypeName(name, List(paramCount) { readTypeName(r, names) })
  }

  private fun readSummary(r: Reader): S2UassetSummary {
    val tag = r.i32()
    if (tag != PACKAGE_FILE_TAG)
      throw S2UassetException("not a .uasset: unexpected package tag 0x%08x".format(tag))
    val legacyFileVersion = r.i32()
    if (legacyFileVersion != -4) r.i32() // LegacyUE3Version
    r.i32() // FileVersionUE4
    // Only packages at LegacyFileVersion -8 or older carry a separate UE5 version.
    val fileVersionUE5 = if (legacyFileVersion <= -8) r.i32() else 0
    r.i32() // FileVersionLicenseeUE4
    val customVersionCount = r.i32()
    if (customVersionCount < 0 || customVersionCount > 1024)
      throw S2UassetException("implausible custom version count $customVersionCount")
    r.skip(customVersionCount * 20) // GUID + version each

    r.i32() // TotalHeaderSize
    val packageName = r.fstring()
    val packageFlags = r.u32()
    val nameCount = r.i32()
    val nameOffset = r.i32()
    if (fileVersionUE5 >= UE5_ADD_SOFTOBJECTPATH_LIST) {
      r.i32() // SoftObjectPathsCount
      r.i32() // SoftObjectPathsOffset
    }
    r.fstring() // LocalizationId
    r.i32() // GatherableTextDataCount
    r.i32() // GatherableTextDataOffset
    val exportCount = r.i32()
    val exportOffset = r.i32()
    val importCount = r.i32()
    val importOffset = r.i32()
    val dependsOffset = r.i32()

    // The tail is only trustworthy if it lands exactly on the name table; if it does not, we
    // mis-modelled some field and the writer must refuse rather than corrupt the summary. Reading
    // still works either way, so a package we cannot write is opened read-only rather than not at
    // all.
    val decoded = runCatching { readSummaryTail(r, packageFlags, fileVersionUE5) }
      .onFailure { S2CfgLog.LOG.warn("could not read summary tail: ${it.message}") }
      .getOrNull()
    val tail = decoded?.takeIf { it.end == nameOffset }
    if (decoded != null && tail == null)
      S2CfgLog.LOG.warn("summary tail ended at ${decoded.end}, expected name table at $nameOffset")

    return S2UassetSummary(
      legacyFileVersion = legacyFileVersion,
      fileVersionUE5 = fileVersionUE5,
      packageName = packageName,
      nameCount = nameCount,
      nameOffset = nameOffset,
      exportCount = exportCount,
      exportOffset = exportOffset,
      importCount = importCount,
      importOffset = importOffset,
      dependsOffset = dependsOffset,
      bulkDataStartOffsetAt = tail?.bulkDataStartOffsetAt,
      payloadTocOffsetAt = tail?.payloadTocOffsetAt,
    )
  }

  private class SummaryTail(
    val bulkDataStartOffsetAt: Int,
    val payloadTocOffsetAt: Int?,
    val end: Int,
  )

  /**
   * The tail of `FPackageFileSummary` past `DependsOffset`. Nothing in it is interesting to read,
   * but two of its fields (`BulkDataStartOffset`, `PayloadTocOffset`) are the only offsets in the
   * package that point *past* the export data, so the writer has to patch them — hence: walk the
   * tail, record where they landed.
   */
  private fun readSummaryTail(r: Reader, packageFlags: Long, ue5: Int): SummaryTail {
    r.skip(4 * 2) // SoftPackageReferencesCount, SoftPackageReferencesOffset
    r.i32() // SearchableNamesOffset
    r.i32() // ThumbnailTableOffset
    r.skip(16) // Guid
    if (packageFlags and PKG_FILTER_EDITOR_ONLY == 0L) r.skip(16) // PersistentGuid
    r.skip(count(r) * 8) // Generations: (ExportCount, NameCount) each
    readEngineVersion(r) // SavedByEngineVersion
    readEngineVersion(r) // CompatibleWithEngineVersion
    r.u32() // CompressionFlags
    r.skip(count(r) * 16) // CompressedChunks — always empty in modern packages
    r.u32() // PackageSource
    r.skip(count(r) * 4) // AdditionalPackagesToCook — FString array, always empty
    r.i32() // AssetRegistryDataOffset
    val bulkDataStartOffsetAt = r.pos
    r.i64() // BulkDataStartOffset
    r.i32() // WorldTileInfoDataOffset
    r.skip(count(r) * 4) // ChunkIDs
    r.i32() // PreloadDependencyCount
    r.i32() // PreloadDependencyOffset
    if (ue5 >= UE5_NAMES_REFERENCED_FROM_EXPORT_DATA) r.i32() // NamesReferencedFromExportDataCount
    val payloadTocOffsetAt = if (ue5 >= UE5_PAYLOAD_TOC) r.pos else null
    if (payloadTocOffsetAt != null) r.i64() // PayloadTocOffset
    if (ue5 >= UE5_DATA_RESOURCES) r.i32() // DataResourceOffset
    return SummaryTail(bulkDataStartOffsetAt, payloadTocOffsetAt, r.pos)
  }

  /** An inline array count, rejected when it is large enough to mean we lost alignment. */
  private fun count(r: Reader): Int {
    val n = r.i32()
    if (n < 0 || n > 1024) throw S2UassetException("implausible inline array count $n")
    return n
  }

  /** `FEngineVersion`: major/minor/patch, changelist, then the branch name. */
  private fun readEngineVersion(r: Reader) {
    r.u16(); r.u16(); r.u16(); r.u32(); r.fstring()
  }

  private fun readNameTable(r: Reader, summary: S2UassetSummary): List<String> {
    if (summary.nameCount < 0 || summary.nameCount > 1_000_000)
      throw S2UassetException("implausible name count ${summary.nameCount}")
    r.pos = summary.nameOffset
    return List(summary.nameCount) { r.fstring().also { r.skip(4) } } // FNameEntry hashes
  }

  /**
   * Import and export entries are fixed-stride records whose exact field list drifts between
   * engine versions, so the stride is derived from the table bounds instead of hardcoded — only
   * the leading fields we actually use are decoded.
   */
  private fun readImports(
    r: Reader,
    summary: S2UassetSummary,
    names: List<String>,
  ): List<S2UassetImport> {
    if (summary.importCount == 0) return emptyList()
    val stride = (summary.exportOffset - summary.importOffset) / summary.importCount
    return List(summary.importCount) { i ->
      r.pos = summary.importOffset + i * stride
      S2UassetImport(
        classPackage = readName(r, names),
        className = readName(r, names),
        outerIndex = r.i32(),
        objectName = readName(r, names),
        packageName = readName(r, names),
      )
    }
  }

  private fun readExports(
    r: Reader,
    summary: S2UassetSummary,
    stride: Int,
    names: List<String>,
    imports: List<S2UassetImport>,
  ): List<S2UassetExport> {
    if (summary.exportCount == 0) return emptyList()
    if (stride < EXPORT_SERIAL_OFFSET_AT + 8)
      throw S2UassetException("implausible export entry stride $stride")
    return List(summary.exportCount) { i ->
      r.pos = summary.exportOffset + i * stride
      val classIndex = r.i32()
      r.i32() // SuperIndex
      r.i32() // TemplateIndex
      r.i32() // OuterIndex
      val objectName = readName(r, names)
      r.u32() // ObjectFlags
      val serialSize = r.i64()
      val serialOffset = r.i64()
      S2UassetExport(
        objectName = objectName,
        className = if (classIndex < 0) imports.getOrNull(-classIndex - 1)?.objectName else null,
        serialOffset = serialOffset,
        serialSize = serialSize,
      )
    }
  }

  private fun readTaggedProperties(
    r: Reader,
    names: List<String>,
    end: Int,
  ): Map<String, S2PropertyValue> {
    val out = LinkedHashMap<String, S2PropertyValue>()
    while (r.pos < end) {
      val name = readName(r, names)
      if (name == "None") break
      val type = readTypeName(r, names)
      val size = r.i32()
      val flags = r.u8()
      if (flags and TAG_HAS_ARRAY_INDEX != 0) r.i32()
      if (flags and TAG_HAS_PROPERTY_GUID != 0) r.skip(16)
      if (flags and TAG_HAS_PROPERTY_EXTENSIONS != 0) {
      // `EPropertyTagExtension` bitfield; `OverridableInformation` adds an operation byte and a
      // bool after it. Never seen in these packages, but mis-sizing it would desynchronise the
      // whole property list rather than a single value.
      if (r.u8() and TAG_EXT_OVERRIDABLE_INFORMATION != 0) r.skip(2)
    }

      // A bool carries its value in the flags and occupies no bytes at all.
      if (type.name == "BoolProperty" && size == 0) {
        out[name] = S2PropertyValue.Flag(flags and TAG_BOOL_TRUE != 0)
        continue
      }

      val valueEnd = r.pos + size
      var value =
        if (flags and TAG_HAS_BINARY_OR_NATIVE_SERIALIZE != 0) null
        else runCatching { readValue(r, names, type, valueEnd) }.getOrNull()
      if (value == null || r.pos != valueEnd) {
        // Natively serialised, undecodable, or decoded to the wrong length. Either way the tag
        // told us how long the value is, so keep its bytes and resynchronise rather than lose the
        // rest of the export.
        value = S2PropertyValue.Raw(r.hex(valueEnd - size, valueEnd))
        r.pos = valueEnd
      }
      out[name] = value
    }
    return out
  }

  private fun readValue(
    r: Reader,
    names: List<String>,
    type: S2PropertyTypeName,
    end: Int,
  ): S2PropertyValue = when (type.name) {
    "Int8Property" -> S2PropertyValue.Num(r.i8().toDouble())
    // Serialises as an FName when the property has an enum, as a raw byte otherwise. UE 5.4's
    // complete type names carry the enum as a parameter (`ByteProperty(EFoo)`), so ask the type
    // rather than guessing from the remaining length — which would be wrong for every element but
    // the last inside an array or map of byte enums.
    "ByteProperty" ->
      if (type.params.isNotEmpty()) S2PropertyValue.Text(readName(r, names))
      else S2PropertyValue.Num(r.u8().toDouble())

    "EnumProperty" -> S2PropertyValue.Text(readName(r, names))
    "Int16Property" -> S2PropertyValue.Num(r.i16().toDouble())
    "UInt16Property" -> S2PropertyValue.Num(r.u16().toDouble())
    "IntProperty" -> S2PropertyValue.Num(r.i32().toDouble())
    "UInt32Property" -> S2PropertyValue.Num(r.u32().toDouble())
    "Int64Property" -> S2PropertyValue.Num(r.i64().toDouble())
    "FloatProperty" -> S2PropertyValue.Num(r.f32().toDouble())
    "DoubleProperty" -> S2PropertyValue.Num(r.f64())
    "NameProperty" -> S2PropertyValue.Text(readName(r, names))
    "StrProperty" -> S2PropertyValue.Text(r.fstring())
    "ObjectProperty" -> S2PropertyValue.Num(r.i32().toDouble()) // FPackageIndex
    "ArrayProperty" -> {
      val count = r.i32()
      S2PropertyValue.Items(List(count) { readValue(r, names, type.params[0], end) })
    }

    "SetProperty" -> {
      val removed = r.i32()
      repeat(removed) { readValue(r, names, type.params[0], end) }
      val count = r.i32()
      S2PropertyValue.Items(List(count) { readValue(r, names, type.params[0], end) })
    }

    "MapProperty" -> {
      val (keyType, valueType) = type.params
      // The SDK's JSON dump calls these `KeysToRemove` and `Entries`; removals only appear in
      // packages saved over an existing map, but they shift every following byte when they do.
      val removed = r.i32()
      repeat(removed) { readValue(r, names, keyType, end) }
      val count = r.i32()
      val map = LinkedHashMap<String, S2PropertyValue>()
      repeat(count) {
        val key = readValue(r, names, keyType, end)
        map[keyText(key)] = readValue(r, names, valueType, end)
      }
      S2PropertyValue.Fields(map)
    }

    "StructProperty" -> S2PropertyValue.Fields(readTaggedProperties(r, names, end))
    else -> throw S2UassetException("unhandled property type $type at offset ${r.pos}")
  }

  private fun keyText(key: S2PropertyValue) = when (key) {
    is S2PropertyValue.Text -> key.value
    is S2PropertyValue.Num -> key.value.toString()
    is S2PropertyValue.Flag -> key.value.toString()
    else -> throw S2UassetException("map key is not a scalar")
  }

  // ---------------------------------------------------------------- writing

  private class Writer {
    private val out = java.io.ByteArrayOutputStream()
    val length get() = out.size()

    fun raw(b: ByteArray) = apply { out.write(b) }
    fun u8(v: Int) = apply { out.write(v and 0xff) }
    fun i32(v: Int) = raw(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())

    /** `FString`: ASCII goes out as UTF-8, anything else as UTF-16LE with a negated length. */
    fun fstring(s: String) = apply {
      if (s.isEmpty()) {
        i32(0)
        return@apply
      }
      if (s.all { it.code in 0..0x7f }) {
        val body = s.toByteArray(Charsets.UTF_8)
        i32(body.size + 1)
        raw(body)
        u8(0)
      } else {
        val body = s.toByteArray(Charsets.UTF_16LE)
        i32(-(body.size / 2 + 1))
        raw(body)
        u8(0)
        u8(0)
      }
    }

    fun toByteArray(): ByteArray = out.toByteArray()
  }

  /** Resolves an FName to its table index; these assets never need to grow the name table. */
  private fun nameIndex(names: List<String>, name: String): Int {
    val i = names.indexOf(name)
    if (i == -1) throw S2UassetException("\"$name\" is not in the package name table")
    return i
  }

  private fun writeName(w: Writer, names: List<String>, name: String) {
    w.i32(nameIndex(names, name)).i32(0)
  }

  /** `FPropertyTypeName`: an FName followed by its parameter list. */
  private fun writeTypeName(w: Writer, names: List<String>, type: S2PropertyTypeName) {
    writeName(w, names, type.name)
    w.i32(type.params.size)
    for (p in type.params) writeTypeName(w, names, p)
  }

  /** A property tag: name, type, value size, then `EPropertyTagFlags` (always 0 for these). */
  private fun writeTag(
    w: Writer,
    names: List<String>,
    name: String,
    type: S2PropertyTypeName,
    value: ByteArray,
  ) {
    writeName(w, names, name)
    writeTypeName(w, names, type)
    w.i32(value.size).u8(0).raw(value)
  }

  private fun typeName(name: String, vararg params: S2PropertyTypeName) =
    S2PropertyTypeName(name, params.toList())

  /**
   * Re-serialises a [LOCALIZATION_CLASS] export's bytes from [entries] — a `LocalizedTexts` array
   * of `{ SID, LanguagesToLocalizedStrings }` structs. Deliberately does not generalise to other
   * asset types: there is no general property writer here.
   */
  fun serializeLocalizedTexts(names: List<String>, entries: List<S2LocalizedText>): ByteArray {
    val elements = Writer()
    elements.i32(entries.size)
    for (entry in entries) {
      writeTag(
        elements, names, SID, typeName("StrProperty"),
        Writer().fstring(entry.sid).toByteArray(),
      )

      val map = Writer()
      map.i32(0) // KeysToRemove
      map.i32(entry.languages.size)
      for ((language, text) in entry.languages) {
        writeName(map, names, language)
        map.fstring(text)
      }
      writeTag(
        elements, names, LANGUAGES,
        typeName(
          "MapProperty",
          typeName(
            "EnumProperty",
            typeName("ELocalizationLanguage", typeName("/Script/Stalker2")),
            typeName("ByteProperty"),
          ),
          typeName("StrProperty"),
        ),
        map.toByteArray(),
      )
      writeName(elements, names, "None") // end of struct element
    }

    val out = Writer()
    out.u8(0) // __SerializationControlExtensions
    writeTag(
      out, names, LOCALIZED_TEXTS,
      typeName(
        "ArrayProperty",
        typeName("StructProperty", typeName("ModTextToolLocalizedText", typeName("/Script/ModKitEditor"))),
      ),
      elements.toByteArray(),
    )
    writeName(out, names, "None") // end of export
    // Observed trailer on every export of this type, after the property list terminator.
    out.i32(0)
    return out.toByteArray()
  }

  /**
   * Returns [original] with its [LOCALIZATION_CLASS] export replaced by [entries].
   *
   * That one export is re-serialised and spliced back in, patching the handful of offsets that
   * move as a result; everything else in the package is copied through untouched.
   */
  fun withLocalizedTexts(original: ByteArray, entries: List<S2LocalizedText>): ByteArray {
    val parsed = parse(original)
    val index = parsed.exports.indexOfFirst { it.className == LOCALIZATION_CLASS }
    if (index == -1) throw S2UassetException("no $LOCALIZATION_CLASS export in this package")
    val target = parsed.exports[index]
    val bulkDataStartOffsetAt = parsed.summary.bulkDataStartOffsetAt
      // Without that position a save would leave the summary pointing into the middle of the
      // export data. Refuse: a package that cannot be written beats a broken one.
      ?: throw S2UassetException(
        "this package's summary did not decode to a layout this plugin can write back"
      )

    val payload = serializeLocalizedTexts(parsed.names, entries)
    val delta = payload.size - target.serialSize
    val out = ByteBuffer
      .allocate(original.size + delta.toInt())
      .order(ByteOrder.LITTLE_ENDIAN)
      .put(original, 0, target.serialOffset.toInt())
      .put(payload)
      .put(
        original,
        (target.serialOffset + target.serialSize).toInt(),
        original.size - (target.serialOffset + target.serialSize).toInt(),
      )

    // Everything after this export shifts by `delta`: the later exports' payloads, plus the two
    // summary offsets that point past the export data.
    out.putLong(
      parsed.summary.exportOffset + index * parsed.exportStride + EXPORT_SERIAL_SIZE_AT,
      payload.size.toLong(),
    )
    for (i in index + 1 until parsed.exports.size) {
      out.putLong(
        parsed.summary.exportOffset + i * parsed.exportStride + EXPORT_SERIAL_OFFSET_AT,
        parsed.exports[i].serialOffset + delta,
      )
    }
    // `payloadTocOffsetAt` is null in packages predating `PayloadTocOffset`.
    for (at in listOfNotNull(bulkDataStartOffsetAt, parsed.summary.payloadTocOffsetAt)) {
      // Both fields use INDEX_NONE when absent; shifting a sentinel would turn it into an offset.
      val value = out.getLong(at)
      if (value > 0) out.putLong(at, value + delta)
    }
    return out.array()
  }
}
