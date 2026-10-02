import Foundation
import SwiftUI
import HarnaxKit
#if canImport(UIKit)
import UIKit
#endif

// MARK: - the size rule

/// What a camera shot has to become before the composer may hold it.
///
/// A picked file is already a picture the user chose; a shot is whatever the sensor handed over, which on a
/// phone is a multi-megabyte JPEG. The streaming edge takes 1 MB of body and no more — its `location` sets no
/// `client_max_body_size` (`harnax-deploy/nginx.conf:72-102`), which leaves nginx's own default and a bare 413
/// page (`:193` records exactly that) — so a shot goes through a reduction rule before it is a data URL, and
/// the rule's ceiling is the one the attachment leg already answers for,
/// `ChatViewModel.imagePayloadBudget` (`Sources/HarnaxFeatures/Chat/ChatViewModel.swift:756`). The spec asked
/// for this in one line: base64 goes into the body whole, so cap the single picture
/// (`harnax-ios/specs/02-session-chat.md:549`).
///
/// The decisions — how long the edge may be, what quality it goes out at, which pass wins, whether anything
/// fits at all — are here rather than in the `UIImagePickerController` delegate, so they can be tested on the
/// macOS host where `UIImage` does not exist (`Package.swift:7`). That is the same split
/// `ChatImagePicker.swift:63-70` documents for the photo library.
enum ChatCameraShot {
    /// One attempt at the shot: its longest edge in pixels and the JPEG quality it is written at.
    struct Pass {
        let longEdge: CGFloat
        let quality: CGFloat
    }

    /// The attempts, largest picture first, each one a smaller picture written at a looser compression.
    ///
    /// Both are reductions rather than a re-encode at arm's length, and what decides them is the ceiling
    /// above, not a picture quality this side can judge: a shot written at 1600 px is accepted up to 700 KB of
    /// JPEG, which is where `ChatCameraShotTests.testTheWireBoundaryIsTheDataUrlNotTheFile` pins the boundary,
    /// and a first pass that somehow lands richer than that falls to 1024 px at 0.6 instead of being refused.
    /// These two numbers are ours, like the 64 KiB margin of the budget they serve
    /// (`Sources/HarnaxFeatures/Chat/ChatViewModel.swift:751-756`); the ceiling they serve is the server's.
    static let passes: [Pass] = [
        Pass(longEdge: 1600, quality: 0.8),
        Pass(longEdge: 1024, quality: 0.6),
    ]

    /// The most a single shot may cost on the wire: the whole attachment leg, which is what
    /// ``fitsForWire(_:)`` and `ChatViewModel.addImages(_:)`
    /// (`Sources/HarnaxFeatures/Chat/ChatViewModel.swift:812`) agree on — the camera invents no second limit.
    static var wireBudget: Int { ChatViewModel.imagePayloadBudget }

    /// A JPEG in the only envelope the runtime reads (`Sources/HarnaxFeatures/Chat/ChatImagePicker.swift:11-20`).
    ///
    /// The type is stated rather than sniffed, because this code wrote those bytes itself as JPEG; the
    /// sniffing in `ChatImageData.mimeType(for:)` is for files somebody else encoded.
    static func dataURL(forJPEG jpeg: Data) -> String {
        ChatImageData.dataURL(mime: "image/jpeg", payload: jpeg)
    }

    /// What a JPEG of this size costs the request body: the data URL goes into the JSON whole, so its utf-8
    /// length is the number the edge measures, counted exactly the way the view model counts it
    /// (`Sources/HarnaxFeatures/Chat/ChatViewModel.swift:758-763`).
    static func wireBytes(ofJPEG jpeg: Data) -> Int {
        dataURL(forJPEG: jpeg).utf8.count
    }

    static func fitsForWire(_ jpeg: Data) -> Bool {
        wireBytes(ofJPEG: jpeg) <= wireBudget
    }

    /// The shot as an attachment, trying each pass in order and stopping at the first that fits.
    ///
    /// `encode` is the drawing step, handed in so the loop is testable without UIKit. When *no* pass fits,
    /// the smallest attempt is still returned rather than dropped: it goes to `addImages`, which refuses it
    /// with the visible sentence (`Sources/HarnaxFeatures/Chat/ChatViewModel.swift:772-773`) instead of this
    /// code quietly truncating bytes the model would then read as a broken picture. A shot that cannot be
    /// compressed enough is refused, never shortened.
    static func dataURL(sequencing encode: (Pass) -> Data?) -> String? {
        var smallest: Data?
        for pass in passes {
            guard let jpeg = encode(pass) else { continue }
            smallest = jpeg
            if fitsForWire(jpeg) { return dataURL(forJPEG: jpeg) }
        }
        return smallest.map(dataURL(forJPEG:))
    }
}

// MARK: - the device

/// Whether a camera can be opened on this host at all.
enum ChatCameraDevice {
    /// Two questions, because they are two different answers: the source type is what UIKit can route to, the
    /// rear device is what the hardware has. Both are settled before anything is presented, so what a user on
    /// a camera-less handset gets is the composer's sentence rather than a sheet that opens only to fail —
    /// the gate is `ChatViewModel.requestCamera(hasCamera:)`
    /// (`Sources/HarnaxFeatures/Chat/ChatViewModel.swift:717-736`, same rule as the vision gate at `:705-715`).
    static var isAvailable: Bool {
        #if canImport(UIKit)
        UIImagePickerController.isSourceTypeAvailable(.camera)
            && UIImagePickerController.isCameraDeviceAvailable(.rear)
        #else
        false
        #endif
    }
}

// MARK: - shooting

#if canImport(UIKit)
/// The camera, wrapped in UIKit because that is the only route the platform gives. `PhotosPicker` has no
/// camera capture at any deployment target — `cameraCaptureMode` is not one of its members, and the only
/// declaration of that name in the SDK is `UIImagePickerController`'s own property in UIKit.
///
/// Not a button of its own: the composer's picture chip asks which source the picture comes from, exactly the
/// way the console's one picture control hands the choice to the operating system
/// (`harnax-webui/src/pages/session/components/ChatWindow.tsx:3500`, the input it clicks at `:3692-3699`).
private struct ChatCameraHost: UIViewControllerRepresentable {
    @Binding private var isPresented: Bool
    private let onShot: ([String]) -> Void

    init(isPresented: Binding<Bool>, onShot: @escaping ([String]) -> Void) {
        _isPresented = isPresented
        self.onShot = onShot
    }

    func makeUIViewController(context: Context) -> UIImagePickerController {
        let picker = UIImagePickerController()
        picker.sourceType = .camera
        picker.cameraCaptureMode = .photo
        picker.allowsEditing = false
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ picker: UIImagePickerController, context: Context) {}

    func makeCoordinator() -> Coordinator {
        Coordinator(onShot: onShot, close: { isPresented = false })
    }

    /// The delegate is `UINavigationControllerDelegate` as well as `UIImagePickerControllerDelegate`: the
    /// picker is a navigation controller, and without both the camera closes without saying anything.
    final class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        private let onShot: ([String]) -> Void
        private let close: () -> Void

        init(onShot: @escaping ([String]) -> Void, close: @escaping () -> Void) {
            self.onShot = onShot
            self.close = close
        }

        func imagePickerController(
            _ picker: UIImagePickerController,
            didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]
        ) {
            let shot = (info[.originalImage] as? UIImage).flatMap(ChatCameraShot.dataURL(from:))
            close()
            // Cancel and "nothing readable came back" both hand over nothing; an over-large shot hands over
            // its own string and lets `addImages` say the sentence.
            onShot(shot.map { [$0] } ?? [])
        }

        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) {
            close()
            onShot([])
        }
    }
}

extension ChatCameraShot {
    /// The shot reduced to one pass, as a data URL — the UIKit half of ``dataURL(sequencing:)``.
    static func dataURL(from image: UIImage) -> String? {
        dataURL(sequencing: { jpeg(from: image, pass: $0) })
    }

    /// The picture redrawn at this pass's long edge and written as JPEG.
    ///
    /// Drawing rather than scaling a `CGImage` also puts the sensor's orientation right: `image.size` is
    /// already orientation-aware, and `draw(in:)` applies it, so what goes out is upright instead of carrying
    /// an EXIF flag the endpoints do not all honour.
    static func jpeg(from image: UIImage, pass: Pass) -> Data? {
        let longEdge = max(image.size.width, image.size.height) * image.scale
        guard longEdge > pass.longEdge else {
            return image.jpegData(compressionQuality: pass.quality)
        }
        let factor = pass.longEdge / longEdge
        let target = CGSize(
            width: max(1, (image.size.width * image.scale * factor).rounded()),
            height: max(1, (image.size.height * image.scale * factor).rounded())
        )
        let format = UIGraphicsImageRendererFormat.default()
        // `target` is already in pixels, and standard range keeps an HDR capture from being written as a
        // wide-gamper the model endpoint has to guess about.
        format.scale = 1
        format.preferredRange = .standard
        return UIGraphicsImageRenderer(size: target, format: format).image { _ in
            image.draw(in: CGRect(origin: .zero, size: target))
        }.jpegData(compressionQuality: pass.quality)
    }
}
#endif

extension View {
    /// The camera, on the hosts that have a UIKit to ask one through. Everywhere else the modifier hands back
    /// the view unchanged, the same way `chatPhotoPicker(isPresented:onPicked:)` does
    /// (`Sources/HarnaxFeatures/Chat/ChatImagePicker.swift:122-134`) — the composer's strip, its removals and
    /// its send all keep working on the test host, they just cannot be filled from a camera that is not there.
    @ViewBuilder
    func chatCameraPicker(isPresented: Binding<Bool>, onShot: @escaping ([String]) -> Void) -> some View {
        #if canImport(UIKit)
        sheet(isPresented: isPresented) {
            ChatCameraHost(isPresented: isPresented, onShot: onShot)
                .ignoresSafeArea()
        }
        #else
        self
        #endif
    }
}
