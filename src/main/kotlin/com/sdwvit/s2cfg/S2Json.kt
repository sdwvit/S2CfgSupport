package com.sdwvit.s2cfg

/**
 * A tiny strict JSON reader/writer.
 *
 * The localization editor round-trips a document the user is free to hand-edit, so what matters
 * here is not speed but that a malformed document is reported with an offset the editor can point
 * at — and that nothing is silently coerced. Only the subset the localization asset needs is
 * modelled: objects, arrays, strings, numbers, booleans and null.
 */
sealed interface S2JsonValue {
  data class Obj(val entries: List<Pair<String, S2JsonValue>>) : S2JsonValue {
    operator fun get(key: String) = entries.firstOrNull { it.first == key }?.second
  }

  data class Arr(val items: List<S2JsonValue>) : S2JsonValue
  data class Str(val value: String) : S2JsonValue
  data class Num(val text: String) : S2JsonValue
  data class Bool(val value: Boolean) : S2JsonValue
  object Null : S2JsonValue
}

/** A parse or schema failure, with the character offset it was noticed at (-1 when unknown). */
class S2JsonException(message: String, val offset: Int = -1) : Exception(message)

object S2Json {

  fun parse(text: String): S2JsonValue {
    val p = Parser(text)
    p.skipWhitespace()
    val value = p.readValue()
    p.skipWhitespace()
    if (p.pos != text.length) p.fail("trailing content after the top-level value")
    return value
  }

  /** Pretty-prints with two-space indentation — the shape the editor shows and diffs cleanly. */
  fun write(value: S2JsonValue): String = StringBuilder().also { write(it, value, 0) }.toString()

  private fun write(out: StringBuilder, value: S2JsonValue, indent: Int) {
    val pad = "  ".repeat(indent)
    val inner = "  ".repeat(indent + 1)
    when (value) {
      is S2JsonValue.Obj ->
        if (value.entries.isEmpty()) out.append("{}")
        else {
          out.append("{\n")
          value.entries.forEachIndexed { i, (key, v) ->
            out.append(inner)
            writeString(out, key)
            out.append(": ")
            write(out, v, indent + 1)
            out.append(if (i == value.entries.lastIndex) "\n" else ",\n")
          }
          out.append(pad).append("}")
        }

      is S2JsonValue.Arr ->
        if (value.items.isEmpty()) out.append("[]")
        else {
          out.append("[\n")
          value.items.forEachIndexed { i, v ->
            out.append(inner)
            write(out, v, indent + 1)
            out.append(if (i == value.items.lastIndex) "\n" else ",\n")
          }
          out.append(pad).append("]")
        }

      is S2JsonValue.Str -> writeString(out, value.value)
      is S2JsonValue.Num -> out.append(value.text)
      is S2JsonValue.Bool -> out.append(value.value)
      S2JsonValue.Null -> out.append("null")
    }
  }

  /**
   * Non-ASCII is emitted as-is rather than escaped: these documents are mostly Cyrillic, Arabic
   * and CJK text a translator has to be able to read in the editor.
   */
  private fun writeString(out: StringBuilder, s: String) {
    out.append('"')
    for (c in s) when {
      c == '"' -> out.append("\\\"")
      c == '\\' -> out.append("\\\\")
      c == '\n' -> out.append("\\n")
      c == '\r' -> out.append("\\r")
      c == '\t' -> out.append("\\t")
      c < ' ' -> out.append("\\u%04x".format(c.code))
      else -> out.append(c)
    }
    out.append('"')
  }

  private class Parser(val text: String, var pos: Int = 0) {

    fun fail(message: String): Nothing = throw S2JsonException(message, pos)

    fun skipWhitespace() {
      while (pos < text.length && text[pos].isWhitespace()) pos++
    }

    private fun peek(): Char = if (pos < text.length) text[pos] else fail("unexpected end of input")

    private fun expect(c: Char) {
      if (pos >= text.length || text[pos] != c) fail("expected '$c'")
      pos++
    }

    fun readValue(): S2JsonValue = when (val c = peek()) {
      '{' -> readObject()
      '[' -> readArray()
      '"' -> S2JsonValue.Str(readString())
      't' -> readLiteral("true", S2JsonValue.Bool(true))
      'f' -> readLiteral("false", S2JsonValue.Bool(false))
      'n' -> readLiteral("null", S2JsonValue.Null)
      else -> if (c == '-' || c.isDigit()) readNumber() else fail("unexpected character '$c'")
    }

    private fun readLiteral(literal: String, value: S2JsonValue): S2JsonValue {
      if (!text.startsWith(literal, pos)) fail("expected $literal")
      pos += literal.length
      return value
    }

    private fun readObject(): S2JsonValue.Obj {
      expect('{')
      val entries = mutableListOf<Pair<String, S2JsonValue>>()
      skipWhitespace()
      if (peek() == '}') {
        pos++
        return S2JsonValue.Obj(entries)
      }
      while (true) {
        skipWhitespace()
        if (peek() != '"') fail("expected a quoted key")
        val key = readString()
        skipWhitespace()
        expect(':')
        skipWhitespace()
        entries += key to readValue()
        skipWhitespace()
        when (peek()) {
          ',' -> pos++
          '}' -> {
            pos++
            return S2JsonValue.Obj(entries)
          }

          else -> fail("expected ',' or '}'")
        }
      }
    }

    private fun readArray(): S2JsonValue.Arr {
      expect('[')
      val items = mutableListOf<S2JsonValue>()
      skipWhitespace()
      if (peek() == ']') {
        pos++
        return S2JsonValue.Arr(items)
      }
      while (true) {
        skipWhitespace()
        items += readValue()
        skipWhitespace()
        when (peek()) {
          ',' -> pos++
          ']' -> {
            pos++
            return S2JsonValue.Arr(items)
          }

          else -> fail("expected ',' or ']'")
        }
      }
    }

    private fun readString(): String {
      expect('"')
      val sb = StringBuilder()
      while (true) {
        if (pos >= text.length) fail("unterminated string")
        when (val c = text[pos++]) {
          '"' -> return sb.toString()
          '\\' -> {
            if (pos >= text.length) fail("unterminated escape")
            when (val e = text[pos++]) {
              '"' -> sb.append('"')
              '\\' -> sb.append('\\')
              '/' -> sb.append('/')
              'b' -> sb.append('\b')
              'f' -> sb.append('\u000C')
              'n' -> sb.append('\n')
              'r' -> sb.append('\r')
              't' -> sb.append('\t')
              'u' -> {
                if (pos + 4 > text.length) fail("truncated \\u escape")
                val hex = text.substring(pos, pos + 4)
                sb.append(hex.toIntOrNull(16)?.toChar() ?: fail("invalid \\u escape '$hex'"))
                pos += 4
              }

              else -> fail("invalid escape '\\$e'")
            }
          }

          else -> {
            if (c < ' ') fail("unescaped control character in string")
            sb.append(c)
          }
        }
      }
    }

    private fun readNumber(): S2JsonValue.Num {
      val start = pos
      if (peek() == '-') pos++
      while (pos < text.length && (text[pos].isDigit() || text[pos] in ".eE+-")) pos++
      val slice = text.substring(start, pos)
      if (slice.toDoubleOrNull() == null) {
        pos = start
        fail("'$slice' is not a number")
      }
      return S2JsonValue.Num(slice)
    }
  }
}
