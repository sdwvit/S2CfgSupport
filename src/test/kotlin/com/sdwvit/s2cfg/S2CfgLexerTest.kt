package com.sdwvit.s2cfg

import junit.framework.TestCase

/** Guards the two lexer properties the IDE's responsiveness depends on: it always makes progress,
 *  and it never does whole-buffer work up front. */
class S2CfgLexerTest : TestCase() {

  private fun lex(text: String): List<String> {
    val lexer = S2CfgLexer()
    lexer.start(text, 0, text.length, 0)
    val out = mutableListOf<String>()
    while (lexer.tokenType != null) {
      assertTrue("zero-length token at ${lexer.tokenStart}", lexer.tokenEnd > lexer.tokenStart)
      out += "${lexer.tokenType}:${text.substring(lexer.tokenStart, lexer.tokenEnd)}"
      lexer.advance()
    }
    assertEquals(text.length, lexer.tokenStart)
    return out
  }

  /** A `;` outside `{...}` starts a line comment, and inside `{...}` separates modifiers. */
  fun testSemicolonIsACommentOutsideBraces() {
    assertTrue(lex("Foo : struct.begin\n   ;note = 5\nstruct.end\n").any { it == "${S2CfgTypes.COMMENT}:;note = 5" })
    assertTrue(lex("Foo : struct.begin {refkey=A;bpatch}").any { it == "${S2CfgTypes.SEMICOLON}:;" })
  }

  /**
   * Termination is the property that matters: a `;` outside braces used to consume nothing and
   * spin forever inside start(), freezing the IDE with no stack in the log. [lex] asserts every
   * token is non-empty and that the whole buffer is consumed, so this is a torture input.
   */
  fun testEveryDelimiterTerminates() {
    val torture = ";:=*[]{}\n} ] * : = ; {\nK = }[*;:\nstruct.end{;}\n[*] : struct.begin {;=}\n"
    lex(torture)
    lex(torture.reversed())
    for (i in 1..torture.length) lex(torture.substring(0, i))
  }

  fun testValueKeepsSpecialCharacters() {
    assertEquals(listOf("${S2CfgTypes.TEXT}:a#b[0]:c"), lex("K = a#b[0]:c").filter { ":a#b" in it })
  }

  fun testNumberValueIsClassifiedDespiteLeadingBlank() {
    assertTrue(lex("K = 1.0").any { it == "${S2CfgTypes.NUMBER}:1.0" })
  }

  fun testRefkeyIndexValueStaysText() {
    assertTrue(lex("K : struct.begin {refkey=[0]}").any { it == "${S2CfgTypes.TEXT}:[0]" })
  }

  /** Lexing is streaming, so reading one token from a huge buffer must not scan all of it. */
  fun testFirstTokenIsCheapOnHugeBuffer() {
    val text = "Foo : struct.begin\n" + "   Key = value\n".repeat(200_000) + "struct.end\n"
    val lexer = S2CfgLexer()
    val started = System.nanoTime()
    lexer.start(text, 0, text.length, 0)
    lexer.advance()
    val tookMs = (System.nanoTime() - started) / 1_000_000
    assertTrue("start()+advance() took ${tookMs}ms on a ${text.length}-char buffer", tookMs < 50)
  }

  /** State must carry the context, or a mid-file restart reclassifies tokens. */
  fun testRestartMidValueUsesState() {
    val text = "K = 1.0\n"
    val lexer = S2CfgLexer()
    lexer.start(text, 0, text.length, 0)
    while (lexer.tokenStart < text.indexOf('1')) lexer.advance()
    assertEquals(text.indexOf('1'), lexer.tokenStart)
    val state = lexer.state
    val restarted = S2CfgLexer()
    restarted.start(text, lexer.tokenStart, text.length, state)
    assertEquals(S2CfgTypes.NUMBER, restarted.tokenType)
  }
}
