import Foundation

struct FitRect: Sendable, Equatable {
    let x: Int64
    let y: Int64
    let width: Int64
    let height: Int64
}

enum RenderGeometry {
    static let blurBackgroundLongEdge: Int64 = 256

    static func aspectFit(srcW: Int64, srcH: Int64, dstW: Int64, dstH: Int64) -> FitRect {
        scaleRect(srcW: srcW, srcH: srcH, dstW: dstW, dstH: dstH, scale: min(
            Double(dstW) / Double(srcW),
            Double(dstH) / Double(srcH)
        ))
    }

    static func aspectFill(srcW: Int64, srcH: Int64, dstW: Int64, dstH: Int64) -> FitRect {
        scaleRect(srcW: srcW, srcH: srcH, dstW: dstW, dstH: dstH, scale: max(
            Double(dstW) / Double(srcW),
            Double(dstH) / Double(srcH)
        ))
    }

    private static func scaleRect(srcW: Int64, srcH: Int64, dstW: Int64, dstH: Int64, scale: Double) -> FitRect {
        let w = Int64((Double(srcW) * scale).rounded())
        let h = Int64((Double(srcH) * scale).rounded())
        return FitRect(x: (dstW - w) / 2, y: (dstH - h) / 2, width: w, height: h)
    }

    static func decodeTarget(viewW: Int64, viewH: Int64, scale: Int64, cap: Int64) -> (Int64, Int64) {
        var w = viewW * scale
        var h = viewH * scale
        let longest = max(w, h)
        if longest > cap {
            w = Int64((Double(w) * Double(cap) / Double(longest)).rounded(.down))
            h = Int64((Double(h) * Double(cap) / Double(longest)).rounded(.down))
        }
        return (w, h)
    }

    static func blurBackgroundSize(srcW: Int64, srcH: Int64) -> (Int64, Int64) {
        let longest = max(srcW, srcH)
        if longest <= blurBackgroundLongEdge { return (srcW, srcH) }
        let scale = Double(blurBackgroundLongEdge) / Double(longest)
        return (
            max(1, Int64((Double(srcW) * scale).rounded(.down))),
            max(1, Int64((Double(srcH) * scale).rounded(.down)))
        )
    }

    static func cacheKey(
        assetId: String,
        assetVersion: Int64,
        viewportW: Int64,
        viewportH: Int64,
        orientation: String,
        fitMode: String,
        qualityLevel: String
    ) -> String {
        "\(assetId):\(assetVersion):\(viewportW)x\(viewportH):\(orientation):\(fitMode):\(qualityLevel)"
    }

    static func edgeColor(pixels: [UInt32], width: Int, height: Int) -> String {
        precondition(pixels.count == width * height, "pixel count mismatch")
        var count: Int64 = 0
        var sumR: Int64 = 0
        var sumG: Int64 = 0
        var sumB: Int64 = 0
        for y in 0..<height {
            for x in 0..<width {
                let isEdge = y == 0 || y == height - 1 || x == 0 || x == width - 1
                guard isEdge else { continue }
                let pixel = pixels[y * width + x]
                sumR += Int64((pixel >> 16) & 0xFF)
                sumG += Int64((pixel >> 8) & 0xFF)
                sumB += Int64(pixel & 0xFF)
                count += 1
            }
        }
        precondition(count > 0, "no edge pixels")
        let r = Int64((Double(sumR) / Double(count)).rounded())
        let g = Int64((Double(sumG) / Double(count)).rounded())
        let b = Int64((Double(sumB) / Double(count)).rounded())
        return String(format: "#%02X%02X%02X", r, g, b)
    }
}
