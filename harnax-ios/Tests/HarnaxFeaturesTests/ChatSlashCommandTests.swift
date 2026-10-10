import XCTest
import HarnaxCore
@testable import HarnaxFeatures

/// The slash line, read the way the console reads it (`ChatWindow.tsx:966-988`, spec 302-310).
///
/// Table-driven because the whole contract is a table: nine keywords, one separator rule, and a fall-through
/// that has to stay silent. A parse that is wrong here is not a wrong label — it is a message sent to the
/// command endpoint that should have been streamed, or the other way round.
final class ChatSlashCommandTests: XCTestCase {
    // MARK: - the keyword table

    func testEveryKeywordMapsToItsCommand() {
        let table: [(String, AgentCommandType)] = [
            ("interrupt", .interrupt),
            ("stop", .interrupt),
            ("clear", .clear),
            ("compact", .compact),
            ("approve", .approve),
            ("stop-sandbox", .stopSandbox),
            ("enable", .enable),
            ("disable", .disable),
            ("permission", .permission),
        ]
        for (keyword, command) in table {
            XCTAssertEqual(
                ChatSlashCommand.parse("/\(keyword)")?.command, command,
                "/\(keyword) must reach \(command.rawValue)"
            )
        }
    }

    func testTheTableIsTheNineKeywordsAndNotTheBackendsTen() {
        // `deny`, `reject` and `refresh` exist on the server but were never given a slash entry
        // (spec 284-286); they answer as ordinary text.
        XCTAssertNil(ChatSlashCommand.parse("/deny"))
        XCTAssertNil(ChatSlashCommand.parse("/reject"))
        XCTAssertNil(ChatSlashCommand.parse("/refresh"))
    }

    // MARK: - the separator

    func testColonAndSpaceAreTheSameSeparator() {
        XCTAssertEqual(
            ChatSlashCommand.parse("/permission:bypass"),
            ChatSlashCommand.parse("/permission bypass")
        )
        XCTAssertEqual(ChatSlashCommand.parse("/permission bypass")?.args, "bypass")
        XCTAssertEqual(ChatSlashCommand.parse("/enable thinking")?.args, "thinking")
    }

    func testTheEarlierSeparatorWinsAndTheRestStaysIntact() {
        // A space before the colon means the colon is part of the argument, not a second separator.
        let command = ChatSlashCommand.parse("/enable think:fast")
        XCTAssertEqual(command?.args, "think:fast")

        let later = ChatSlashCommand.parse("/permission:bypass now")
        XCTAssertEqual(later?.command, .permission)
        XCTAssertEqual(later?.args, "bypass now")
    }

    func testArgumentsAreTrimmedButInnerSpacingIsNot() {
        XCTAssertEqual(ChatSlashCommand.parse("/enable   thinking  ")?.args, "thinking")
        XCTAssertEqual(ChatSlashCommand.parse("/approve  yes  please")?.args, "yes  please")
    }

    // MARK: - what is not a command

    func testACommandNeedsToStartWithASlash() {
        XCTAssertNil(ChatSlashCommand.parse("clear"))
        XCTAssertNil(ChatSlashCommand.parse(" /clear"))
    }

    func testABareSlashAndASlashWithNothingAfterItAreText() {
        XCTAssertNil(ChatSlashCommand.parse("/"))
        XCTAssertNil(ChatSlashCommand.parse("/   "))
    }

    /// An unknown keyword is not an error: the line goes out as a plain message, exactly as typed
    /// (spec 309). A parser that guessed at the closest keyword would send a command nobody asked for.
    func testAnUnknownKeywordFallsThroughAsPlainText() {
        XCTAssertNil(ChatSlashCommand.parse("/clearr"))
        XCTAssertNil(ChatSlashCommand.parse("/clearr me"))
        XCTAssertNil(ChatSlashCommand.parse("/stop-sandboxes"))
    }

    func testTheKeywordIsCaseInsensitiveAndTheArgumentIsNot() {
        XCTAssertEqual(ChatSlashCommand.parse("/CLEAR")?.command, .clear)
        XCTAssertEqual(ChatSlashCommand.parse("/Stop-Sandbox")?.command, .stopSandbox)
        XCTAssertEqual(ChatSlashCommand.parse("/Permission Bypass")?.args, "Bypass")
    }

    // MARK: - the confirmation gate

    func testOnlyClearAndStopSandboxAskFirst() {
        func kind(_ line: String) -> ChatPendingCommand.Kind? {
            guard let parsed = ChatSlashCommand.parse(line) else { return nil }
            return ChatPendingCommand(command: parsed, rawText: line).kind
        }
        XCTAssertEqual(kind("/clear"), .clear)
        XCTAssertEqual(kind("/stop-sandbox"), .stopSandbox)
        for line in ["/interrupt", "/stop", "/approve", "/enable thinking", "/disable plan", "/permission bypass"] {
            XCTAssertNil(kind(line), "\(line) goes straight through")
        }
    }
}

// MARK: - data URLs

/// The strings the runtime will accept as a picture.
///
/// `imageUrls` is not a list of links: a string that does not begin `data:image` is opened as a path inside
/// the sandbox (`HarnessAgentWrapper.kt:814-829`), so this type is the only place that decides what the
/// composer is allowed to hold.
final class ChatImageDataTests: XCTestCase {
    func testTheDataURLKeepsThePrefixOrderTheServerSplitsOn() {
        let url = ChatImageData.dataURL(mime: "image/png", payload: Data([0x01, 0x02]))
        XCTAssertEqual(url, "data:image/png;base64,AQI=")
        XCTAssertTrue(ChatImageData.isDataURL(url))
    }

    func testOnlyAnImageDataURLCountsAsAPicture() {
        XCTAssertTrue(ChatImageData.isDataURL("data:image/jpeg;base64,AAA="))
        XCTAssertFalse(ChatImageData.isDataURL("data:application/pdf;base64,AAA="))
        XCTAssertFalse(ChatImageData.isDataURL("https://cdn/example.png"))
        XCTAssertFalse(ChatImageData.isDataURL("/workspace/photo.png"))
    }

    func testPayloadRoundTrips() {
        let bytes = Data((0..<64).map { UInt8($0 % 7) })
        XCTAssertEqual(
            ChatImageData.payload(of: ChatImageData.dataURL(mime: "image/png", payload: bytes)), bytes
        )
        XCTAssertNil(ChatImageData.payload(of: "not a data url"))
        XCTAssertNil(ChatImageData.payload(of: "data:image/png;base64,!!!"))
    }

    func testTheFormatComesFromTheMagicBytesNotFromAName() {
        func mime(_ bytes: [UInt8]) -> String {
            ChatImageData.mimeType(for: Data(bytes))
        }
        XCTAssertEqual(mime([0x89, 0x50, 0x4E, 0x47, 0x0D]), "image/png")
        XCTAssertEqual(mime([0xFF, 0xD8, 0xFF, 0xE0]), "image/jpeg")
        XCTAssertEqual(mime([0x47, 0x49, 0x46, 0x38, 0x39]), "image/gif")
        XCTAssertEqual(mime([0x42, 0x4D, 0x00, 0x00]), "image/bmp")
        XCTAssertEqual(mime([0x52, 0x49, 0x46, 0x46, 0x08, 0x00, 0x00, 0x00, 0x57, 0x45, 0x42, 0x50]), "image/webp")
        // The server's own assumption when it is handed something it cannot type (`:826`).
        XCTAssertEqual(mime([0x00, 0x01]), "image/png")
        XCTAssertEqual(mime([]), "image/png")
    }

    func testAShortPayloadCannotCrashTheWebpCheck() {
        // `RIFF` alone is not a WebP, and reading bytes 8…11 of a four-byte file would trap.
        XCTAssertEqual(ChatImageData.mimeType(for: Data([0x52, 0x49, 0x46, 0x46])), "image/png")
    }
}
