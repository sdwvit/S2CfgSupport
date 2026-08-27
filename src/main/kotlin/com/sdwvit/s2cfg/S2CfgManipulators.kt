package com.sdwvit.s2cfg

import com.intellij.openapi.util.TextRange
import com.intellij.psi.AbstractElementManipulator

/** Lets rename rewrite `SID = Foo` values. */
class S2CfgValueManipulator : AbstractElementManipulator<S2CfgValue>() {
  override fun handleContentChange(element: S2CfgValue, range: TextRange, newContent: String): S2CfgValue {
    val entry = element.parent as? S2CfgEntry ?: return element
    val key = entry.keyName ?: return element
    val newText = range.replace(element.text, newContent)
    val replacement = S2CfgElementFactory.createEntry(element.project, key, newText.trim())
      .valueElement ?: return element
    return element.replace(replacement) as S2CfgValue
  }

  override fun getRangeInElement(element: S2CfgValue): TextRange {
    val text = element.text
    val start = text.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
    return TextRange(start, text.trimEnd().length.coerceAtLeast(start))
  }
}

/** Lets rename rewrite `{refkey=Foo}` values. */
class S2CfgRefManipulator : AbstractElementManipulator<S2CfgRef>() {
  override fun handleContentChange(element: S2CfgRef, range: TextRange, newContent: String): S2CfgRef {
    val keyword = element.keyword ?: return element
    val newText = range.replace(element.text, newContent)
    val value = newText.substringAfter('=', "").trim().ifEmpty { return element }
    val replacement = S2CfgElementFactory.createRef(element.project, keyword, value)
    return element.replace(replacement) as S2CfgRef
  }
}
