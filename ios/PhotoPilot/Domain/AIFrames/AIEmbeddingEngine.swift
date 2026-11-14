import CoreGraphics
import Foundation
import ImageIO
import MediaPipeTasksRetrieval
import MediaPipeCategoryLinkage
import OnnxRuntimeBindings
import UniformTypeIdentifiers

/// Each instance owns its native sessions. Actor isolation bounds inference to one image at a time.
actor AIEmbeddingEngine {
    private let gemma: UniversalEmbedder?
    private let ortEnvironment: ORTEnv?
    private let vision: ORTSession?
    private let text: ORTSession?
    private let projection: AIClipProjection?
    private let dimension: Int
    private let tokenizer: AIWordPieceTokenizer?
    private var textCache: [Data: [Float]] = [:]

    init(model: AIModelDescriptor, directory: URL, maximumTextLength: Int = 2048) throws {
        PhotoPilotLoadMediaPipeCategories()
        dimension = model.embeddingDimension
        guard let file = model.files.first(where: { $0.role == "model" }) else { throw AIFrameError.invalidManifest }
        let path = directory.appendingPathComponent(file.name).path
        if model.runtime == "litertlm" {
            let options = UniversalEmbedderOptions()
            options.baseOptions.modelAssetPath = path
            options.maxInputLength = maximumTextLength
            options.l2Normalize = true
            gemma = try UniversalEmbedder(options: options)
            ortEnvironment = nil
            vision = nil
            text = nil
            projection = nil
            tokenizer = nil
        } else {
            guard model.embeddingDimension == 512,
                  let tokenizerFile = model.files.first(where: { $0.role == "tokenizer" }),
                  let textFile = model.files.first(where: { $0.role == "text" }),
                  let projectionFile = model.files.first(where: { $0.role == "projection" }) else { throw AIFrameError.invalidManifest }
            let environment = try ORTEnv(loggingLevel: .warning)
            let options = try ORTSessionOptions()
            try options.setIntraOpNumThreads(2)
            try options.setGraphOptimizationLevel(.all)
            ortEnvironment = environment
            vision = try ORTSession(env: environment, modelPath: path, sessionOptions: options)
            text = try ORTSession(env: environment, modelPath: directory.appendingPathComponent(textFile.name).path, sessionOptions: options)
            tokenizer = try AIWordPieceTokenizer(vocabulary: String(contentsOf: directory.appendingPathComponent(tokenizerFile.name), encoding: .utf8))
            projection = try AIClipProjection(data: Data(contentsOf: directory.appendingPathComponent(projectionFile.name)))
            gemma = nil
        }
    }

    func encode(text: String) throws -> [Float] {
        try Task.checkCancellation()
        let cacheKey = Data(text.utf8)
        if let cached = textCache[cacheKey] { return cached }
        let vector: [Float]
        if let gemma {
            guard text.utf8.count <= 2000 else { throw AIFrameError.promptTooLong }
            do { vector = try Self.vector(gemma.embed(text: text)) }
            catch {
                let description = error.localizedDescription.lowercased()
                if ["token", "sequence", "input length", "too long"].contains(where: { description.contains($0) }) {
                    throw AIFrameError.promptTooLong
                }
                throw error
            }
        } else {
            guard let session = self.text, let tokenizer, let projection else { throw AIFrameError.missingModel }
            var ids = try tokenizer.encode(text)
            var mask = [Int64](repeating: 1, count: ids.count)
            let idsData = ids.withUnsafeMutableBytes { NSMutableData(bytes: $0.baseAddress!, length: $0.count) }
            let maskData = mask.withUnsafeMutableBytes { NSMutableData(bytes: $0.baseAddress!, length: $0.count) }
            let inputs = [
                "input_ids": try ORTValue(tensorData: idsData, elementType: .int64, shape: [1, NSNumber(value: ids.count)]),
                "attention_mask": try ORTValue(tensorData: maskData, elementType: .int64, shape: [1, NSNumber(value: ids.count)])
            ]
            let hidden = try Self.run(session, inputs: inputs, output: "last_hidden_state")
            vector = try projection.project(hidden: hidden, tokenCount: ids.count)
        }
        guard vector.count == dimension else { throw AIFrameError.invalidVector }
        _ = try AIFrameScoring.cosine(vector, vector)
        if textCache.count >= 128 { textCache.removeAll(keepingCapacity: true) }
        textCache[cacheKey] = vector
        return vector
    }

    func encode(imageURL: URL) throws -> [Float] {
        try Task.checkCancellation()
        let vector: [Float]
        if let gemma {
            let image = try Self.decodedImage(imageURL, maximumSize: 1024)
            let data = NSMutableData()
            guard let destination = CGImageDestinationCreateWithData(data, UTType.jpeg.identifier as CFString, 1, nil) else {
                throw AIFrameError.invalidVector
            }
            CGImageDestinationAddImage(destination, image, [kCGImageDestinationLossyCompressionQuality: 0.9] as CFDictionary)
            guard CGImageDestinationFinalize(destination) else { throw AIFrameError.invalidVector }
            vector = try Self.vector(gemma.embed(imageBytes: data as Data))
        } else {
            guard let vision else { throw AIFrameError.missingModel }
            var pixels = try Self.clipPixels(imageURL)
            let data = pixels.withUnsafeMutableBytes { NSMutableData(bytes: $0.baseAddress!, length: $0.count) }
            vector = try Self.run(vision, inputs: ["pixel_values": ORTValue(tensorData: data, elementType: .float, shape: [1, 3, 224, 224])], output: "image_embeds")
        }
        guard vector.count == dimension else { throw AIFrameError.invalidVector }
        _ = try AIFrameScoring.cosine(vector, vector)
        return vector
    }

    private static func vector(_ result: EmbeddingResult) throws -> [Float] {
        guard let numbers = result.embeddings.first?.floatEmbedding, !numbers.isEmpty else { throw AIFrameError.invalidVector }
        return numbers.map(\.floatValue)
    }

    private static func run(_ session: ORTSession, inputs: [String: ORTValue], output: String) throws -> [Float] {
        let results = try session.run(withInputs: inputs, outputNames: [output], runOptions: nil)
        guard let result = results[output] else { throw AIFrameError.invalidVector }
        let info = try result.tensorTypeAndShapeInfo()
        guard info.elementType == .float else { throw AIFrameError.invalidVector }
        let data = try result.tensorData() as Data
        guard !data.isEmpty, data.count.isMultiple(of: MemoryLayout<Float>.size) else { throw AIFrameError.invalidVector }
        return data.withUnsafeBytes { Array($0.bindMemory(to: Float.self)) }
    }

    private static func decodedImage(_ url: URL, maximumSize: Int) throws -> CGImage {
        guard let source = CGImageSourceCreateWithURL(url as CFURL, nil),
              let image = CGImageSourceCreateThumbnailAtIndex(source, 0, [
                kCGImageSourceCreateThumbnailFromImageAlways: true,
                kCGImageSourceCreateThumbnailWithTransform: true,
                kCGImageSourceThumbnailMaxPixelSize: maximumSize,
                kCGImageSourceShouldCacheImmediately: true
              ] as CFDictionary) else { throw AIFrameError.invalidVector }
        return image
    }

    private static func clipPixels(_ url: URL) throws -> [Float] {
        let image = try decodedImage(url, maximumSize: 4096)
        let side = 224
        var rgba = [UInt8](repeating: 0, count: side * side * 4)
        let drawn = rgba.withUnsafeMutableBytes { buffer -> Bool in
            guard let context = CGContext(data: buffer.baseAddress, width: side, height: side,
                                          bitsPerComponent: 8, bytesPerRow: side * 4,
                                          space: CGColorSpaceCreateDeviceRGB(),
                                          bitmapInfo: CGBitmapInfo.byteOrder32Big.rawValue | CGImageAlphaInfo.premultipliedLast.rawValue) else { return false }
            context.interpolationQuality = .high
            context.setFillColor(CGColor(gray: 1, alpha: 1))
            context.fill(CGRect(x: 0, y: 0, width: side, height: side))
            // CLIP resizes its shorter edge before taking a centered square crop.
            let scale = Double(side) / Double(min(image.width, image.height))
            let width = floor(Double(image.width) * scale)
            let height = floor(Double(image.height) * scale)
            let left = floor((width - Double(side)) / 2)
            let top = floor((height - Double(side)) / 2)
            context.draw(image, in: CGRect(x: -left, y: -top, width: width, height: height))
            return true
        }
        guard drawn else { throw AIFrameError.invalidVector }
        let mean: [Float] = [0.48145466, 0.4578275, 0.40821073]
        let deviation: [Float] = [0.26862954, 0.26130258, 0.27577711]
        var pixels = [Float](repeating: 0, count: 3 * side * side)
        for pixel in 0..<(side * side) {
            for channel in 0..<3 {
                pixels[channel * side * side + pixel] = (Float(rgba[pixel * 4 + channel]) / 255 - mean[channel]) / deviation[channel]
            }
        }
        return pixels
    }
}
