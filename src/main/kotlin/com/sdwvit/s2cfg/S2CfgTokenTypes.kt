package com.sdwvit.s2cfg

import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet

class S2CfgTokenType(debugName: String) : IElementType(debugName, S2CfgLanguage) {
  override fun toString() = "S2Cfg:" + super.toString()
}

class S2CfgElementType(debugName: String) : IElementType(debugName, S2CfgLanguage)

object S2CfgTypes {
  // tokens
  val COMMENT = S2CfgTokenType("COMMENT")
  val STRUCT_BEGIN = S2CfgTokenType("struct.begin")
  val STRUCT_END = S2CfgTokenType("struct.end")
  val REMOVE_NODE = S2CfgTokenType("removenode")
  val IDENT = S2CfgTokenType("IDENT")
  val NUMBER = S2CfgTokenType("NUMBER")
  val BOOLEAN = S2CfgTokenType("BOOLEAN")
  val ENUM_VALUE = S2CfgTokenType("ENUM_VALUE")
  val TEXT = S2CfgTokenType("TEXT")
  val REF_KEYWORD = S2CfgTokenType("REF_KEYWORD")
  val COLON = S2CfgTokenType("COLON")
  val EQ = S2CfgTokenType("EQ")
  val SEMICOLON = S2CfgTokenType("SEMICOLON")
  val LBRACKET = S2CfgTokenType("LBRACKET")
  val RBRACKET = S2CfgTokenType("RBRACKET")
  val LBRACE = S2CfgTokenType("LBRACE")
  val RBRACE = S2CfgTokenType("RBRACE")
  val ASTERISK = S2CfgTokenType("ASTERISK")

  // composite elements
  val STRUCT = S2CfgElementType("STRUCT")
  val STRUCT_HEAD = S2CfgElementType("STRUCT_HEAD")
  val REFS = S2CfgElementType("REFS")
  val REF = S2CfgElementType("REF")
  val ENTRY = S2CfgElementType("ENTRY")
  val KEY = S2CfgElementType("KEY")
  val VALUE = S2CfgElementType("VALUE")

  val COMMENTS = TokenSet.create(COMMENT)
  val STRINGS = TokenSet.create(TEXT)
}

/** The `{...}` modifiers, per Struct.mts KEYWORDS. */
val REF_KEYWORDS = setOf("refurl", "refkey", "bskipref", "bpatch", "removenode")
