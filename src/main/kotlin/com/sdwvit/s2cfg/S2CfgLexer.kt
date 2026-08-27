package com.sdwvit.s2cfg

import com.intellij.lexer.LexerBase
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType

/**
 * The cfg grammar is small enough that a hand-rolled lexer beats codegen. All tokens for the
 * buffer are produced up front in [start]; [advance] just walks the list.
 *
 * Three lexing contexts matter, because the same characters mean different things in each:
 *  - KEY:   line start up to `=` / `:` — identifiers, `[0]`, `[*]`, `struct.begin`, `struct.end`
 *  - VALUE: after `=` — free text up to end of line or an opening `{`
 *  - REFS:  inside `{ ... }` — `refkey=X; bpatch` modifier list
 */
class S2CfgLexer : LexerBase() {
  private var buffer: CharSequence = ""
  private var endOffset = 0
  private var starts = IntArray(0)
  private var ends = IntArray(0)
  private var types = arrayOfNulls<IElementType>(0)
  private var count = 0
  private var index = 0

  private enum class Ctx { KEY, VALUE, REFS }

  override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
    this.buffer = buffer
    this.endOffset = endOffset
    tokenize(startOffset, endOffset)
    index = 0
  }

  override fun getState() = 0
  override fun getTokenType(): IElementType? = if (index < count) types[index] else null
  override fun getTokenStart() = if (index < count) starts[index] else endOffset
  override fun getTokenEnd() = if (index < count) ends[index] else endOffset
  override fun advance() { if (index < count) index++ }
  override fun getBufferSequence() = buffer
  override fun getBufferEnd() = endOffset

  private fun add(type: IElementType, from: Int, to: Int) {
    if (to <= from) return
    if (count == types.size) {
      val cap = if (count == 0) 256 else count * 2
      starts = starts.copyOf(cap); ends = ends.copyOf(cap); types = types.copyOf(cap)
    }
    starts[count] = from; ends[count] = to; types[count] = type; count++
  }

  private fun tokenize(from: Int, to: Int) {
    count = 0
    var i = from
    var ctx = Ctx.KEY
    while (i < to) {
      val c = buffer[i]
      when {
        c == '\n' || c == '\r' -> {
          val s = i
          while (i < to && (buffer[i] == '\n' || buffer[i] == '\r')) i++
          add(TokenType.WHITE_SPACE, s, i)
          ctx = Ctx.KEY
        }
        c == ' ' || c == '\t' || c == '\uFEFF' -> {
          val s = i
          while (i < to && (buffer[i] == ' ' || buffer[i] == '\t' || buffer[i] == '\uFEFF')) i++
          add(TokenType.WHITE_SPACE, s, i)
        }
        c == '#' || (c == '/' && i + 1 < to && buffer[i + 1] == '/') -> {
          val s = i
          while (i < to && buffer[i] != '\n' && buffer[i] != '\r') i++
          add(S2CfgTypes.COMMENT, s, i)
        }
        c == '{' -> { add(S2CfgTypes.LBRACE, i, i + 1); i++; ctx = Ctx.REFS }
        c == '}' -> { add(S2CfgTypes.RBRACE, i, i + 1); i++; ctx = Ctx.KEY }
        c == ';' && ctx == Ctx.REFS -> { add(S2CfgTypes.SEMICOLON, i, i + 1); i++ }
        c == '=' -> {
          add(S2CfgTypes.EQ, i, i + 1); i++
          i = if (ctx == Ctx.REFS) readRefValue(i, to) else { ctx = Ctx.VALUE; readValue(i, to) }
        }
        c == ':' && ctx != Ctx.VALUE -> { add(S2CfgTypes.COLON, i, i + 1); i++ }
        c == '[' && ctx != Ctx.VALUE -> { add(S2CfgTypes.LBRACKET, i, i + 1); i++ }
        c == ']' && ctx != Ctx.VALUE -> { add(S2CfgTypes.RBRACKET, i, i + 1); i++ }
        c == '*' && ctx != Ctx.VALUE -> { add(S2CfgTypes.ASTERISK, i, i + 1); i++ }
        ctx == Ctx.VALUE -> i = readValue(i, to)
        else -> i = readWord(i, to, ctx)
      }
    }
  }

  /** `struct.begin` / `struct.end` / ref keyword / plain identifier. */
  private fun readWord(from: Int, to: Int, ctx: Ctx): Int {
    var i = from
    while (i < to && !isDelimiter(buffer[i])) i++
    val word = buffer.subSequence(from, i).toString()
    val type = when {
      word == "struct.begin" -> S2CfgTypes.STRUCT_BEGIN
      word == "struct.end" -> S2CfgTypes.STRUCT_END
      ctx == Ctx.REFS && word in REF_KEYWORDS -> S2CfgTypes.REF_KEYWORD
      // `Conditions : removenode` deletes an inherited struct; see Struct.mts REMOVE_NODE
      ctx == Ctx.KEY && word == "removenode" -> S2CfgTypes.REMOVE_NODE
      else -> S2CfgTypes.IDENT
    }
    add(type, from, i)
    return i
  }

  /** Everything after `=` up to end of line or an opening `{`, classified by shape. */
  private fun readValue(from: Int, to: Int): Int {
    var i = from
    while (i < to && buffer[i] != '\n' && buffer[i] != '\r' && buffer[i] != '{') i++
    var end = i
    while (end > from && (buffer[end - 1] == ' ' || buffer[end - 1] == '\t')) end--
    if (end > from) add(classify(buffer.subSequence(from, end).toString()), from, end)
    return if (end < i) end else i
  }

  /** A modifier's value inside `{...}`: up to `;`, `}` or end of line. */
  private fun readRefValue(from: Int, to: Int): Int {
    var i = from
    while (i < to && buffer[i] != ';' && buffer[i] != '}' && buffer[i] != '\n' && buffer[i] != '\r') i++
    var end = i
    while (end > from && (buffer[end - 1] == ' ' || buffer[end - 1] == '\t')) end--
    if (end > from) add(S2CfgTypes.TEXT, from, end)
    return if (end < i) end else i
  }

  private fun classify(value: String): IElementType = when {
    value == "true" || value == "false" -> S2CfgTypes.BOOLEAN
    NUMBER.matches(value) -> S2CfgTypes.NUMBER
    ENUM.matches(value) -> S2CfgTypes.ENUM_VALUE
    else -> S2CfgTypes.TEXT
  }

  private fun isDelimiter(c: Char) =
    c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\uFEFF' ||
      c == ':' || c == '=' || c == '{' || c == '}' || c == ';' ||
      c == '[' || c == ']' || c == '*'

  private companion object {
    // matches -0.1f / 1. / 0.f / .1 / .1f, mirroring parseValue() in Struct.mts
    val NUMBER = Regex("^-?(\\d+\\.?\\d*|\\.\\d+)f?$")
    val ENUM = Regex("^E[A-Za-z0-9_]*::[A-Za-z0-9_]+$")
  }
}
