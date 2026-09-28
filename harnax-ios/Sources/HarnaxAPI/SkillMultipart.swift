import Foundation

/// A `multipart/form-data` body assembled by hand.
///
/// `URLSession` has no multipart API, and the backend reads exactly two parts off this route —
/// `@RequestParam("file") MultipartFile` and `@RequestParam("name") String`
/// (`SkillSourceController.kt:137-160`) — so the field names here are contract, not choice. In particular
/// `name` has to be a form field: as a query item the request answers 400.
///
/// Pure string/byte work with no I/O, which is what makes the layout unit-testable against a byte-for-byte
/// expected body.
public enum HarnaxMultipart {
    /// One part of the body. A `fileName` turns it into a file part (which is what makes Spring hand the
    /// value to a `MultipartFile`); without one it is a plain form field.
    public struct Part: Sendable, Equatable {
        public let name: String
        public let fileName: String?
        public let contentType: String?
        /// Already-encoded field text for a plain part, raw archive bytes for a file part.
        public let data: Data

        public init(name: String, fileName: String? = nil, contentType: String? = nil, data: Data) {
            self.name = name
            self.fileName = fileName
            self.contentType = contentType
            self.data = data
        }

        public static func field(_ name: String, _ value: String) -> Part {
            Part(name: name, data: Data(value.utf8))
        }

        /// A file part, with the type the console's own upload sends (`RepositoryForm.tsx:119-133`).
        public static func file(_ name: String, fileName: String, payload: Data, contentType: String = "application/zip") -> Part {
            Part(name: name, fileName: fileName, contentType: contentType, data: payload)
        }
    }

    public struct Result: Sendable, Equatable {
        public let boundary: String
        public let contentType: String
        public let body: Data
    }

    /// A boundary that cannot occur inside the payload: the closing delimiter is a literal byte sequence,
    /// so an archive containing it would truncate the request. Regenerated until unique, which is why the
    /// randomness is injectable.
    public static func makeResult(
        parts: [Part],
        boundary: String? = nil,
        next: @escaping @Sendable () -> String = { UUID().uuidString }
    ) -> Result {
        var candidate = boundary ?? next()
        if boundary == nil {
            while overlaps(candidate, in: parts) { candidate = next() }
        }
        return Result(boundary: candidate, contentType: contentType(boundary: candidate), body: makeBody(boundary: candidate, parts: parts))
    }

    public static func contentType(boundary: String) -> String {
        "multipart/form-data; boundary=\(boundary)"
    }

    /// The wire bytes. CRLF everywhere, and the binary slice is appended without any encoding pass.
    public static func makeBody(boundary: String, parts: [Part]) -> Data {
        var body = Data()
        for part in parts {
            body.append(Data("--\(boundary)\r\n".utf8))
            body.append(Data(disposition(for: part).utf8))
            if let contentType = part.contentType {
                body.append(Data("Content-Type: \(contentType)\r\n".utf8))
            }
            body.append(Data("\r\n".utf8))
            body.append(part.data)
            body.append(Data("\r\n".utf8))
        }
        body.append(Data("--\(boundary)--\r\n".utf8))
        return body
    }

    /// The only place the header grammar lives. `filename` is quoted and its own quotes and control
    /// characters are escaped, because an uploaded archive name is user input.
    static func disposition(for part: Part) -> String {
        var line = "Content-Disposition: form-data; name=\"\(escape(part.name))\""
        if let fileName = part.fileName {
            line += "; filename=\"\(escape(fileName))\""
        }
        return line + "\r\n"
    }

    static func escape(_ value: String) -> String {
        value
            .replacingOccurrences(of: "\\", with: "\\\\")
            .replacingOccurrences(of: "\"", with: "\\\"")
            .replacingOccurrences(of: "\r\n", with: " ")
            .replacingOccurrences(of: "\n", with: " ")
            .replacingOccurrences(of: "\r", with: " ")
    }

    private static func overlaps(_ boundary: String, in parts: [Part]) -> Bool {
        let needle = Data(boundary.utf8)
        return parts.contains { $0.data.range(of: needle) != nil }
    }
}
