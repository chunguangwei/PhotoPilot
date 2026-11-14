package dev.photopilot.scaffold.data.aiframes

/** Cased BERT normalization and WordPiece for the pinned multilingual CLIP vocabulary. */
class WordPieceTokenizer(vocabulary: List<String>) {
    private val vocabulary = vocabulary.withIndex().associate { it.value to it.index.toLong() }
    private val special = listOf("[PAD]", "[UNK]", "[CLS]", "[SEP]", "[MASK]")

    init {
        require(this.vocabulary["[PAD]"] == 0L && this.vocabulary["[UNK]"] == 100L)
        require(this.vocabulary["[CLS]"] == 101L && this.vocabulary["[SEP]"] == 102L && this.vocabulary["[MASK]"] == 103L)
    }

    fun encode(text: String): LongArray {
        val output = mutableListOf(101L)
        val chunk = StringBuilder()
        fun flushChunk() {
            basicTokens(chunk.toString()).forEach { output.addAll(wordPieces(it)) }
            chunk.clear()
        }
        var offset = 0
        while (offset < text.length) {
            val token = special.firstOrNull { text.startsWith(it, offset) }
            if (token != null) {
                flushChunk()
                output.add(requireNotNull(vocabulary[token]))
                offset += token.length
            } else {
                val codePoint = text.codePointAt(offset)
                chunk.appendCodePoint(codePoint)
                offset += Character.charCount(codePoint)
            }
        }
        flushChunk()
        require(output.size < 128) { "description_too_long" }
        output.add(102L)
        return output.toLongArray()
    }

    private fun basicTokens(text: String): List<String> {
        val result = mutableListOf<String>()
        val word = StringBuilder()
        fun flushWord() {
            if (word.isNotEmpty()) {
                result.add(word.toString())
                word.clear()
            }
        }
        for (codePoint in text.codePoints().toArray()) {
            when {
                codePoint == 0 || codePoint == 0xFFFD || isControl(codePoint) -> Unit
                Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint) -> flushWord()
                isChinese(codePoint) || isPunctuation(codePoint) -> {
                    flushWord()
                    result.add(String(Character.toChars(codePoint)))
                }
                else -> word.appendCodePoint(codePoint)
            }
        }
        flushWord()
        return result
    }

    private fun wordPieces(word: String): List<Long> {
        val points = word.codePoints().toArray()
        if (points.size > 100) return listOf(100L)
        val result = mutableListOf<Long>()
        var start = 0
        while (start < points.size) {
            var end = points.size
            var found: Long? = null
            while (end > start) {
                val candidate = (if (start == 0) "" else "##") + String(points, start, end - start)
                found = vocabulary[candidate]
                if (found != null) break
                end--
            }
            if (found == null) return listOf(100L)
            result.add(found)
            start = end
        }
        return result
    }

    private fun isControl(codePoint: Int): Boolean {
        if (codePoint == 9 || codePoint == 10 || codePoint == 13) return false
        return Character.getType(codePoint) in setOf(
            Character.CONTROL.toInt(), Character.FORMAT.toInt(), Character.PRIVATE_USE.toInt(),
            Character.SURROGATE.toInt(), Character.UNASSIGNED.toInt(),
        )
    }

    private fun isPunctuation(codePoint: Int): Boolean {
        if (codePoint in 33..47 || codePoint in 58..64 || codePoint in 91..96 || codePoint in 123..126) return true
        return Character.getType(codePoint) in setOf(
            Character.CONNECTOR_PUNCTUATION.toInt(), Character.DASH_PUNCTUATION.toInt(),
            Character.START_PUNCTUATION.toInt(), Character.END_PUNCTUATION.toInt(),
            Character.INITIAL_QUOTE_PUNCTUATION.toInt(), Character.FINAL_QUOTE_PUNCTUATION.toInt(),
            Character.OTHER_PUNCTUATION.toInt(),
        )
    }

    private fun isChinese(codePoint: Int): Boolean =
        codePoint in 0x4E00..0x9FFF || codePoint in 0x3400..0x4DBF ||
            codePoint in 0x20000..0x2A6DF || codePoint in 0x2A700..0x2B73F ||
            codePoint in 0x2B740..0x2B81F || codePoint in 0x2B820..0x2CEAF ||
            codePoint in 0xF900..0xFAFF || codePoint in 0x2F800..0x2FA1F
}
