import Foundation
import SwiftUI
import HarnaxKit
#if canImport(UIKit)
import PhotosUI
import UIKit
#endif

/// Images the composer holds, as the strings the runtime actually understands.
///
/// `ChatAgentRequest.imageUrls` is not a list of URLs to fetch: the wrapper only reads a string that starts
/// with `data:image` as base64, and treats anything else as a path inside the sandbox it then opens with
/// `Files.readAllBytes` and stamps `image/png`
/// (`harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/agent/harness/HarnessAgentWrapper.kt:814-829`,
/// in this repo at `harnax-agent/harnax-harness-core/src/main/kotlin/com/agnetix/harnax/harness/HarnessAgentWrapper.kt`).
/// An `https://` link therefore cannot work, which is why every string that leaves this type is a data URL and
/// every string that does not read as one is dropped rather than sent.
///
/// The web console builds the same shape with `FileReader.readAsDataURL` (`ChatWindow.tsx:2447-2483`); this is
/// that line of code, in the other direction — it never asks the network anything.
enum ChatImageData {
    /// `data:image/<mime>;base64,<bytes>`, the exact prefix order the server splits on: it takes the text
    /// between `:` and `;` of the part before the comma as the mime type.
    static func dataURL(mime: String, payload: Data) -> String {
        "data:\(mime);base64,\(payload.base64EncodedString())"
    }

    /// Whether the runtime would read this string as an image at all.
    static func isDataURL(_ text: String) -> Bool {
        text.hasPrefix("data:image")
    }

    /// The bytes behind a data URL, nil when it is not one or its payload is not base64.
    static func payload(of dataURL: String) -> Data? {
        guard isDataURL(dataURL), let comma = dataURL.firstIndex(of: ",") else { return nil }
        return Data(base64Encoded: String(dataURL[dataURL.index(after: comma)...]))
    }

    /// The mime type from the magic bytes rather than from the file name: the picker hands over decoded image
    /// data with no name attached, and a wrong type is a picture the model reads as something else.
    ///
    /// Only the four formats the model endpoints accept. Anything unrecognised goes out as PNG, which is what
    /// the server itself assumes when it is handed a bare path (`HarnessAgentWrapper.kt:826`).
    static func mimeType(for payload: Data) -> String {
        func starts(_ signature: [UInt8]) -> Bool {
            Array(payload.prefix(signature.count)) == signature
        }
        if starts([0x89, 0x50, 0x4E, 0x47]) { return "image/png" }
        if starts([0xFF, 0xD8, 0xFF]) { return "image/jpeg" }
        if starts([0x47, 0x49, 0x46, 0x38]) { return "image/gif" }
        if starts([0x42, 0x4D]) { return "image/bmp" }
        // WebP is the one format whose tag is not in its first four bytes: `RIFF....WEBP`.
        if starts([0x52, 0x49, 0x46, 0x46]), payload.count >= 12,
           Array(payload.dropFirst(8).prefix(4)) == Array("WEBP".utf8) {
            return "image/webp"
        }
        return "image/png"
    }
}

// MARK: - picking

#if canImport(UIKit)
/// The photo library, behind the one gate this feature needs.
///
/// `PhotosPicker` is a UIKit-only symbol for our purposes — the test host is macOS 14 (`.macOS(.v14)` in
/// `Package.swift`) and importing it there would take the whole chat target down, so the wrapper is compiled
/// out exactly the way `HXPasteboard` compiles out its `UIPasteboard` call
/// (`Sources/HarnaxFeatures/SystemDomain/HXPasteboard.swift:1-19`). Everything past the picker is plain
/// `[String]` data URLs, which is what keeps the view model testable.
///
/// Presented by the caller's own button rather than being a button of its own, so the vision gate can answer
/// before the sheet opens.
private struct ChatPhotoPickerHost: View {
    @Binding private var isPresented: Bool
    private let onPicked: ([String]) -> Void
    @State private var selection: [PhotosPickerItem] = []

    init(isPresented: Binding<Bool>, onPicked: @escaping ([String]) -> Void) {
        _isPresented = isPresented
        self.onPicked = onPicked
    }

    var body: some View {
        Color.clear
            .frame(width: 0, height: 0)
            .photosPicker(
                isPresented: $isPresented,
                selection: $selection,
                maxSelectionCount: 0,
                matching: .images,
                photoLibrary: .shared()
            )
            // Cleared on the way out rather than left to grow: the console resets its hidden file input for
            // the same reason, so picking the same picture twice is possible (`ChatWindow.tsx:2481-2483`).
            .onChange(of: selection) { _, items in
                guard !items.isEmpty else { return }
                let picked = items
                Task {
                    let urls = await Self.dataURLs(for: picked)
                    if !urls.isEmpty { onPicked(urls) }
                    selection = []
                }
            }
    }

    /// Decoded picture bytes in, data URLs out. A picture that cannot be read is skipped instead of failing
    /// the whole batch — the console's `Promise.all` drops the entire pick on one bad file, which is worse.
    static func dataURLs(for items: [PhotosPickerItem]) async -> [String] {
        var urls: [String] = []
        for item in items {
            guard let payload = try? await item.loadTransferable(type: Data.self), !payload.isEmpty else {
                continue
            }
            urls.append(ChatImageData.dataURL(mime: ChatImageData.mimeType(for: payload), payload: payload))
        }
        return urls
    }
}
#endif

extension View {
    /// The picture sheet, on the hosts that have one. Everywhere else the modifier hands back the view
    /// unchanged, so the composer's strip, its removals and its send all still work — they just cannot be
    /// filled from a photo library that does not exist here.
    @ViewBuilder
    func chatPhotoPicker(isPresented: Binding<Bool>, onPicked: @escaping ([String]) -> Void) -> some View {
        #if canImport(UIKit)
        self.background(ChatPhotoPickerHost(isPresented: isPresented, onPicked: onPicked))
        #else
        self
        #endif
    }
}

// MARK: - drawing

/// One picked picture, in the preview strip and in the user's bubble.
///
/// Decoded from the data URL the composer already holds, so the strip and the message that goes out cannot
/// disagree about what the picture is.
struct ChatImageThumb: View {
    let dataURL: String

    var body: some View {
        ZStack(alignment: .bottomTrailing) {
            thumbnail
                .frame(width: 62, height: 62)
                .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                .overlay(
                    RoundedRectangle(cornerRadius: 10, style: .continuous)
                        .strokeBorder(Color.hx(.separator), lineWidth: 1)
                )
                .accessibilityHidden(true)
        }
    }

    @ViewBuilder
    private var thumbnail: some View {
        let payload = ChatImageData.payload(of: dataURL)
        #if canImport(UIKit)
        if let payload, let image = UIImage(data: payload) {
            Image(uiImage: image)
                .resizable()
                .scaledToFill()
        } else {
            placeholder
        }
        #else
        // The macOS test host has no image bridge here by design; the strip still lays out and still removes.
        placeholder
        #endif
    }

    private var placeholder: some View {
        Image(systemName: "photo")
            .font(.system(size: 18))
            .foregroundStyle(Color.hx(.textTertiary))
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(Color.hx(.surfaceAlt))
    }
}
