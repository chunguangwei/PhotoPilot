package dev.photopilot.scaffold.data.aiframes

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/** SentencePiece BPE for the pinned SigLIP 2 identity-normalized, byte-fallback vocabulary. */
class SentencePieceTokenizer(model: ByteArray) {
    private data class Piece(val id: Int, val score: Float, val type: Int)
    private var userDefined = emptyList<String>()
    private val pieces = HashMap<String, Piece>(300000)
    init {
        val reader = Proto(model)
        var id = 0
        while (reader.remaining()) {
            val tag = reader.varint().toInt()
            if (tag == 10) {
                val entry = Proto(reader.bytes())
                var text = ""
                var score = 0f
                var type = 1
                while (entry.remaining()) {
                    val field = entry.varint().toInt()
                    when (field) {
                        10 -> text = entry.bytes().toString(Charsets.UTF_8)
                        21 -> score = entry.float()
                        24 -> type = entry.varint().toInt()
                        else -> entry.skip(field and 7)
                    }
                }
                pieces[text] = Piece(id++, score, type)
            } else reader.skip(tag and 7)
        }
        userDefined = pieces.filterValues { it.type == 4 }.keys.sortedByDescending { it.length }
        require(pieces.size == 256000 && pieces["<eos>"]?.id == 1 && pieces["<pad>"]?.id == 0)
    }
    fun encode(text: String, length: Int): LongArray {
        require(length >= 2)
        val ids = raw(text.lowercase(Locale.ROOT))
        require(ids.size < length) { "description_too_long" }
        return LongArray(length).also { output ->
            ids.forEachIndexed { index, id -> output[index] = id }
            output[minOf(ids.size, length - 1)] = 1
        }
    }
    fun raw(text: String): LongArray {
        val normalized = text.replace(" ", "▁")
        val symbols = mutableListOf<String>()
        var position = 0
        while (position < normalized.length) {
            val special = userDefined.firstOrNull { normalized.startsWith(it, position) }
            val symbol = special ?: String(Character.toChars(normalized.codePointAt(position)))
            symbols.add(symbol)
            position += symbol.length
        }
        while (symbols.size > 1) {
            var bestIndex = -1
            var bestScore = Float.NEGATIVE_INFINITY
            for (index in 0 until symbols.size - 1) {
                val piece = pieces[symbols[index] + symbols[index + 1]] ?: continue
                if (piece.type == 1 && piece.score > bestScore) { bestIndex = index; bestScore = piece.score }
            }
            if (bestIndex < 0) break
            symbols[bestIndex] += symbols.removeAt(bestIndex + 1)
        }
        return symbols.flatMap { symbol ->
            val piece = pieces[symbol]
            if (piece != null && piece.type in listOf(1, 4)) listOf(piece.id.toLong()) else symbol.toByteArray(Charsets.UTF_8).map { byte ->
                requireNotNull(pieces["<0x%02X>".format(byte.toInt() and 255)]).id.toLong()
            }
        }.toLongArray()
    }
    private class Proto(private val data: ByteArray) {
        private var position = 0
        fun remaining() = position < data.size
        fun varint(): Long {
            var value = 0L
            var shift = 0
            while (shift < 64) {
                require(position < data.size)
                val byte = data[position++].toInt() and 255
                value = value or ((byte and 127).toLong() shl shift)
                if (byte and 128 == 0) return value
                shift += 7
            }
            error("protobuf")
        }
        fun bytes(): ByteArray {
            val length = varint().toInt()
            require(length >= 0 && position + length <= data.size)
            return data.copyOfRange(position, position + length).also { position += length }
        }
        fun float(): Float {
            require(position + 4 <= data.size)
            return ByteBuffer.wrap(data, position, 4).order(ByteOrder.LITTLE_ENDIAN).float.also { position += 4 }
        }
        fun skip(wire: Int) { when (wire) { 0 -> varint(); 1 -> position += 8; 2 -> { val length = varint().toInt(); position += length }; 5 -> position += 4; else -> error("protobuf") }; require(position <= data.size) }
    }
}
