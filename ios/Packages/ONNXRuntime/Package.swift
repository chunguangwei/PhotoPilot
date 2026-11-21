// swift-tools-version: 5.8
// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.
// Localized vendored snapshot of ONNX Runtime iOS 1.30.0.
// The upstream GitHub repository microsoft/onnxruntime (and its Swift Package
// Manager distribution, microsoft/onnxruntime-swift-package-manager) was
// deleted in 2026, so the exactVersion 1.20.0 package can no longer be
// resolved. PhotoPilot historically imported this package's product
// "onnxruntime" and its Swift module "OnnxRuntimeBindings" (the Objective-C
// wrapper around the ONNX Runtime C++ API). This local package reproduces that
// shape from the official CocoaPods 1.30.0 distribution. See UPSTREAM.txt.
import PackageDescription

let package = Package(
    name: "ONNXRuntime",
    platforms: [.iOS(.v15)],
    products: [
        .library(name: "onnxruntime", targets: ["OnnxRuntimeBindings"])
    ],
    targets: [
        .binaryTarget(
            name: "onnxruntime",
            path: "onnxruntime-ios.xcframework"
        ),
        .target(
            name: "OnnxRuntimeBindings",
            dependencies: ["onnxruntime"],
            path: "Sources/OnnxRuntimeBindings",
            publicHeadersPath: "include",
            cSettings: [
                .define("SPM_BUILD"),
                .headerSearchPath("include"),
                .unsafeFlags(["-std=gnu++17"])
            ]
        )
    ]
)
