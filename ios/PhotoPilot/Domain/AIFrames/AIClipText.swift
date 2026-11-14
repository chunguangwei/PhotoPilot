import Accelerate
import Foundation

/// The cased multilingual BERT tokenizer used by the pinned CLIP text encoder.
struct AIWordPieceTokenizer {
    // Byte keys preserve the model's distinction between composed and decomposed accents.
    private let vocabulary: [Data: Int64]
    private let specials = ["[PAD]", "[UNK]", "[CLS]", "[SEP]", "[MASK]"]

    init(vocabulary text: String) throws {
        let words = text.components(separatedBy: "\n")
        var vocabulary: [Data: Int64] = [:]
        for (index, word) in words.enumerated() where !word.isEmpty { vocabulary[Data(word.utf8)] = Int64(index) }
        guard vocabulary[Data("[PAD]".utf8)] == 0, vocabulary[Data("[UNK]".utf8)] == 100,
              vocabulary[Data("[CLS]".utf8)] == 101, vocabulary[Data("[SEP]".utf8)] == 102,
              vocabulary[Data("[MASK]".utf8)] == 103 else { throw AIFrameError.invalidManifest }
        self.vocabulary = vocabulary
    }

    func encode(_ text: String) throws -> [Int64] {
        var result: [Int64] = [101]
        var remainder = text[...]
        while !remainder.isEmpty {
            let next = specials.compactMap { special -> (Range<String.Index>, String)? in
                remainder.range(of: special).map { ($0, special) }
            }.min { $0.0.lowerBound < $1.0.lowerBound }
            if let (range, special) = next {
                result += pieces(String(remainder[..<range.lowerBound]))
                result.append(vocabulary[Data(special.utf8)]!)
                remainder = remainder[range.upperBound...]
            } else {
                result += pieces(String(remainder))
                remainder = remainder[remainder.endIndex...]
            }
            guard result.count <= 127 else { throw AIFrameError.promptTooLong }
        }
        result.append(102)
        return result
    }

    private func pieces(_ text: String) -> [Int64] {
        var words: [String] = []
        var current = ""
        for scalar in text.unicodeScalars {
            let value = scalar.value
            if value == 0 || value == 0xFFFD { continue }
            if Self.isWhitespace(scalar) {
                if !current.isEmpty { words.append(current); current = "" }
            } else if [.control, .format, .privateUse, .surrogate, .unassigned].contains(scalar.properties.generalCategory) {
                continue
            } else if Self.isChinese(value) || Self.isPunctuation(scalar) {
                if !current.isEmpty { words.append(current); current = "" }
                words.append(String(scalar))
            } else { current.unicodeScalars.append(scalar) }
        }
        if !current.isEmpty { words.append(current) }
        return words.flatMap(wordPieces)
    }

    private func wordPieces(_ word: String) -> [Int64] {
        let scalars = Array(word.unicodeScalars)
        guard scalars.count <= 100 else { return [100] }
        var result: [Int64] = []
        var start = 0
        while start < scalars.count {
            var end = scalars.count
            var found: Int64?
            while end > start {
                let piece = (start > 0 ? "##" : "") + String(String.UnicodeScalarView(scalars[start..<end]))
                if let id = vocabulary[Data(piece.utf8)] { found = id; break }
                end -= 1
            }
            guard let found else { return [100] }
            result.append(found)
            start = end
        }
        return result
    }

    private static func isWhitespace(_ scalar: Unicode.Scalar) -> Bool {
        [9, 10, 13, 32].contains(scalar.value) || [.spaceSeparator, .lineSeparator, .paragraphSeparator].contains(scalar.properties.generalCategory)
    }

    private static func isChinese(_ value: UInt32) -> Bool {
        (0x4E00...0x9FFF).contains(value) || (0x3400...0x4DBF).contains(value)
            || (0x20000...0x2A6DF).contains(value) || (0x2A700...0x2B73F).contains(value)
            || (0x2B740...0x2B81F).contains(value) || (0x2B820...0x2CEAF).contains(value)
            || (0xF900...0xFAFF).contains(value) || (0x2F800...0x2FA1F).contains(value)
    }

    private static func isPunctuation(_ scalar: Unicode.Scalar) -> Bool {
        let value = scalar.value
        if (33...47).contains(value) || (58...64).contains(value) || (91...96).contains(value) || (123...126).contains(value) { return true }
        switch scalar.properties.generalCategory {
        case .connectorPunctuation, .dashPunctuation, .openPunctuation, .closePunctuation,
             .initialPunctuation, .finalPunctuation, .otherPunctuation: return true
        default: return false
        }
    }
}

struct AIClipProjection {
    private let weights: [Float]

    init(data: Data) throws {
        guard data.count >= 8 else { throw AIFrameError.invalidManifest }
        let headerLength = data.withUnsafeBytes { UInt64(littleEndian: $0.loadUnaligned(as: UInt64.self)) }
        guard headerLength > 0, headerLength <= 1_048_576, headerLength <= data.count - 8 else { throw AIFrameError.invalidManifest }
        let offset = 8 + Int(headerLength)
        guard let header = try JSONSerialization.jsonObject(with: data[8..<offset]) as? [String: Any],
              let tensor = header["linear.weight"] as? [String: Any], tensor["dtype"] as? String == "F32",
              tensor["shape"] as? [Int] == [512, 768], tensor["data_offsets"] as? [Int] == [0, 512 * 768 * 4],
              data.count - offset == 512 * 768 * 4 else { throw AIFrameError.invalidManifest }
        weights = data.withUnsafeBytes { buffer in
            (0..<(512 * 768)).map { Float(bitPattern: UInt32(littleEndian: buffer.loadUnaligned(fromByteOffset: offset + $0 * 4, as: UInt32.self))) }
        }
        guard weights.allSatisfy(\.isFinite) else { throw AIFrameError.invalidVector }
    }

    /// Every supplied token is attended. Inputs use dynamic sequence lengths without padding.
    func project(hidden: [Float], tokenCount: Int) throws -> [Float] {
        guard tokenCount > 0, tokenCount <= 128, hidden.count == tokenCount * 768,
              hidden.allSatisfy(\.isFinite) else { throw AIFrameError.invalidVector }
        var mean = [Float](repeating: 0, count: 768)
        for token in 0..<tokenCount {
            for feature in 0..<768 { mean[feature] += hidden[token * 768 + feature] / Float(tokenCount) }
        }
        var output = [Float](repeating: 0, count: 512)
        cblas_sgemv(CblasRowMajor, CblasNoTrans, 512, 768, 1, weights, 768, mean, 1, 0, &output, 1)
        let norm = sqrt(output.reduce(Double(0)) { $0 + Double($1) * Double($1) })
        guard norm.isFinite, norm > 0 else { throw AIFrameError.invalidVector }
        return output.map { Float(Double($0) / norm) }
    }
}
