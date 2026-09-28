// swift-tools-version:6.0
import PackageDescription

let package = Package(
    name: "Harnax",
    defaultLocalization: "en",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [
        .library(name: "HarnaxCore", targets: ["HarnaxCore"]),
        .library(name: "HarnaxAPI", targets: ["HarnaxAPI"]),
        .library(name: "HarnaxKit", targets: ["HarnaxKit"]),
        .library(name: "HarnaxFeatures", targets: ["HarnaxFeatures"]),
    ],
    targets: [
        .target(name: "HarnaxCore", swiftSettings: [.swiftLanguageMode(.v5)]),
        .testTarget(
            name: "HarnaxCoreTests",
            dependencies: ["HarnaxCore"],
            resources: [.copy("Fixtures")],
            swiftSettings: [.swiftLanguageMode(.v5)]
        ),
        .target(
            name: "HarnaxAPI",
            dependencies: ["HarnaxCore"],
            swiftSettings: [.swiftLanguageMode(.v5)]
        ),
        .testTarget(
            name: "HarnaxAPITests",
            dependencies: ["HarnaxAPI"],
            swiftSettings: [.swiftLanguageMode(.v5)]
        ),
        .target(
            name: "HarnaxKit",
            resources: [.process("Resources")],
            swiftSettings: [.swiftLanguageMode(.v5)]
        ),
        .testTarget(
            name: "HarnaxKitTests",
            dependencies: ["HarnaxKit"],
            swiftSettings: [.swiftLanguageMode(.v5)]
        ),
        .target(
            name: "HarnaxFeatures",
            dependencies: ["HarnaxCore", "HarnaxAPI", "HarnaxKit"],
            swiftSettings: [.swiftLanguageMode(.v5)]
        ),
        .testTarget(
            name: "HarnaxFeaturesTests",
            dependencies: ["HarnaxFeatures"],
            swiftSettings: [.swiftLanguageMode(.v5)]
        ),
    ]
)
