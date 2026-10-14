import CoreGraphics
import CoreImage
import Foundation
import ImageIO
import UniformTypeIdentifiers

enum ImageRenderer {

    static func thumbnail(fileURL: URL, maxPixelWidth: Int, maxPixelHeight: Int) -> CGImage? {
        guard let source = CGImageSourceCreateWithURL(fileURL as CFURL, nil) else { return nil }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceShouldCacheImmediately: false,
            kCGImageSourceThumbnailMaxPixelSize: max(maxPixelWidth, maxPixelHeight),
        ]
        return CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary)
    }

    static func edgeColor(of image: CGImage) -> String? {
        let width = max(1, image.width / 8)
        let height = max(1, image.height / 8)
        guard let pixels = rasterizeARGB(image, width: width, height: height) else { return nil }
        return RenderGeometry.edgeColor(pixels: pixels, width: width, height: height)
    }

    static func blurredBackground(of image: CGImage, blurRadius: Double = 24) -> CGImage? {
        let (w, h) = RenderGeometry.blurBackgroundSize(srcW: Int64(image.width), srcH: Int64(image.height))
        guard let small = rasterize(image, width: Int(w), height: Int(h)) else { return nil }
        let context = CIContext()
        let input = CIImage(cgImage: small)
        guard let filter = CIFilter(name: "CIGaussianBlur") else { return nil }
        filter.setValue(input, forKey: kCIInputImageKey)
        filter.setValue(blurRadius, forKey: kCIInputRadiusKey)
        guard let output = filter.outputImage else { return nil }
        let cropped = output.cropped(to: CGRect(x: 0, y: 0, width: Int(w), height: Int(h)))
        return context.createCGImage(cropped, from: cropped.extent)
    }

    private static func rasterize(_ image: CGImage, width: Int, height: Int) -> CGImage? {
        guard let context = bitmapContext(width: width, height: height) else { return nil }
        context.interpolationQuality = .high
        context.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
        return context.makeImage()
    }

    private static func rasterizeARGB(_ image: CGImage, width: Int, height: Int) -> [UInt32]? {
        guard let context = bitmapContext(width: width, height: height) else { return nil }
        context.interpolationQuality = .high
        context.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
        guard let data = context.data else { return nil }
        let buffer = data.bindMemory(to: UInt32.self, capacity: width * height)
        return Array(UnsafeBufferPointer(start: buffer, count: width * height))
    }

    private static func bitmapContext(width: Int, height: Int) -> CGContext? {
        CGContext(
            data: nil,
            width: width,
            height: height,
            bitsPerComponent: 8,
            bytesPerRow: width * 4,
            space: CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: CGImageAlphaInfo.premultipliedFirst.rawValue | CGBitmapInfo.byteOrder32Little.rawValue
        )
    }
}
