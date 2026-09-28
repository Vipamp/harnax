import SwiftUI

public struct HXSegmentOption: Identifiable, Equatable, Sendable {
    public let id: Int
    public let titleKey: String
    public let count: Int?

    public init(id: Int, _ titleKey: String, count: Int? = nil) {
        self.id = id
        self.titleKey = titleKey
        self.count = count
    }
}

/// The three-way switch on top of the record lists — the mockup's `.seg` row with live counts.
public struct HXSegmented: View {
    private let options: [HXSegmentOption]
    @Binding private var selection: Int

    public init(_ options: [HXSegmentOption], selection: Binding<Int>) {
        self.options = options
        self._selection = selection
    }

    public var body: some View {
        HStack(spacing: 4) {
            ForEach(options) { option in
                let isOn = option.id == selection
                Button {
                    selection = option.id
                } label: {
                    HStack(spacing: 5) {
                        HXText(option.titleKey)
                        if let count = option.count {
                            Text(verbatim: String(count))
                                .font(.caption.weight(.semibold))
                                .opacity(isOn ? 1 : 0.75)
                        }
                    }
                    .font(.subheadline.weight(isOn ? .semibold : .regular))
                    .foregroundStyle(isOn ? Color.hx(.textPrimary) : Color.hx(.textSecondary))
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 8)
                    .background {
                        if isOn {
                            Color.hx(.surface)
                                .clipShape(Capsule())
                                .shadow(color: Color.hx(.textPrimary).opacity(0.08), radius: 2, y: 1)
                        }
                    }
                    .contentShape(Capsule())
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(isOn ? .isSelected : [])
            }
        }
        .padding(4)
        .background(Color.hx(.surfaceAlt), in: Capsule())
    }
}
