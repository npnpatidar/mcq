package com.mcqapp.data.backup

import java.io.BufferedReader
import java.io.Closeable
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader

/**
 * Yields the raw text of one JSON value at a time from [input].
 *
 * Why a second pull reader sits next to `com.mcqapp.data.io.JsonPull`: the
 * study file's rows already have serializers, and going through those
 * serializers is what keeps a restored row's fields, defaults and
 * unknown-field tolerance identical to [McqBackupFormat.read]. A tokenizer
 * hands back tokens, so a reader built on one would have to copy every field
 * of every row by hand — a copy that drifts the moment a row grows a field.
 * This class only has to find where one value ends and the next begins; the
 * row's own serializer does the rest.
 *
 * It streams: one value's text is held at a time, never the document. A
 * malformed document raises [McqBackupFormat.Invalid], because every failure
 * here means the same thing to the caller — the study file cannot be read.
 */
internal class JsonValueStream(input: InputStream) : Closeable {

    private val reader: Reader =
        BufferedReader(InputStreamReader(input, Charsets.UTF_8), 1 shl 14)

    /** One character of lookahead; [EMPTY] when nothing has been read yet. */
    private var pushed = EMPTY

    /** Consumes the object's opening brace. */
    fun beginObject() {
        consumeWhitespace()
        if (take() != '{'.code) malformed("expected an object")
    }

    /** Consumes the object's closing brace. */
    fun endObject() {
        consumeWhitespace()
        if (take() != '}'.code) malformed("expected the end of the object")
    }

    /**
     * The next field name, decoded, or null once the object's last field has
     * been read. The name's colon is consumed with it; the closing brace is
     * left for [endObject].
     */
    fun readName(): String? {
        consumeWhitespace()
        if (peek() == '}'.code) return null
        if (peek() == ','.code) take()
        consumeWhitespace()
        val name = readEscapedString("a field name")
        consumeWhitespace()
        if (take() != ':'.code) malformed("expected a colon after the field name '$name'")
        return name
    }

    /** Calls [value] with each value of the array, in order. */
    suspend fun readArray(value: suspend (String) -> Unit) {
        consumeWhitespace()
        if (take() != '['.code) malformed("expected an array")
        var first = true
        while (true) {
            consumeWhitespace()
            if (peek() == ']'.code) {
                take()
                return
            }
            if (!first) {
                if (take() != ','.code) malformed("expected a comma between the array's values")
                consumeWhitespace()
            }
            first = false
            value(readValue(build = true))
        }
    }

    /** The next value, verbatim text. */
    fun readValue(): String = readValue(build = true)

    /** The next value, discarded. */
    fun skipValue() {
        readValue(build = false)
    }

    override fun close() {
        reader.close()
    }

    private fun readValue(build: Boolean): String {
        consumeWhitespace()
        return when (val c = peek()) {
            '"'.code -> {
                val body = readStringBody(build)
                if (build) "\"$body\"" else ""
            }
            '{'.code, '['.code -> readNested(take().toChar(), build)
            -1 -> malformed("the document ended where a value was expected")
            else -> readLiteral(build)
        }
    }

    /** The inside of a string whose opening quote is next. */
    private fun readStringBody(build: Boolean): String {
        if (take() != '"'.code) malformed("expected a string")
        return readStringBodyAfterQuote(build)
    }

    /**
     * The rest of a string whose opening quote has already been consumed, up
     * to and including its closing quote. Escapes are kept verbatim, which is
     * exactly what a value's text needs to carry: whoever decodes the value
     * handles them.
     */
    private fun readStringBodyAfterQuote(build: Boolean): String {
        val out = if (build) StringBuilder() else null
        while (true) {
            val c = take()
            if (c == -1) malformed("a string is not terminated")
            if (c == '\\'.code) {
                val escaped = take()
                if (escaped == -1) malformed("a string ends in a dangling escape")
                out?.append(c.toChar())?.append(escaped.toChar())
                continue
            }
            if (c == '"'.code) return out?.toString().orEmpty()
            out?.append(c.toChar())
        }
    }

    /** A field name: a string whose escapes are decoded, since callers match on it. */
    private fun readEscapedString(what: String): String {
        if (take() != '"'.code) malformed("expected $what")
        val out = StringBuilder()
        while (true) {
            val c = take()
            if (c == -1) malformed("$what is not terminated")
            if (c == '"'.code) return out.toString()
            if (c != '\\'.code) {
                out.append(c.toChar())
                continue
            }
            when (val escaped = take()) {
                'n'.code -> out.append('\n')
                't'.code -> out.append('\t')
                'r'.code -> out.append('\r')
                'b'.code -> out.append('\b')
                'f'.code -> out.append('\u000C')
                '/'.code -> out.append('/')
                '"'.code -> out.append('"')
                '\\'.code -> out.append('\\')
                'u'.code -> out.append(hexQuad())
                -1 -> malformed("$what ends in a dangling escape")
                else -> malformed("$what holds a bad escape \\${escaped.toChar()}")
            }
        }
    }

    private fun hexQuad(): Char {
        var value = 0
        repeat(4) {
            val c = take()
            val digit = when (c) {
                -1 -> -1
                in '0'.code..'9'.code -> c - '0'.code
                in 'a'.code..'f'.code -> c - 'a'.code + 10
                in 'A'.code..'F'.code -> c - 'A'.code + 10
                else -> -2
            }
            if (digit < 0) malformed("a \\u escape is truncated")
            value = value * 16 + digit
        }
        return value.toChar()
    }

    /** An object or array, copied whole (or walked through when not building). */
    private fun readNested(open: Char, build: Boolean): String {
        val out = if (build) StringBuilder().append(open) else null
        var depth = 1
        while (depth > 0) {
            val c = take()
            if (c == -1) malformed("an object or array is not closed")
            when (c.toChar()) {
                '"' -> {
                    // A quote inside a string cannot close a container.
                    val body = readStringBodyAfterQuote(build)
                    if (out != null) out.append('"').append(body).append('"')
                }
                '{', '[' -> {
                    depth++
                    out?.append(c.toChar())
                }
                '}', ']' -> {
                    depth--
                    out?.append(c.toChar())
                }
                else -> out?.append(c.toChar())
            }
        }
        return out?.toString().orEmpty()
    }

    /** A number, `true`, `false` or `null`: everything up to the next delimiter. */
    private fun readLiteral(build: Boolean): String {
        val out = if (build) StringBuilder() else null
        while (true) {
            val c = peek()
            if (c == -1) break
            val ch = c.toChar()
            if (ch == ',' || ch == '}' || ch == ']' || ch.isWhitespace()) break
            take()
            out?.append(ch)
        }
        val text = out?.toString().orEmpty()
        if (build && text.isEmpty()) malformed("expected a value")
        return text
    }

    private fun consumeWhitespace() {
        while (true) {
            val c = peek()
            if (c == -1) return
            val ch = c.toChar()
            if (ch.isWhitespace() || ch == '\uFEFF') {
                take()
                continue
            }
            return
        }
    }

    private fun peek(): Int {
        if (pushed == EMPTY) pushed = reader.read()
        return pushed
    }

    private fun take(): Int {
        val c = peek()
        pushed = EMPTY
        return c
    }

    private fun malformed(detail: String): Nothing =
        throw McqBackupFormat.Invalid("The backup's study file is unreadable: $detail.")

    private companion object {
        /** Nothing has been read yet. Not a character, not end of stream. */
        const val EMPTY = -2
    }
}
