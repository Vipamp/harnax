import Foundation

enum FixtureError: Error {
    case missing(String)
}

/// Fixtures are captured from the running dev stack (`harnax-admin` on 127.0.0.1:28080,
/// 2026-09-28) with `curl`; token and API-key values are masked, structure and key presence are not.
enum Fixture {
    static func data(_ name: String) throws -> Data {
        guard let url = Bundle.module.url(forResource: name, withExtension: "json", subdirectory: "Fixtures")
            ?? Bundle.module.url(forResource: name, withExtension: "json") else {
            throw FixtureError.missing(name)
        }
        return try Data(contentsOf: url)
    }

    static func decode<T: Decodable>(_ type: T.Type, _ name: String) throws -> T {
        try JSONDecoder().decode(type, from: data(name))
    }
}
