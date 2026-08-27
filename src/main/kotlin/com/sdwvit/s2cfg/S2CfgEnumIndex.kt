package com.sdwvit.s2cfg

import com.intellij.util.indexing.*
import com.intellij.util.io.EnumeratorStringDescriptor
import com.intellij.util.io.KeyDescriptor

/**
 * Maps an entry key (`ItemType`) to the enum literals the corpus actually assigns to it
 * (`EItemType::Armor`, ...), so completion can offer the right enum instead of every enum.
 *
 * The per-file value is one newline-joined string: the platform's string externalizer is enough,
 * and a collection externalizer would buy nothing at this size.
 */
class S2CfgEnumIndex : FileBasedIndexExtension<String, String>() {

  override fun getName() = NAME
  override fun getVersion() = 1
  override fun dependsOnFileContent() = true
  override fun getKeyDescriptor(): KeyDescriptor<String> = EnumeratorStringDescriptor.INSTANCE
  override fun getValueExternalizer() = EnumeratorStringDescriptor.INSTANCE
  override fun getInputFilter() = DefaultFileTypeSpecificInputFilter(S2CfgFileType)

  override fun getIndexer() = DataIndexer<String, String, FileContent> { content ->
    val byKey = LinkedHashMap<String, LinkedHashSet<String>>()
    for (rawLine in content.contentAsText.lineSequence()) {
      val line = rawLine.trim()
      if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) continue
      val eq = line.indexOf('=')
      if (eq <= 0) continue
      val key = line.take(eq).trim()
      if (key.isEmpty() || key.startsWith("[")) continue
      val value = line.substring(eq + 1).trim().substringBefore('{').trim()
      if (ENUM_LITERAL.matches(value)) byKey.getOrPut(key) { LinkedHashSet() } += value
    }
    byKey.mapValues { (_, literals) -> literals.joinToString("\n") }
  }

  companion object {
    val NAME: ID<String, String> = ID.create("com.sdwvit.s2cfg.enums")
    private val ENUM_LITERAL = Regex("^E[A-Za-z0-9_]*::[A-Za-z0-9_]+$")

    fun literalsFor(project: com.intellij.openapi.project.Project, key: String): Set<String> =
      FileBasedIndex.getInstance()
        .getValues(NAME, key, com.intellij.psi.search.GlobalSearchScope.allScope(project))
        .flatMap { it.split("\n") }
        .filter { it.isNotEmpty() }
        .toSet()
  }
}
