package com.sdwvit.s2cfg

import com.intellij.codeInsight.completion.*
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.progress.ProgressManager
import com.intellij.util.Processor
import com.intellij.patterns.PlatformPatterns.psiElement
import com.intellij.psi.PsiElement
import com.intellij.util.ProcessingContext

class S2CfgCompletionContributor : CompletionContributor() {
  init {
    extend(CompletionType.BASIC, psiElement().withParent(S2CfgValue::class.java), ValueCompletion)
    extend(CompletionType.BASIC, psiElement().withParent(S2CfgRef::class.java), RefkeyCompletion)
  }
}

/** Enum literals for the key being assigned, plus record names for `*SID` keys. */
private object ValueCompletion : CompletionProvider<CompletionParameters>() {
  override fun addCompletions(
    parameters: CompletionParameters,
    context: ProcessingContext,
    result: CompletionResultSet,
  ) {
    val value = parameters.position.parent as? S2CfgValue ?: return
    val entry = value.parent as? S2CfgEntry ?: return
    val key = entry.keyName ?: return
    val sink = result.withPrefixMatcher(prefixBefore(parameters, value))

    S2CfgEnumIndex.literalsFor(value.project, key).forEach {
      sink.addElement(LookupElementBuilder.create(it).withIcon(S2CfgIcons.FILE).withTypeText("enum"))
    }
    if (S2CfgDeclarations.isReferenceKey(key)) addDeclarationNames(value, sink)
  }
}

/** `{refkey=<caret>}` — the base struct to inherit from. */
private object RefkeyCompletion : CompletionProvider<CompletionParameters>() {
  override fun addCompletions(
    parameters: CompletionParameters,
    context: ProcessingContext,
    result: CompletionResultSet,
  ) {
    val ref = parameters.position.parent as? S2CfgRef ?: return
    if (ref.keyword != "refkey") return
    addDeclarationNames(ref, result.withPrefixMatcher(prefixBefore(parameters, ref)))
  }
}

/**
 * GameData declares hundreds of thousands of records, so project-wide names are added only when
 * they match the typed prefix, and only up to [MAX_PROJECT_NAMES] of them. Adding all of them
 * unconditionally builds a lookup element per record on the completion thread and hangs the popup.
 */
private const val MAX_PROJECT_NAMES = 1000

private fun addDeclarationNames(context: PsiElement, result: CompletionResultSet) {
  // structs in this file first: refkey without refurl looks here, and cross-file names are legion
  val local = (context.containingFile as? S2CfgFile)?.structs
    ?.flatMap { S2CfgDeclarations.namesDeclaredBy(it) }
    ?.toSet()
    .orEmpty()
  local.forEach {
    result.addElement(LookupElementBuilder.create(it).withTypeText("this file").withBoldness(true))
  }

  val matcher = result.prefixMatcher
  var added = 0
  S2CfgLog.timed(what = { "completion for prefix '${matcher.prefix}'" }) {
    S2CfgDeclarations.processNames(context.project, Processor { name ->
      ProgressManager.checkCanceled()
      if (name !in local && matcher.prefixMatches(name)) {
        result.addElement(LookupElementBuilder.create(name).withTypeText("record"))
        added++
      }
      added < MAX_PROJECT_NAMES
    })
  }
  if (added >= MAX_PROJECT_NAMES) {
    // typing more narrows the prefix, so ask for a rerun instead of pretending the list is complete
    result.restartCompletionOnAnyPrefixChange()
    S2CfgLog.LOG.debug("completion truncated at $MAX_PROJECT_NAMES for '${matcher.prefix}'")
  }
}

/**
 * The platform's default prefix stops at `:` and `.`, which would break `EItemType::Armor`, so the
 * prefix is taken straight from the text before the caret.
 */
private fun prefixBefore(parameters: CompletionParameters, element: PsiElement): String {
  val offset = (parameters.offset - element.textRange.startOffset).coerceIn(0, element.textLength)
  return element.text.take(offset).substringAfterLast('=').trim()
}
