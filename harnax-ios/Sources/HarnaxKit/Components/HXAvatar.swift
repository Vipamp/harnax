import SwiftUI

public struct HXAvatar: View {
    public enum Size {
        case small, regular, large

        var side: CGFloat {
            switch self {
            case .small: return 28
            case .regular: return 40
            case .large: return 52
            }
        }

        var font: Font {
            switch self {
            case .small: return .caption.weight(.semibold)
            case .regular: return .body.weight(.semibold)
            case .large: return .title3.weight(.semibold)
            }
        }
    }

    /// Hues the initials may land on. Chosen from the token set so every pair is already under the gate.
    private static let cycle: [PaletteSlot] = [.brand, .indigo, .purple, .teal, .success, .warning]

    private let name: String
    private let size: Size
    private let slot: PaletteSlot

    public init(name: String, size: Size = .regular, slot: PaletteSlot? = nil) {
        self.name = name
        self.size = size
        if let slot {
            self.slot = slot
        } else {
            self.slot = Self.cycle[Int(Self.stableHash(of: name) % UInt64(Self.cycle.count))]
        }
    }

    /// `String.hashValue` is seeded per process, which would repaint every avatar on the next launch.
    private static func stableHash(of name: String) -> UInt64 {
        var hash: UInt64 = 0xcbf29ce484222325
        for scalar in name.unicodeScalars {
            hash ^= UInt64(scalar.value)
            hash = hash &* 0x100000001b3
        }
        return hash
    }

    public var body: some View {
        Text(verbatim: Self.initials(of: name))
            .font(size.font)
            .foregroundStyle(Color.hx(slot))
            .frame(width: size.side, height: size.side)
            .background(Color.hxFill(slot, alpha: 0.2), in: Circle())
            .accessibilityHidden(true)
    }

    private static func initials(of name: String) -> String {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let first = trimmed.first else { return "•" }
        // Word-initial works for latin names; CJK has no word boundary at index 0, so take the glyph.
        if first.isASCII {
            let parts = trimmed.split(whereSeparator: { $0 == " " || $0 == "-" || $0 == "_" })
            let letters = parts.prefix(2).compactMap { $0.first }
            return String(letters).uppercased()
        }
        return String(first)
    }
}
