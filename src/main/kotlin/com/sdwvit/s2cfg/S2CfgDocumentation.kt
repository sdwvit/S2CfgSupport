package com.sdwvit.s2cfg

import com.intellij.lang.documentation.AbstractDocumentationProvider
import com.intellij.psi.PsiElement

/**
 * Ctrl+Q on a reference shows the target record inline, so following a quest graph does not need a
 * jump per node.
 */
class S2CfgDocumentationProvider : AbstractDocumentationProvider() {

  override fun getQuickNavigateInfo(element: PsiElement, originalElement: PsiElement?): String? {
    val struct = element as? S2CfgStruct ?: return null
    return "${struct.presentableName} in ${struct.containingFile?.name}"
  }

  override fun generateDoc(element: PsiElement, originalElement: PsiElement?): String? {
    val struct = element as? S2CfgStruct ?: return null
    val fields = struct.entries.mapNotNull { entry ->
      val key = entry.keyName ?: return@mapNotNull null
      "<tr><td valign='top'><code>$key</code></td><td><code>${entry.valueText ?: ""}</code></td></tr>"
    }
    val inherits = struct.refkey?.let { key ->
      val url = struct.refurl
      "<p>inherits <code>$key</code>${if (url != null) " from <code>$url</code>" else ""}</p>"
    } ?: ""
    val nested = struct.childStructs.takeIf { it.isNotEmpty() }
      ?.joinToString(", ") { "<code>${it.presentableName}</code>" }
      ?.let { "<p>nested: $it</p>" } ?: ""

    return buildString {
      append("<div class='definition'><pre><b>${struct.presentableName}</b>")
      append(" &mdash; ${struct.containingFile?.name}</pre></div>")
      append("<div class='content'>")
      append(inherits)
      if (fields.isNotEmpty()) {
        append("<table>")
        fields.take(MAX_FIELDS).forEach(::append)
        append("</table>")
        if (fields.size > MAX_FIELDS) append("<p>... ${fields.size - MAX_FIELDS} more</p>")
      }
      append(nested)
      append("</div>")
    }
  }

  private companion object {
    const val MAX_FIELDS = 20
  }
}
