import Foundation
import HarnaxCore
import SwiftUI
#if canImport(UIKit)
import UIKit
#endif

/// A file one of the two drawers pulled out of the server and is about to put in front of the user.
///
/// The bytes travel by value rather than as a URL because neither route ever writes a file: the console turns
/// the same response into a blob and an `<a download>` click (`WorkspaceDrawer.tsx:291-297`,
/// `TeamArtifactsDrawer.tsx:56-62`), and iOS' equivalent of that click is the share sheet.
public struct HXSharedFile: Equatable, Sendable {
    public let name: String
    public let data: Data
    public let mimeType: String?

    public init(name: String, data: Data, mimeType: String? = nil) {
        self.name = name
        self.data = data
        self.mimeType = mimeType
    }
}

/// The one place a sandbox or artifact byte ever leaves a view model.
///
/// Kept in its own file, and behind the same `canImport(UIKit) && os(iOS)` guard `HXPasteboard` uses
/// (`Sources/HarnaxFeatures/SystemDomain/HXPasteboard.swift:14`), because the test host is macOS 14 and
/// `UIActivityViewController` does not exist there. Both view models take this as an injected closure over
/// plain `Data`, so the behaviour a test cares about — *which name and which bytes a download hands over* —
/// is checked without a window to present in.
public enum HXFileShare {
    @Sendable
    public static func share(_ file: HXSharedFile) {
        #if canImport(UIKit) && os(iOS)
        guard let directory = Self.stagingDirectory(for: file.name) else { return }
        do {
            try file.data.write(to: directory, options: .atomic)
        } catch {
            // A byte that cannot be staged has nothing to share; the drawer keeps its listing either way.
            return
        }
        guard let sheet = Self.activityViewController(for: directory) else { return }
        Self.present(sheet)
        #endif
    }

    /// Writes into a directory of its own, one per share.
    ///
    /// The name the server suggested is client-influenced text — `Content-Disposition` repeats whatever the
    /// sandbox had — so it goes through the same rule the router applies before it puts a name in a path
    /// (`AgentProxyController.kt:268-273`): a `..` or a slash in that name would otherwise step outside the
    /// temporary directory.
    private static func stagingDirectory(for name: String) -> URL? {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        do {
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        } catch {
            return nil
        }
        return directory.appendingPathComponent(WorkspacePath.safeFileName(name))
    }

    #if canImport(UIKit) && os(iOS)
    private static func activityViewController(for url: URL) -> UIActivityViewController? {
        let sheet = UIActivityViewController(activityItems: [url], applicationActivities: nil)
        // iPad needs an anchor or the popover has nowhere to come from.
        sheet.popoverPresentationController?.sourceView = Self.keyWindow
        sheet.popoverPresentationController?.sourceRect = CGRect(x: 0, y: 0, width: 1, height: 1)
        return sheet
    }

    private static func present(_ sheet: UIActivityViewController) {
        guard let root = keyWindow?.rootViewController else { return }
        var top = root
        while let presented = top.presentedViewController { top = presented }
        top.present(sheet, animated: true)
    }

    private static var keyWindow: UIWindow? {
        UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap(\.windows)
            .first(where: \.isKeyWindow)
    }
    #endif
}
