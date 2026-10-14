import XCTest
@testable import PhotoPilot

final class PhotoPilotRenderTests: XCTestCase {

    private func loadVector(_ name: String) throws -> [String: Any] {
        let url = try XCTUnwrap(
            Bundle(for: type(of: self)).url(forResource: name, withExtension: "json"),
            "向量文件未入测试 bundle：\(name).json"
        )
        let data = try Data(contentsOf: url)
        return try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
    }

    private func int64Pair(_ vectorCase: [String: Any], key: String) throws -> (Int64, Int64) {
        let array = try XCTUnwrap(vectorCase[key] as? [NSNumber])
        return (array[0].int64Value, array[1].int64Value)
    }

    func testRenderGeometryContractVectors() throws {
        let cases = try XCTUnwrap((try loadVector("render-geometry"))["cases"] as? [[String: Any]])
        XCTAssertGreaterThanOrEqual(cases.count, 15)
        for vectorCase in cases {
            let id = try XCTUnwrap(vectorCase["id"] as? String)
            switch try XCTUnwrap(vectorCase["scenario"] as? String) {
            case "aspect_fit", "aspect_fill":
                let (srcW, srcH) = try int64Pair(vectorCase, key: "src")
                let (dstW, dstH) = try int64Pair(vectorCase, key: "dst")
                let rect = (vectorCase["scenario"] as? String) == "aspect_fit"
                    ? RenderGeometry.aspectFit(srcW: srcW, srcH: srcH, dstW: dstW, dstH: dstH)
                    : RenderGeometry.aspectFill(srcW: srcW, srcH: srcH, dstW: dstW, dstH: dstH)
                let expected = try XCTUnwrap(vectorCase["expected"] as? [NSNumber])
                XCTAssertEqual(FitRect(
                    x: expected[0].int64Value,
                    y: expected[1].int64Value,
                    width: expected[2].int64Value,
                    height: expected[3].int64Value
                ), rect, id)
            case "decode_target":
                let (viewW, viewH) = try int64Pair(vectorCase, key: "view")
                let actual = RenderGeometry.decodeTarget(
                    viewW: viewW,
                    viewH: viewH,
                    scale: try XCTUnwrap((vectorCase["scale"] as? NSNumber)?.int64Value),
                    cap: try XCTUnwrap((vectorCase["cap"] as? NSNumber)?.int64Value)
                )
                let expected = try int64Pair(vectorCase, key: "expected")
                XCTAssertEqual(expected.0, actual.0, id)
                XCTAssertEqual(expected.1, actual.1, id)
            case "blur_background":
                let (srcW, srcH) = try int64Pair(vectorCase, key: "src")
                let actual = RenderGeometry.blurBackgroundSize(srcW: srcW, srcH: srcH)
                let expected = try int64Pair(vectorCase, key: "expected")
                XCTAssertEqual(expected.0, actual.0, id)
                XCTAssertEqual(expected.1, actual.1, id)
            case "cache_key":
                let (viewportW, viewportH) = try int64Pair(vectorCase, key: "viewport")
                XCTAssertEqual(
                    try XCTUnwrap(vectorCase["expected"] as? String),
                    RenderGeometry.cacheKey(
                        assetId: try XCTUnwrap(vectorCase["asset_id"] as? String),
                        assetVersion: try XCTUnwrap((vectorCase["asset_version"] as? NSNumber)?.int64Value),
                        viewportW: viewportW,
                        viewportH: viewportH,
                        orientation: try XCTUnwrap(vectorCase["orientation"] as? String),
                        fitMode: try XCTUnwrap(vectorCase["fit_mode"] as? String),
                        qualityLevel: try XCTUnwrap(vectorCase["quality_level"] as? String)
                    ),
                    id
                )
            case "edge_color":
                let pixels = try XCTUnwrap(vectorCase["pixels"] as? [NSNumber]).map { $0.uint32Value }
                XCTAssertEqual(
                    try XCTUnwrap(vectorCase["expected"] as? String),
                    RenderGeometry.edgeColor(
                        pixels: pixels,
                        width: try XCTUnwrap((vectorCase["width"] as? NSNumber)?.intValue),
                        height: try XCTUnwrap((vectorCase["height"] as? NSNumber)?.intValue)
                    ),
                    id
                )
            default:
                XCTFail("\(id): unknown scenario")
            }
        }
    }
}
