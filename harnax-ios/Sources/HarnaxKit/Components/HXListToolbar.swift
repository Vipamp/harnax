import SwiftUI

/// The trailing pair every entity list shares: a `+` that opens the create form, and one funnel that holds
/// every filter the name search cannot carry.
///
/// Both live at the trailing end because the leading end is where a pushed screen keeps its back button, and
/// the lists that used to put the funnel there had the two collide. The order — `+` first, funnel second — is
/// what the 上下文 tab's model list has always drawn, and the rest of the app was brought to it rather than
/// the other way round.
public struct HXPlusButton: View {
    private let titleKey: String
    private let action: () -> Void

    public init(titleKey: String, action: @escaping () -> Void) {
        self.titleKey = titleKey
        self.action = action
    }

    public var body: some View {
        Button {
            action()
        } label: {
            Image(systemName: "plus")
        }
        .accessibilityLabel(hx(titleKey))
    }
}

/// One row of the filter menu: a name, and the tick that says it is the one in force.
public struct HXFilterChoice: Identifiable {
    public let id: String
    let titleKey: String
    let isSelected: Bool
    let select: () -> Void

    public init(id: String, titleKey: String, isSelected: Bool, select: @escaping () -> Void) {
        self.id = id
        self.titleKey = titleKey
        self.isSelected = isSelected
        self.select = select
    }
}

public struct HXFilterChoices: View {
    let choices: [HXFilterChoice]

    public init(_ choices: [HXFilterChoice]) { self.choices = choices }

    public var body: some View {
        ForEach(choices) { choice in
            Button {
                choice.select()
            } label: {
                HStack {
                    HXText(choice.titleKey)
                    if choice.isSelected { Image(systemName: "checkmark") }
                }
            }
        }
    }
}

/// The funnel itself. It fills in when anything is set, because a filter that has bitten is the one piece of
/// state a list cannot show in its rows once every row is gone.
///
/// The menu's contents stay at the call site: a status list is a row of choices, while status-and-type lists
/// are two pickers, and the second shape is the only way its two halves can be told apart without a screenshot.
public struct HXFilterMenu<Content: View>: View {
    private let isFiltering: Bool
    private let label: String
    private let content: Content

    public init(isFiltering: Bool, accessibilityLabel label: String, @ViewBuilder content: () -> Content) {
        self.isFiltering = isFiltering
        self.label = label
        self.content = content()
    }

    public var body: some View {
        Menu {
            content
        } label: {
            Image(systemName: isFiltering
                ? "line.3.horizontal.decrease.circle.fill"
                : "line.3.horizontal.decrease.circle")
        }
        .accessibilityLabel(Text(verbatim: label))
    }
}

extension HXFilterMenu where Content == HXFilterChoices {
    public init(isFiltering: Bool, accessibilityLabel label: String, choices: [HXFilterChoice]) {
        self.init(isFiltering: isFiltering, accessibilityLabel: label) { HXFilterChoices(choices) }
    }
}
