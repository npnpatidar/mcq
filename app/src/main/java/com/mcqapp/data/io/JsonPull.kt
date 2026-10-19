package com.mcqapp.data.io

import java.io.BufferedReader
import java.io.Closeable
import java.io.InputStream
import java.io.InputStreamReader

/**
 * A minimal pull tokenizer for JSON documents.
 *
 * Why not `kotlinx.serialization`'s DOM or a typed decode: the legacy parser
 * keeps its per-item tolerance (one malformed question must not kill a bank),
 * and a whole-document DOM is what made a 48 MB bank import die in an OOM.
 * Why not `android.util.JsonReader`: that class is a stub in plain unit tests,
 * which would leave the parser untestable outside Robolectric.
 *
 * The tokenizer streams: it reads the document through a buffered reader and
 * hands out one small slice at a time, so nothing ever holds the whole
 * document in memory. Recursion is bounded by the parser's
 * [LegacyParser.MAX_NESTING_DEPTH] budget on the slice side; the token walk
 * itself iterates, [skipValue] included — a hostile file may nest its *read*
 * values only to the budget, but its *skipped* values to any depth, and
 * neither may grow the stack.
 */
internal class JsonPull(input: InputStream) : Closeable {

    enum class Token { BEGIN_OBJECT, END_OBJECT, BEGIN_ARRAY, END_ARRAY, NAME, STRING, NUMBER, BOOLEAN, NULL, EOF }

    /** One object or array context: whether the next token is a member name. */
    private class Site(val kind: Char, var nameExpected: Boolean)

    private val reader: BufferedReader = BufferedReader(InputStreamReader(input, Charsets.UTF_8), 1 shl 14)
    private var pushback = NO_CHAR
    private var current = Token.EOF
    private var literal = ""
    private val ctx = ArrayDeque<Site>()

    /** Read ahead once so [peek] is valid from construction. */
    init {
        skipBom()
        scan()
    }

    fun peek(): Token = current

    /** True while more members remain, at any nesting level. */
    fun hasNext(): Boolean = current != Token.END_OBJECT && current != Token.END_ARRAY && current != Token.EOF

    fun beginObject() {
        expect(Token.BEGIN_OBJECT)
        ctx.addLast(Site('{', nameExpected = true))
        scan()
    }

    fun endObject() {
        expect(Token.END_OBJECT)
        popContext()
        scan()
    }

    fun beginArray() {
        expect(Token.BEGIN_ARRAY)
        ctx.addLast(Site('[', nameExpected = false))
        scan()
    }

    fun endArray() {
        expect(Token.END_ARRAY)
        popContext()
        scan()
    }

    /** The member name just scanned; consumes its following colon too. */
    fun nextName(): String {
        expect(Token.NAME)
        val name = literal
        scan()
        return name
    }

    /**
     * The literal text of a string, number or boolean. Numbers keep their
     * exact literal ("3", "2.5"), exactly as the old document-wide DOM did,
     * so `intOrNull`/`doubleOrNull`/`booleanOrNull` on the slices behave
     * identically.
     */
    fun nextString(): String {
        if (current != Token.STRING && current != Token.NUMBER && current != Token.BOOLEAN) {
            throw syntaxError("expected a value")
        }
        val value = literal
        valueConsumed()
        return value
    }

    fun nextNull() {
        expect(Token.NULL)
        valueConsumed()
    }

    /**
     * Consumes one whole value, whatever its shape.
     *
     * Iterative, matching the walk everywhere else: a hostile file nests its
     * *skipped* members too (`{"junk": [[[[[…]]]]]}` is valid JSON at any
     * depth), and one frame per level here would overflow the stack before
     * the slice reader's own depth budget is ever reached.
     */
    fun skipValue() {
        if (current != Token.BEGIN_OBJECT && current != Token.BEGIN_ARRAY) {
            valueConsumed()
            return
        }
        // Container kinds, innermost last: each terminator is closed against
        // the kind that opened it, so a malformed `}` reports exactly what
        // the recursive form reported.
        val open = ArrayDeque<Char>()
        while (true) {
            when (current) {
                Token.BEGIN_OBJECT -> {
                    beginObject(); open.addLast('{')
                }
                Token.BEGIN_ARRAY -> {
                    beginArray(); open.addLast('[')
                }
                Token.END_OBJECT, Token.END_ARRAY -> {
                    if (open.removeLast() == '{') endObject() else endArray()
                    if (open.isEmpty()) return
                }
                Token.EOF -> {
                    // The value never closed; let expect() report it against
                    // whichever container is open, as before.
                    if (open.last() == '{') endObject() else endArray()
                }
                Token.NAME -> nextName()
                else -> valueConsumed()
            }
        }
    }

    override fun close() {
        reader.close()
    }

    // --- internals ---------------------------------------------------------

    private fun popContext() {
        if (ctx.isEmpty()) throw syntaxError("unbalanced container")
        ctx.removeLast()
        // Only marks the parent: the caller scans the terminator's successor,
        // because scanning here too would consume it.
        markValueConsumed()
    }

    private fun valueConsumed() {
        markValueConsumed()
        scan()
    }

    private fun markValueConsumed() {
        val site = ctx.lastOrNull() ?: return
        if (site.kind == '{') site.nameExpected = true
    }

    private fun expect(token: Token) {
        if (current != token) throw syntaxError("expected $token but was $current")
    }

    private fun syntaxError(message: String) =
        IllegalArgumentException("Malformed JSON: $message")

    private fun read(): Int {
        if (pushback != NO_CHAR) {
            val c = pushback
            pushback = NO_CHAR
            return c
        }
        return reader.read()
    }

    private fun unread(c: Int) {
        pushback = c
    }

    private fun skipBom() {
        val first = read()
        if (first == -1) return
        if (first == 0xFEFF) return
        unread(first)
    }

    private fun skipWhitespace() {
        while (true) {
            when (val c = read()) {
                -1 -> return
                ' '.code, '\t'.code, '\r'.code, '\n'.code, '\uFEFF'.code -> Unit
                else -> {
                    unread(c)
                    return
                }
            }
        }
    }

    private fun scan() {
        skipWhitespace()
        val c = read()
        if (c == -1) {
            current = Token.EOF
            return
        }
        when (val ch = c.toChar()) {
            ',' , ':' -> {
                // A separator outside string context: the next token follows.
                scan()
                return
            }
            '{' -> current = Token.BEGIN_OBJECT
            '}' -> current = Token.END_OBJECT
            '[' -> current = Token.BEGIN_ARRAY
            ']' -> current = Token.END_ARRAY
            '"' -> {
                literal = readString()
                val expectsName = ctx.lastOrNull()?.let { it.kind == '{' && it.nameExpected } == true
                current = if (expectsName) Token.NAME else Token.STRING
                if (expectsName) ctx.last().nameExpected = false
                return
            }
            else -> {
                val word = readLiteral(ch)
                current = when (word) {
                    "true", "false" -> Token.BOOLEAN
                    "null" -> Token.NULL
                    else -> Token.NUMBER
                }
                literal = word
                return
            }
        }
    }

    private fun readString(): String {
        val out = StringBuilder()
        while (true) {
            when (val c = read()) {
                -1 -> throw syntaxError("unterminated string")
                '"'.code -> return out.toString()
                '\\'.code -> out.append(readEscape())
                else -> out.append(c.toChar())
            }
        }
    }

    private fun readEscape(): Char {
        when (val c = read()) {
            -1 -> throw syntaxError("unterminated escape")
            '"'.code -> return '"'
            '\\'.code -> return '\\'
            '/'.code -> return '/'
            'b'.code -> return '\b'
            'f'.code -> return '\u000C'
            'n'.code -> return '\n'
            'r'.code -> return '\r'
            't'.code -> return '\t'
            'u'.code -> {
                var code = 0
                repeat(4) {
                    val digit = read()
                    if (digit == -1) throw syntaxError("truncated \\u escape")
                    code = (code shl 4) or hexValue(digit.toChar())
                }
                return code.toChar()
            }
            else -> throw syntaxError("bad escape \\$c")
        }
    }

    private fun hexValue(ch: Char): Int = when (ch) {
        in '0'..'9' -> ch - '0'
        in 'a'..'f' -> ch - 'a' + 10
        in 'A'..'F' -> ch - 'A' + 10
        else -> throw syntaxError("bad hex digit '$ch'")
    }

    /** A bare literal until the next structural character. */
    private fun readLiteral(first: Char): String {
        val out = StringBuilder()
        out.append(first)
        while (true) {
            when (val c = read()) {
                -1 -> return out.toString()
                ' '.code, '\t'.code, '\r'.code, '\n'.code, ','.code, ':'.code,
                '{'.code, '}'.code, '['.code, ']'.code -> {
                    unread(c)
                    return out.toString()
                }
                else -> out.append(c.toChar())
            }
        }
    }

    private companion object {
        const val NO_CHAR = -2
    }
}
