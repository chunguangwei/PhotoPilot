// swift-tools-version: 5.8
// Copyright 2026 The MediaPipe Authors. Licensed under Apache-2.0.
// Adapted from revision 02ca022d573c004c463d8c61eb3f8ce17e95a7fc.
// This app uses UniversalEmbedder's direct LiteRT-LM path. The general graph
// calculator registration flag and unrelated audio/decision/text/vision products
// are omitted. A small, reproducible category artifact retains required helpers.
import PackageDescription

let package = Package(
    name: "MediaPipeTasks",
    platforms: [.iOS(.v15)],
    products: [
        .library(name: "MediaPipeTasksRetrieval", targets: ["MediaPipeTasksRetrieval", "MediaPipeTasksCommon"])
    ],
    targets: [
        .binaryTarget(
            name: "MediaPipeTasksCommonBinary",
            url: "https://dl.google.com/cpdc/20261111-125016/MediaPipeTasksCommon-1.2.3.xcframework.zip",
            checksum: "39990d7ff26dedeef5e0f43e7a8656ead9219b52133ae390af5e9ffcfa208b1f"
        ),
        .binaryTarget(
            name: "MediaPipeTaskGraphsBinary",
            url: "https://dl.google.com/cpdc/20261111-125016/MediaPipeTaskGraphs-1.2.3.xcframework.zip",
            checksum: "706d2c894767e281ac5c02c27f1f4bba3c1f88b25a1a0ef51d134685ae30e05c"
        ),
        .binaryTarget(
            name: "MediaPipeTasksRetrieval",
            url: "https://dl.google.com/cpdc/20261111-125016/MediaPipeTasksRetrieval-1.2.3.xcframework.zip",
            checksum: "64116abf9b5e03305118b7cb9f575823b6f31752ec6cd98d409bfab6d936f4b8"
        ),
        .binaryTarget(name: "MediaPipeRequiredCategories", path: "MediaPipeRequiredCategories.xcframework"),
        .target(name: "MediaPipeCategoryLinkage", dependencies: ["MediaPipeRequiredCategories"], publicHeadersPath: "include"),
        .target(
            name: "MediaPipeTasksCommon",
            dependencies: ["MediaPipeTasksCommonBinary", "MediaPipeTaskGraphsBinary", "MediaPipeTasksRetrieval", "MediaPipeCategoryLinkage"],
            resources: [.copy("PrivacyInfo.xcprivacy")],
            linkerSettings: [
                .linkedLibrary("c++"), .linkedLibrary("sqlite3"),
                .linkedFramework("Accelerate"), .linkedFramework("AVFoundation"),
                .linkedFramework("CoreMedia"), .linkedFramework("AudioToolbox"),
                .linkedFramework("CoreGraphics"), .linkedFramework("CoreImage"),
                .linkedFramework("CoreVideo"), .linkedFramework("QuartzCore")
            ]
        )
    ]
)
