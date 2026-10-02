import XCTest
import HarnaxCore
import HarnaxKit
@testable import HarnaxFeatures

/// The camera leg: the reduction rule a shot goes through, the envelope it comes back in, and the two gates
/// that decide whether anything may be presented at all.
///
/// None of it needs UIKit. The drawing step is handed to `ChatCameraShot.dataURL(sequencing:)` as a closure
/// precisely so the loop can be tested where `UIImage` does not exist
/// (`Sources/HarnaxFeatures/Chat/ChatCameraPicker.swift:69-85`), and the device probe is an argument to
/// `ChatViewModel.requestCamera(hasCamera:)`
/// (`Sources/HarnaxFeatures/Chat/ChatViewModel.swift:777-800`) for the same reason. What cannot be driven from
/// here is the `UIImagePickerController` itself — the test host has no camera to open.
@MainActor
final class ChatCameraShotTests: XCTestCase {
    private let conversation = ChatConversation(id: "s-1", title: "拍一张")

    private func makeModel() -> ChatViewModel {
        ChatViewModel(streaming: ScriptedChatStream(), conversation: conversation)
    }

    /// Bytes with a JPEG's magic start, so the sniffer and the stated type can be checked against each other.
    private func jpeg(_ byteCount: Int) -> Data {
        var bytes = Data([0xFF, 0xD8, 0xFF])
        bytes.append(Data(count: max(0, byteCount - 3)))
        return bytes
    }

    /// The first pass's output has to be a reduction, not a re-encode at arm's length: a phone sensor hands
    /// over an edge thousands of pixels long, and the whole point of the pass is to make it smaller.
    func testThePassesGoFromTheLargestPictureToTheSmallest() {
        let passes = ChatCameraShot.passes
        XCTAssertGreaterThan(passes.count, 1, "a rule with one attempt has nothing to fall back to")
        for (earlier, later) in zip(passes, passes.dropFirst()) {
            XCTAssertLessThan(later.longEdge, earlier.longEdge, "edges have to shrink")
            XCTAssertLessThan(later.quality, earlier.quality, "and so has the compression")
            XCTAssertGreaterThan(earlier.longEdge, 0)
        }
    }

    /// A shot becomes exactly one string in the only envelope the runtime reads, typed as the JPEG this code
    /// wrote rather than sniffed from whatever the sensor returned.
    func testAShotComesBackAsExactlyOneJpegDataURL() {
        let payload = jpeg(120_000)
        var asked: [ChatCameraShot.Pass] = []
        let shot = ChatCameraShot.dataURL(sequencing: { pass in
            asked.append(pass)
            return payload
        })

        let url = try! XCTUnwrap(shot)
        XCTAssertTrue(url.hasPrefix("data:image/jpeg;base64,"))
        XCTAssertEqual(ChatImageData.payload(of: url), payload, "the whole picture rides along")
        XCTAssertEqual(ChatImageData.mimeType(for: payload), "image/jpeg", "the stated type is true")
        XCTAssertEqual(asked.count, 1, "a shot that fits the first pass is never compressed twice")

        let vm = makeModel()
        vm.addImages([url])
        XCTAssertEqual(vm.images.count, 1, "one shot, one entry in the strip")
        XCTAssertNil(vm.composerNotice)
    }

    /// A shot the first pass could not fit is not thrown away: the rule tries the next pass, and the smaller
    /// picture is what goes out.
    func testAFirstPassTooRichForTheBodyFallsToTheNextPass() {
        let big = jpeg(4_000_000)
        let small = jpeg(200_000)
        var asked: [ChatCameraShot.Pass] = []
        let shot = ChatCameraShot.dataURL(sequencing: { pass in
            asked.append(pass)
            return asked.count == 1 ? big : small
        })

        XCTAssertEqual(
            asked.map(\.longEdge),
            ChatCameraShot.passes.map(\.longEdge),
            "the ladder, in its own order"
        )
        let url = try! XCTUnwrap(shot)
        XCTAssertEqual(ChatImageData.payload(of: url), small, "the reduced shot is the one attached")

        let vm = makeModel()
        vm.addImages([url])
        XCTAssertEqual(vm.images.count, 1)
        XCTAssertNil(vm.composerNotice, "a reduction is not a refusal")
    }

    /// Where the wire boundary sits, counted in the file's own bytes: base64 inflates by a third, so a JPEG as
    /// large as the whole attachment leg does not fit inside it, while the worst plausible output of the first
    /// pass does. The rule counts the string the body carries, never the file it came from.
    func testTheWireBoundaryIsTheDataUrlNotTheFile() {
        XCTAssertEqual(ChatCameraShot.wireBudget, ChatViewModel.imagePayloadBudget, "no second limit")
        XCTAssertTrue(ChatCameraShot.fitsForWire(jpeg(700_000)), "the first pass has room to be rich")
        XCTAssertFalse(ChatCameraShot.fitsForWire(jpeg(ChatCameraShot.wireBudget)))
        XCTAssertEqual(
            ChatCameraShot.wireBytes(ofJPEG: jpeg(900)),
            ChatCameraShot.dataURL(forJPEG: jpeg(900)).utf8.count
        )
    }

    /// A shot no pass can compress enough is refused with the sentence the attachment leg already says, and it
    /// is refused whole: nothing is cut down to squeeze past the edge, because the model would then be handed a
    /// picture that stops halfway through.
    func testAShotThatCannotBeReducedEnoughIsRefusedRatherThanTruncated() {
        let unusable = jpeg(3_000_000)
        let shot = ChatCameraShot.dataURL(sequencing: { _ in unusable })

        let url = try! XCTUnwrap(shot)
        XCTAssertEqual(ChatImageData.payload(of: url), unusable, "the bytes come back whole")
        XCTAssertFalse(ChatCameraShot.fitsForWire(unusable))

        let vm = makeModel()
        vm.addImages([url])
        XCTAssertTrue(vm.images.isEmpty, "and nothing reaches the strip")
        XCTAssertEqual(vm.composerNotice?.tone, .warning)
        XCTAssertEqual(vm.composerNotice?.text, hx("chat.image.overLimit"))
    }

    /// The refusal a camera-less device gets, answerable with no UIKit in sight: a sentence, and no sheet.
    func testAHostWithoutACameraIsRefusedWithASentence() {
        XCTAssertFalse(ChatCameraDevice.isAvailable, "the test host has no camera to find")

        let vm = makeModel()
        XCTAssertFalse(vm.requestCamera(hasCamera: false))
        XCTAssertEqual(vm.composerNotice?.tone, .warning)
        XCTAssertEqual(vm.composerNotice?.text, hx("chat.image.noCamera"))
        XCTAssertTrue(vm.images.isEmpty, "a refusal attaches nothing")

        vm.composerNotice = nil
        XCTAssertTrue(vm.requestCamera(hasCamera: true))
        XCTAssertNil(vm.composerNotice, "with a camera there is nothing to say yet")
    }

    /// Vision is still the first gate: a model that could not read the shot never sees either sheet, so the
    /// camera cannot get past the rule the picture chip already enforces.
    func testANonVisionModelIsRefusedBeforeTheCameraGateIsAsked() async {
        let config = ScriptedSessionConfig()
        config.row = SessionSummary(modelSupportVision: 0)
        let vm = ChatViewModel(streaming: ScriptedChatStream(), config: config, conversation: conversation)
        await vm.loadComposerConfig()

        XCTAssertFalse(vm.canPickImages)
        XCTAssertFalse(vm.requestImages())
        XCTAssertFalse(vm.requestCamera(hasCamera: true), "the device is never consulted")
        XCTAssertEqual(vm.composerNotice?.text, hx("chat.model.noVision"))

        vm.addImages([ChatCameraShot.dataURL(forJPEG: jpeg(1_000))])
        XCTAssertTrue(vm.images.isEmpty)
    }

    /// The two legs share one strip and one send: a shot picked from the camera rides out with the text exactly
    /// the way a file picked from the library does.
    func testAShotAndAnAlbumPictureSitInTheSameStripAndGoOutTogether() async {
        let stream = ScriptedChatStream()
        let vm = ChatViewModel(streaming: stream, conversation: conversation)
        let fromCamera = ChatCameraShot.dataURL(forJPEG: jpeg(2_000))
        let fromLibrary = ChatImageData.dataURL(mime: "image/png", payload: Data([0x89, 0x50, 0x4E, 0x47]))

        vm.addImages([fromLibrary])
        vm.addImages([try! XCTUnwrap(fromCamera)])
        XCTAssertEqual(vm.images.count, 2)

        vm.draft = "看这两张"
        vm.send()
        for _ in 0..<400 where stream.chatRequests.isEmpty {
            await Task.yield()
            try? await Task.sleep(nanoseconds: 1_000_000)
        }

        let sent = try! XCTUnwrap(stream.chatRequests.first)
        XCTAssertEqual(sent.imageUrls.count, 2)
        XCTAssertTrue(sent.imageUrls[1].hasPrefix("data:image/jpeg;base64,"))
        XCTAssertTrue(sent.imageUrls[0].hasPrefix("data:image/png;base64,"))
    }
}
