package com.sdwvit.s2cfg

import com.intellij.lexer.LexerBase
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType

/**
 * The cfg grammar is small enough that a hand-rolled lexer beats codegen.
 *
 * The lexer is *streaming*: [advance] produces exactly one token and nothing is pre-computed in
 * [start]. It must stay that way — the editor highlighter relexes with `endOffset` = end of
 * document on every keystroke, so an eager "tokenize the whole buffer up front" implementation
 * costs O(file size) time and allocation per typed character and freezes the UI on the
 * multi-megabyte cfgs in GameData.
 *
 * Three lexing contexts matter, because the same characters mean different things in each:
 *  - KEY:   line start up to `=` / `:` — identifiers, `[0]`, `[*]`, `struct.begin`, `struct.end`
 *  - VALUE: after `=` — free text up to end of line or an opening `{`
 *  - REFS:  inside `{ ... }` — `refkey=X; bpatch` modifier list
 *
 * The context is reported by [getState] and restored from `initialState`, so relexing that restarts
 * mid-file resumes in the right context instead of silently reclassifying tokens.
 */
class S2CfgLexer : LexerBase() {
  private var buffer: CharSequence = ""
  private var bufferEnd = 0

  private var tokenStart = 0
  private var tokenEnd = 0
  private var tokenType: IElementType? = null

  /** Context at [tokenStart] — what [getState] must report, so a restart there is faithful. */
  private var tokenState = KEY

  /** Context the next token is read in. */
  private var ctx = KEY

  override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
    this.buffer = buffer
    this.bufferEnd = endOffset
    this.tokenStart = startOffset
    this.tokenEnd = startOffset
    this.ctx = if (initialState in KEY..REFS_VALUE) initialState else KEY
    nextToken()
  }

  override fun getState() = tokenState
  override fun getTokenType(): IElementType? = tokenType
  override fun getTokenStart() = tokenStart
  override fun getTokenEnd() = tokenEnd
  override fun getBufferSequence() = buffer
  override fun getBufferEnd() = bufferEnd

  override fun advance() {
    if (tokenType == null) return
    tokenStart = tokenEnd
    nextToken()
  }

  /**
   * Produce the token starting at [tokenStart] and leave [ctx] pointing at the context the token
   * after it is read in.
   *
   * This is a loop, not a chain of returns, because an empty value (`Name =`) contributes no token.
   * Every iteration must consume at least one character or hand the character to a context that
   * does — a branch that returns a zero-length token hangs the IDE, not just the file.
   */
  private fun nextToken() {
    var i = tokenStart
    while (i < bufferEnd) {
      tokenStart = i
      tokenState = ctx

      // A value is read as one token straight after `=`, so that `#`, `[` and `:` inside it stay
      // part of the text. Leading blanks are left to the whitespace rule below, so that `K = 1.0`
      // classifies as a number rather than as text starting with a space. Falling through means
      // the value was empty.
      if ((ctx == VALUE || ctx == REFS_VALUE) && !isSpace(buffer[i])) {
        val refs = ctx == REFS_VALUE
        val stop =
          if (refs) scanTo(i) { it == ';' || it == '}' || it == '\n' || it == '\r' }
          else scanTo(i) { it == '\n' || it == '\r' || it == '{' }
        val end = trimTrailing(i, stop)
        if (refs) ctx = REFS
        if (end > i) return emit(if (refs) S2CfgTypes.TEXT else classify(i, end), end)
      }

      val c = buffer[i]
      when {
        c == '\n' || c == '\r' -> {
          var j = i
          while (j < bufferEnd && (buffer[j] == '\n' || buffer[j] == '\r')) j++
          ctx = KEY
          return emit(TokenType.WHITE_SPACE, j)
        }
        isSpace(c) -> {
          var j = i
          while (j < bufferEnd && isSpace(buffer[j])) j++
          return emit(TokenType.WHITE_SPACE, j)
        }
        c == '#' || (c == '/' && i + 1 < bufferEnd && buffer[i + 1] == '/') -> {
          var j = i
          while (j < bufferEnd && buffer[j] != '\n' && buffer[j] != '\r') j++
          return emit(S2CfgTypes.COMMENT, j)
        }
        c == '{' -> { ctx = REFS; return emit(S2CfgTypes.LBRACE, i + 1) }
        c == '}' -> { ctx = KEY; return emit(S2CfgTypes.RBRACE, i + 1) }
        c == ';' && ctx == REFS -> return emit(S2CfgTypes.SEMICOLON, i + 1)
        c == '=' -> {
          ctx = if (ctx == REFS) REFS_VALUE else VALUE
          return emit(S2CfgTypes.EQ, i + 1)
        }
        c == ':' && ctx != VALUE -> return emit(S2CfgTypes.COLON, i + 1)
        c == '[' && ctx != VALUE -> return emit(S2CfgTypes.LBRACKET, i + 1)
        c == ']' && ctx != VALUE -> return emit(S2CfgTypes.RBRACKET, i + 1)
        c == '*' && ctx != VALUE -> return emit(S2CfgTypes.ASTERISK, i + 1)
        else -> {
          val end = scanTo(i, ::isDelimiter)
          // a delimiter no branch above claims (a stray `;` outside braces) would read as a
          // zero-length word and spin forever; report it as one bad character instead
          if (end == i) return emit(TokenType.BAD_CHARACTER, i + 1)
          return emit(wordType(i, end), end)
        }
      }
    }
    tokenStart = i
    tokenEnd = i
    tokenState = ctx
    tokenType = null
  }

  private fun emit(type: IElementType, end: Int) {
    tokenType = type
    tokenEnd = end
  }

  private inline fun scanTo(from: Int, stop: (Char) -> Boolean): Int {
    var i = from
    while (i < bufferEnd && !stop(buffer[i])) i++
    return i
  }

  private fun trimTrailing(from: Int, to: Int): Int {
    var end = to
    while (end > from && (buffer[end - 1] == ' ' || buffer[end - 1] == '\t')) end--
    return end
  }

  /** `struct.begin` / `struct.end` / ref keyword / plain identifier. */
  private fun wordType(from: Int, to: Int): IElementType {
    val word = buffer.subSequence(from, to).toString()
    return when {
      word == "struct.begin" -> S2CfgTypes.STRUCT_BEGIN
      word == "struct.end" -> S2CfgTypes.STRUCT_END
      ctx == REFS && word in REF_KEYWORDS -> S2CfgTypes.REF_KEYWORD
      // `Conditions : removenode` deletes an inherited struct; see Struct.mts REMOVE_NODE
      ctx == KEY && word == "removenode" -> S2CfgTypes.REMOVE_NODE
      else -> S2CfgTypes.IDENT
    }
  }

  private fun classify(from: Int, to: Int): IElementType {
    val value = buffer.subSequence(from, to).toString()
    return when {
      value == "true" || value == "false" -> S2CfgTypes.BOOLEAN
      NUMBER.matches(value) -> S2CfgTypes.NUMBER
      ENUM.matches(value) -> S2CfgTypes.ENUM_VALUE
      else -> S2CfgTypes.TEXT
    }
  }

  private fun isSpace(c: Char) = c == ' ' || c == '\t' || c == '﻿'

  private fun isDelimiter(c: Char) =
    c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '﻿' ||
      c == ':' || c == '=' || c == '{' || c == '}' || c == ';' ||
      c == '[' || c == ']' || c == '*'

  private companion object {
    // lexer states; KEY must be 0, the state the platform starts a file with
    const val KEY = 0
    const val VALUE = 1
    const val REFS = 2
    const val REFS_VALUE = 3

    // matches -0.1f / 1. / 0.f / .1 / .1f, mirroring parseValue() in Struct.mts
    val NUMBER = Regex("^-?(\\d+\\.?\\d*|\\.\\d+)f?$")
    val ENUM = Regex("^E[A-Za-z0-9_]*::[A-Za-z0-9_]+$")
  }
}
