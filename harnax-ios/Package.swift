// swift-tools-version:6.0
import PackageDescription

let package = Package(
    name: "Harnax",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [
        .library(name: "HarnaxCore", targets: ["HarnaxCore"]),
    ],
    targets: [
        .target(name: "HarnaxCore", swiftSettings: [.swiftLanguageMode(.v5)]),
        .testTarget(
            name: "HarnaxCoreTests",
            dependencies: ["HarnaxCore"],
            resources: [.copy("Fixtures")],
            swiftSettings: [.swiftLanguageMode(.v5)]
        ),
    ]
)
