package com.sdwvit.s2cfg

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal reader for UE5 `.uasset` packages as written by the STALKER 2 Mod SDK
 * (`LegacyFileVersion -8`, `FileVersionUE4 522`, `FileVersionUE5 1013` — UE 5.4 era). Ported from
 * the S2Mods `src/localization/uasset.mts` reference reader.
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

/** The byte position of a stored offset field, and how wide it is. */
class S2OffsetField(val at: Int, val bytes: Int)

class S2UassetSummary(
  val legacyFileVersion: Int,
  val fileVersionUE5: Int,
  val totalHeaderSize: Int,
  val packageName: String,
  /** Where the `PackageName` FString starts, so a rename can splice a different-length one in. */
  val packageNameAt: Int,
  /** The namespace the package's gathered text lives under: an md5 of the package name. */
  val localizationId: String,
  val nameCount: Int,
  val nameOffset: Int,
  /**
   * Where `NameOffset` sits. The table start does not move when the table is *resized*, but a
   * rename splices the package name in front of it, so a rename does have to shift it.
   */
  val nameOffsetAt: Int,
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
  /** Where `NameCount` sits, so a writer that grows the name table can correct it. */
  val nameCountAt: Int,
  /**
   * Where each generation's `NameCount` sits. The last one mirrors the summary's and the editor
   * trusts it, so growing the table has to grow these too.
   */
  val generationNameCountsAt: List<Int>,
  /** Where `NamesReferencedFromExportDataCount` sits — the length of the sorted table prefix. */
  val namesReferencedFromExportDataCountAt: Int?,
  /** Where `AssetRegistryDataOffset` sits; the section it points at stores offsets of its own. */
  val assetRegistryDataOffsetAt: Int?,
  /**
   * Every offset field in the summary that points *behind* the name table, so resizing the table
   * can shift them all without naming them one at a time. `NameOffset` itself is deliberately not
   * here: the table start does not move.
   */
  val postNameOffsetFieldsAt: List<S2OffsetField>,
)

class S2Uasset(
  val summary: S2UassetSummary,
  /** Derived from the table bounds, not hardcoded — see `readExports`. */
  val exportStride: Int,
  /** Where the name table's bytes end, so a writer can measure how much a rebuilt one grows. */
  val nameTableEnd: Int,
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
    val nameTableEnd = r.pos
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
      return S2Uasset(summary, stride, nameTableEnd, names, imports, exports)
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
    return S2Uasset(summary, stride, nameTableEnd, names, imports, exports)
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

    val postNameOffsetFieldsAt = ArrayList<S2OffsetField>()
    /** Reads an offset field, remembering where it was so the writer can shift it. */
    fun offset32(): Int {
      postNameOffsetFieldsAt.add(S2OffsetField(r.pos, 4))
      return r.i32()
    }

    // TotalHeaderSize is the end of the header, so it moves with everything the name table pushes.
    val totalHeaderSize = offset32()
    val packageNameAt = r.pos
    val packageName = r.fstring()
    val packageFlags = r.u32()
    val nameCountAt = r.pos
    val nameCount = r.i32()
    val nameOffsetAt = r.pos
    val nameOffset = r.i32() // resizing the table does not move its start; not an offset32()
    if (fileVersionUE5 >= UE5_ADD_SOFTOBJECTPATH_LIST) {
      r.i32() // SoftObjectPathsCount
      offset32() // SoftObjectPathsOffset
    }
    val localizationId = r.fstring()
    r.i32() // GatherableTextDataCount
    offset32() // GatherableTextDataOffset
    val exportCount = r.i32()
    val exportOffset = offset32()
    val importCount = r.i32()
    val importOffset = offset32()
    val dependsOffset = offset32()

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
      totalHeaderSize = totalHeaderSize,
      packageName = packageName,
      packageNameAt = packageNameAt,
      localizationId = localizationId,
      nameCount = nameCount,
      nameOffset = nameOffset,
      nameOffsetAt = nameOffsetAt,
      exportCount = exportCount,
      exportOffset = exportOffset,
      importCount = importCount,
      importOffset = importOffset,
      dependsOffset = dependsOffset,
      bulkDataStartOffsetAt = tail?.bulkDataStartOffsetAt,
      payloadTocOffsetAt = tail?.payloadTocOffsetAt,
      nameCountAt = nameCountAt,
      generationNameCountsAt = tail?.generationNameCountsAt ?: emptyList(),
      namesReferencedFromExportDataCountAt = tail?.namesReferencedFromExportDataCountAt,
      assetRegistryDataOffsetAt = tail?.assetRegistryDataOffsetAt,
      postNameOffsetFieldsAt = postNameOffsetFieldsAt + (tail?.offsets ?: emptyList()),
    )
  }

  private class SummaryTail(
    val bulkDataStartOffsetAt: Int,
    val payloadTocOffsetAt: Int?,
    val generationNameCountsAt: List<Int>,
    val namesReferencedFromExportDataCountAt: Int?,
    val assetRegistryDataOffsetAt: Int,
    val offsets: List<S2OffsetField>,
    val end: Int,
  )

  /**
   * The tail of `FPackageFileSummary` past `DependsOffset`. Nothing in it is interesting to read,
   * but two of its fields (`BulkDataStartOffset`, `PayloadTocOffset`) are the only offsets in the
   * package that point *past* the export data, so the writer has to patch them — hence: walk the
   * tail, record where they landed.
   */
  private fun readSummaryTail(r: Reader, packageFlags: Long, ue5: Int): SummaryTail {
    val offsets = ArrayList<S2OffsetField>()
    /** Reads an offset field, remembering where it was so the writer can shift it. */
    fun offset32(): Int {
      offsets.add(S2OffsetField(r.pos, 4))
      return r.i32()
    }
    r.i32() // SoftPackageReferencesCount
    offset32() // SoftPackageReferencesOffset
    offset32() // SearchableNamesOffset
    offset32() // ThumbnailTableOffset
    r.skip(16) // Guid
    if (packageFlags and PKG_FILTER_EDITOR_ONLY == 0L) r.skip(16) // PersistentGuid
    // Generations: (ExportCount, NameCount) each.
    val generationNameCountsAt = ArrayList<Int>()
    repeat(count(r)) {
      r.i32() // ExportCount
      generationNameCountsAt.add(r.pos)
      r.i32() // NameCount
    }
    readEngineVersion(r) // SavedByEngineVersion
    readEngineVersion(r) // CompatibleWithEngineVersion
    r.u32() // CompressionFlags
    r.skip(count(r) * 16) // CompressedChunks — always empty in modern packages
    r.u32() // PackageSource
    r.skip(count(r) * 4) // AdditionalPackagesToCook — FString array, always empty
    val assetRegistryDataOffsetAt = r.pos
    offset32() // AssetRegistryDataOffset
    val bulkDataStartOffsetAt = r.pos
    r.i64() // BulkDataStartOffset
    offset32() // WorldTileInfoDataOffset
    r.skip(count(r) * 4) // ChunkIDs
    r.i32() // PreloadDependencyCount
    offset32() // PreloadDependencyOffset
    var namesReferencedFromExportDataCountAt: Int? = null
    if (ue5 >= UE5_NAMES_REFERENCED_FROM_EXPORT_DATA) {
      namesReferencedFromExportDataCountAt = r.pos
      r.i32() // NamesReferencedFromExportDataCount
    }
    val payloadTocOffsetAt = if (ue5 >= UE5_PAYLOAD_TOC) r.pos else null
    if (payloadTocOffsetAt != null) r.i64() // PayloadTocOffset
    if (ue5 >= UE5_DATA_RESOURCES) offset32() // DataResourceOffset
    return SummaryTail(
      bulkDataStartOffsetAt = bulkDataStartOffsetAt,
      payloadTocOffsetAt = payloadTocOffsetAt,
      generationNameCountsAt = generationNameCountsAt,
      namesReferencedFromExportDataCountAt = namesReferencedFromExportDataCountAt,
      assetRegistryDataOffsetAt = assetRegistryDataOffsetAt,
      offsets = offsets,
      end = r.pos,
    )
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

  /**
   * Resolves an FName to its table index, appending it to [names] if it is not there yet — a
   * package saved without some language has none of that language's names, so every one the
   * payload needs has to be added on the first write.
   */
  private fun nameIndex(names: MutableList<String>, name: String): Int {
    val i = names.indexOf(name)
    if (i != -1) return i
    names.add(name)
    return names.size - 1
  }

  /** CRC table for `FCrc::Strihash_DEPRECATED`: polynomial 0x04C11DB7, MSB-first. */
  private val STRIHASH_TABLE = IntArray(256) { i ->
    var c = i shl 24
    repeat(8) { c = if (c and 0x8000_0000.toInt() != 0) (c shl 1) xor 0x04C1_1DB7 else c shl 1 }
    c
  }

  /** Standard reflected CRC-32 table, as `FCrc::CRCTablesSB8[0]` used by `FCrc::StrCrc32`. */
  private val CRC32_TABLE = IntArray(256) { i ->
    var c = i
    repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0xEDB8_8320.toInt() else c ushr 1 }
    c
  }

  /**
   * `FCrc::Strihash_DEPRECATED` — the case-insensitive hash `FNameEntrySerialized` stores first.
   * One byte per character; every FName in these packages is ASCII, which the writer enforces.
   */
  private fun strihash(name: String): Int {
    var hash = 0
    for (ch in name.uppercase()) {
      hash = ((hash ushr 8) and 0x00FF_FFFF) xor STRIHASH_TABLE[(hash xor ch.code) and 0xff]
    }
    return hash and 0xffff
  }

  /** `FCrc::StrCrc32` — the case-preserving hash, four bytes per character. */
  private fun strCrc32(name: String): Int {
    var crc = -1
    for (ch in name) {
      var v = ch.code
      repeat(4) {
        crc = (crc ushr 8) xor CRC32_TABLE[(crc xor v) and 0xff]
        v = v ushr 8
      }
    }
    return crc.inv() and 0xffff
  }

  /**
   * How the editor orders the export-data half of the name table: case-insensitively, with case as
   * the tiebreak. Only the order has to match — nothing reads the names positionally — but matching
   * it keeps a rewritten asset byte-identical to one the editor saved.
   */
  private val NAME_ORDER = Comparator<String> { a, b ->
    val byLower = a.lowercase().compareTo(b.lowercase())
    if (byLower != 0) byLower else a.compareTo(b)
  }

  /** An `FNameEntrySerialized`: the string, then its non-case-preserving and case-preserving hash. */
  private fun serializeNameEntries(names: List<String>): ByteArray {
    val w = Writer()
    for (name in names) {
      if (!name.all { it.code in 0..0x7f })
        throw S2UassetException("cannot hash non-ASCII FName \"$name\"")
      w.fstring(name)
      w.raw(
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
          .putShort(strihash(name).toShort())
          .putShort(strCrc32(name).toShort())
          .array()
      )
    }
    return w.toByteArray()
  }

  /** FName index positions inside an import record, relative to the record's start. */
  private val IMPORT_NAME_FIELDS_AT = intArrayOf(0, 8, 20, 28)

  /** …and inside an export record: `ObjectName`. */
  private const val EXPORT_OBJECT_NAME_AT = 16

  /** `PackageMetaData`'s payload is not tagged properties; its two FName fields sit at these bytes. */
  private val METADATA_NAME_FIELDS_AT = intArrayOf(1, 21)

  /**
   * `ScriptSerializationEndOffset`: where the export's tagged-property block ends, relative to the
   * export's own data. It is `SerialSize - 4` in every asset the Mod Editor has written here, so it
   * moves with the payload; left stale, the editor stops reading properties mid-entry.
   */
  private const val EXPORT_SCRIPT_SERIALIZATION_END_AT = 104

  /**
   * The names referenced by every export's data *except* the localization payload — i.e. the part
   * of `NamesReferencedFromExportData` a rewrite has to preserve without knowing what the old
   * payload used. Only `PackageMetaData` has any in this package shape.
   */
  private fun metadataNames(original: ByteArray, parsed: S2Uasset): List<String> {
    val bb = ByteBuffer.wrap(original).order(ByteOrder.LITTLE_ENDIAN)
    return parsed.exports
      .filter { it.className == "MetaData" }
      .flatMap { exp ->
        METADATA_NAME_FIELDS_AT.map { at ->
          parsed.names.getOrNull(bb.getInt(exp.serialOffset.toInt() + at))
        }
      }
      .filterNotNull()
  }

  private fun writeName(w: Writer, names: MutableList<String>, name: String) {
    w.i32(nameIndex(names, name)).i32(0)
  }

  /** `FPropertyTypeName`: an FName followed by its parameter list. */
  private fun writeTypeName(w: Writer, names: MutableList<String>, type: S2PropertyTypeName) {
    writeName(w, names, type.name)
    w.i32(type.params.size)
    for (p in type.params) writeTypeName(w, names, p)
  }

  /** A property tag: name, type, value size, then `EPropertyTagFlags` (always 0 for these). */
  private fun writeTag(
    w: Writer,
    names: MutableList<String>,
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
  fun serializeLocalizedTexts(names: MutableList<String>, entries: List<S2LocalizedText>): ByteArray {
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

  /** An `FString` holding an ASCII package name: length prefix, the bytes, a terminator. */
  private fun fstringBytes(value: String): ByteArray {
    if (value.any { it.code !in 0x20..0x7e })
      throw S2UassetException("$value: package names must be ASCII")
    return Writer().fstring(value).toByteArray()
  }

  /** Replaces `[at, at + length)` with [bytes]. */
  private class S2ByteEdit(val at: Int, val length: Int, val bytes: ByteArray)

  /**
   * Splices non-overlapping [edits] into [original] and hands back the result plus a `shift` that
   * maps any byte position in the original to where it ended up. Every offset stored in the
   * package — in the summary, in the export table, inside the asset registry section — is patched
   * through it, so a rename does not have to know which of its edits sits in front of which offset.
   */
  private class S2Spliced(val out: ByteArray, private val sorted: List<S2ByteEdit>) {
    fun shift(pos: Int): Int =
      pos + sorted.filter { it.at < pos }.sumOf { it.bytes.size - it.length }
  }

  private fun applyEdits(original: ByteArray, edits: List<S2ByteEdit>): S2Spliced {
    val sorted = edits.sortedBy { it.at }
    val out = java.io.ByteArrayOutputStream()
    var at = 0
    for (edit in sorted) {
      if (edit.at < at) throw S2UassetException("overlapping edit at ${edit.at}")
      out.write(original, at, edit.at - at)
      out.write(edit.bytes)
      at = edit.at + edit.length
    }
    out.write(original, at, original.size - at)
    return S2Spliced(out.toByteArray(), sorted)
  }

  /** Every position in [haystack] where the ASCII [needle] occurs, searching from [from]. */
  private fun indicesOf(haystack: ByteArray, needle: String, from: Int): List<Int> {
    val bytes = needle.toByteArray(Charsets.ISO_8859_1)
    val found = ArrayList<Int>()
    var at = from
    while (at <= haystack.size - bytes.size) {
      if ((bytes.indices).all { haystack[at + it] == bytes[it] }) found.add(at)
      at++
    }
    return found
  }

  /**
   * Returns [original] rewritten under a new [packageName] — i.e. mints a new text asset from an
   * existing one, which is what repairs a package copied from another mod and renamed on disk.
   *
   * [packageName] is the mount path the cooker addresses the asset by: `/<SdkModName>/<AssetName>`
   * for a package sitting at the root of the SDK mod's `Content/`.
   *
   * The identity is in four kinds of places, and the last is why this cannot be a string replace:
   * the summary's `PackageName` and the asset registry's object-path and asset-name strings are
   * length-prefixed `FString`s, the name table holds both the full path and the short name as
   * hashed `FName`s (and its export-data half is sorted, so a rename can reorder it), and
   * `LocalizationId` namespaces the package's gathered text — two mods must not share one, so it
   * is re-derived from the new name rather than inherited.
   *
   * Ported from S2Mods' `renameLocalizationPackage`.
   */
  fun renameLocalizationPackage(original: ByteArray, packageName: String): ByteArray {
    val parsed = parse(original)
    val summary = parsed.summary
    if (summary.bulkDataStartOffsetAt == null)
      throw S2UassetException("this package's summary did not decode to a layout we can write back")
    if (summary.packageName == packageName) return original
    val registryAt = summary.assetRegistryDataOffsetAt
      ?: throw S2UassetException("this package has no asset registry section to rename")

    val src = ByteBuffer.wrap(original).order(ByteOrder.LITTLE_ENDIAN)
    fun shortOf(name: String) = name.substring(name.lastIndexOf('/') + 1)
    val spellings = listOf(
      summary.packageName to packageName,
      shortOf(summary.packageName) to shortOf(packageName),
    )
    fun rename(name: String) = spellings.firstOrNull { it.first == name }?.second ?: name

    // The name table: substitute, then re-sort the export-data half the way the editor keeps it.
    val nrefAt = summary.namesReferencedFromExportDataCountAt
    val oldRef = if (nrefAt == null) parsed.names.size else src.getInt(nrefAt)
    val names = parsed.names.take(oldRef).map(::rename).sortedWith(NAME_ORDER) +
      parsed.names.drop(oldRef).map(::rename)
    if (names.toSet().size != names.size)
      throw S2UassetException("renaming to $packageName collides with a name already in the table")
    val remap = parsed.names.withIndex().associate { (i, n) -> i to names.indexOf(rename(n)) }

    val edits = ArrayList<S2ByteEdit>()
    edits.add(
      S2ByteEdit(
        summary.packageNameAt,
        fstringBytes(summary.packageName).size,
        fstringBytes(packageName),
      )
    )
    edits.add(
      S2ByteEdit(
        summary.nameOffset,
        parsed.nameTableEnd - summary.nameOffset,
        serializeNameEntries(names),
      )
    )

    // The localization id, in the summary and again in PackageMetaData's
    // PackageLocalizationNamespace. Same length, so this shifts nothing.
    val localizationId = java.security.MessageDigest.getInstance("MD5")
      .digest(packageName.toByteArray(Charsets.UTF_8))
      .joinToString("") { "%02X".format(it) }
    if (localizationId.length != summary.localizationId.length)
      throw S2UassetException("unexpected localization id ${summary.localizationId}")
    for (at in indicesOf(original, summary.localizationId, 0)) {
      edits.add(
        S2ByteEdit(at, localizationId.length, localizationId.toByteArray(Charsets.ISO_8859_1))
      )
    }

    // The asset registry section spells the asset out again, as plain FStrings. They sit between
    // the depends table and the end of the header; anything matching a spelling *and* carrying the
    // right length prefix is one of them.
    for ((from, to) in spellings) {
      for (at in indicesOf(original, "$from\u0000", summary.dependsOffset)) {
        if (at >= summary.totalHeaderSize) continue
        if (src.getInt(at - 4) != from.length + 1) continue
        edits.add(S2ByteEdit(at - 4, from.length + 5, fstringBytes(to)))
      }
    }

    val spliced = applyEdits(original, edits.sortedBy { it.at })
    val out = ByteBuffer.wrap(spliced.out).order(ByteOrder.LITTLE_ENDIAN)

    /** Patches a stored file offset: both the field's own position and its value have moved. */
    fun shiftStoredOffset(at: Int, bytes: Int) {
      val to = spliced.shift(at)
      val value = if (bytes == 4) out.getInt(to).toLong() else out.getLong(to)
      // Absent tables store 0 or INDEX_NONE; shifting one would invent an offset.
      if (value <= 0) return
      if (bytes == 4) out.putInt(to, spliced.shift(value.toInt()))
      else out.putLong(to, spliced.shift(value.toInt()).toLong())
    }

    shiftStoredOffset(summary.nameOffsetAt, 4)
    for (field in summary.postNameOffsetFieldsAt) shiftStoredOffset(field.at, field.bytes)
    for (at in listOfNotNull(summary.bulkDataStartOffsetAt, summary.payloadTocOffsetAt))
      shiftStoredOffset(at, 8)
    for (i in parsed.exports.indices)
      shiftStoredOffset(summary.exportOffset + i * parsed.exportStride + EXPORT_SERIAL_OFFSET_AT, 8)
    // The asset registry section opens with a file offset to its dependency data, and the four
    // bytes in front of the section are one too.
    val sectionAt = src.getInt(registryAt)
    if (sectionAt > 0) {
      shiftStoredOffset(sectionAt - 4, 4)
      shiftStoredOffset(sectionAt, 8)
    }

    // Every FName index outside the name table points into the old ordering.
    fun remapAt(at: Int) {
      val to = spliced.shift(at)
      val index = remap[out.getInt(to)]
      if (index == null || index < 0) throw S2UassetException("name index at $at is out of range")
      out.putInt(to, index)
    }
    val importStride =
      if (summary.importCount > 0) (summary.exportOffset - summary.importOffset) / summary.importCount
      else 0
    for (i in 0 until summary.importCount)
      for (at in IMPORT_NAME_FIELDS_AT) remapAt(summary.importOffset + i * importStride + at)
    for (i in parsed.exports.indices)
      remapAt(summary.exportOffset + i * parsed.exportStride + EXPORT_OBJECT_NAME_AT)
    for (exp in parsed.exports) {
      if (exp.className != "MetaData") continue
      for (field in METADATA_NAME_FIELDS_AT) remapAt(exp.serialOffset.toInt() + field)
    }
    return out.array()
  }

  /**
   * Returns [original] with its [LOCALIZATION_CLASS] export replaced by [entries].
   *
   * The export is re-serialised, the name table rebuilt around whatever FNames the new payload
   * needs, and every stored offset behind the table shifted to match. Rebuilding the table rather
   * than inheriting it is what lets a language the package was never saved with be added — and,
   * in the other direction, makes a rewrite with fewer entries *shrink* the header instead of
   * leaving the old payload's names behind as orphans.
   *
   * Ported from S2Mods' `writeLocalizedTexts`; the byte-exact fixture tests are the contract.
   */
  fun withLocalizedTexts(original: ByteArray, entries: List<S2LocalizedText>): ByteArray {
    val parsed = parse(original)
    val summary = parsed.summary
    val index = parsed.exports.indexOfFirst { it.className == LOCALIZATION_CLASS }
    if (index == -1) throw S2UassetException("no $LOCALIZATION_CLASS export in this package")
    val target = parsed.exports[index]
    val bulkDataStartOffsetAt = summary.bulkDataStartOffsetAt
      // Without that position a save would leave the summary pointing into the middle of the
      // export data. Refuse: a package that cannot be written beats a broken one.
      ?: throw S2UassetException(
        "this package's summary did not decode to a layout this plugin can write back"
      )
    if (parsed.exportStride < EXPORT_SCRIPT_SERIALIZATION_END_AT + 4)
      throw S2UassetException("export entry stride ${parsed.exportStride} is too small to write")

    val src = ByteBuffer.wrap(original).order(ByteOrder.LITTLE_ENDIAN)

    // The editor lays the name table out in two parts: the names the export data references,
    // sorted, then the rest (package name, import and export object names) in load order.
    // `NamesReferencedFromExportDataCount` is the length of that first part, so a writer cannot
    // just append new names at the end — the count would have to cover the header names too, and
    // the editor errors on the mismatch. Rebuild both parts instead and remap every index that
    // pointed into the old table.
    val nrefAt = summary.namesReferencedFromExportDataCountAt
    val oldRef = if (nrefAt == null) parsed.names.size else src.getInt(nrefAt)
    // What the payload alone references: serialising against an empty table collects exactly that.
    val payloadNames = ArrayList<String>()
    serializeLocalizedTexts(payloadNames, entries)
    // The other exports' export data references names too (`MetaData` uses two) and those have to
    // stay. Take exactly those rather than the whole old prefix: keeping the prefix would carry
    // every name the *previous* entries used into the new table, so rewriting an asset with fewer
    // texts would leave orphans behind and the bytes would depend on what the file held before.
    val head = (metadataNames(original, parsed) + payloadNames).distinct().sortedWith(NAME_ORDER)
    val headSet = head.toHashSet()
    // A name can move from the tail into the prefix — `/Script/ModKitEditor` does, once export
    // data names it — and must not then appear twice.
    val names = ArrayList(head + parsed.names.drop(oldRef).filter { it !in headSet })
    val sizeBefore = names.size
    val payload = serializeLocalizedTexts(names, entries)
    if (names.size != sizeBefore) throw S2UassetException("name table grew while writing")

    val newTable = serializeNameEntries(names)
    val nameDelta = newTable.size - (parsed.nameTableEnd - summary.nameOffset)
    val remap = parsed.names.withIndex().associate { (i, name) -> i to names.indexOf(name) }
    val delta = payload.size - target.serialSize.toInt()

    val targetEnd = (target.serialOffset + target.serialSize).toInt()
    val out = ByteBuffer
      .allocate(original.size + nameDelta + delta)
      .order(ByteOrder.LITTLE_ENDIAN)
      .put(original, 0, summary.nameOffset)
      .put(newTable)
      .put(original, parsed.nameTableEnd, target.serialOffset.toInt() - parsed.nameTableEnd)
      .put(payload)
      .put(original, targetEnd, original.size - targetEnd)

    out.putInt(summary.nameCountAt, names.size)
    for (at in summary.generationNameCountsAt) out.putInt(at, names.size)
    if (nrefAt != null) out.putInt(nrefAt, head.size)

    // Everything behind the name table shifts by `nameDelta`, and everything behind the rewritten
    // export by `delta` as well: the later exports' payloads, plus the two summary offsets that
    // point past the export data. Every other offset lives in the summary, which we walked to find.
    for (field in summary.postNameOffsetFieldsAt) {
      val value = if (field.bytes == 4) out.getInt(field.at).toLong() else out.getLong(field.at)
      // Absent tables use 0 or INDEX_NONE; shifting a sentinel would turn it into an offset.
      if (value <= 0) continue
      if (field.bytes == 4) out.putInt(field.at, (value + nameDelta).toInt())
      else out.putLong(field.at, value + nameDelta)
    }

    fun exportField(i: Int, at: Int) =
      summary.exportOffset + nameDelta + i * parsed.exportStride + at

    out.putLong(exportField(index, EXPORT_SERIAL_SIZE_AT), payload.size.toLong())
    val scriptEndAt = exportField(index, EXPORT_SCRIPT_SERIALIZATION_END_AT)
    out.putInt(scriptEndAt, out.getInt(scriptEndAt) + delta)
    for (i in parsed.exports.indices) {
      val shift = nameDelta + (if (i > index) delta else 0)
      out.putLong(exportField(i, EXPORT_SERIAL_OFFSET_AT), parsed.exports[i].serialOffset + shift)
    }

    // Every FName index outside the payload we just rebuilt points into the old table, so move
    // them all onto the new one. These are the only places this package shape stores one.
    fun remapAt(at: Int) {
      val to = remap[out.getInt(at)]
      if (to == null || to < 0) throw S2UassetException("name index at $at is out of range")
      out.putInt(at, to)
    }
    val importStride =
      if (summary.importCount > 0) (summary.exportOffset - summary.importOffset) / summary.importCount
      else 0
    for (i in 0 until summary.importCount)
      for (at in IMPORT_NAME_FIELDS_AT)
        remapAt(summary.importOffset + nameDelta + i * importStride + at)
    for (i in parsed.exports.indices) remapAt(exportField(i, EXPORT_OBJECT_NAME_AT))
    for (i in parsed.exports.indices) {
      if (i == index || parsed.exports[i].className != "MetaData") continue
      val at = parsed.exports[i].serialOffset.toInt() + nameDelta + (if (i > index) delta else 0)
      for (field in METADATA_NAME_FIELDS_AT) remapAt(at + field)
    }

    // The asset registry section is not just a blob: its first field is an `int64` file offset to
    // the package's dependency data, which sits at the end of the same section. It moves with the
    // name table like every other header offset, and leaving it stale makes the UE editor seek
    // into the name table and read garbage counts ("SerializeAssetRegistryDependencyData").
    val registryAt = summary.assetRegistryDataOffsetAt
    if (registryAt != null) {
      val sectionAt = out.getInt(registryAt)
      if (sectionAt > 0) {
        // The four bytes in front of the section are a file offset too (`DependsOffset + 12` in
        // every asset the editor has written here), and nothing in the summary points at them.
        val before = out.getInt(sectionAt - 4)
        if (before > 0 && before < summary.totalHeaderSize)
          out.putInt(sectionAt - 4, before + nameDelta)
        val dependencyDataOffset = out.getLong(sectionAt)
        if (dependencyDataOffset > 0) out.putLong(sectionAt, dependencyDataOffset + nameDelta)
      }
    }

    // `payloadTocOffsetAt` is null in packages predating `PayloadTocOffset`.
    for (at in listOfNotNull(bulkDataStartOffsetAt, summary.payloadTocOffsetAt)) {
      // Both fields use INDEX_NONE when absent; shifting a sentinel would turn it into an offset.
      val value = out.getLong(at)
      if (value > 0) out.putLong(at, value + nameDelta + delta)
    }
    return out.array()
  }
}
