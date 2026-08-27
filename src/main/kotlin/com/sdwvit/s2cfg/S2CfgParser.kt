package com.sdwvit.s2cfg

import com.intellij.lang.ASTNode
import com.intellij.lang.PsiBuilder
import com.intellij.lang.PsiParser
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.tree.IElementType

/**
 * file  := (comment | struct)*
 * struct := head body 'struct.end' | key ':' 'removenode'
 * head   := [key ':'] 'struct.begin' ['{' refs '}']
 * body   := (comment | struct | entry)*
 * entry  := key '=' value ['{' refs '}']
 */
class S2CfgParser : PsiParser {
  override fun parse(root: IElementType, builder: PsiBuilder): ASTNode {
    val file = builder.mark()
    while (!builder.eof()) {
      ProgressManager.checkCanceled()
      if (!parseTopLevel(builder)) builder.advanceLexer()
    }
    file.done(root)
    return builder.treeBuilt
  }

  private fun parseTopLevel(b: PsiBuilder): Boolean = when {
    b.tokenType == S2CfgTypes.COMMENT -> { b.advanceLexer(); true }
    b.tokenType == S2CfgTypes.STRUCT_END -> { b.error("'struct.end' without a matching 'struct.begin'"); b.advanceLexer(); true }
    startsStruct(b) -> { parseStruct(b); true }
    startsEntry(b) -> { b.error("assignment outside of a struct"); parseEntry(b); true }
    else -> false
  }

  /** A struct starts either with `struct.begin` or with `<key> :`. */
  private fun startsStruct(b: PsiBuilder): Boolean {
    if (b.tokenType == S2CfgTypes.STRUCT_BEGIN) return true
    if (!isKeyStart(b.tokenType)) return false
    var i = 0
    while (i < MAX_KEY_LOOKAHEAD) {
      val t = b.lookAhead(i) ?: return false
      when (t) {
        S2CfgTypes.COLON -> return true
        S2CfgTypes.EQ -> return false
        S2CfgTypes.IDENT, S2CfgTypes.LBRACKET, S2CfgTypes.RBRACKET,
        S2CfgTypes.ASTERISK, S2CfgTypes.NUMBER -> i++
        else -> return false
      }
    }
    return false
  }

  private fun startsEntry(b: PsiBuilder): Boolean {
    if (!isKeyStart(b.tokenType)) return false
    var i = 0
    while (i < MAX_KEY_LOOKAHEAD) {
      when (b.lookAhead(i) ?: return false) {
        S2CfgTypes.EQ -> return true
        S2CfgTypes.IDENT, S2CfgTypes.LBRACKET, S2CfgTypes.RBRACKET,
        S2CfgTypes.ASTERISK, S2CfgTypes.NUMBER -> i++
        else -> return false
      }
    }
    return false
  }

  private fun isKeyStart(t: IElementType?) =
    t == S2CfgTypes.IDENT || t == S2CfgTypes.LBRACKET || t == S2CfgTypes.NUMBER

  private fun parseStruct(b: PsiBuilder) {
    val struct = b.mark()
    val head = b.mark()
    if (b.tokenType != S2CfgTypes.STRUCT_BEGIN) {
      parseKey(b)
      if (b.tokenType == S2CfgTypes.COLON) b.advanceLexer() else b.error("':' expected")
      // `Key : removenode` removes an inherited struct and has no body
      if (b.tokenType == S2CfgTypes.REMOVE_NODE) {
        b.advanceLexer()
        head.done(S2CfgTypes.STRUCT_HEAD)
        struct.done(S2CfgTypes.STRUCT)
        return
      }
    }
    if (b.tokenType == S2CfgTypes.STRUCT_BEGIN) b.advanceLexer() else b.error("'struct.begin' expected")
    if (b.tokenType == S2CfgTypes.LBRACE) parseRefs(b)
    head.done(S2CfgTypes.STRUCT_HEAD)

    while (!b.eof() && b.tokenType != S2CfgTypes.STRUCT_END) {
      ProgressManager.checkCanceled()
      when {
        b.tokenType == S2CfgTypes.COMMENT -> b.advanceLexer()
        startsStruct(b) -> parseStruct(b)
        startsEntry(b) -> parseEntry(b)
        else -> b.advanceLexer()
      }
    }
    if (b.tokenType == S2CfgTypes.STRUCT_END) b.advanceLexer() else b.error("'struct.end' expected")
    struct.done(S2CfgTypes.STRUCT)
  }

  private fun parseEntry(b: PsiBuilder) {
    val entry = b.mark()
    parseKey(b)
    if (b.tokenType == S2CfgTypes.EQ) b.advanceLexer() else b.error("'=' expected")
    val value = b.mark()
    if (b.tokenType == S2CfgTypes.BOOLEAN || b.tokenType == S2CfgTypes.NUMBER ||
      b.tokenType == S2CfgTypes.ENUM_VALUE || b.tokenType == S2CfgTypes.TEXT ||
      b.tokenType == S2CfgTypes.IDENT
    ) b.advanceLexer() // an empty value is legal: `Name =`
    value.done(S2CfgTypes.VALUE)
    if (b.tokenType == S2CfgTypes.LBRACE) parseRefs(b)
    entry.done(S2CfgTypes.ENTRY)
  }

  /** `Name`, `[0]` or `[*]`. */
  private fun parseKey(b: PsiBuilder) {
    val key = b.mark()
    if (b.tokenType == S2CfgTypes.LBRACKET) {
      b.advanceLexer()
      if (b.tokenType == S2CfgTypes.NUMBER || b.tokenType == S2CfgTypes.ASTERISK ||
        b.tokenType == S2CfgTypes.IDENT
      ) b.advanceLexer() else b.error("index or '*' expected")
      if (b.tokenType == S2CfgTypes.RBRACKET) b.advanceLexer() else b.error("']' expected")
    } else if (b.tokenType == S2CfgTypes.IDENT || b.tokenType == S2CfgTypes.NUMBER) {
      b.advanceLexer()
    } else {
      b.error("key expected")
    }
    key.done(S2CfgTypes.KEY)
  }

  /** `{refkey=Foo;bpatch}` */
  private fun parseRefs(b: PsiBuilder) {
    val refs = b.mark()
    b.advanceLexer() // '{'
    while (!b.eof() && b.tokenType != S2CfgTypes.RBRACE) {
      if (b.tokenType == S2CfgTypes.REF_KEYWORD || b.tokenType == S2CfgTypes.IDENT) {
        val ref = b.mark()
        if (b.tokenType == S2CfgTypes.IDENT) b.error("unknown modifier")
        b.advanceLexer()
        if (b.tokenType == S2CfgTypes.EQ) {
          b.advanceLexer()
          if (b.tokenType == S2CfgTypes.TEXT) b.advanceLexer() else b.error("value expected")
        }
        ref.done(S2CfgTypes.REF)
      } else {
        b.advanceLexer()
      }
    }
    if (b.tokenType == S2CfgTypes.RBRACE) b.advanceLexer() else b.error("'}' expected")
    refs.done(S2CfgTypes.REFS)
  }

  private companion object {
    /**
     * A key is one token (`Name`) or three (`[`, `0`, `]`). Anything longer is malformed input, and
     * scanning it unbounded turns a single junk line into quadratic work: every token in the line
     * re-scans the rest of it. Real `.cfg`-named files that are not STALKER cfgs hit exactly that.
     */
    const val MAX_KEY_LOOKAHEAD = 8
  }
}
