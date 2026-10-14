package dev.photopilot.scaffold.data.logic

object JsonMini {

    fun parseObject(json: String?): Map<String, Any?> {
        if (json.isNullOrBlank()) return emptyMap()
        return runCatching {
            Parser(json).parseRoot()
        }.getOrNull() as? Map<String, Any?> ?: emptyMap()
    }

    fun toJson(value: Any?): String = buildString { writeValue(value) }

    private fun StringBuilder.writeValue(value: Any?) {
        when (value) {
            null -> append("null")
            is String -> writeString(value)
            is Boolean -> append(value)
            is Int, is Long -> append(value)
            is Double -> {
                if (value == Math.floor(value) && !value.isInfinite() && kotlin.math.abs(value) < 1e15) {
                    append(value.toLong())
                } else {
                    append(value)
                }
            }
            is Map<*, *> -> {
                append('{')
                value.entries.forEachIndexed { index, entry ->
                    if (index > 0) append(',')
                    writeString(entry.key.toString())
                    append(':')
                    writeValue(entry.value)
                }
                append('}')
            }
            is List<*> -> {
                append('[')
                value.forEachIndexed { index, item ->
                    if (index > 0) append(',')
                    writeValue(item)
                }
                append(']')
            }
            else -> writeString(value.toString())
        }
    }

    private fun StringBuilder.writeString(value: String) {
        append('"')
        for (c in value) {
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(c)
            }
        }
        append('"')
    }

    private class Parser(private val source: String) {
        private var pos = 0

        fun parseRoot(): Any? {
            val value = parseValue()
            skipWhitespace()
            require(pos == source.length) { "trailing content" }
            return value
        }

        private fun skipWhitespace() {
            while (pos < source.length && source[pos].isWhitespace()) pos++
        }

        private fun parseValue(): Any? {
            skipWhitespace()
            require(pos < source.length) { "unexpected end" }
            return when (val c = source[pos]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> parseLiteral("true", true)
                'f' -> parseLiteral("false", false)
                'n' -> parseLiteral("null", null)
                '-', in '0'..'9' -> parseNumber()
                else -> throw IllegalArgumentException("unexpected char: $c")
            }
        }

        private fun parseObject(): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            pos++
            skipWhitespace()
            if (pos < source.length && source[pos] == '}') {
                pos++
                return out
            }
            while (true) {
                skipWhitespace()
                require(pos < source.length && source[pos] == '"') { "expected key" }
                val key = parseString()
                skipWhitespace()
                require(pos < source.length && source[pos] == ':') { "expected colon" }
                pos++
                out[key] = parseValue()
                skipWhitespace()
                require(pos < source.length) { "unterminated object" }
                when (source[pos]) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return out
                    }
                    else -> throw IllegalArgumentException("expected comma or brace")
                }
            }
        }

        private fun parseArray(): List<Any?> {
            val out = mutableListOf<Any?>()
            pos++
            skipWhitespace()
            if (pos < source.length && source[pos] == ']') {
                pos++
                return out
            }
            while (true) {
                out.add(parseValue())
                skipWhitespace()
                require(pos < source.length) { "unterminated array" }
                when (source[pos]) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return out
                    }
                    else -> throw IllegalArgumentException("expected comma or bracket")
                }
            }
        }

        private fun parseString(): String {
            val out = StringBuilder()
            pos++
            while (true) {
                require(pos < source.length) { "unterminated string" }
                when (val c = source[pos]) {
                    '"' -> {
                        pos++
                        return out.toString()
                    }
                    '\\' -> {
                        pos++
                        require(pos < source.length) { "bad escape" }
                        when (val esc = source[pos]) {
                            '"', '\\', '/' -> out.append(esc)
                            'n' -> out.append('\n')
                            't' -> out.append('\t')
                            'r' -> out.append('\r')
                            'b' -> out.append('\b')
                            'f' -> out.append('')
                            'u' -> {
                                require(pos + 4 < source.length) { "bad unicode escape" }
                                out.append(
                                    source.substring(pos + 1, pos + 5).toInt(16).toChar(),
                                )
                                pos += 4
                            }
                            else -> throw IllegalArgumentException("bad escape: $esc")
                        }
                        pos++
                    }
                    else -> {
                        out.append(c)
                        pos++
                    }
                }
            }
        }

        private fun parseNumber(): Any {
            val start = pos
            if (source[pos] == '-') pos++
            while (pos < source.length && (source[pos].isDigit() || source[pos] == '.' || source[pos] == 'e' || source[pos] == 'E' || source[pos] == '+' || source[pos] == '-')) {
                pos++
            }
            val text = source.substring(start, pos)
            return text.toDoubleOrNull() ?: throw IllegalArgumentException("bad number: $text")
        }

        private fun parseLiteral(literal: String, value: Any?): Any? {
            require(source.startsWith(literal, pos)) { "bad literal" }
            pos += literal.length
            return value
        }
    }
}
