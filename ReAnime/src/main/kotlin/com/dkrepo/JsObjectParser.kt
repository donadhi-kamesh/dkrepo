package com.dkrepo

/**
 * Tiny tolerant parser for JavaScript object literals (also strict JSON).
 * Handles: objects with quoted/unquoted keys, arrays, double/single quoted
 * strings with escapes, numbers (int/long/double/hex), true/false/null,
 * and `undefined`.
 */
object JsObjectParser {

    fun parse(text: String): Any? {
        val p = Parser(text)
        p.skipWs()
        val v = p.parseValue()
        return v
    }

    private class Parser(val s: String) {
        var pos = 0

        fun skipWs() {
            while (pos < s.length && (s[pos] == ' ' || s[pos] == '\t' || s[pos] == '\n' || s[pos] == '\r')) pos++
        }

        fun parseValue(): Any? {
            skipWs()
            if (pos >= s.length) return null
            return when (val c = s[pos]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"', '\'' -> parseString(c)
                't' -> { expectLit("true"); true }
                'f' -> { expectLit("false"); false }
                'n' -> { expectLit("null"); null }
                'u' -> { expectLit("undefined"); null }
                'N' -> { expectLit("NaN"); null }
                else -> parseNumberOrWord(c)
            }
        }

        private fun expectLit(lit: String) {
            if (s.regionMatches(pos, lit, 0, lit.length)) pos += lit.length
        }

        private fun parseObject(): Map<String, Any?> {
            val map = LinkedHashMap<String, Any?>()
            pos++ // {
            skipWs()
            if (pos < s.length && s[pos] == '}') { pos++; return map }
            while (pos < s.length) {
                skipWs()
                val key = when {
                    pos < s.length && (s[pos] == '"' || s[pos] == '\'') -> parseString(s[pos])
                    else -> parseIdentifier()
                } ?: ""
                skipWs()
                if (pos < s.length && s[pos] == ':') pos++
                val value = parseValue()
                map[key] = value
                skipWs()
                if (pos < s.length && s[pos] == ',') { pos++; continue }
                if (pos < s.length && s[pos] == '}') { pos++; break }
                break
            }
            return map
        }

        private fun parseArray(): List<Any?> {
            val list = ArrayList<Any?>()
            pos++ // [
            skipWs()
            if (pos < s.length && s[pos] == ']') { pos++; return list }
            while (pos < s.length) {
                val value = parseValue()
                list.add(value)
                skipWs()
                if (pos < s.length && s[pos] == ',') { pos++; continue }
                if (pos < s.length && s[pos] == ']') { pos++; break }
                break
            }
            return list
        }

        private fun parseString(quote: Char): String {
            pos++ // opening quote
            val sb = StringBuilder()
            while (pos < s.length) {
                val c = s[pos]
                when {
                    c == quote -> { pos++; return sb.toString() }
                    c == '\\' && pos + 1 < s.length -> {
                        pos++
                        when (val e = s[pos]) {
                            'n' -> sb.append('\n')
                            't' -> sb.append('\t')
                            'r' -> sb.append('\r')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'u' -> {
                                if (pos + 4 < s.length) {
                                    val hex = s.substring(pos + 1, pos + 5)
                                    hex.toIntOrNull(16)?.let { sb.append(it.toChar()) }
                                    pos += 4
                                }
                            }
                            else -> sb.append(e)
                        }
                        pos++
                    }
                    else -> { sb.append(c); pos++ }
                }
            }
            return sb.toString()
        }

        private fun parseIdentifier(): String? {
            val start = pos
            while (pos < s.length) {
                val c = s[pos]
                if (c.isLetterOrDigit() || c == '_' || c == '$' || c == '-' || c == '.') pos++ else break
            }
            return if (pos > start) s.substring(start, pos) else null
        }

        private fun parseNumberOrWord(first: Char): Any? {
            val start = pos
            if (first == '-' || first == '+') pos++
            if (pos + 1 < s.length && s[pos] == '0' && (s[pos + 1] == 'x' || s[pos + 1] == 'X')) {
                pos += 2
                val hexStart = pos
                while (pos < s.length && s[pos].isDigit() || pos < s.length && s[pos].lowercaseChar() in 'a'..'f') pos++
                return s.substring(hexStart, pos).toLongOrNull(16)
            }
            while (pos < s.length) {
                val c = s[pos]
                if (c.isDigit() || c == '.' || c == 'e' || c == 'E' || c == '-' || c == '+') pos++ else break
            }
            val num = s.substring(start, pos)
            if (num.isEmpty()) { pos = start + 1; return null }
            num.toLongOrNull()?.let { return if (it in Int.MIN_VALUE..Int.MAX_VALUE) it.toInt() else it }
            num.toDoubleOrNull()?.let { return it }
            return num
        }
    }
}
