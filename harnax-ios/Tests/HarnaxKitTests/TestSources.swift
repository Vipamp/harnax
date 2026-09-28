import Foundation

/// Locates the package sources from the compiled-in test path so the source gates walk real files.
enum TestSources {
    static let packageRoot: URL = {
        let file = URL(fileURLWithPath: #filePath)
        // .../harnax-ios/Tests/HarnaxKitTests/<this>.swift
        return file.deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
    }()

    static let sourcesRoot = packageRoot.appendingPathComponent("Sources")

    static func swiftFiles() -> [URL] {
        let fm = FileManager.default
        guard let walker = fm.enumerator(at: sourcesRoot, includingPropertiesForKeys: [.isRegularFileKey]) else {
            return []
        }
        return walker.compactMap { item in
            guard let url = item as? URL, url.pathExtension == "swift" else { return nil }
            return url
        }
        .sorted { $0.path < $1.path }
    }

    /// `Sources/HarnaxKit/Theme/Palette.swift` -> `HarnaxKit/Theme/Palette.swift`
    static func relative(_ url: URL) -> String {
        url.path.replacingOccurrences(of: sourcesRoot.path + "/", with: "")
    }

    static func contents(of url: URL) throws -> String {
        try String(contentsOf: url, encoding: .utf8)
    }
}
