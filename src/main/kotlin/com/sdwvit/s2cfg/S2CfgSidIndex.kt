package com.sdwvit.s2cfg

import com.intellij.util.indexing.*
import com.intellij.util.io.EnumeratorStringDescriptor
import com.intellij.util.io.KeyDescriptor

/**
 * Maps a declared name (top-level struct name or its `SID`) to the files declaring it.
 *
 * Indexing runs on raw text rather than PSI: the grammar is regular enough to track nesting depth
 * by counting `struct.begin` / `struct.end`, which is both faster and independent of indentation
 * (the corpus mixes tabs and spaces).
 */
class S2CfgSidIndex : ScalarIndexExtension<String>() {

  override fun getName() = NAME
  override fun getVersion() = 2
  override fun dependsOnFileContent() = true
  override fun getKeyDescriptor(): KeyDescriptor<String> = EnumeratorStringDescriptor.INSTANCE
  override fun getInputFilter() = DefaultFileTypeSpecificInputFilter(S2CfgFileType)

  override fun getIndexer() = DataIndexer<String, Void?, FileContent> { content ->
    scanDeclarations(content.contentAsText).associateWith { null }
  }

  companion object {
    val NAME: ID<String, Void?> = ID.create("com.sdwvit.s2cfg.sid")

    /** Names declared at the top level of [text]. Shared with the tests. */
    fun scanDeclarations(text: CharSequence): Set<String> {
      val names = LinkedHashSet<String>()
      var depth = 0
      for (rawLine in text.lineSequence()) {
        val line = rawLine.trim().removePrefix("﻿").trim()
        if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) continue

        when {
          line.contains("struct.begin") -> {
            if (depth == 0) declaredStructName(line)?.let { names += it }
            depth++
          }
          line.startsWith("struct.end") -> depth--
          // `Key : removenode` has no body, so it never changes depth
          depth == 1 -> sidValue(line)?.let { names += it }
        }
      }
      return names
    }

    /** `Foo : struct.begin {bpatch}` -> `Foo`; array elements and anonymous heads -> null. */
    private fun declaredStructName(line: String): String? {
      val name = line.substringBefore(':', "").trim()
      return name.takeIf { it.isNotEmpty() && !it.startsWith("[") }
    }

    private fun sidValue(line: String): String? {
      val eq = line.indexOf('=')
      if (eq < 0) return null
      if (line.take(eq).trim() != "SID") return null
      return line.substring(eq + 1).trim().substringBefore('{').trim().takeIf { it.isNotEmpty() }
    }
  }
}
